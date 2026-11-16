package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.api.MergePeopleRequest
import com.moxietech.family_tree.api.MergePeopleResponse
import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.AuditAction
import com.moxietech.family_tree.domain.Event
import com.moxietech.family_tree.domain.Membership
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.domain.Union
import com.moxietech.family_tree.domain.UnionChild
import com.moxietech.family_tree.merge.MergeConflict
import com.moxietech.family_tree.merge.MergeField
import com.moxietech.family_tree.merge.MergeSide
import com.moxietech.family_tree.merge.PersonMerge
import com.moxietech.family_tree.server.collaboration.AuditJson
import com.moxietech.family_tree.server.collaboration.AuditLogRepository
import com.moxietech.family_tree.server.config.DatabaseDispatcher
import com.moxietech.family_tree.server.errors.ConflictException
import com.moxietech.family_tree.server.errors.NotFoundException
import com.moxietech.family_tree.server.errors.ValidationException
import com.moxietech.family_tree.server.errors.parseUuidField
import com.moxietech.family_tree.server.errors.requireValid
import com.moxietech.family_tree.server.errors.staleVersion
import com.moxietech.family_tree.server.sources.CitationRepository
import com.moxietech.family_tree.server.sources.CitationTargetKind
import com.moxietech.family_tree.server.sources.CitationTargetRef
import com.moxietech.family_tree.server.sources.toDto
import com.moxietech.family_tree.server.trees.MembershipRepository
import com.moxietech.family_tree.server.trees.TreeService
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.sql.SQLException
import kotlin.uuid.Uuid

const val MERGE_SAME_PERSON_MESSAGE = "A person can't be merged with themselves"
const val MERGE_UNKNOWN_FIELD_MESSAGE = "choices names an unknown field"
const val MERGE_UNKNOWN_SIDE_MESSAGE = "Each choice must be SURVIVOR or RETIRED"

/**
 * FT-007: merges two records of one person in a tree (owner only,
 * `POST /api/v1/trees/{treeId}/people/merge`).
 *
 * The owner names the survivor and the retired person and the version of each they
 * reviewed, and chooses, for every profile field that differs, whose value is kept
 * ([PersonMerge]). Everything runs in one READ COMMITTED transaction:
 *
 * 1. [GraphWriteLock] exclusive for the tree, then both people `FOR UPDATE` in UUID
 *    order, each resolved in the URL's tree (missing or foreign: the generic 404).
 * 2. Both versions must still be the reviewed ones (409), then the choices must name
 *    exactly the differing fields (400). Choices and versions are one decision: a
 *    changed person changes what the choices mean, so it is a 409 before they are read.
 * 3. Every row referring to either person (V1-V5 + FT-002 inventory, as in
 *    [RecordDeletion]) is locked in a fixed order: unions by partner (by ID), child
 *    links (by union, child), the retired person's events (by ID), memberships (by
 *    account), the retired person's citations (by ID).
 * 4. The merged graph must stay valid ([PersonMerge.relationshipConflicts] on every
 *    union of the tree, read under the exclusive lock) and every link must keep one
 *    unambiguous value; otherwise a 409 that writes nothing.
 * 5. References move to the survivor keeping their IDs, each with its version
 *    advanced where it has one; the survivor gets the chosen values and a new
 *    version; the retired person is deleted at its reviewed version.
 * 6. One audit entry for the decision and one per changed row, in the same transaction.
 *
 * Retargeting never changes a union's type, dates or other links, so the FT-066
 * union policy (type and links visible, dates hidden with a hidden partner) applies
 * to the result unchanged.
 */
object PersonMergeService {

