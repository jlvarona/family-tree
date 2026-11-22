package com.moxietech.family_tree.permissions

import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.Grant
import com.moxietech.family_tree.domain.GrantRole
import com.moxietech.family_tree.domain.Membership
import com.moxietech.family_tree.domain.MembershipStatus
import com.moxietech.family_tree.domain.Tree

/**
 * Tree-level access rules shared by the server (enforcement) and the clients
 * (showing or hiding edit controls).
 *
 * - Reading a tree needs an ACTIVE membership in it (FT-068). An INVITED membership
 *   grants nothing until the invitation is accepted.
 * - [canWrite]: the tree owner's operations. Since FT-012 these are ownership and
 *   lifecycle operations only: person merges, invitations, member linking and grant
 *   writes. The web client still uses it for its owner workflow.
 * - [canEditSomewhere] (FT-012): the first gate for any person, relationship, event or
 *   citation write; which people a member may change is decided per record by
 *   [EditAuthority] inside the write.
 * - [canWriteTreeWide] (FT-012): tree-wide records (places, sources, media uploads) need
 *   the owner or a whole-tree admin.
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

    /** The active tree owner (ownership and lifecycle operations). */
    fun canWrite(tree: Tree, callerAccountId: AccountId, membership: Membership?): Boolean =
        canRead(tree, callerAccountId, membership) && tree.ownerAccountId == callerAccountId

    /**
     * An active member who may change at least something: the owner, or a holder of a
     * grant whose role can edit ([GrantRules]). [grants] are the caller's grants in [tree].
     */
    fun canEditSomewhere(tree: Tree, callerAccountId: AccountId, membership: Membership?, grants: List<Grant>): Boolean =
        canRead(tree, callerAccountId, membership) &&
            (tree.ownerAccountId == callerAccountId || effectiveRoles(tree, callerAccountId, grants).any(GrantRules::canEditWith))

    /** The active owner or a whole-tree admin. [grants] are the caller's grants in [tree]. */
    fun canWriteTreeWide(tree: Tree, callerAccountId: AccountId, membership: Membership?, grants: List<Grant>): Boolean =
        canRead(tree, callerAccountId, membership) &&
            (tree.ownerAccountId == callerAccountId || GrantRole.ADMIN in effectiveRoles(tree, callerAccountId, grants))

    /**
     * FT-014: an active member who reviews some change proposals: the owner, or a holder of
     * an effective BRANCH_OWNER grant (which proposals exactly is `ReviewAuthority`'s
     * per-proposal rule). A whole-tree ADMIN doesn't review by that role.
     */
    fun canReviewSomewhere(tree: Tree, callerAccountId: AccountId, membership: Membership?, grants: List<Grant>): Boolean =
        canRead(tree, callerAccountId, membership) &&
            (tree.ownerAccountId == callerAccountId || GrantRole.BRANCH_OWNER in effectiveRoles(tree, callerAccountId, grants))

    private fun effectiveRoles(tree: Tree, callerAccountId: AccountId, grants: List<Grant>): List<GrantRole> =
        grants.filter { it.treeId == tree.id && it.accountId == callerAccountId }
            .mapNotNull { GrantRules.effectiveRole(it.role, it.branch) }
}
