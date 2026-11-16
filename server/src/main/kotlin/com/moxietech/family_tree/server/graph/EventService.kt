package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.api.CreateEventRequest
import com.moxietech.family_tree.api.EventDto
import com.moxietech.family_tree.api.UpdateEventRequest
import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.AuditAction
import com.moxietech.family_tree.domain.EventId
import com.moxietech.family_tree.domain.EventType
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.format.GedcomDateParser
import com.moxietech.family_tree.server.collaboration.AuditJson
import com.moxietech.family_tree.server.collaboration.AuditLogRepository
import com.moxietech.family_tree.server.config.DatabaseDispatcher
import com.moxietech.family_tree.server.errors.ValidationException
import com.moxietech.family_tree.server.errors.parseOptionalUuidField
import com.moxietech.family_tree.server.errors.parseUuidField
import com.moxietech.family_tree.server.errors.staleVersion
import com.moxietech.family_tree.server.trees.TreeService
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

object EventService {

    suspend fun createEvent(treeId: TreeId, callerAccountId: AccountId, request: CreateEventRequest): EventDto {
        TreeService.getTreeForWriter(treeId, callerAccountId)
        if ((request.personId == null) == (request.unionId == null)) {
            throw ValidationException(listOf("An event must belong to exactly one of personId or unionId"))
        }
        val type = runCatching { EventType.valueOf(request.type) }.getOrElse {
            throw ValidationException(listOf("Unknown event type '${request.type}'"))
        }
        val personUuid = parseOptionalUuidField("personId", request.personId)
        val unionUuid = parseOptionalUuidField("unionId", request.unionId)
        val placeUuid = parseOptionalUuidField("placeId", request.placeId)
        val date = request.date?.let { GedcomDateParser.parse(it) }

        return withContext(DatabaseDispatcher) {
            transaction {
                val treeUuid = Uuid.parse(treeId.value)
                personUuid?.let { TreeScopedLookups.requirePerson(treeUuid, it) }
                unionUuid?.let { TreeScopedLookups.requireUnion(treeUuid, it) }
                val place = placeUuid?.let { TreeScopedLookups.requirePlace(treeUuid, it) }
                val event = EventRepository.insert(
                    treeId = treeUuid,
                    personId = personUuid,
                    unionId = unionUuid,
                    type = type,
                    date = date,
                    placeId = placeUuid,
                    notes = request.notes,
                )
                val dto = event.toDto(place)
                AuditLogRepository.append(
                    treeId = treeUuid,
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

    /**
     * FT-001: replaces an event's fields, owner only, with the same validation as
     * [createEvent]: exactly one of person or union, each resolved inside
     * [treeId] along with the place. The event itself is resolved before the
     * body's IDs are parsed, so a missing event stays a 404. A version that is
     * no longer the stored one is a 409 that changes neither the event nor the
     * audit log.
     */
    suspend fun updateEvent(
        treeId: TreeId,
        eventId: EventId,
        callerAccountId: AccountId,
        request: UpdateEventRequest,
    ): EventDto {
        TreeService.getTreeForWriter(treeId, callerAccountId)
        val eventUuid = parseUuidField("eventId", eventId.value)
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
                val before = TreeScopedLookups.requireEvent(treeUuid, eventUuid)
                val beforePlace = before.placeId?.let { PlaceRepository.findById(treeUuid, Uuid.parse(it.value)) }
                val personUuid = parseOptionalUuidField("personId", request.personId)
                val unionUuid = parseOptionalUuidField("unionId", request.unionId)
                val placeUuid = parseOptionalUuidField("placeId", request.placeId)
                personUuid?.let { TreeScopedLookups.requirePerson(treeUuid, it) }
                unionUuid?.let { TreeScopedLookups.requireUnion(treeUuid, it) }
                // An unchanged place reuses the one read for "before", so a concurrent place
                // edit can't show up in this event's audit entry as if the event changed it.
                val place = when {
                    placeUuid == null -> null
                    beforePlace != null && placeUuid.toString() == beforePlace.id.value -> beforePlace
                    else -> TreeScopedLookups.requirePlace(treeUuid, placeUuid)
                }
                if (before.version != request.version) throw staleVersion("Event", before.id.value)
                val after = EventRepository.update(
                    treeId = treeUuid,
                    id = eventUuid,
                    expectedVersion = request.version,
                    personId = personUuid,
                    unionId = unionUuid,
                    type = type,
                    date = date,
                    placeId = placeUuid,
                    notes = request.notes,
                ) ?: throw staleVersion("Event", before.id.value)
                val dto = after.toDto(place)
                AuditLogRepository.append(
                    treeId = treeUuid,
                    actorAccountId = Uuid.parse(callerAccountId.value),
                    entityType = "Event",
                    entityId = after.id.value,
                    action = AuditAction.UPDATE,
                    beforeJson = AuditJson.encodeToString(before.toDto(beforePlace)),
                    afterJson = AuditJson.encodeToString(dto),
                )
                dto
            }
        }
    }

    suspend fun listEventsForPerson(treeId: TreeId, personId: PersonId, callerAccountId: AccountId): List<EventDto> {
        val tree = TreeService.getTreeForReader(treeId, callerAccountId)
        return withContext(DatabaseDispatcher) {
            transaction {
                val treeUuid = Uuid.parse(treeId.value)
                val personUuid = parseUuidField("personId", personId.value)
                val person = TreeScopedLookups.requirePerson(treeUuid, personUuid)
                // A hidden living person still resolves (their placeholder is visible), but
                // none of their events are returned: no dates, places or notes (FT-066).
                ViewerPolicy(tree, callerAccountId).eventsOf(person) {
                    EventRepository.listByPerson(treeUuid, personUuid).map { event ->
                        val place = event.placeId?.let { PlaceRepository.findById(treeUuid, Uuid.parse(it.value)) }
                        event.toDto(place)
                    }
                }
            }
        }
    }
}
