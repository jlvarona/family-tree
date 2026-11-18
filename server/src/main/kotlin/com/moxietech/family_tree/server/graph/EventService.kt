package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.api.CreateEventRequest
import com.moxietech.family_tree.api.EventDto
import com.moxietech.family_tree.api.UpdateEventRequest
import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.AuditAction
import com.moxietech.family_tree.domain.Event
import com.moxietech.family_tree.domain.EventId
import com.moxietech.family_tree.domain.Person
import com.moxietech.family_tree.domain.EventType
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.format.GedcomDateParser
import com.moxietech.family_tree.server.collaboration.AuditJson
import com.moxietech.family_tree.server.collaboration.AuditLogRepository
import com.moxietech.family_tree.server.config.DatabaseDispatcher
import com.moxietech.family_tree.server.errors.NotFoundException
import com.moxietech.family_tree.server.errors.ValidationException
import com.moxietech.family_tree.server.errors.parseOptionalUuidField
import com.moxietech.family_tree.server.errors.parseUuidField
import com.moxietech.family_tree.server.errors.parseVersionParam
import com.moxietech.family_tree.server.errors.referenced
import com.moxietech.family_tree.server.errors.staleVersion
import com.moxietech.family_tree.server.permissions.ReadAccess
import com.moxietech.family_tree.server.permissions.WriteAccess
import com.moxietech.family_tree.server.permissions.WriteScope
import com.moxietech.family_tree.server.trees.TreeService
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import kotlin.uuid.Uuid

object EventService {

