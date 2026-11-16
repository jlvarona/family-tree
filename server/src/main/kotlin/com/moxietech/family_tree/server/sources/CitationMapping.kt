package com.moxietech.family_tree.server.sources

import com.moxietech.family_tree.api.CitationDto
import com.moxietech.family_tree.domain.Citation
import com.moxietech.family_tree.domain.CitationTarget
import com.moxietech.family_tree.domain.EventId
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.UnionId
import com.moxietech.family_tree.server.errors.NotFoundException
import kotlin.uuid.Uuid

/**
 * The kinds of [CitationTarget], with their wire and database value ([name]: the
 * `citation.target_type` check constraint, equal to `RecordDeletion`'s constants) and
 * the path segment and ID field of their target-scoped listing route.
 */
internal enum class CitationTargetKind(val collection: String, val pathField: String, private val label: String) {
    PERSON("people", "personId", "person"),
    EVENT("events", "eventId", "event"),
    UNION("unions", "unionId", "union"),
    ;

    fun target(id: Uuid): CitationTarget = when (this) {
        PERSON -> CitationTarget.OnPerson(PersonId(id.toString()))
        EVENT -> CitationTarget.OnEvent(EventId(id.toString()))
        UNION -> CitationTarget.OnUnion(UnionId(id.toString()))
    }

    /** The same generic 404 as `TreeScopedLookups` gives for this kind of record. */
    fun notFound(id: Uuid) = NotFoundException("No $label with id $id")

    companion object {
        /** Exact, case-sensitive match on the wire value, like every other enum field in the API. */
        fun parse(wire: String): CitationTargetKind? = entries.firstOrNull { it.name == wire }

        fun of(target: CitationTarget): CitationTargetKind = when (target) {
            is CitationTarget.OnPerson -> PERSON
            is CitationTarget.OnEvent -> EVENT
            is CitationTarget.OnUnion -> UNION
        }
    }
}

/** A citation target whose ID was parsed once from the request and stays typed from then on. */
internal data class CitationTargetRef(val kind: CitationTargetKind, val id: Uuid)

private val CitationTarget.idValue: String
    get() = when (this) {
        is CitationTarget.OnPerson -> personId.value
        is CitationTarget.OnEvent -> eventId.value
        is CitationTarget.OnUnion -> unionId.value
    }

fun Citation.toDto() = CitationDto(
    id = id.value,
    sourceId = sourceId.value,
    targetType = CitationTargetKind.of(target).name,
    targetId = target.idValue,
    page = page,
    confidence = confidence.level,
    note = note,
    mediaId = mediaId?.value,
)
