package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.api.AddUnionChildRequest
import com.moxietech.family_tree.api.CreateUnionRequest
import com.moxietech.family_tree.api.UnionDto
import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.AuditAction
import com.moxietech.family_tree.domain.ChildRelation
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.domain.UnionId
import com.moxietech.family_tree.domain.UnionType
import com.moxietech.family_tree.format.GedcomDateParser
import com.moxietech.family_tree.server.collaboration.AuditJson
import com.moxietech.family_tree.server.collaboration.AuditLogRepository
import com.moxietech.family_tree.server.config.DatabaseDispatcher
import com.moxietech.family_tree.server.errors.ConflictException
import com.moxietech.family_tree.server.errors.ValidationException
import com.moxietech.family_tree.server.errors.parseOptionalUuidField
import com.moxietech.family_tree.server.errors.parseUuidField
import com.moxietech.family_tree.server.errors.requireValid
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
