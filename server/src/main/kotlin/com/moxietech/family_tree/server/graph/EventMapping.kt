package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.api.EventDto
import com.moxietech.family_tree.api.PlaceDto
import com.moxietech.family_tree.api.toDto
import com.moxietech.family_tree.domain.Event
import com.moxietech.family_tree.domain.Place

fun Event.toDto(place: Place?) = EventDto(
    id = id.value,
    treeId = treeId.value,
    personId = personId?.value,
    unionId = unionId?.value,
    type = type.name,
    date = date?.toDto(),
    place = place?.toDto(),
    notes = notes,
    version = version,
)

fun Place.toDto() = PlaceDto(
    id = id.value,
    name = name,
    hierarchy = hierarchy,
    latitude = latitude,
    longitude = longitude,
)
