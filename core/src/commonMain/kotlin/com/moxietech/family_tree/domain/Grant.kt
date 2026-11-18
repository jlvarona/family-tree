package com.moxietech.family_tree.domain

/**
 * FT-010: a branch-scoped permission held by one membership (account in a tree).
 * Grants union: a member's reach is every grant's resolved person set, and the
 * highest role on a person wins (concept-architecture.md "Rules"). Since FT-012 they
 * decide who may write ([com.moxietech.family_tree.permissions.EditAuthority]); FT-013
 * (redaction) and FT-014 (proposals) are still to come.
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
 * (for example, an admin's scope is the whole tree). FT-012 enforces what each role
 * allows for writes ([com.moxietech.family_tree.permissions.GrantRules]: an ADMIN grant
 * counts only over the whole tree). The tree owner is not a grant: ownership stays on
 * the tree.
 */
enum class GrantRole {
    ADMIN,
    BRANCH_OWNER,
    CONTRIBUTOR,
    VIEWER,
}
