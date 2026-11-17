package com.moxietech.family_tree.domain

/**
 * FT-010: a branch-scoped permission held by one membership (account in a tree).
 * Grants union: a member's reach is every grant's resolved person set, and the
 * highest role on a person wins (concept-architecture.md "Rules"). FT-012 through
 * FT-014 decide what each role may do; until then grants are stored and resolved
 * but don't change who may read or write (Phase 1 owner-only policy, FT-068).
 */
data class Grant(
    val id: GrantId,
    val treeId: TreeId,
    val accountId: AccountId,
    val role: GrantRole,
    val branch: Branch,
    val version: Long,
)

data class GrantId(val value: String)

/**
 * The role names a grant can carry, as defined in concept-architecture.md "Roles"
 * (for example, an admin's scope is the whole tree). FT-010 only stores them and gives
 * them no authority; FT-012 enforces what each role allows. The tree owner is not a
 * grant: ownership stays on the tree.
 */
enum class GrantRole {
    ADMIN,
    BRANCH_OWNER,
    CONTRIBUTOR,
    VIEWER,
}
