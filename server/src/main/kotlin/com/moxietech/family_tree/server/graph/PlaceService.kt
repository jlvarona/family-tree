package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.api.CreatePlaceRequest
import com.moxietech.family_tree.api.PlaceDto
import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.server.config.DatabaseDispatcher
import com.moxietech.family_tree.server.errors.requireValid
import com.moxietech.family_tree.server.trees.TreeService
import com.moxietech.family_tree.validation.Validation
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

object PlaceService {

    suspend fun createPlace(treeId: TreeId, callerAccountId: AccountId, request: CreatePlaceRequest): PlaceDto {
        TreeService.getTreeForWriter(treeId, callerAccountId)
        Validation.validateNotBlank("Place name", request.name).requireValid()
        return withContext(DatabaseDispatcher) {
            PlaceRepository.insert(
                treeId = Uuid.parse(treeId.value),
                name = request.name,
                hierarchy = request.hierarchy,
                latitude = request.latitude,
                longitude = request.longitude,
            )
        }.toDto()
    }

    suspend fun listPlaces(treeId: TreeId, callerAccountId: AccountId): List<PlaceDto> {
        TreeService.getTreeForReader(treeId, callerAccountId)
        return withContext(DatabaseDispatcher) {
            PlaceRepository.listByTree(Uuid.parse(treeId.value))
        }.map { it.toDto() }
    }
}
