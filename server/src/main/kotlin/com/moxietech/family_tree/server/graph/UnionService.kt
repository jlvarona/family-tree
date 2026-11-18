package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.api.AddUnionChildRequest
import com.moxietech.family_tree.api.CreateUnionRequest
import com.moxietech.family_tree.api.UnionDto
import com.moxietech.family_tree.api.UpdateUnionChildRequest
import com.moxietech.family_tree.api.UpdateUnionRequest
import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.AuditAction
import com.moxietech.family_tree.domain.ChildRelation
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.domain.Union
import com.moxietech.family_tree.domain.UnionChild
import com.moxietech.family_tree.domain.UnionId
import com.moxietech.family_tree.domain.UnionType
import com.moxietech.family_tree.format.GedcomDateParser
import com.moxietech.family_tree.server.collaboration.AuditJson
import com.moxietech.family_tree.server.collaboration.AuditLogRepository
import com.moxietech.family_tree.server.config.DatabaseDispatcher
import com.moxietech.family_tree.server.errors.ConflictException
import com.moxietech.family_tree.server.errors.NotFoundException
import com.moxietech.family_tree.server.errors.ValidationException
import com.moxietech.family_tree.server.errors.parseOptionalUuidField
import com.moxietech.family_tree.server.errors.parseUuidField
import com.moxietech.family_tree.server.errors.parseVersionParam
import com.moxietech.family_tree.server.errors.referenced
import com.moxietech.family_tree.server.errors.requireValid
import com.moxietech.family_tree.server.errors.staleVersion
import com.moxietech.family_tree.server.permissions.GraphGeneration
import com.moxietech.family_tree.server.permissions.ReadAccess
import com.moxietech.family_tree.server.permissions.WriteAccess
import com.moxietech.family_tree.server.permissions.WriteScope
import com.moxietech.family_tree.server.trees.TreeService
import com.moxietech.family_tree.validation.Validation
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.sql.SQLException
import kotlin.uuid.Uuid

object UnionService {

