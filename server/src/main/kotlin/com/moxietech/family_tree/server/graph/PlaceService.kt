package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.api.CreatePlaceRequest
import com.moxietech.family_tree.api.PlaceDto
import com.moxietech.family_tree.api.UpdatePlaceRequest
import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.AuditAction
import com.moxietech.family_tree.domain.PlaceId
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.server.collaboration.AuditJson
import com.moxietech.family_tree.server.collaboration.AuditLogRepository
import com.moxietech.family_tree.server.config.DatabaseDispatcher
import com.moxietech.family_tree.server.errors.parseUuidField
import com.moxietech.family_tree.server.errors.requireValid
import com.moxietech.family_tree.server.errors.staleVersion
import com.moxietech.family_tree.server.trees.TreeService
import com.moxietech.family_tree.validation.Validation
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

object PlaceService {

    suspend fun createPlace(treeId: TreeId, callerAccountId: AccountId, request: CreatePlaceRequest): PlaceDto {
        TreeService.getTreeForWriter(treeId, callerAccountId)
        Validation.validateNotBlank("Place name", request.name).requireValid()
        return withContext(DatabaseDispatcher) {
            // One transaction for the place and its audit entry: a failed audit
            // insert rolls back the place (FT-069). The repository's own
            // transaction { } joins this one rather than committing separately.
            transaction {
                val treeUuid = Uuid.parse(treeId.value)
                val place = PlaceRepository.insert(
                    treeId = treeUuid,
                    name = request.name,
                    hierarchy = request.hierarchy,
                    latitude = request.latitude,
                    longitude = request.longitude,
                )
                val dto = place.toDto()
                AuditLogRepository.append(
                    treeId = treeUuid,
                    actorAccountId = Uuid.parse(callerAccountId.value),
                    entityType = "Place",
                    entityId = place.id.value,
                    action = AuditAction.CREATE,
                    beforeJson = null,
                    afterJson = AuditJson.encodeToString(dto),
                )
                dto
            }
        }
    }

    /**
     * FT-001: replaces a place's fields, owner only, with the same validation as
     * [createPlace]. The place is resolved inside [treeId]; a version that is no
     * longer the stored one is a 409 that changes neither the place nor the
     * audit log. Events that point at the place show the new values.
     */
    suspend fun updatePlace(
        treeId: TreeId,
        placeId: PlaceId,
        callerAccountId: AccountId,
        request: UpdatePlaceRequest,
    ): PlaceDto {
        TreeService.getTreeForWriter(treeId, callerAccountId)
        val placeUuid = parseUuidField("placeId", placeId.value)
        Validation.validateNotBlank("Place name", request.name).requireValid()
        return withContext(DatabaseDispatcher) {
            transaction {
                val treeUuid = Uuid.parse(treeId.value)
                val before = TreeScopedLookups.requirePlace(treeUuid, placeUuid)
                if (before.version != request.version) throw staleVersion("Place", before.id.value)
                val after = PlaceRepository.update(
                    treeId = treeUuid,
                    id = placeUuid,
                    expectedVersion = request.version,
                    name = request.name,
                    hierarchy = request.hierarchy,
                    latitude = request.latitude,
                    longitude = request.longitude,
                ) ?: throw staleVersion("Place", before.id.value)
                val dto = after.toDto()
                AuditLogRepository.append(
                    treeId = treeUuid,
                    actorAccountId = Uuid.parse(callerAccountId.value),
                    entityType = "Place",
                    entityId = after.id.value,
                    action = AuditAction.UPDATE,
                    beforeJson = AuditJson.encodeToString(before.toDto()),
                    afterJson = AuditJson.encodeToString(dto),
                )
                dto
            }
        }
    }

    suspend fun listPlaces(treeId: TreeId, callerAccountId: AccountId): List<PlaceDto> {
        TreeService.getTreeForReader(treeId, callerAccountId)
        return withContext(DatabaseDispatcher) {
            PlaceRepository.listByTree(Uuid.parse(treeId.value))
        }.map { it.toDto() }
    }
}
