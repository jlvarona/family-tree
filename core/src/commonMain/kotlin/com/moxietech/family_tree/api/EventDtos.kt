package com.moxietech.family_tree.api

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

@Serializable
data class PlaceDto(
    val id: String,
    val name: String,
    val hierarchy: String?,
    val latitude: Double?,
    val longitude: Double?,
)

@Serializable
data class CreatePlaceRequest(
    val name: String,
    val hierarchy: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)
