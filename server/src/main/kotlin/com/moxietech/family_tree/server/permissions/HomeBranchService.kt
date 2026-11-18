package com.moxietech.family_tree.server.permissions

import com.moxietech.family_tree.domain.Membership
import com.moxietech.family_tree.domain.MembershipStatus
import com.moxietech.family_tree.permissions.HomeBranch
import com.moxietech.family_tree.server.trees.MembershipRepository
import kotlin.uuid.Uuid

/**
 * FT-011: a member's home branch, derived from the person their membership is linked
 * to ([HomeBranch] in :core: the parents and their descendants, with partners). It is
 * derived at read time, never stored as a grant, so it follows every graph change and
 * every relink without a cache to invalidate. Like [GrantScopeService] it performs no
 * caller authorization; FT-012 writes use [homeBranchForWriter] through [WriteAccess].
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

    /**
     * FT-012: the home branch of the member [membership] (already read under the write's
     * locks), for a write authorizing itself in its own transaction. Same preconditions
     * as [GrantScopeService.scopesForWriter]: READ COMMITTED with [com.moxietech.family_tree.server.graph.GraphWriteLock]
     * exclusive, so no relink, redemption, merge or graph change commits before the write.
     */
    fun homeBranchForWriter(treeId: Uuid, membership: Membership): Set<Uuid> {
        GrantScopeService.checkWriterLocks(treeId)
        require(membership.treeId.value == treeId.toString()) { "Membership is not in tree $treeId" }
        if (membership.status != MembershipStatus.ACTIVE) return emptySet()
        val person = membership.personId ?: return emptySet()
        return GrantScopeRepository.resolveBranch(treeId, HomeBranch.of(person))
    }
}
