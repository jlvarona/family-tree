package com.moxietech.family_tree.api

import kotlinx.serialization.Serializable

@Serializable
data class PersonDto(
    val id: String,
    val treeId: String,
    val givenNames: String,
    val surnamePaternal: String?,
    val surnameMaternal: String?,
    val fullName: String,
    val sex: String,
    val isLiving: Boolean,
    val profileMediaId: String?,
    /** Rich-text bio, or null when redacted (living person outside caller's grants). */
    val bio: String?,
    val version: Long,
)

@Serializable
data class CreatePersonRequest(
    val givenNames: String,
    val surnamePaternal: String? = null,
    val surnameMaternal: String? = null,
    val sex: String,
    val isLiving: Boolean,
    val bio: String? = null,
)

@Serializable
data class UpdatePersonRequest(
    val givenNames: String,
    val surnamePaternal: String? = null,
    val surnameMaternal: String? = null,
    val sex: String,
    val isLiving: Boolean,
    val bio: String? = null,
    val version: Long,
)

/** Result of the create-time duplicate check (concept-architecture.md MVP feature). */
@Serializable
data class DuplicateCandidateDto(
    val person: PersonDto,
    val score: Int,
)
