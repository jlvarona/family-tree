package com.moxietech.family_tree.domain

/** The tenant boundary: every row in every other table carries a [TreeId]. */
data class Tree(
    val id: TreeId,
    val name: String,
    val ownerAccountId: AccountId,
    val version: Long,
)

/** A login; not tenant-scoped — one account can belong to many trees. */
data class Account(
    val id: AccountId,
    val email: String,
    val displayName: String,
    val locale: Locale,
)

enum class Locale {
    EN,
    ES,
}

/**
 * Links an [Account] to a [Tree] and, once linked, to that person's own
 * [Person] record — the link the home branch is derived from.
 */
data class Membership(
    val treeId: TreeId,
    val accountId: AccountId,
    val personId: PersonId?,
    val status: MembershipStatus,
)

enum class MembershipStatus {
    INVITED,
    ACTIVE,
}
