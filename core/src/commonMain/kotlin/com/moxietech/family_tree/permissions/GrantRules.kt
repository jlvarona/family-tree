package com.moxietech.family_tree.permissions

import com.moxietech.family_tree.domain.Branch
import com.moxietech.family_tree.domain.BranchDirection
import com.moxietech.family_tree.domain.GrantRole

/**
 * FT-012: what a stored grant's role actually allows, and which new grant definitions
 * are accepted (concept-architecture.md "Roles"; decisions table "Grant roles (FT-012)").
 *
 * - [GrantRole.ADMIN] is a whole-tree role. FT-010 and FT-011 stored any role on any
 *   branch before roles had authority, so an ADMIN grant on a limited branch can exist.
 *   It fails closed: it carries no authority at all, not even a contributor's, and it is
 *   never widened to the whole tree. New grant writes and invitation proposals reject it.
 * - Every other role keeps its branch as stored.
 */
object GrantRules {

    const val ADMIN_SCOPE_MESSAGE = "An ADMIN grant must cover the whole tree (direction WHOLE_TREE)"

    /** The role [role] on [branch] carries, or null when the grant carries no authority. */
    fun effectiveRole(role: GrantRole, branch: Branch): GrantRole? =
        if (role == GrantRole.ADMIN && branch.direction != BranchDirection.WHOLE_TREE) null else role

    /** Errors for a new or replacement grant definition (grant writes, invitation proposals). */
    fun validate(role: GrantRole, branch: Branch): List<String> =
        if (effectiveRole(role, branch) == null) listOf(ADMIN_SCOPE_MESSAGE) else emptyList()

    /** Higher is more authority; "the highest role on a person wins". */
    fun rank(role: GrantRole): Int = when (role) {
        GrantRole.VIEWER -> 0
        GrantRole.CONTRIBUTOR -> 1
        GrantRole.BRANCH_OWNER -> 2
        GrantRole.ADMIN -> 3
    }

    /** Whether [role] lets its holder change anything (everything but VIEWER). */
    fun canEditWith(role: GrantRole): Boolean = rank(role) >= rank(GrantRole.CONTRIBUTOR)
}
