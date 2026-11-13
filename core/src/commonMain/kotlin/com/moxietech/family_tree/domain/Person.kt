package com.moxietech.family_tree.domain

/**
 * A person in a tree. Names are stored in three parts (never pre-concatenated)
 * so Spanish naming conventions (given names + paternal surname + maternal
 * surname) display and sort correctly; see [com.moxietech.family_tree.format.formatPersonName].
 */
data class Person(
    val id: PersonId,
    val treeId: TreeId,
    val givenNames: String,
    val surnamePaternal: String?,
    val surnameMaternal: String?,
    val sex: Sex,
    val isLiving: Boolean,
    val profileMediaId: MediaId?,
    val bio: String?,
    val createdByAccountId: AccountId?,
    val version: Long,
)

enum class Sex {
    FEMALE,
    MALE,
    UNKNOWN,
}

/** Wraps a raw id in a typed wrapper so ids of different entities can't be mixed up by accident. */
data class PersonId(val value: String)

data class TreeId(val value: String)

data class AccountId(val value: String)

data class MediaId(val value: String)
