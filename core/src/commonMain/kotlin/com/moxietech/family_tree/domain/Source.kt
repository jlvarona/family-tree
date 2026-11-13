package com.moxietech.family_tree.domain

/** A reusable source (a document or record), cited from many facts. */
data class Source(
    val id: SourceId,
    val treeId: TreeId,
    val title: String,
    val type: SourceType,
    val author: String?,
    val publisher: String?,
    val repository: String?,
    val url: String?,
    val date: GedcomDate?,
    val version: Long,
)

enum class SourceType {
    VITAL_RECORD,
    CENSUS,
    CHURCH_RECORD,
    NEWSPAPER,
    FAMILY_BIBLE,
    ORAL_HISTORY,
    OTHER,
}

/** Ties one fact (a person, event or union) to a [Source]. */
data class Citation(
    val id: CitationId,
    val sourceId: SourceId,
    val target: CitationTarget,
    val page: String?,
    val confidence: Confidence,
    val note: String?,
    val mediaId: MediaId?,
)

sealed class CitationTarget {
    data class OnPerson(val personId: PersonId) : CitationTarget()
    data class OnEvent(val eventId: EventId) : CitationTarget()
    data class OnUnion(val unionId: UnionId) : CitationTarget()
}

/** 0 = unreliable, 3 = primary/direct evidence. */
enum class Confidence(val level: Int) {
    UNRELIABLE(0),
    QUESTIONABLE(1),
    SECONDARY(2),
    PRIMARY(3),
}

data class SourceId(val value: String)

data class CitationId(val value: String)
