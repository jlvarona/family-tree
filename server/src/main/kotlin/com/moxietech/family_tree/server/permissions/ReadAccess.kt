package com.moxietech.family_tree.server.permissions

import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.Tree
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.permissions.PersonVisibility
import com.moxietech.family_tree.server.graph.ViewerPolicy
import com.moxietech.family_tree.server.trees.TreeService
import kotlin.uuid.Uuid

/**
 * FT-013: who a caller may see, from their explicit grants, decided in the same
 * transaction as the data it shapes (decisions table "Grant-based living-person
 * redaction (FT-013)"; the rule itself is `:core` [PersonVisibility]).
 *
 * - Reads go through [read]: one read-only REPEATABLE READ transaction that loads the
 *   tree (404), checks the caller's ACTIVE membership (403), reads the caller's grants
 *   and their scopes ([GrantScopeService.scopedGrantsInSnapshot]) and then runs the
 *   read. Grants, people, relationships and the projection all come from one snapshot,
 *   so a grant revoked or a person relinked mid-read can't mix two states.
 * - Writes use [WriteScope.viewer]: the scopes of the grants [WriteAccess.begin] locked,
 *   computed once with [GrantScopeService.scopesForWriter] under the write's locks.
 *
 * The home branch is deliberately not read here: it decides writes only.
 */
internal object ReadAccess {

    /**
     * Authorize an active member of [treeId] and run [block] with the tree and the
     * caller's [ViewerPolicy], all in one read-only REPEATABLE READ snapshot.
     */
    suspend fun <T> read(treeId: TreeId, caller: AccountId, block: (Tree, ViewerPolicy) -> T): T =
        TreeService.readAsMember(treeId, caller) { tree -> block(tree, viewerInSnapshot(tree, caller)) }

    /** The caller's policy from the current snapshot; the caller is already an active member. */
    fun viewerInSnapshot(tree: Tree, caller: AccountId): ViewerPolicy {
        if (tree.ownerAccountId == caller) return ViewerPolicy(PersonVisibility.owner())
        val grants = GrantScopeService.scopedGrantsInSnapshot(Uuid.parse(tree.id.value), Uuid.parse(caller.value))
        return ViewerPolicy(PersonVisibility.member(grants))
    }
}
