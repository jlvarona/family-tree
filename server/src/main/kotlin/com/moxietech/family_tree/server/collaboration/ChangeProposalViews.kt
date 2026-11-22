package com.moxietech.family_tree.server.collaboration

import com.moxietech.family_tree.api.ChangeProposalDto
import com.moxietech.family_tree.api.ProposalFieldDto
import com.moxietech.family_tree.domain.Person
import com.moxietech.family_tree.proposals.ProposalField
import com.moxietech.family_tree.proposals.ProposalFields
import com.moxietech.family_tree.proposals.ProposalOperation
import com.moxietech.family_tree.proposals.ProposalStatus
import com.moxietech.family_tree.server.graph.EventService
import com.moxietech.family_tree.server.graph.PersonRepository
import com.moxietech.family_tree.server.graph.UnionRepository
import com.moxietech.family_tree.server.graph.EventRepository
import com.moxietech.family_tree.server.graph.ViewerPolicy
import com.moxietech.family_tree.server.graph.partnerUuids
import com.moxietech.family_tree.api.ProposalReferenceDto
import com.moxietech.family_tree.server.graph.PlaceRepository
import com.moxietech.family_tree.server.sources.SourceRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.uuid.Uuid

/**
 * FT-014: a proposal's target as stored now, in the shape of the write's request body
 * ([values]), with its [version] and the people it is about ([subjects]).
 */
internal class CurrentRecord(val values: JsonObject, val version: Long, val subjects: List<Person>)

/**
 * FT-014: reads a proposal's target as stored now, inside the caller's transaction, in the
 * request shape its operation uses. Null for creates (no target yet; a new child link's
 * union isn't the record being changed) and for a target that no longer exists.
 */
internal object ProposalTargets {

    fun current(treeUuid: Uuid, record: ChangeProposalRecord): CurrentRecord? {
        val target = record.targetId ?: return null
        return when (record.operation) {
            ProposalOperation.UPDATE_PERSON, ProposalOperation.DELETE_PERSON -> person(treeUuid, Uuid.parse(target))
            ProposalOperation.UPDATE_UNION, ProposalOperation.DELETE_UNION -> union(treeUuid, Uuid.parse(target))
            ProposalOperation.UPDATE_CHILD, ProposalOperation.DELETE_CHILD -> {
                val (union, child) = target.split(':').map(Uuid::parse)
                childLink(treeUuid, union, child)
            }
            ProposalOperation.UPDATE_EVENT, ProposalOperation.DELETE_EVENT -> event(treeUuid, Uuid.parse(target))
            ProposalOperation.CREATE_UNION, ProposalOperation.ADD_CHILD, ProposalOperation.CREATE_EVENT,
            ProposalOperation.CREATE_CITATION -> null
        }
    }

    private fun person(treeUuid: Uuid, id: Uuid): CurrentRecord? {
        val person = PersonRepository.findById(treeUuid, id) ?: return null
        val values = JsonObject(
            mapOf(
                "givenNames" to JsonPrimitive(person.givenNames),
                "surnamePaternal" to JsonPrimitive(person.surnamePaternal),
                "surnameMaternal" to JsonPrimitive(person.surnameMaternal),
                "sex" to JsonPrimitive(person.sex.name),
                "isLiving" to JsonPrimitive(person.isLiving),
                "bio" to JsonPrimitive(person.bio),
            ),
        )
        return CurrentRecord(values, person.version, listOf(person))
    }

    private fun union(treeUuid: Uuid, id: Uuid): CurrentRecord? {
        val union = UnionRepository.findById(treeUuid, id) ?: return null
        val values = JsonObject(
            mapOf(
                "partner1Id" to JsonPrimitive(union.partner1Id.value),
                "partner2Id" to JsonPrimitive(union.partner2Id?.value),
                "type" to JsonPrimitive(union.type.name),
                "startDate" to JsonPrimitive(union.startDate?.displayText),
                "endDate" to JsonPrimitive(union.endDate?.displayText),
            ),
        )
        return CurrentRecord(values, union.version, peopleOf(treeUuid, union.partnerUuids()) ?: return null)
    }

    private fun childLink(treeUuid: Uuid, unionUuid: Uuid, childUuid: Uuid): CurrentRecord? {
        val union = UnionRepository.findById(treeUuid, unionUuid) ?: return null
        val link = UnionRepository.findChild(treeUuid, unionUuid, childUuid) ?: return null
        val people = peopleOf(treeUuid, union.partnerUuids() + childUuid) ?: return null
        return CurrentRecord(JsonObject(mapOf("relation" to JsonPrimitive(link.relation.name))), link.version, people)
    }

    private fun event(treeUuid: Uuid, id: Uuid): CurrentRecord? {
        val event = EventRepository.findById(treeUuid, id) ?: return null
        val values = JsonObject(
            mapOf(
                "personId" to JsonPrimitive(event.personId?.value),
                "unionId" to JsonPrimitive(event.unionId?.value),
                "type" to JsonPrimitive(event.type.name),
                "date" to JsonPrimitive(event.date?.displayText),
                "placeId" to JsonPrimitive(event.placeId?.value),
                "notes" to JsonPrimitive(event.notes),
            ),
        )
        return CurrentRecord(values, event.version, EventService.subjectsOfEvent(treeUuid, event))
    }