    /**
     * FT-012: the caller needs live access to the event's person, or to a partner of its
     * union. Places are tree-wide and may be referenced by anyone who can write the event.
     * Event responses carry only the values the caller sent (and the place, which every
     * member can list), so they aren't projected.
     */
    suspend fun createEvent(treeId: TreeId, callerAccountId: AccountId, request: CreateEventRequest): EventDto {
        TreeService.getTreeForEditor(treeId, callerAccountId)
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
            transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
                val treeUuid = Uuid.parse(treeId.value)
                // Waits for a person merge in this tree, so a retired subject is a 404 (FT-007).
                val scope = WriteAccess.begin(treeUuid, callerAccountId, ownerTakesSharedLock = true)
                personUuid?.let { TreeScopedLookups.requirePerson(treeUuid, it) }
                unionUuid?.let { TreeScopedLookups.requireUnion(treeUuid, it) }
                val place = placeUuid?.let { TreeScopedLookups.requirePlace(treeUuid, it) }
                scope.requireFact(subjectsOf(treeUuid, personUuid, unionUuid))
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
     * FT-001: replaces an event's fields with the same validation as
     * [createEvent]: exactly one of person or union, each resolved inside
     * [treeId] along with the place. The event itself is resolved before the
     * body's IDs are parsed, so a missing event stays a 404. A version that is
     * no longer the stored one is a 409 that changes neither the event nor the
     * audit log.
     *
     * FT-012: the caller needs live access to the stored event's subject and to the
     * replacement's, so an event can't be moved onto, or off, a person outside their
     * branches. A stored event the caller can't see at all is the same 404 as a missing
     * one.
     */
    suspend fun updateEvent(
        treeId: TreeId,
        eventId: EventId,
        callerAccountId: AccountId,
        request: UpdateEventRequest,
    ): EventDto {
        TreeService.getTreeForEditor(treeId, callerAccountId)
        val eventUuid = parseUuidField("eventId", eventId.value)

        return withContext(DatabaseDispatcher) {
            transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
                val treeUuid = Uuid.parse(treeId.value)
                // Taken before the event's row lock, in the same order as a merge (FT-007).
                val scope = WriteAccess.begin(treeUuid, callerAccountId, ownerTakesSharedLock = true)
                val before = TreeScopedLookups.requireEvent(treeUuid, eventUuid)
                requireStoredEvent(scope, treeUuid, before)
                // FT-012: the body's values only once the stored event's branch allows the change.
                if ((request.personId == null) == (request.unionId == null)) {
                    throw ValidationException(listOf("An event must belong to exactly one of personId or unionId"))
                }
                val type = runCatching { EventType.valueOf(request.type) }.getOrElse {
                    throw ValidationException(listOf("Unknown event type '${request.type}'"))
                }
                val date = request.date?.let { GedcomDateParser.parse(it) }
                val beforePlace = before.placeId?.let { PlaceRepository.findById(treeUuid, Uuid.parse(it.value)) }
                val personUuid = parseOptionalUuidField("personId", request.personId)
                val unionUuid = parseOptionalUuidField("unionId", request.unionId)
                val placeUuid = parseOptionalUuidField("placeId", request.placeId)
                personUuid?.let { TreeScopedLookups.requirePerson(treeUuid, it) }
                unionUuid?.let { TreeScopedLookups.requireUnion(treeUuid, it) }
                val replacementSubjects = subjectsOf(treeUuid, personUuid, unionUuid)
                scope.requireFact(replacementSubjects)
                // FT-013: the event keeps its ID and citations; moving it off a hidden person
                // would let those be read through its new subject.
                scope.requireNoHiddenSubjectRemoved(
                    storedSubjectsOf(treeUuid, before),
                    replacementSubjects.map { Uuid.parse(it.id.value) },
                )
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

    /**
     * FT-001: deletes an event no citation targets at the current [version] (see
     * [RecordDeletion]). FT-012: live access to its subject, as for an update. The
     * audit's before embeds its place.
     */
    suspend fun deleteEvent(treeId: TreeId, eventId: EventId, callerAccountId: AccountId, version: List<String>?) {
        TreeService.getTreeForEditor(treeId, callerAccountId)
        val eventUuid = parseUuidField("eventId", eventId.value)
        val treeUuid = Uuid.parse(treeId.value)
        // Nothing references an event through a foreign key; only citations can block it.
        RecordDeletion.guarded("Event", eventUuid.toString(), emptySet()) {
            // The owner takes no graph lock here, as before FT-012; anyone else takes it
            // exclusive so the branches decided on can't change before the commit.
            val scope = WriteAccess.begin(treeUuid, callerAccountId, ownerTakesSharedLock = false)
            val before = TreeScopedLookups.requireEvent(treeUuid, eventUuid)
            requireStoredEvent(scope, treeUuid, before)
            // FT-012: the version is checked only once the branch allows the delete.
            val expected = parseVersionParam(version)
            if (before.version != expected) throw staleVersion("Event", before.id.value)
            if (RecordDeletion.isCited(treeUuid, RecordDeletion.EVENT, eventUuid)) throw referenced("Event", before.id.value)
            val place = before.placeId?.let { PlaceRepository.findById(treeUuid, Uuid.parse(it.value)) }
            if (EventRepository.delete(treeUuid, eventUuid, expected) == 0) throw staleVersion("Event", before.id.value)
            if (RecordDeletion.isCited(treeUuid, RecordDeletion.EVENT, eventUuid)) throw referenced("Event", before.id.value)
            AuditLogRepository.append(
                treeId = treeUuid,
                actorAccountId = Uuid.parse(callerAccountId.value),
                entityType = "Event",
                entityId = before.id.value,
                action = AuditAction.DELETE,
                beforeJson = AuditJson.encodeToString(before.toDto(place)),
                afterJson = null,
            )
        }
    }

    /** The people an event about [personUuid] or [unionUuid] is about (resolved in the tree; 404 if gone). */
    private fun subjectsOf(treeUuid: Uuid, personUuid: Uuid?, unionUuid: Uuid?): List<Person> = when {
        personUuid != null -> listOf(TreeScopedLookups.requirePerson(treeUuid, personUuid))
        unionUuid != null -> TreeScopedLookups.requireUnion(treeUuid, unionUuid).partnerUuids()
            .map { TreeScopedLookups.requirePerson(treeUuid, it) }
        else -> throw ValidationException(listOf("An event must belong to exactly one of personId or unionId"))
    }

    /** FT-012: live access to a stored event's subject; an event hidden from the caller is the missing-event 404. */
    private fun requireStoredEvent(scope: WriteScope, treeUuid: Uuid, event: Event) {
        scope.requireFact(storedSubjectsOf(treeUuid, event)) { NotFoundException("No event with id ${event.id.value}") }
    }

    private fun storedSubjectsOf(treeUuid: Uuid, event: Event): List<Person> = subjectsOf(
        treeUuid,
        event.personId?.let { Uuid.parse(it.value) },
        event.unionId?.let { Uuid.parse(it.value) },
    )

    /**
     * A person's own events. FT-013: the membership check, the caller's grant scopes, the
     * person and the events come from one read-only snapshot ([ReadAccess]); the events
     * of a hidden person aren't read at all.
     */
    suspend fun listEventsForPerson(treeId: TreeId, personId: PersonId, callerAccountId: AccountId): List<EventDto> =
        ReadAccess.read(treeId, callerAccountId) { tree, policy ->
            val treeUuid = Uuid.parse(tree.id.value)
            val personUuid = parseUuidField("personId", personId.value)
            val person = TreeScopedLookups.requirePerson(treeUuid, personUuid)
            // A hidden living person still resolves (their placeholder is visible), but
            // none of their events are returned: no dates, places or notes (FT-066).
            policy.eventsOf(person) {
                EventRepository.listByPerson(treeUuid, personUuid).map { event ->
                    val place = event.placeId?.let { PlaceRepository.findById(treeUuid, Uuid.parse(it.value)) }
                    event.toDto(place)
                }
            }
        }
}
