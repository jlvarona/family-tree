package com.moxietech.family_tree.api

import kotlinx.serialization.Serializable

@Serializable
data class UnionDto(
    val id: String,
    val treeId: String,
    val partner1Id: String,
    val partner2Id: String?,
    val type: String,
    val startDate: GedcomDateDto?,
    val endDate: GedcomDateDto?,
    val children: List<UnionChildDto>,
    val version: Long,
)

@Serializable
data class UnionChildDto(
    val childId: String,
    val relation: String,
)

@Serializable
data class CreateUnionRequest(
    val partner1Id: String,
    val partner2Id: String? = null,
    val type: String,
    val startDate: String? = null,
    val endDate: String? = null,
)

@Serializable
data class AddUnionChildRequest(
    val childId: String,
    val relation: String,
)

/** A date as both the free text a researcher typed and the sortable range derived from it. */
@Serializable
data class GedcomDateDto(
    val displayText: String,
    val earliestIso: String?,
    val latestIso: String?,
)
