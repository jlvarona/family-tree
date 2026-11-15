package com.moxietech.family_tree.server.sources

import com.moxietech.family_tree.api.CreateSourceRequest
import com.moxietech.family_tree.api.SourceDto
import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.AuditAction
import com.moxietech.family_tree.domain.SourceType
import com.moxietech.family_tree.domain.TreeId
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

object SourceService {

    suspend fun createSource(treeId: TreeId, callerAccountId: AccountId, request: CreateSourceRequest): SourceDto {
        TreeService.getTreeForWriter(treeId, callerAccountId)
        Validation.validateNotBlank("Source title", request.title).requireValid()
        val type = runCatching { SourceType.valueOf(request.type) }.getOrElse {
            throw ValidationException(listOf("Unknown source type '${request.type}'"))
        }
        val date = request.date?.let { GedcomDateParser.parse(it) }

        return withContext(DatabaseDispatcher) {
            transaction {
                val source = SourceRepository.insert(
                    treeId = Uuid.parse(treeId.value),
                    title = request.title,
                    type = type,
                    author = request.author,
                    publisher = request.publisher,
                    repository = request.repository,
                    url = request.url,
                    date = date,
                )
                val dto = source.toDto()
                AuditLogRepository.append(
                    treeId = Uuid.parse(treeId.value),
                    actorAccountId = Uuid.parse(callerAccountId.value),
                    entityType = "Source",
                    entityId = source.id.value,
                    action = AuditAction.CREATE,
                    beforeJson = null,
                    afterJson = AuditJson.encodeToString(dto),
                )
                dto
            }
        }
    }

    suspend fun listSources(treeId: TreeId, callerAccountId: AccountId): List<SourceDto> {
        TreeService.getTreeForReader(treeId, callerAccountId)
        return withContext(DatabaseDispatcher) {
            SourceRepository.listByTree(Uuid.parse(treeId.value))
        }.map { it.toDto() }
    }
}
