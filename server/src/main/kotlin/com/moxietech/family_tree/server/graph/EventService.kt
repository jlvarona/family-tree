package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.api.CreateEventRequest
import com.moxietech.family_tree.api.EventDto
import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.AuditAction
import com.moxietech.family_tree.domain.EventType
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.format.GedcomDateParser
import com.moxietech.family_tree.server.collaboration.AuditJson
import com.moxietech.family_tree.server.collaboration.AuditLogRepository
import com.moxietech.family_tree.server.config.DatabaseDispatcher
import com.moxietech.family_tree.server.errors.ValidationException
import com.moxietech.family_tree.server.trees.TreeService
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

object EventService {

    suspend fun createEvent(treeId: TreeId, callerAccountId: AccountId, request: CreateEventRequest): EventDto {
        TreeService.getTreeForMember(treeId, callerAccountId)
        if ((request.personId == null) == (request.unionId == null)) {
            throw ValidationException(listOf("An event must belong to exactly one of personId or unionId"))
        }
        val type = runCatching { EventType.valueOf(request.type) }.getOrElse {
            throw ValidationException(listOf("Unknown event type '${request.type}'"))
        }
        val date = request.date?.let { GedcomDateParser.parse(it) }

        return withContext(DatabaseDispatcher) {
            transaction {
                val treeUuid = Uuid.parse(treeId.value)
                val person = request.personId?.let { TreeScopedLookups.requirePerson(treeUuid, Uuid.parse(it)) }
                val union = request.unionId?.let { TreeScopedLookups.requireUnion(treeUuid, Uuid.parse(it)) }
                val place = request.placeId?.let { TreeScopedLookups.requirePlace(treeUuid, Uuid.parse(it)) }
                val event = EventRepository.insert(
                    treeId = treeUuid,
                    personId = person?.let { Uuid.parse(it.id.value) },
                    unionId = union?.let { Uuid.parse(it.id.value) },
                    type = type,
                    date = date,
                    placeId = place?.let { Uuid.parse(it.id.value) },
                    notes = request.notes,
                )
                val dto = event.toDto(place)
                AuditLogRepository.append(
                    treeId = Uuid.parse(treeId.value),
                    actorAccountId = Uuid.parse(callerAccountId.value),
                    entityType = "Event",
                    entityId = event.id.value,
                    action = AuditAction.CREATE,
                    beforeJson = null,
                    afterJson = AuditJson.encodeToString(dto),
                )
                dto
            }
        }
    }

    suspend fun listEventsForPerson(treeId: TreeId, personId: PersonId, callerAccountId: AccountId): List<EventDto> {
        TreeService.getTreeForMember(treeId, callerAccountId)
        return withContext(DatabaseDispatcher) {
            transaction {
                val treeUuid = Uuid.parse(treeId.value)
                val person = TreeScopedLookups.requirePerson(treeUuid, Uuid.parse(personId.value))
                EventRepository.listByPerson(treeUuid, Uuid.parse(person.id.value)).map { event ->
                    val place = event.placeId?.let { PlaceRepository.findById(treeUuid, Uuid.parse(it.value)) }
                    event.toDto(place)
                }
            }
        }
    }
}
