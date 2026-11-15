package com.moxietech.family_tree.permissions

import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.Membership
import com.moxietech.family_tree.domain.MembershipStatus
import com.moxietech.family_tree.domain.Tree

/**
 * Tree-level access rules shared by the server (enforcement) and the clients
 * (showing or hiding edit controls).
 *
 * Phase 1 policy (FT-068):
 * - Reading a tree needs an ACTIVE membership in it. An INVITED membership
 *   grants nothing until the invitation is accepted.
 * - Writing to an existing tree needs an ACTIVE membership and tree
 *   ownership. Branch grants (FT-012) and change proposals (FT-014) will
 *   widen this for other members; until then every mutation uses this rule.
 *
 * Creating a tree is not covered here: any account may create one and becomes
 * its active owner.
 */
object TreePermissions {

    /** [membership] is the caller's membership row for [tree], or null if there is none. */
    fun canRead(tree: Tree, callerAccountId: AccountId, membership: Membership?): Boolean =
        membership != null &&
            membership.treeId == tree.id &&
            membership.accountId == callerAccountId &&
            membership.status == MembershipStatus.ACTIVE

    fun canWrite(tree: Tree, callerAccountId: AccountId, membership: Membership?): Boolean =
        canRead(tree, callerAccountId, membership) && tree.ownerAccountId == callerAccountId
}
