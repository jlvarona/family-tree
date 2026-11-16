package com.moxietech.family_tree.api

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
data class EventDto(
    val id: String,
    val treeId: String,
    val personId: String?,
    val unionId: String?,
    val type: String,
    val date: GedcomDateDto?,
    val place: PlaceDto?,
    val notes: String?,
    val version: Long,
)

@Serializable
data class CreateEventRequest(
    val personId: String? = null,
    val unionId: String? = null,
    val type: String,
    val date: String? = null,
    val placeId: String? = null,
    val notes: String? = null,
)

/** Replaces an event's fields (same shape as [CreateEventRequest]); [version] is the one last read (FT-001). */
@Serializable
data class UpdateEventRequest(
    val personId: String? = null,
    val unionId: String? = null,
    val type: String,
    val date: String? = null,
    val placeId: String? = null,
    val notes: String? = null,
    val version: Long,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PlaceDto(
    val id: String,
    val name: String,
    val hierarchy: String?,
    val latitude: Double?,
    val longitude: Double?,
    /**
     * Sent back in [UpdatePlaceRequest] (FT-001). Always encoded; the default
     * only lets JSON from before FT-001 still decode.
     */
    @EncodeDefault val version: Long = 1,
)

@Serializable
data class CreatePlaceRequest(
    val name: String,
    val hierarchy: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/** Replaces a place's fields (same shape as [CreatePlaceRequest]); [version] is the one last read (FT-001). */
@Serializable
data class UpdatePlaceRequest(
    val name: String,
    val hierarchy: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val version: Long,
)
