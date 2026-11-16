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

    suspend fun createUnion(treeId: TreeId, callerAccountId: AccountId, request: CreateUnionRequest): UnionDto {
        TreeService.getTreeForWriter(treeId, callerAccountId)
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
            transaction {
                val treeUuid = Uuid.parse(treeId.value)
                TreeScopedLookups.requirePerson(treeUuid, partner1Uuid)
                partner2Uuid?.let { TreeScopedLookups.requirePerson(treeUuid, it) }
                val union = UnionRepository.insert(
                    treeId = treeUuid,
                    partner1Id = partner1Uuid,
                    partner2Id = partner2Uuid,
                    type = type,
                    startDate = startDate,
                    endDate = endDate,
                )
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
                dto
            }
        }
    }

    suspend fun addChild(
        treeId: TreeId,
        unionId: UnionId,
        callerAccountId: AccountId,
        request: AddUnionChildRequest,
    ): UnionDto {
        TreeService.getTreeForWriter(treeId, callerAccountId)
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
            transaction {
                val treeUuid = Uuid.parse(treeId.value)
                // Resolve the union before parsing the body's child ID: a missing union
                // stays a 404 even when childId is malformed (PR #3).
                val union = TreeScopedLookups.requireUnion(treeUuid, unionUuid)
                val childUuid = parseUuidField("childId", rawChildId)
                TreeScopedLookups.requirePerson(treeUuid, childUuid)
                UnionRepository.addChild(
                    treeId = treeUuid,
                    unionId = unionUuid,
                    childId = childUuid,
                    relation = relation,
                )
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
                dto
            }
        }

    /**
     * FT-001: replaces a union's fields, owner only, with the same validation as
     * [createUnion]. The union is resolved inside [treeId] before the body's
     * partner IDs are parsed, so a missing union stays a 404 (as in [addChild]);
     * both partners must be in the same tree. A version that is no longer the
     * stored one is a 409 that changes neither the union nor the audit log.
     * Child links keep their own versions: the audit's before and after carry the
     * same child list, read once in this transaction.
     */
    suspend fun updateUnion(
        treeId: TreeId,
        unionId: UnionId,
        callerAccountId: AccountId,
        request: UpdateUnionRequest,
    ): UnionDto {
        TreeService.getTreeForWriter(treeId, callerAccountId)
        val unionUuid = parseUuidField("unionId", unionId.value)
        val type = runCatching { UnionType.valueOf(request.type) }.getOrElse {
            throw ValidationException(listOf("Unknown union type '${request.type}'"))
        }
        val startDate = request.startDate?.let { GedcomDateParser.parse(it) }
        val endDate = request.endDate?.let { GedcomDateParser.parse(it) }
        Validation.validateDateOrder(startDate, endDate).requireValid()

        return withContext(DatabaseDispatcher) {
            transaction {
                val treeUuid = Uuid.parse(treeId.value)
                val before = TreeScopedLookups.requireUnion(treeUuid, unionUuid)
                val partner1Uuid = parseUuidField("partner1Id", request.partner1Id)
                val partner2Uuid = parseOptionalUuidField("partner2Id", request.partner2Id)
                if (partner1Uuid == partner2Uuid) {
                    throw ValidationException(listOf("A union's two partners must be different people"))
                }
                TreeScopedLookups.requirePerson(treeUuid, partner1Uuid)
                partner2Uuid?.let { TreeScopedLookups.requirePerson(treeUuid, it) }
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
                dto
            }
        }
    }

    /**
     * FT-001: changes how [childId] belongs to [unionId] (the relation), owner
     * only. The union and child are the link's identity, taken from the path and
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
        TreeService.getTreeForWriter(treeId, callerAccountId)
        val unionUuid = parseUuidField("unionId", unionId.value)
        val childUuid = parseUuidField("childId", childId.value)
        val relation = runCatching { ChildRelation.valueOf(request.relation) }.getOrElse {
            throw ValidationException(listOf("Unknown child relation '${request.relation}'"))
        }

        return withContext(DatabaseDispatcher) {
            transaction {
                val treeUuid = Uuid.parse(treeId.value)
                val union = TreeScopedLookups.requireUnion(treeUuid, unionUuid)
                val link = UnionRepository.findChild(treeUuid, unionUuid, childUuid)
                    ?: throw NotFoundException("No child $childUuid in union $unionUuid")
                val entityId = "$unionUuid:$childUuid"
                if (link.version != request.version) throw staleVersion("Child link", entityId)
                if (!UnionRepository.updateChild(treeUuid, unionUuid, childUuid, request.version, relation)) {
                    throw staleVersion("Child link", entityId)
                }
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
                dto
            }
        }
    }

    /**
     * FT-001: deletes a union with no child links, events or citations, owner
     * only, at the current [version] (see [RecordDeletion]). The audit's before
     * is the union with its (necessarily empty) child list.
     */
    suspend fun deleteUnion(treeId: TreeId, unionId: UnionId, callerAccountId: AccountId, version: List<String>?) {
        TreeService.getTreeForWriter(treeId, callerAccountId)
        val unionUuid = parseUuidField("unionId", unionId.value)
        val expected = parseVersionParam(version)
        val treeUuid = Uuid.parse(treeId.value)
        RecordDeletion.guarded("Union", unionUuid.toString(), RecordDeletion.UNION_FOREIGN_KEYS) {
            val before = TreeScopedLookups.requireUnion(treeUuid, unionUuid)
            if (before.version != expected) throw staleVersion("Union", before.id.value)
            if (RecordDeletion.unionIsReferenced(treeUuid, unionUuid)) throw referenced("Union", before.id.value)
            if (UnionRepository.delete(treeUuid, unionUuid, expected) == 0) throw staleVersion("Union", before.id.value)
            if (RecordDeletion.isCited(treeUuid, RecordDeletion.UNION, unionUuid)) throw referenced("Union", before.id.value)
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
     * FT-001: removes one child link, owner only, at the link's current
     * [version]. Nothing references a link, so only a missing or stale link
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
        TreeService.getTreeForWriter(treeId, callerAccountId)
        val unionUuid = parseUuidField("unionId", unionId.value)
        val childUuid = parseUuidField("childId", childId.value)
        val expected = parseVersionParam(version)
        val treeUuid = Uuid.parse(treeId.value)
        val entityId = "$unionUuid:$childUuid"
        RecordDeletion.guarded("Child link", entityId, emptySet()) {
            val union = TreeScopedLookups.requireUnion(treeUuid, unionUuid)
            val link = UnionRepository.findChild(treeUuid, unionUuid, childUuid)
                ?: throw NotFoundException("No child $childUuid in union $unionUuid")
            if (link.version != expected) throw staleVersion("Child link", entityId)
            if (UnionRepository.deleteChild(treeUuid, unionUuid, childUuid, expected) == 0) {
                throw staleVersion("Child link", entityId)
            }
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

    suspend fun listUnions(treeId: TreeId, callerAccountId: AccountId): List<UnionDto> {
        val tree = TreeService.getTreeForReader(treeId, callerAccountId)
        val policy = ViewerPolicy(tree, callerAccountId)
        return withContext(DatabaseDispatcher) {
            // One snapshot for people and unions, so a living partner committed between
            // the two queries can't be missing from the hidden set (FT-004 review).
            transaction(transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ, readOnly = true) {
                val treeUuid = Uuid.parse(treeId.value)
                val hidden = policy.hiddenIds(PersonRepository.listByTree(treeUuid))
                UnionRepository.listByTree(treeUuid).map { union ->
                    policy.project(union, UnionRepository.childrenOf(treeUuid, Uuid.parse(union.id.value)), hidden)
                }
            }
        }
    }
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
