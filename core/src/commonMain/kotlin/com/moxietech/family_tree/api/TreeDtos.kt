package com.moxietech.family_tree.api

import kotlinx.serialization.Serializable

@Serializable
data class TreeDto(
    val id: String,
    val name: String,
    val ownerAccountId: String,
    val version: Long,
)

@Serializable
data class CreateTreeRequest(
    val name: String,
)

@Serializable
data class AccountDto(
    val id: String,
    val email: String,
    val displayName: String,
    val locale: String,
)
