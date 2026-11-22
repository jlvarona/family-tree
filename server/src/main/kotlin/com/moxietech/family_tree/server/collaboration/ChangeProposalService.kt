package com.moxietech.family_tree.server.collaboration

import com.moxietech.family_tree.api.AddUnionChildRequest
import com.moxietech.family_tree.api.ApproveProposalRequest
import com.moxietech.family_tree.api.ChangeProposalDto
import com.moxietech.family_tree.api.CreateCitationRequest
import com.moxietech.family_tree.api.CreateEventRequest
import com.moxietech.family_tree.api.CreateUnionRequest
import com.moxietech.family_tree.api.DecideProposalRequest
import com.moxietech.family_tree.api.UpdateEventRequest
import com.moxietech.family_tree.api.UpdatePersonRequest
import com.moxietech.family_tree.api.UpdateUnionChildRequest
import com.moxietech.family_tree.api.UpdateUnionRequest
import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.AuditAction
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.permissions.PersonVisibility
import com.moxietech.family_tree.permissions.TreePermissions
import com.moxietech.family_tree.proposals.ProposalField
import com.moxietech.family_tree.proposals.ProposalFields
import com.moxietech.family_tree.proposals.ProposalOperation
import com.moxietech.family_tree.proposals.ProposalStatus
import com.moxietech.family_tree.proposals.ReviewAuthority
import com.moxietech.family_tree.server.config.DatabaseDispatcher
import com.moxietech.family_tree.server.errors.ConflictException
import com.moxietech.family_tree.server.errors.ForbiddenException
import com.moxietech.family_tree.server.errors.NotFoundException
import com.moxietech.family_tree.server.errors.ValidationException
import com.moxietech.family_tree.server.errors.parseUuidField
import com.moxietech.family_tree.server.errors.staleVersion
import com.moxietech.family_tree.server.graph.EventService
import com.moxietech.family_tree.server.graph.PersonService
import com.moxietech.family_tree.server.graph.UnionService
import com.moxietech.family_tree.server.graph.ViewerPolicy
import com.moxietech.family_tree.server.graph.isDuplicateUnionChild
import com.moxietech.family_tree.server.permissions.GrantScopeService
import com.moxietech.family_tree.server.permissions.WriteAccess
import com.moxietech.family_tree.server.permissions.WriteScope
import com.moxietech.family_tree.server.sources.CitationService
import com.moxietech.family_tree.server.trees.MembershipRepository
import com.moxietech.family_tree.server.trees.TreeRepository
import com.moxietech.family_tree.server.trees.TreeService
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.sql.SQLException
import kotlin.uuid.Uuid

/**
 * FT-014 change proposals: listing, detail, approval, rejection and withdrawal
 * (decisions table "Change proposals (FT-014)"). Proposals are created by the graph
 * writes themselves ([WriteScope.tentatively]) when proposals are enabled.
 *
 * Who sees a proposal: its proposer, and members who may review it ([ReviewAuthority]:
 * the tree owner, or a branch owner whose BRANCH_OWNER grants cover every person it
 * affects). Anyone else gets the generic 404, the same as a missing ID or another tree's.
 * What they see is shaped by [ChangeProposalViews] with the caller's living-person policy.
 *
 * Approval is one READ COMMITTED transaction: [WriteAccess.beginReview] (graph lock, the
 * reviewer's membership and grants), the proposal row `FOR UPDATE` (a concurrent review,
 * retry or withdrawal waits and then sees it decided: 409), its status and version, the
 * proposer's access ([WriteScope.attachProposer]), then the same write the proposer
 * attempted, replayed by the reviewer in review mode with only the approved fields (per
 * field: the others as stored, at the proposal's base version, so a record changed since
 * is a stale 409), then the proposal's decision and its audit. Any refusal rolls back the
 * graph change, the decision and both audits together; the proposal stays pending.
 */
internal object ChangeProposalService {

    fun notFoundMessage(id: String) = "No proposal with id $id"

    fun alreadyDecidedMessage(id: String) = "Proposal $id has already been decided"

    const val OWN_PROPOSAL_MESSAGE = "You can't review your own proposal; withdraw it instead"

    const val PROPOSER_ONLY_MESSAGE = "Only the proposer can withdraw a proposal"

    fun stillReferencedMessage(id: String) = "Proposal $id can't be applied: a record it removes is still referenced"

