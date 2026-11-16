package com.moxietech.family_tree.api

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UnionChildDto(
    val childId: String,
    val relation: String,
    /**
     * The child link's own version (FT-001), sent back in [UpdateUnionChildRequest] and
     * on delete. An opaque change token: it increases but can skip numbers, and a
     * deleted and recreated link never gets an earlier link's value (V5). Always
     * encoded; the default only lets JSON from before FT-001 still decode.
     */
    @EncodeDefault val version: Long = 1,
)

@Serializable
data class CreateUnionRequest(
    val partner1Id: String,
    val partner2Id: String? = null,
    val type: String,
    val startDate: String? = null,
    val endDate: String? = null,
)

/** Replaces a union's fields (same shape as [CreateUnionRequest]); [version] is the one last read (FT-001). */
@Serializable
data class UpdateUnionRequest(
    val partner1Id: String,
    val partner2Id: String? = null,
    val type: String,
    val startDate: String? = null,
    val endDate: String? = null,
    val version: Long,
)

@Serializable
data class AddUnionChildRequest(
    val childId: String,
    val relation: String,
)

/**
 * Changes how a child belongs to a union. The union and child are the link's
 * identity and come from the path, so only the relation can change (FT-001).
 */
@Serializable
data class UpdateUnionChildRequest(
    val relation: String,
    val version: Long,
)

/** A date as both the free text a researcher typed and the sortable range derived from it. */
@Serializable
data class GedcomDateDto(
    val displayText: String,
    val earliestIso: String?,
    val latestIso: String?,
)
