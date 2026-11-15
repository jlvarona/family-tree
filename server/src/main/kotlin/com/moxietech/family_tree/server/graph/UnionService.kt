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
import com.moxietech.family_tree.server.errors.ValidationException
import com.moxietech.family_tree.server.errors.requireValid
import com.moxietech.family_tree.server.trees.TreeService
import com.moxietech.family_tree.validation.Validation
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

object UnionService {

    suspend fun createUnion(treeId: TreeId, callerAccountId: AccountId, request: CreateUnionRequest): UnionDto {
        TreeService.getTreeForWriter(treeId, callerAccountId)
        if (request.partner2Id != null && request.partner1Id == request.partner2Id) {
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
                val partner1Uuid = Uuid.parse(request.partner1Id)
                val partner2Uuid = request.partner2Id?.let { Uuid.parse(it) }
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
        return withContext(DatabaseDispatcher) {
            transaction {
                val treeUuid = Uuid.parse(treeId.value)
                val unionUuid = Uuid.parse(unionId.value)
                // Resolve the union before touching the body: a missing union stays a 404
                // even when childId is malformed (malformed-ID 400s are FT-070's scope).
                val union = TreeScopedLookups.requireUnion(treeUuid, unionUuid)
                val childUuid = Uuid.parse(request.childId)
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
    }

    suspend fun listUnions(treeId: TreeId, callerAccountId: AccountId): List<UnionDto> {
        TreeService.getTreeForReader(treeId, callerAccountId)
        return withContext(DatabaseDispatcher) {
            transaction {
                val treeUuid = Uuid.parse(treeId.value)
                UnionRepository.listByTree(treeUuid).map { union ->
                    union.toDto(UnionRepository.childrenOf(treeUuid, Uuid.parse(union.id.value)))
                }
            }
        }
    }
}