    suspend fun list(treeId: TreeId, caller: AccountId): List<ChangeProposalDto> =
        TreeService.readAsMember(treeId, caller) { tree ->
            val treeUuid = Uuid.parse(tree.id.value)
            val view = ViewContext.of(tree.ownerAccountId == caller, treeUuid, caller)
            ChangeProposalRepository.listByTree(treeUuid).mapNotNull { view.shape(treeUuid, it) }
        }

    suspend fun get(treeId: TreeId, rawId: String, caller: AccountId): ChangeProposalDto =
        TreeService.readAsMember(treeId, caller) { tree ->
            val treeUuid = Uuid.parse(tree.id.value)
            val id = parseUuidField("proposalId", rawId)
            val view = ViewContext.of(tree.ownerAccountId == caller, treeUuid, caller)
            ChangeProposalRepository.find(treeUuid, id)?.let { view.shape(treeUuid, it) }
                ?: throw NotFoundException(notFoundMessage(id.toString()))
        }

    /** The caller's policy and review scope in one read snapshot. */
    private class ViewContext(val caller: Uuid, val policy: ViewerPolicy, val review: ReviewAuthority) {
        fun shape(treeUuid: Uuid, record: ChangeProposalRecord): ChangeProposalDto? {
            val own = record.proposer == caller
            val reviewable = review.canReview(record.subjects.map { PersonId(it.toString()) })
            if (!own && !reviewable) return null
            val pending = record.status == ProposalStatus.PENDING
            return ChangeProposalViews.shaped(
                record,
                ProposalTargets.current(treeUuid, record),
                policy,
                canReview = pending && reviewable && !own,
                canWithdraw = pending && own,
            )
        }

        companion object {
            fun of(isOwner: Boolean, treeUuid: Uuid, caller: AccountId): ViewContext {
                val callerUuid = Uuid.parse(caller.value)
                if (isOwner) return ViewContext(callerUuid, ViewerPolicy(PersonVisibility.owner()), ReviewAuthority.owner())
                val grants = GrantScopeService.scopedGrantsInSnapshot(treeUuid, callerUuid)
                return ViewContext(callerUuid, ViewerPolicy(PersonVisibility.member(grants)), ReviewAuthority.member(grants))
            }
        }
    }

    /**
     * The review routes' first gate, before the body is decoded (FT-012 style): an active
     * member who reviews something (the owner, or a holder of an effective BRANCH_OWNER
     * grant), else 403. Repeated under locks by [WriteAccess.beginReview].
     */
    suspend fun requireReviewer(treeId: TreeId, caller: AccountId) {
        TreeService.readAsMember(treeId, caller) { tree ->
            val treeUuid = Uuid.parse(tree.id.value)
            if (!ViewContext.of(tree.ownerAccountId == caller, treeUuid, caller).review.isReviewer) {
                throw ForbiddenException(WriteAccess.notReviewerMessage(tree.id.value))
            }
        }
    }