    /**
     * FT-012: the caller needs live access to at least one partner, and the new union
     * must not let them edit anyone they couldn't before ([WriteScope.requireNoGainedReach]),
     * both decided in the write's transaction.
     */
    suspend fun createUnion(treeId: TreeId, callerAccountId: AccountId, request: CreateUnionRequest): UnionDto {
        TreeService.getTreeForEditor(treeId, callerAccountId)
        val partner1Uuid = parseUuidField("partner1Id", request.partner1Id)
        val partner2Uuid = parseOptionalUuidField("partner2Id", request.partner2Id)
        // Compare parsed IDs, so two spellings of one UUID count as the same person (FT-070).
        if (partner1Uuid == partner2Uuid) {
            throw ValidationException(listOf("A union's two partners must be different people"))
        }
        val type = runCatching { UnionType.valueOf(request.type) }.getOrElse {
            throw ValidationException(listOf("Unknown union type '${request.type}'"))
        }
        val startDate = request.startDate?.let { GedcomDateParser.parse(it) }
        val endDate = request.endDate?.let { GedcomDateParser.parse(it) }
        Validation.validateDateOrder(startDate, endDate).requireValid()

        return withContext(DatabaseDispatcher) {
            transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
                val treeUuid = Uuid.parse(treeId.value)
                // Waits for a person merge in this tree, so a retired partner is a 404 (FT-007).
                val scope = WriteAccess.begin(treeUuid, callerAccountId, ownerTakesSharedLock = true)
                TreeScopedLookups.requirePerson(treeUuid, partner1Uuid)
                partner2Uuid?.let { TreeScopedLookups.requirePerson(treeUuid, it) }
                scope.requireRelationship(listOfNotNull(partner1Uuid, partner2Uuid))
                val union = UnionRepository.insert(
                    treeId = treeUuid,
                    partner1Id = partner1Uuid,
                    partner2Id = partner2Uuid,
                    type = type,
                    startDate = startDate,
                    endDate = endDate,
                )
                // New partners change branches that include partners (FT-010).
                GraphGeneration.advance(treeUuid)
                scope.requireNoGainedReach()
                val dto = union.toDto(children = emptyList())
                AuditLogRepository.append(
                    treeId = treeUuid,
                    actorAccountId = Uuid.parse(callerAccountId.value),
                    entityType = "Union",
                    entityId = union.id.value,
                    action = AuditAction.CREATE,
                    beforeJson = null,
                    afterJson = AuditJson.encodeToString(dto),
                )
                projectForCaller(scope, treeUuid, union, emptyList())
            }
        }
    }

    /** FT-012: the caller needs live access to a partner or the child, and no gained reach. */
    suspend fun addChild(
        treeId: TreeId,
        unionId: UnionId,
        callerAccountId: AccountId,
        request: AddUnionChildRequest,
    ): UnionDto {
        TreeService.getTreeForEditor(treeId, callerAccountId)
        val relation = runCatching { ChildRelation.valueOf(request.relation) }.getOrElse {
            throw ValidationException(listOf("Unknown child relation '${request.relation}'"))
        }
        val unionUuid = parseUuidField("unionId", unionId.value)
        // The duplicate check runs after the transaction has rolled back, so the 409
        // is never produced inside the failed write (FT-070).
        return try {
            insertChild(treeId, unionUuid, callerAccountId, request.childId, relation)
        } catch (e: SQLException) {
            if (e.isDuplicateUnionChild()) {
                throw ConflictException("This person is already a child of union $unionUuid")
            }
            throw e
        }
    }

    private suspend fun insertChild(
        treeId: TreeId,
        unionUuid: Uuid,
        callerAccountId: AccountId,
        rawChildId: String,
        relation: ChildRelation,
    ): UnionDto =
        withContext(DatabaseDispatcher) {
            transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
                val treeUuid = Uuid.parse(treeId.value)
                // A new child link is an ancestry edge: serialized with person merges (FT-007).
                val scope = WriteAccess.begin(treeUuid, callerAccountId, ownerTakesSharedLock = true)
                // Resolve the union before parsing the body's child ID: a missing union
                // stays a 404 even when childId is malformed (PR #3).
                val union = TreeScopedLookups.requireUnion(treeUuid, unionUuid)
                val childUuid = parseUuidField("childId", rawChildId)
                TreeScopedLookups.requirePerson(treeUuid, childUuid)
                scope.requireRelationship(union.partnerUuids() + childUuid)
                UnionRepository.addChild(
                    treeId = treeUuid,
                    unionId = unionUuid,
                    childId = childUuid,
                    relation = relation,
                )
                GraphGeneration.advance(treeUuid)
                // Attaching an existing person (and their descendants) below a union is how a
                // member could pull people into their branch: refused unless nobody new.
                scope.requireNoGainedReach()
                val children = UnionRepository.childrenOf(treeUuid, unionUuid)
                val dto = union.toDto(children)
                AuditLogRepository.append(
                    treeId = treeUuid,
                    actorAccountId = Uuid.parse(callerAccountId.value),
                    entityType = "UnionChild",
                    entityId = "$unionUuid:$childUuid",
                    action = AuditAction.CREATE,
                    beforeJson = null,
                    afterJson = AuditJson.encodeToString(dto),
                )
                projectForCaller(scope, treeUuid, union, children)
            }
        }

    /**
     * FT-001: replaces a union's fields with the same validation as
     * [createUnion]. The union is resolved inside [treeId] before the body's
     * partner IDs are parsed, so a missing union stays a 404 (as in [addChild]);
     * both partners must be in the same tree. A version that is no longer the
     * stored one is a 409 that changes neither the union nor the audit log.
     * Child links keep their own versions: the audit's before and after carry the
     * same child list, read once in this transaction.
     *
     * FT-012: the caller needs live access to a partner of the stored union and to a
     * partner of the replacement, and the change must not widen their reach (a partner
     * swap can pull a person into a branch that includes partners).
     */
    suspend fun updateUnion(
        treeId: TreeId,
        unionId: UnionId,
        callerAccountId: AccountId,
        request: UpdateUnionRequest,
    ): UnionDto {
        TreeService.getTreeForEditor(treeId, callerAccountId)
        val unionUuid = parseUuidField("unionId", unionId.value)

        return withContext(DatabaseDispatcher) {
            transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
                val treeUuid = Uuid.parse(treeId.value)
                // Taken before the union's row lock, in the same order as a merge (FT-007).
                val scope = WriteAccess.begin(treeUuid, callerAccountId, ownerTakesSharedLock = true)
                val before = TreeScopedLookups.requireUnion(treeUuid, unionUuid)
                // The stored union, from this transaction, before anything in the body counts
                // (FT-012): a caller outside the branch learns nothing about the body's values.
                scope.requireRelationship(before.partnerUuids())
                val type = runCatching { UnionType.valueOf(request.type) }.getOrElse {
                    throw ValidationException(listOf("Unknown union type '${request.type}'"))
                }
                val startDate = request.startDate?.let { GedcomDateParser.parse(it) }
                val endDate = request.endDate?.let { GedcomDateParser.parse(it) }
                Validation.validateDateOrder(startDate, endDate).requireValid()
                val partner1Uuid = parseUuidField("partner1Id", request.partner1Id)
                val partner2Uuid = parseOptionalUuidField("partner2Id", request.partner2Id)
                if (partner1Uuid == partner2Uuid) {
                    throw ValidationException(listOf("A union's two partners must be different people"))
                }
                TreeScopedLookups.requirePerson(treeUuid, partner1Uuid)
                partner2Uuid?.let { TreeScopedLookups.requirePerson(treeUuid, it) }
                scope.requireRelationship(listOfNotNull(partner1Uuid, partner2Uuid))
                // FT-013: the union keeps its ID, events, citations and scans; taking a hidden
                // partner out of it would let them be read through the remaining partners.
                scope.requireNoHiddenSubjectRemoved(
                    before.partnerUuids().map { TreeScopedLookups.requirePerson(treeUuid, it) },
                    listOfNotNull(partner1Uuid, partner2Uuid),
                )
                if (before.version != request.version) throw staleVersion("Union", before.id.value)
                val after = UnionRepository.update(
                    treeId = treeUuid,
                    id = unionUuid,
                    expectedVersion = request.version,
                    partner1Id = partner1Uuid,
                    partner2Id = partner2Uuid,
                    type = type,
                    startDate = startDate,
                    endDate = endDate,
                ) ?: throw staleVersion("Union", before.id.value)
                // Partners can change (FT-010).
                GraphGeneration.advance(treeUuid)
                scope.requireNoGainedReach()
                val children = UnionRepository.childrenOf(treeUuid, unionUuid)
                val dto = after.toDto(children)
                AuditLogRepository.append(
                    treeId = treeUuid,
                    actorAccountId = Uuid.parse(callerAccountId.value),
                    entityType = "Union",
                    entityId = after.id.value,
                    action = AuditAction.UPDATE,
                    beforeJson = AuditJson.encodeToString(before.toDto(children)),
                    afterJson = AuditJson.encodeToString(dto),
                )
                projectForCaller(scope, treeUuid, after, children)
            }
        }
    }

    /**
     * FT-001: changes how [childId] belongs to [unionId] (the relation). FT-012: the
     * caller needs live access to a partner or the child. The union and child are the link's identity, taken from the path and
     * resolved inside [treeId]; a link that doesn't exist there is a 404. A
     * version that is no longer the link's stored one is a 409 that changes
     * neither the link nor the audit log. Audited as "UnionChild" with the same
     * entity ID and union-shaped snapshots as the link's creation.
     */
    suspend fun updateChild(
        treeId: TreeId,
        unionId: UnionId,
        childId: PersonId,
        callerAccountId: AccountId,
        request: UpdateUnionChildRequest,
    ): UnionDto {
        TreeService.getTreeForEditor(treeId, callerAccountId)
        val unionUuid = parseUuidField("unionId", unionId.value)
        val childUuid = parseUuidField("childId", childId.value)

        return withContext(DatabaseDispatcher) {
            transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
                val treeUuid = Uuid.parse(treeId.value)
                // Not for a merge (that holds its links already) but for a grant-scope
                // rebuild, which must not run while the edge changes (FT-010).
                val scope = WriteAccess.begin(treeUuid, callerAccountId, ownerTakesSharedLock = true)
                val union = TreeScopedLookups.requireUnion(treeUuid, unionUuid)
                val link = UnionRepository.findChild(treeUuid, unionUuid, childUuid)
                    ?: throw NotFoundException("No child $childUuid in union $unionUuid")
                scope.requireRelationship(union.partnerUuids() + childUuid)
                // FT-012: the body's relation only once the branch allows the change.
                val relation = runCatching { ChildRelation.valueOf(request.relation) }.getOrElse {
                    throw ValidationException(listOf("Unknown child relation '${request.relation}'"))
                }
                val entityId = "$unionUuid:$childUuid"
                if (link.version != request.version) throw staleVersion("Child link", entityId)
                if (!UnionRepository.updateChild(treeUuid, unionUuid, childUuid, request.version, relation)) {
                    throw staleVersion("Child link", entityId)
                }
                // Every relation counts today, but the relation is part of the edge (FT-010).
                GraphGeneration.advance(treeUuid)
                // Every relation counts in a branch, so this can't widen anyone's reach;
                // checked anyway so a future relation rule can't skip it.
                scope.requireNoGainedReach()
                val updated = UnionRepository.findChild(treeUuid, unionUuid, childUuid)
                    ?: error("Child link $entityId was just updated but cannot be read back")
                // One read of the sibling links, taken after this link's row lock is held,
                // with only the targeted link swapped between its original and updated value.
                // Two separate reads could straddle a sibling's commit and attribute that
                // sibling's change to this entry (FT-001 review).
                val siblings = UnionRepository.childrenOf(treeUuid, unionUuid)
                fun withLink(value: UnionChild) = siblings.map { if (it.childId == value.childId) value else it }
                val dto = union.toDto(withLink(updated))
                AuditLogRepository.append(
                    treeId = treeUuid,
                    actorAccountId = Uuid.parse(callerAccountId.value),
                    entityType = "UnionChild",
                    entityId = entityId,
                    action = AuditAction.UPDATE,
                    beforeJson = AuditJson.encodeToString(union.toDto(withLink(link))),
                    afterJson = AuditJson.encodeToString(dto),
                )
                projectForCaller(scope, treeUuid, union, withLink(updated))
            }
        }
    }

    /**
     * FT-001: deletes a union with no child links, events or citations at the
     * current [version] (see [RecordDeletion]). FT-012: the caller needs live access
     * to a partner, and the removal must not widen their reach. The audit's before
     * is the union with its (necessarily empty) child list.
     */
    suspend fun deleteUnion(treeId: TreeId, unionId: UnionId, callerAccountId: AccountId, version: List<String>?) {
        TreeService.getTreeForEditor(treeId, callerAccountId)
        val unionUuid = parseUuidField("unionId", unionId.value)
        val treeUuid = Uuid.parse(treeId.value)
        RecordDeletion.guarded("Union", unionUuid.toString(), RecordDeletion.UNION_FOREIGN_KEYS) {
            val scope = WriteAccess.begin(treeUuid, callerAccountId, ownerTakesSharedLock = true)
            val before = TreeScopedLookups.requireUnion(treeUuid, unionUuid)
            scope.requireRelationship(before.partnerUuids())
            // FT-012: the version is checked only once the branch allows the delete.
            val expected = parseVersionParam(version)
            if (before.version != expected) throw staleVersion("Union", before.id.value)
            if (RecordDeletion.unionIsReferenced(treeUuid, unionUuid)) throw referenced("Union", before.id.value)
            if (UnionRepository.delete(treeUuid, unionUuid, expected) == 0) throw staleVersion("Union", before.id.value)
            if (RecordDeletion.isCited(treeUuid, RecordDeletion.UNION, unionUuid)) throw referenced("Union", before.id.value)
            GraphGeneration.advance(treeUuid)
            // Removing a link only shrinks branches, but it can leave a person the caller
            // created without relationships, which would make them the caller's again.
            scope.requireNoGainedReach()
            AuditLogRepository.append(
                treeId = treeUuid,
                actorAccountId = Uuid.parse(callerAccountId.value),
                entityType = "Union",
                entityId = before.id.value,
                action = AuditAction.DELETE,
                beforeJson = AuditJson.encodeToString(before.toDto(emptyList())),
                afterJson = null,
            )
        }
    }

    /**
     * FT-001: removes one child link at the link's current [version]. FT-012: the
     * caller needs live access to a partner or the child, and no gained reach.
     * Nothing references a link, so only a missing or stale link
     * fails. Audited as "UnionChild" with a union-shaped before: one read of the
     * remaining links, taken after the DELETE, plus the removed link as it was.
     */
    suspend fun deleteChild(
        treeId: TreeId,
        unionId: UnionId,
        childId: PersonId,
        callerAccountId: AccountId,
        version: List<String>?,
    ) {
        TreeService.getTreeForEditor(treeId, callerAccountId)
        val unionUuid = parseUuidField("unionId", unionId.value)
        val childUuid = parseUuidField("childId", childId.value)
        val treeUuid = Uuid.parse(treeId.value)
        val entityId = "$unionUuid:$childUuid"
        RecordDeletion.guarded("Child link", entityId, emptySet()) {
            val scope = WriteAccess.begin(treeUuid, callerAccountId, ownerTakesSharedLock = true)
            val union = TreeScopedLookups.requireUnion(treeUuid, unionUuid)
            val link = UnionRepository.findChild(treeUuid, unionUuid, childUuid)
                ?: throw NotFoundException("No child $childUuid in union $unionUuid")
            scope.requireRelationship(union.partnerUuids() + childUuid)
            // FT-012: the version is checked only once the branch allows the delete.
            val expected = parseVersionParam(version)
            if (link.version != expected) throw staleVersion("Child link", entityId)
            if (UnionRepository.deleteChild(treeUuid, unionUuid, childUuid, expected) == 0) {
                throw staleVersion("Child link", entityId)
            }
            GraphGeneration.advance(treeUuid)
            scope.requireNoGainedReach()
            val remaining = UnionRepository.childrenOf(treeUuid, unionUuid)
            AuditLogRepository.append(
                treeId = treeUuid,
                actorAccountId = Uuid.parse(callerAccountId.value),
                entityType = "UnionChild",
                entityId = entityId,
                action = AuditAction.DELETE,
                beforeJson = AuditJson.encodeToString(union.toDto(remaining + link)),
                afterJson = null,
            )
        }
    }

    /**
     * One snapshot for the membership check, the caller's grant scopes (FT-013), people
     * and unions, so a living partner committed between the queries can't be missing
     * from the hidden set (FT-004 review).
     */
    suspend fun listUnions(treeId: TreeId, callerAccountId: AccountId): List<UnionDto> =
        ReadAccess.read(treeId, callerAccountId) { tree, policy ->
            val treeUuid = Uuid.parse(tree.id.value)
            val hidden = policy.hiddenIds(PersonRepository.listByTree(treeUuid))
            UnionRepository.listByTree(treeUuid).map { union ->
                policy.project(union, UnionRepository.childrenOf(treeUuid, Uuid.parse(union.id.value)), hidden)
            }
        }
}