    suspend fun merge(treeId: TreeId, callerAccountId: AccountId, request: MergePeopleRequest): MergePeopleResponse {
        val tree = TreeService.getTreeForWriter(treeId, callerAccountId)
        // Each caller-supplied ID is parsed once here and stays typed from then on.
        val treeUuid = Uuid.parse(tree.id.value)
        val survivorUuid = parseUuidField("survivorId", request.survivorId)
        val retiredUuid = parseUuidField("retiredId", request.retiredId)
        if (survivorUuid == retiredUuid) throw ValidationException(listOf(MERGE_SAME_PERSON_MESSAGE))
        val choices = parseChoices(request.choices)
        val merge = Merge(
            treeId = treeUuid,
            actorId = Uuid.parse(callerAccountId.value),
            survivorId = survivorUuid,
            survivorVersion = request.survivorVersion,
            retiredId = retiredUuid,
            retiredVersion = request.retiredVersion,
            choices = choices,
        )
        return try {
            withContext(DatabaseDispatcher) {
                // READ COMMITTED (stated, not assumed): each statement after a lock wait
                // must see the rows the other transaction committed.
                transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) { merge.execute() }
            }
        } catch (e: SQLException) {
            // Unreachable while the retired person is locked FOR UPDATE (no new reference can
            // be added); kept so a missed reference is a 409 after rollback, never a 500.
            if (e.isForeignKeyViolation(RecordDeletion.PERSON_FOREIGN_KEYS)) {
                throw ConflictException("Person $retiredUuid is still referenced by other records; reload and try again")
            }
            throw e
        }
    }

    private fun parseChoices(raw: Map<String, String>): Map<MergeField, MergeSide> = raw.entries.associate { (field, side) ->
        val parsedField = MergeField.entries.firstOrNull { it.name == field }
            ?: throw ValidationException(listOf(MERGE_UNKNOWN_FIELD_MESSAGE))
        val parsedSide = MergeSide.entries.firstOrNull { it.name == side }
            ?: throw ValidationException(listOf(MERGE_UNKNOWN_SIDE_MESSAGE))
        parsedField to parsedSide
    }

    private class Merge(
        val treeId: Uuid,
        val actorId: Uuid,
        val survivorId: Uuid,
        val survivorVersion: Long,
        val retiredId: Uuid,
        val retiredVersion: Long,
        val choices: Map<MergeField, MergeSide>,
    ) {
        private val survivorKey = PersonId(survivorId.toString())
        private val retiredKey = PersonId(retiredId.toString())

        fun execute(): MergePeopleResponse {
            GraphWriteLock.exclusive(treeId)

            // 1-2. The two people, locked in UUID order (lower-case canonical text sorts
            // like PostgreSQL's uuid ordering), then their versions and the choices.
            val people = listOf(survivorId, retiredId).sortedBy { it.toString() }.associateWith { id ->
                PersonRepository.lockForUpdate(treeId, id) ?: throw NotFoundException("No person with id $id")
            }
            val survivor = people.getValue(survivorId)
            val retired = people.getValue(retiredId)
            if (survivor.version != survivorVersion) throw staleVersion("Person", survivor.id.value)
            if (retired.version != retiredVersion) throw staleVersion("Person", retired.id.value)
            val survivorProfile = PersonMerge.profileOf(survivor)
            val retiredProfile = PersonMerge.profileOf(retired)
            val differing = PersonMerge.differingFields(survivorProfile, retiredProfile)
            PersonMerge.choiceErrors(differing, choices).requireValid()
            if (survivor.profileMediaId != retired.profileMediaId) {
                // No route sets profile media until FT-008, which will add it as a choice.
                throw ConflictException("These people have different profile photos; a merge can't keep both yet")
            }

            // 3. Every reference to either person, locked in a fixed order.
            val both = listOf(survivorId, retiredId)
            val unions = UnionRepository.lockByPartners(treeId, both)
            val links = UnionRepository.lockChildLinksOf(treeId, both)
            val events = EventRepository.lockByPerson(treeId, retiredId)
            val memberships = MembershipRepository.lockByPeople(treeId, both)
            val citations = CitationRepository.lockOnPerson(treeId, retiredId)

            // 4. The result must be a valid graph with unambiguous links.
            val conflicts = PersonMerge.relationshipConflicts(survivorKey.value, retiredKey.value, treeUnions())
            if (conflicts.isNotEmpty()) throw ConflictException(conflictMessage(conflicts.first()))
            if (memberships.any { it.personId == survivorKey } && memberships.any { it.personId == retiredKey }) {
                throw ConflictException("Both people are linked to member accounts; only one account can be linked to the merged person")
            }

            // 5-6. Move every reference, then the survivor's profile, then retire the person.
            unions.filter { it.partner1Id == retiredKey || it.partner2Id == retiredKey }.forEach(::movePartner)
            links.filter { it.childId == retiredKey }.forEach { link ->
                val survivorLink = links.firstOrNull { it.unionId == link.unionId && it.childId == survivorKey }
                if (survivorLink == null) moveChildLink(link) else dropDuplicateChildLink(link)
            }
            events.forEach(::moveEvent)
            memberships.filter { it.personId == retiredKey }.forEach(::moveMembership)
            if (citations.isNotEmpty()) {
                // FT-002 lock contract on the new target (already held FOR UPDATE here).
                if (!CitationRepository.lockTarget(treeId, CitationTargetRef(CitationTargetKind.PERSON, survivorId))) {
                    throw NotFoundException("No person with id $survivorId")
                }
                citations.forEach { citation ->
                    val citationId = Uuid.parse(citation.id.value)
                    val after = CitationRepository.retargetPerson(treeId, citationId, retiredId, survivorId)
                        ?: error("Citation $citationId was locked but no longer targets $retiredId")
                    audit("Citation", citation.id.value, AuditAction.UPDATE, AuditJson.encodeToString(citation.toDto()), AuditJson.encodeToString(after.toDto()))
                }
            }

            val merged = PersonMerge.merged(survivorProfile, retiredProfile, choices)
            val updated = PersonRepository.update(
                treeId = treeId,
                id = survivorId,
                expectedVersion = survivor.version,
                givenNames = merged.givenNames,
                surnamePaternal = merged.surnamePaternal,
                surnameMaternal = merged.surnameMaternal,
                sex = merged.sex,
                isLiving = merged.isLiving,
                bio = merged.bio,
            ) ?: throw staleVersion("Person", survivor.id.value)
            val survivorDto = updated.toDto()
            audit("Person", survivor.id.value, AuditAction.UPDATE, AuditJson.encodeToString(survivor.toDto()), AuditJson.encodeToString(survivorDto))

            if (PersonRepository.delete(treeId, retiredId, retired.version) == 0) throw staleVersion("Person", retired.id.value)
            // Citations have no foreign key: re-check after the DELETE, as RecordDeletion does.
            if (RecordDeletion.isCited(treeId, RecordDeletion.PERSON, retiredId)) {
                throw ConflictException("Person $retiredId is still referenced by other records; reload and try again")
            }
            audit("Person", retired.id.value, AuditAction.DELETE, AuditJson.encodeToString(retired.toDto()), null)

            val decision = PersonMergeAudit(
                survivorId = survivor.id.value,
                survivorVersion = survivor.version,
                retiredId = retired.id.value,
                retiredVersion = retired.version,
                choices = differing.associate { it.name to choices.getValue(it).name },
                survivorVersionAfter = updated.version,
            )
            audit("PersonMerge", "${survivor.id.value}:${retired.id.value}", AuditAction.CREATE, null, AuditJson.encodeToString(decision))

            return MergePeopleResponse(survivor = survivorDto, retiredId = retired.id.value)
        }

        /** Every union of the tree with its child links, as the ancestry check sees them. */
        private fun treeUnions() = UnionRepository.childrenByUnion(treeId).let { childrenByUnion ->
            UnionRepository.listByTree(treeId).map { it.toDto(childrenByUnion[it.id].orEmpty()) }
        }

        private fun movePartner(union: Union) {
            val unionId = Uuid.parse(union.id.value)
            // One read of the links for both snapshots: they don't change here.
            val children = UnionRepository.childrenOf(treeId, unionId)
            val after = UnionRepository.replacePartner(treeId, union, retiredId, survivorId)
                ?: throw staleVersion("Union", union.id.value)
            audit("Union", union.id.value, AuditAction.UPDATE, AuditJson.encodeToString(union.toDto(children)), AuditJson.encodeToString(after.toDto(children)))
        }

        /**
         * The retired person's link moves to the survivor with its relation, under a fresh
         * sequence version. Audited as an update of the link's old key ("union:retired"),
         * with union-shaped snapshots from one read of the links taken after the move.
         */
        private fun moveChildLink(link: UnionChild) {
            val unionId = Uuid.parse(link.unionId.value)
            val entityId = "$unionId:$retiredId"
            val union = TreeScopedLookups.requireUnion(treeId, unionId)
            if (!UnionRepository.retargetChild(treeId, unionId, retiredId, survivorId, link.version)) {
                throw staleVersion("Child link", entityId)
            }
            val after = UnionRepository.childrenOf(treeId, unionId)
            val before = after.map { if (it.childId == survivorKey) link else it }
            audit("UnionChild", entityId, AuditAction.UPDATE, AuditJson.encodeToString(union.toDto(before)), AuditJson.encodeToString(union.toDto(after)))
        }

        /**
         * Both people are children of this union with the same relation (a different one
         * is a conflict above): the survivor's link already says everything, so the
         * retired person's link is removed, audited like a child-link delete.
         */
        private fun dropDuplicateChildLink(link: UnionChild) {
            val unionId = Uuid.parse(link.unionId.value)
            val entityId = "$unionId:$retiredId"
            val union = TreeScopedLookups.requireUnion(treeId, unionId)
            if (UnionRepository.deleteChild(treeId, unionId, retiredId, link.version) == 0) {
                throw staleVersion("Child link", entityId)
            }
            val remaining = UnionRepository.childrenOf(treeId, unionId)
            audit("UnionChild", entityId, AuditAction.DELETE, AuditJson.encodeToString(union.toDto(remaining + link)), null)
        }

        private fun moveEvent(event: Event) {
            val eventId = Uuid.parse(event.id.value)
            // One read of the place for both snapshots, as in an event update.
            val place = event.placeId?.let { PlaceRepository.findById(treeId, Uuid.parse(it.value)) }
            val after = EventRepository.retargetPerson(treeId, eventId, event.version, survivorId)
                ?: throw staleVersion("Event", event.id.value)
            audit("Event", event.id.value, AuditAction.UPDATE, AuditJson.encodeToString(event.toDto(place)), AuditJson.encodeToString(after.toDto(place)))
        }

        private fun moveMembership(membership: Membership) {
            val accountId = Uuid.parse(membership.accountId.value)
            if (MembershipRepository.retargetPerson(treeId, accountId, retiredId, survivorId) != 1) {
                error("Membership of $accountId was locked but no longer names $retiredId")
            }
            audit(
                "Membership",
                "$treeId:$accountId",
                AuditAction.UPDATE,
                AuditJson.encodeToString(membership.snapshot()),
                AuditJson.encodeToString(membership.copy(personId = survivorKey).snapshot()),
            )
        }

        private fun audit(entityType: String, entityId: String, action: AuditAction, before: String?, after: String?) =
            AuditLogRepository.append(
                treeId = treeId,
                actorAccountId = actorId,
                entityType = entityType,
                entityId = entityId,
                action = action,
                beforeJson = before,
                afterJson = after,
            )
    }

    private fun conflictMessage(conflict: MergeConflict): String = when (conflict) {
        MergeConflict.PARTNERS_OF_EACH_OTHER ->
            "These people are partners in a union; the merged person would be both partners"
        MergeConflict.PARENT_AND_CHILD ->
            "One of these people is a parent of the other; the merged person would be their own parent"
        MergeConflict.ANCESTOR_AND_DESCENDANT ->
            "One of these people is an ancestor of the other; the merged person would be their own ancestor"
        MergeConflict.CHILD_LINK_RELATION_DIFFERS ->
            "Both people are children of the same union with different relations; make them match first"
    }
}

/** The decision recorded in audit as entity `PersonMerge`, ID `survivor:retired` (FT-007). */
@Serializable
internal data class PersonMergeAudit(
    val survivorId: String,
    val survivorVersion: Long,
    val retiredId: String,
    val retiredVersion: Long,
    /** Every field that differed, with the side whose value was kept. */
    val choices: Map<String, String>,
    val survivorVersionAfter: Long,
)

/** Audit shape of a membership row (FT-007): it has no API DTO or version. */
@Serializable
internal data class MembershipAuditSnapshot(
    val treeId: String,
    val accountId: String,
    val personId: String?,
    val status: String,
)

private fun Membership.snapshot() = MembershipAuditSnapshot(treeId.value, accountId.value, personId?.value, status.name)