    suspend fun approve(treeId: TreeId, rawId: String, caller: AccountId, request: ApproveProposalRequest): ChangeProposalDto {
        requireReviewer(treeId, caller)
        val treeUuid = Uuid.parse(treeId.value)
        val id = parseUuidField("proposalId", rawId)
        return try {
            withContext(DatabaseDispatcher) {
                transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
                    val scope = WriteAccess.beginReview(treeUuid, caller)
                    val record = lockForReview(scope, treeUuid, id, request.version)
                    val approved = ProposalFields.approved(record.operation, request.fields)
                    if (approved.errors.isNotEmpty()) throw ValidationException(approved.errors)
                    scope.attachProposer(record.proposer)
                    replay(scope, treeUuid, record, approved.fields)
                    val fields = record.operation.fields.filter { it in approved.fields }
                    val decided = ChangeProposalRepository.decide(
                        treeUuid, id, record.version, ProposalStatus.APPLIED, Uuid.parse(caller.value), fields,
                    ) ?: throw staleVersion("Proposal", id.toString())
                    audit(scope, record, decided)
                    ChangeProposalViews.shaped(decided, ProposalTargets.current(treeUuid, decided), scope.viewer(), canReview = false, canWithdraw = false)
                }
            }
        } catch (e: SQLException) {
            // Raised at the replayed write's statement, after the rollback, as for live writes.
            if (e.isDuplicateUnionChild()) throw ConflictException("This person is already a child of the union")
            if (e.isForeignKeyViolation()) throw ConflictException(stillReferencedMessage(id.toString()))
            throw e
        }
    }

    suspend fun reject(treeId: TreeId, rawId: String, caller: AccountId, request: DecideProposalRequest): ChangeProposalDto {
        requireReviewer(treeId, caller)
        val treeUuid = Uuid.parse(treeId.value)
        val id = parseUuidField("proposalId", rawId)
        return withContext(DatabaseDispatcher) {
            transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
                val scope = WriteAccess.beginReview(treeUuid, caller)
                val record = lockForReview(scope, treeUuid, id, request.version)
                val decided = ChangeProposalRepository.decide(
                    treeUuid, id, record.version, ProposalStatus.REJECTED, Uuid.parse(caller.value), null,
                ) ?: throw staleVersion("Proposal", id.toString())
                audit(scope, record, decided)
                ChangeProposalViews.shaped(decided, ProposalTargets.current(treeUuid, decided), scope.viewer(), canReview = false, canWithdraw = false)
            }
        }
    }

    /** The proposer takes back a pending proposal. Nothing in the graph changes. */
    suspend fun withdraw(treeId: TreeId, rawId: String, caller: AccountId, request: DecideProposalRequest): ChangeProposalDto {
        TreeService.getTreeForReader(treeId, caller)
        val treeUuid = Uuid.parse(treeId.value)
        val callerUuid = Uuid.parse(caller.value)
        val id = parseUuidField("proposalId", rawId)
        return withContext(DatabaseDispatcher) {
            transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
                val tree = TreeRepository.findById(treeUuid) ?: throw NotFoundException("No tree with id $treeUuid")
                if (!TreePermissions.canRead(tree, caller, MembershipRepository.find(treeUuid, callerUuid))) {
                    throw ForbiddenException("Not a member of tree $treeUuid")
                }
                val record = ChangeProposalRepository.lock(treeUuid, id) ?: throw NotFoundException(notFoundMessage(id.toString()))
                if (record.proposer != callerUuid) {
                    // The owner sees every proposal; anyone else can't tell it exists.
                    if (tree.ownerAccountId == caller) throw ForbiddenException(PROPOSER_ONLY_MESSAGE)
                    throw NotFoundException(notFoundMessage(id.toString()))
                }
                if (record.status != ProposalStatus.PENDING) throw ConflictException(alreadyDecidedMessage(id.toString()))
                if (record.version != request.version) throw staleVersion("Proposal", id.toString())
                val decided = ChangeProposalRepository.decide(treeUuid, id, record.version, ProposalStatus.WITHDRAWN, null, null)
                    ?: throw staleVersion("Proposal", id.toString())
                AuditLogRepository.append(
                    treeId = treeUuid,
                    actorAccountId = callerUuid,
                    entityType = CHANGE_PROPOSAL_ENTITY,
                    entityId = id.toString(),
                    action = AuditAction.UPDATE,
                    beforeJson = AuditJson.encodeToString(ChangeProposalAuditDto.serializer(), record.toAuditDto()),
                    afterJson = AuditJson.encodeToString(ChangeProposalAuditDto.serializer(), decided.toAuditDto()),
                )
                ChangeProposalViews.forProposer(decided)
            }
        }
    }

    /**
     * The proposal locked for a review: 404 unless this reviewer may review it (missing,
     * another tree's and someone else's branch look the same), 403 for one's own, 409 when
     * already decided or at another version.
     */
    private fun lockForReview(scope: WriteScope, treeUuid: Uuid, id: Uuid, version: Long): ChangeProposalRecord {
        val record = ChangeProposalRepository.lock(treeUuid, id) ?: throw NotFoundException(notFoundMessage(id.toString()))
        if (!scope.review.canReview(record.subjects.map { PersonId(it.toString()) })) {
            throw NotFoundException(notFoundMessage(id.toString()))
        }
        if (record.proposer.toString() == scope.caller.value) throw ForbiddenException(OWN_PROPOSAL_MESSAGE)
        if (record.status != ProposalStatus.PENDING) throw ConflictException(alreadyDecidedMessage(id.toString()))
        if (record.version != version) throw staleVersion("Proposal", id.toString())
        return record
    }

    private fun audit(scope: WriteScope, before: ChangeProposalRecord, after: ChangeProposalRecord) {
        AuditLogRepository.append(
            treeId = before.treeId,
            actorAccountId = Uuid.parse(scope.caller.value),
            entityType = CHANGE_PROPOSAL_ENTITY,
            entityId = before.id.toString(),
            action = AuditAction.UPDATE,
            beforeJson = AuditJson.encodeToString(ChangeProposalAuditDto.serializer(), before.toAuditDto()),
            afterJson = AuditJson.encodeToString(ChangeProposalAuditDto.serializer(), after.toAuditDto()),
        )
    }

    /**
     * Replays the proposer's write as [scope]'s reviewer, through the same service code,
     * with the [approved] fields. Every graph change it makes is audited by that code, with
     * the reviewer as the actor.
     */
    private fun replay(scope: WriteScope, treeUuid: Uuid, record: ChangeProposalRecord, approved: Set<ProposalField>) {
        val target = record.targetId
        val base = record.baseVersion
        val deleteVersion = listOfNotNull(base?.toString())
        when (record.operation) {
            ProposalOperation.UPDATE_PERSON ->
                PersonService.updatePersonIn(scope, treeUuid, Uuid.parse(target!!), request<UpdatePersonRequest>(treeUuid, record, approved))
            ProposalOperation.DELETE_PERSON -> PersonService.deletePersonIn(scope, treeUuid, Uuid.parse(target!!), deleteVersion)
            ProposalOperation.CREATE_UNION ->
                UnionService.createUnionIn(scope, treeUuid, ProposalPayloads.request<CreateUnionRequest>(record.payload, null))
            ProposalOperation.UPDATE_UNION ->
                UnionService.updateUnionIn(scope, treeUuid, Uuid.parse(target!!), request<UpdateUnionRequest>(treeUuid, record, approved))
            ProposalOperation.DELETE_UNION -> UnionService.deleteUnionIn(scope, treeUuid, Uuid.parse(target!!), deleteVersion)
            ProposalOperation.ADD_CHILD ->
                UnionService.addChildIn(scope, treeUuid, Uuid.parse(target!!), ProposalPayloads.request<AddUnionChildRequest>(record.payload, null))
            ProposalOperation.UPDATE_CHILD -> {
                val (union, child) = target!!.split(':').map(Uuid::parse)
                UnionService.updateChildIn(scope, treeUuid, union, child, request<UpdateUnionChildRequest>(treeUuid, record, approved))
            }
            ProposalOperation.DELETE_CHILD -> {
                val (union, child) = target!!.split(':').map(Uuid::parse)
                UnionService.deleteChildIn(scope, treeUuid, union, child, deleteVersion)
            }
            ProposalOperation.CREATE_EVENT ->
                EventService.createEventIn(scope, treeUuid, ProposalPayloads.request<CreateEventRequest>(record.payload, null))
            ProposalOperation.UPDATE_EVENT ->
                EventService.updateEventIn(scope, treeUuid, Uuid.parse(target!!), request<UpdateEventRequest>(treeUuid, record, approved))
            ProposalOperation.DELETE_EVENT -> EventService.deleteEventIn(scope, treeUuid, Uuid.parse(target!!), deleteVersion)
            ProposalOperation.CREATE_CITATION ->
                CitationService.createCitationIn(scope, treeUuid, ProposalPayloads.request<CreateCitationRequest>(record.payload, null))
        }
    }

    /**
     * An update's request: every approved field as proposed, every other field as stored
     * now, at the proposal's base version. A target that is gone replays the proposal as
     * it was, so the write itself answers with its usual 404.
     */
    private inline fun <reified T> request(treeUuid: Uuid, record: ChangeProposalRecord, approved: Set<ProposalField>): T {
        val current = ProposalTargets.current(treeUuid, record)
        val values: JsonObject = if (current == null) {
            record.payload
        } else {
            ProposalFields.compose(record.operation, approved, record.payload, current.values)
        }
        return ProposalPayloads.request(values, record.baseVersion)
    }

    private fun SQLException.isForeignKeyViolation(): Boolean =
        generateSequence<Throwable>(this) { it.cause }.filterIsInstance<PSQLException>().any { it.sqlState == "23503" }
}
