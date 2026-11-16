package com.moxietech.family_tree.domain

/**
 * A life event attached to exactly one [Person] or one [Union] (never both).
 */
data class Event(
    val id: EventId,
    val treeId: TreeId,
    val personId: PersonId?,
    val unionId: UnionId?,
    val type: EventType,
    val date: GedcomDate?,
    val placeId: PlaceId?,
    val notes: String?,
    val version: Long,
) {
    init {
        require((personId == null) != (unionId == null)) {
            "Event must belong to exactly one of personId or unionId"
        }
    }
}

enum class EventType {
    BIRTH,
    DEATH,
    MARRIAGE,
    DIVORCE,
    BAPTISM,
    BURIAL,
    IMMIGRATION,
    EMIGRATION,
    RESIDENCE,
    OCCUPATION,
    OTHER,
}

data class EventId(val value: String)

data class Place(
    val id: PlaceId,
    val treeId: TreeId,
    val name: String,
    val hierarchy: String?,
    val latitude: Double?,
    val longitude: Double?,
    val version: Long,
)

data class PlaceId(val value: String)
