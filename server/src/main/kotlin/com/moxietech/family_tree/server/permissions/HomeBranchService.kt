package com.moxietech.family_tree.server.permissions

import com.moxietech.family_tree.domain.MembershipStatus
import com.moxietech.family_tree.permissions.HomeBranch
import com.moxietech.family_tree.server.trees.MembershipRepository
import kotlin.uuid.Uuid

/**
 * FT-011: a member's home branch, derived from the person their membership is linked
 * to ([HomeBranch] in :core: the parents and their descendants, with partners). It is
 * derived at read time, never stored as a grant, so it follows every graph change and
 * every relink without a cache to invalidate. Like [GrantScopeService]'s snapshot
 * reads it performs no caller authorization and nothing enforces it before FT-012.
 */
object HomeBranchService {

    /**
     * The people in [accountId]'s home branch in [treeId], from the caller's
     * `REPEATABLE READ` or `SERIALIZABLE` snapshot. Empty when the account has no
     * membership in the tree, the membership isn't ACTIVE, or it isn't linked to a
     * person: an unlinked member has no home branch, and a person with no recorded
     * parents gets only their own line downwards, never the whole tree.
     */
    fun homeBranchInSnapshot(treeId: Uuid, accountId: Uuid): Set<Uuid> {
        GrantScopeService.checkSnapshotIsolation()
        val membership = MembershipRepository.find(treeId, accountId) ?: return emptySet()
        if (membership.status != MembershipStatus.ACTIVE) return emptySet()
        val person = membership.personId ?: return emptySet()
        return GrantScopeRepository.resolveBranch(treeId, HomeBranch.of(person))
    }
}
