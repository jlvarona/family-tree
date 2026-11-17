package com.moxietech.family_tree.api

import kotlinx.serialization.Serializable

/**
 * FT-010: a branch grant held by one member of a tree. [role] is a
 * `com.moxietech.family_tree.domain.GrantRole` name and [direction] a
 * `BranchDirection` name; [depth] null is unlimited. Used for audit snapshots now;
 * grant routes come with invitations and grant-based authorization (FT-011, FT-012).
 */
@Serializable
data class GrantDto(
    val id: String,
    val treeId: String,
    val accountId: String,
    val role: String,
    val anchorPersonId: String,
    val direction: String,
    val depth: Int? = null,
    val includePartners: Boolean,
    val version: Long,
)

/** FT-010: a new grant for the member [accountId] (any membership status) of the tree. */
@Serializable
data class CreateGrantRequest(
    val accountId: String,
    val role: String,
    val anchorPersonId: String,
    val direction: String,
    val depth: Int? = null,
    val includePartners: Boolean = false,
)

/** FT-010: replaces a grant's role and branch at the [version] last read; the member can't change. */
@Serializable
data class UpdateGrantRequest(
    val role: String,
    val anchorPersonId: String,
    val direction: String,
    val depth: Int? = null,
    val includePartners: Boolean = false,
    val version: Long,
)