    private fun peopleOf(treeUuid: Uuid, ids: List<Uuid>): List<Person>? =
        ids.map { PersonRepository.findById(treeUuid, it) ?: return null }
}

/**
 * FT-014: a proposal as one caller may see it (decisions table "Change proposals (FT-014)").
 * - Proposed values are the proposer's own input and are shown to the proposer and to the
 *   reviewers who may see the proposal at all.
 * - The stored values ([CurrentRecord]), whether each field differs, and whether the
 *   record changed since ([ChangeProposalDto.stale]) are shown only when [policy] lets the
 *   caller see every person the record is about, the rule citations use: a proposer never
 *   learns a hidden person's values, a hidden event's fields, or even that they changed.
 * - Subjects (who is affected) are never listed.
 */
internal object ChangeProposalViews {

    /** The response to the write that queued it: the proposer's own values only. */
    fun forProposer(record: ChangeProposalRecord): ChangeProposalDto =
        dto(record, current = null, canReview = false, canWithdraw = record.status == ProposalStatus.PENDING)

    fun shaped(
        record: ChangeProposalRecord,
        current: CurrentRecord?,
        policy: ViewerPolicy,
        canReview: Boolean,
        canWithdraw: Boolean,
    ): ChangeProposalDto {
        val shown = current?.takeIf { policy.canSeeAll(it.subjects) }
        return dto(record, shown, canReview, canWithdraw).copy(references = references(record, shown, policy))
    }

    /**
     * The places, sources and events [record] names: its target (an event; a child link's
     * parts are people and a union, which the client has) and every `…Id` value in the
     * proposed values and in the stored values [shown] (only when they are shown at all).
     * Resolved in the proposal's tree only. Places and sources are tree-wide records every
     * member can list. An event is described only when [policy] lets the caller see every
     * person it is about, the rule every read uses; otherwise it is left out, exactly as
     * if it didn't exist, so a hidden event's type, date or subject never leaks.
     */
    private fun references(record: ChangeProposalRecord, shown: CurrentRecord?, policy: ViewerPolicy): List<ProposalReferenceDto> {
        val ids = linkedSetOf<Uuid>()
        record.targetId?.split(':')?.forEach { part -> parseOrNull(part)?.let(ids::add) }
        for (values in listOfNotNull(record.payload, shown?.values)) {
            for ((key, value) in values) {
                if (!key.endsWith("Id")) continue
                ((value as? JsonPrimitive)?.contentOrNull)?.let(::parseOrNull)?.let(ids::add)
            }
        }
        val tree = record.treeId
        return ids.mapNotNull { id ->
            PlaceRepository.findById(tree, id)?.let { place ->
                return@mapNotNull ProposalReferenceDto(id.toString(), "PLACE", name = place.name, detail = place.hierarchy)
            }
            SourceRepository.findById(tree, id)?.let { source ->
                return@mapNotNull ProposalReferenceDto(id.toString(), "SOURCE", name = source.title, detail = source.type.name)
            }
            val event = EventRepository.findById(tree, id) ?: return@mapNotNull null
            val subjects = runCatching { EventService.subjectsOfEvent(tree, event) }.getOrNull() ?: return@mapNotNull null
            if (!policy.canSeeAll(subjects)) return@mapNotNull null
            ProposalReferenceDto(
                id = id.toString(),
                kind = "EVENT",
                eventType = event.type.name,
                eventDate = event.date?.displayText,
                personId = event.personId?.value,
                unionId = event.unionId?.value,
            )
        }
    }

    private fun parseOrNull(raw: String): Uuid? = runCatching { Uuid.parse(raw) }.getOrNull()

    private fun dto(record: ChangeProposalRecord, current: CurrentRecord?, canReview: Boolean, canWithdraw: Boolean): ChangeProposalDto {
        val currentValues = current?.values
        val fields = record.operation.fields.map { field ->
            val proposed = if (field == ProposalField.RECORD) record.payload else ProposalFields.slice(field, record.payload)
            val shown = currentValues?.let { values ->
                if (field == ProposalField.RECORD) values else ProposalFields.slice(field, values)
            }
            ProposalFieldDto(
                field = field.name,
                proposed = proposed,
                current = shown,
                changed = if (currentValues != null && record.operation.isPerField) {
                    ProposalFields.differs(field, record.payload, currentValues)
                } else {
                    null
                },
            )
        }
        return ChangeProposalDto(
            id = record.id.toString(),
            treeId = record.treeId.toString(),
            operation = record.operation.name,
            targetId = record.targetId,
            status = record.status.name,
            reason = record.reason.name,
            proposerAccountId = record.proposer.toString(),
            reviewerAccountId = record.reviewer?.toString(),
            createdAt = record.createdAt.toString(),
            decidedAt = record.decidedAt?.toString(),
            fields = fields,
            approvedFields = record.approvedFields?.map { it.name },
            stale = if (current != null && record.status == ProposalStatus.PENDING && record.baseVersion != null) {
                current.version != record.baseVersion
            } else {
                null
            },
            canReview = canReview,
            canWithdraw = canWithdraw,
            version = record.version,
        )
    }
}
