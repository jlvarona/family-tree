package com.moxietech.family_tree.api

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@OptIn(ExperimentalSerializationApi::class)
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
    /**
     * FT-013: true when the server returned this person as the private placeholder
     * (a living person no grant of the caller covers). Clients show their localized
     * placeholder from this flag alone, never by guessing from the name or from tree
     * ownership. The wire placeholder name stays `"Private"` for older clients.
     * Omitted when false (absent means a full record), so audit snapshots, which are
     * never redacted, keep their pre-FT-013 shape.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val redacted: Boolean = false,
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

@Serializable
data class CreatePersonResponse(
    val person: PersonDto,
    val possibleDuplicates: List<DuplicateCandidateDto> = emptyList(),
)
