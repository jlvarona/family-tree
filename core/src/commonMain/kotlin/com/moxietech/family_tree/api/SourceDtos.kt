package com.moxietech.family_tree.api

import kotlinx.serialization.Serializable

@Serializable
data class SourceDto(
    val id: String,
    val treeId: String,
    val title: String,
    val type: String,
    val author: String?,
    val publisher: String?,
    val repository: String?,
    val url: String?,
    val date: GedcomDateDto?,
    val version: Long,
)

@Serializable
data class CreateSourceRequest(
    val title: String,
    val type: String,
    val author: String? = null,
    val publisher: String? = null,
    val repository: String? = null,
    val url: String? = null,
    val date: String? = null,
)

@Serializable
data class CitationDto(
    val id: String,
    val sourceId: String,
    val targetType: String,
    val targetId: String,
    val page: String?,
    val confidence: Int,
    val note: String?,
    val mediaId: String?,
)

@Serializable
data class CreateCitationRequest(
    val sourceId: String,
    val targetType: String,
    val targetId: String,
    val page: String? = null,
    val confidence: Int,
    val note: String? = null,
    val mediaId: String? = null,
)
