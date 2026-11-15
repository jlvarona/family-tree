package com.moxietech.family_tree.permissions

import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.Person
import com.moxietech.family_tree.domain.Tree

/**
 * Living-person privacy (AGENTS.md): who may see a person's details.
 * Callers have already passed [TreePermissions.canRead] for [Tree].
 *
 * Phase 1 policy (FT-066):
 * - The tree owner sees everyone in full.
 * - Other active members see deceased people in full and living people as a
 *   private placeholder.
 *
 * FT-013 widens this so a grant covering a living person makes them visible.
 * Server responses and client UI both use this rule, so they agree on who
 * shows as "Private".
 */
object PersonVisibility {

    fun canSeeDetails(tree: Tree, callerAccountId: AccountId, person: Person): Boolean =
        !person.isLiving || tree.ownerAccountId == callerAccountId
}