/** The union's partners as UUIDs (one or two). */
internal fun Union.partnerUuids(): List<Uuid> = listOfNotNull(partner1Id, partner2Id).map { Uuid.parse(it.value) }

/**
 * FT-012: a union write's response, shaped by [ViewerPolicy] like a read now that
 * members other than the owner write: a union with a partner hidden from the caller
 * keeps its type and links but not its dates (FT-066). The owner's response is the
 * union as stored. Call inside the write's transaction, after the change: since FT-013
 * the caller's grant scopes are read as the graph now is ([WriteScope.viewer]).
 */
private fun projectForCaller(scope: WriteScope, treeUuid: Uuid, union: Union, children: List<UnionChild>): UnionDto {
    if (scope.isOwner) return union.toDto(children)
    val policy = scope.viewer()
    val partners = union.partnerUuids().mapNotNull { PersonRepository.findById(treeUuid, it) }
    return policy.project(union, children, policy.hiddenIds(partners))
}

private const val UNIQUE_VIOLATION = "23505"
private const val UNION_CHILD_PRIMARY_KEY = "union_child_pkey"

/**
 * True only for PostgreSQL's unique violation on `union_child`'s primary key
 * (union_id, child_id); every other database error keeps propagating as a 500.
 */
private fun SQLException.isDuplicateUnionChild(): Boolean =
    generateSequence<Throwable>(this) { it.cause }
        .filterIsInstance<PSQLException>()
        .any { it.sqlState == UNIQUE_VIOLATION && it.serverErrorMessage?.constraint == UNION_CHILD_PRIMARY_KEY }
