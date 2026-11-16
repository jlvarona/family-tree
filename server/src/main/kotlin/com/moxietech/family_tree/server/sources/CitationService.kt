package com.moxietech.family_tree.server.sources

import com.moxietech.family_tree.api.CitationDto
import com.moxietech.family_tree.api.CreateCitationRequest
import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.AuditAction
import com.moxietech.family_tree.domain.Confidence
import com.moxietech.family_tree.domain.Person
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.domain.Union
import com.moxietech.family_tree.server.collaboration.AuditJson
import com.moxietech.family_tree.server.collaboration.AuditLogRepository
import com.moxietech.family_tree.server.config.DatabaseDispatcher
import com.moxietech.family_tree.server.errors.NotFoundException
import com.moxietech.family_tree.server.errors.ValidationException
import com.moxietech.family_tree.server.errors.parseUuidField
import com.moxietech.family_tree.server.graph.TreeScopedLookups
import com.moxietech.family_tree.server.graph.ViewerPolicy
import com.moxietech.family_tree.server.trees.TreeService
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import kotlin.uuid.Uuid

/** FT-002: 400 for a confidence outside [Confidence]'s levels. */
const val CITATION_CONFIDENCE_MESSAGE = "Citation confidence must be 0, 1, 2 or 3"

/**
 * FT-002: 400 for a non-null `mediaId`. There is no media table or upload flow yet
 * (FT-008), so a media ID can't be resolved inside the tree; accepting one would
 * store an unchecked reference that a later signed-URL route could follow.
 */
const val CITATION_MEDIA_MESSAGE = "mediaId is not supported yet; attach citation scans once photo upload exists"

/**
 * FT-002 citation routes. A citation ties one person, event or union to a source.
 *
 * Writes are owner-only (FT-068) and run in one READ COMMITTED transaction that
 * follows the FT-001 lock contract: the source and the target are each locked
 * `FOR KEY SHARE`, filtered by ID and `tree_id`, before the insert, and the audit
 * entry is written in the same transaction. A row that is missing, in another
 * tree, or deleted by a delete that committed first is the same generic 404.
 *
 * Reads are target-scoped and follow [ViewerPolicy.citationsOn]: the citations of a
 * fact are returned only when the caller can see every person the fact is about,
 * and an event whose people are hidden reads as missing (404).
 * There is deliberately no tree-wide citation listing.
 */
object CitationService {

    suspend fun createCitation(treeId: TreeId, callerAccountId: AccountId, request: CreateCitationRequest): CitationDto {
        val tree = TreeService.getTreeForWriter(treeId, callerAccountId)
        val treeUuid = Uuid.parse(tree.id.value)
        val sourceUuid = parseUuidField("sourceId", request.sourceId)
        val kind = CitationTargetKind.parse(request.targetType)
            ?: throw ValidationException(listOf("Unknown citation target type '${request.targetType}'"))
        val target = CitationTargetRef(kind, parseUuidField("targetId", request.targetId))
        val confidence = Confidence.entries.firstOrNull { it.level == request.confidence }
            ?: throw ValidationException(listOf(CITATION_CONFIDENCE_MESSAGE))
        if (request.mediaId != null) throw ValidationException(listOf(CITATION_MEDIA_MESSAGE))

        return withContext(DatabaseDispatcher) {
            // READ COMMITTED (stated, not assumed): a lock that waited for a delete must
            // re-read the row and find it gone, not fail with a serialization error.
            // Once the source lock is held, a source delete waits for this transaction,
            // so the insert's own foreign-key check on the source can't fail.
            transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
                if (!CitationRepository.lockSource(treeUuid, sourceUuid)) {
                    throw NotFoundException("No source with id $sourceUuid")
                }
                if (!CitationRepository.lockTarget(treeUuid, target)) throw target.kind.notFound(target.id)
                val citation = CitationRepository.insert(
                    treeId = treeUuid,
                    sourceId = sourceUuid,
                    target = target,
                    page = request.page,
                    confidence = confidence,
                    note = request.note,
                )
                val dto = citation.toDto()
                AuditLogRepository.append(
                    treeId = treeUuid,
                    actorAccountId = Uuid.parse(callerAccountId.value),
                    entityType = "Citation",
                    entityId = citation.id.value,
                    action = AuditAction.CREATE,
                    beforeJson = null,
                    afterJson = AuditJson.encodeToString(dto),
                )
                dto
            }
        }
    }

    /**
     * The citations on one person, event or union, for any active member. The target
     * is resolved in the URL's tree (another tree's ID is the same 404 as a missing
     * one). The target, the people it is about and its citations are read in one
     * read-only REPEATABLE READ snapshot, so visibility is decided on the same data
     * the citations come from.
     *
     * A person or union the caller can't fully see still resolves (its placeholder or
     * links are visible, FT-066), so its list is just empty. An event whose people
     * the caller can't see is omitted from every other response, so here it is the
     * same 404 as a missing event: the route can't confirm a private event exists.
     */
    internal suspend fun listCitations(
        treeId: TreeId,
        kind: CitationTargetKind,
        rawTargetId: String,
        callerAccountId: AccountId,
    ): List<CitationDto> {
        val tree = TreeService.getTreeForReader(treeId, callerAccountId)
        val treeUuid = Uuid.parse(tree.id.value)
        val target = CitationTargetRef(kind, parseUuidField(kind.pathField, rawTargetId))
        val policy = ViewerPolicy(tree, callerAccountId)
        return withContext(DatabaseDispatcher) {
            transaction(transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ, readOnly = true) {
                val subjects = subjectsOf(treeUuid, target)
                if (target.kind == CitationTargetKind.EVENT && !policy.canSeeAll(subjects)) {
                    throw target.kind.notFound(target.id)
                }
                policy.citationsOn(subjects) {
                    CitationRepository.listByTarget(treeUuid, target).map { it.toDto() }
                }
            }
        }
    }

    /** The people [target] is about; 404 when the target isn't in [treeId]. */
    private fun subjectsOf(treeId: Uuid, target: CitationTargetRef): List<Person> = when (target.kind) {
        CitationTargetKind.PERSON -> listOf(TreeScopedLookups.requirePerson(treeId, target.id))
        CitationTargetKind.UNION -> partnersOf(treeId, TreeScopedLookups.requireUnion(treeId, target.id))
        CitationTargetKind.EVENT -> {
            val event = TreeScopedLookups.requireEvent(treeId, target.id)
            val personId = event.personId
            val unionId = event.unionId
            when {
                personId != null -> listOf(TreeScopedLookups.requirePerson(treeId, Uuid.parse(personId.value)))
                unionId != null -> partnersOf(treeId, TreeScopedLookups.requireUnion(treeId, Uuid.parse(unionId.value)))
                else -> error("Event ${event.id.value} has neither a person nor a union")
            }
        }
    }

    private fun partnersOf(treeId: Uuid, union: Union): List<Person> =
        listOfNotNull(union.partner1Id, union.partner2Id).map { TreeScopedLookups.requirePerson(treeId, Uuid.parse(it.value)) }
}
