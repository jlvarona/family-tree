package com.moxietech.family_tree.server.permissions

import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.Branch
import com.moxietech.family_tree.domain.BranchDirection
import com.moxietech.family_tree.domain.Grant
import com.moxietech.family_tree.domain.GrantId
import com.moxietech.family_tree.domain.GrantRole
import com.moxietech.family_tree.domain.Membership
import com.moxietech.family_tree.domain.MembershipStatus
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.Tree
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.permissions.EditAuthority
import com.moxietech.family_tree.permissions.PersonVisibility
import com.moxietech.family_tree.permissions.TreePermissions
import com.moxietech.family_tree.server.errors.ForbiddenException
import com.moxietech.family_tree.server.errors.NotFoundException
import com.moxietech.family_tree.server.graph.GraphWriteLock
import com.moxietech.family_tree.server.trees.MembershipRepository
import com.moxietech.family_tree.server.trees.TreeRepository
import com.moxietech.family_tree.server.trees.TreeService
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.sql.Connection
import kotlin.uuid.Uuid

/**
 * FT-012: authorization of a write inside the write's own transaction, so the decision
 * and the change come from the same data and nothing that decided it can change before
 * the commit. The route and the service still run a cheap gate first
 * ([TreeService.getTreeForEditor], [TreeService.getTreeForTreeWideWriter]) so a caller
 * who can't change anything gets 403 before the body is decoded; this is the
 * authoritative check.
 *
 * Both entry points must be the first thing the write transaction does, which must be
 * READ COMMITTED (checked): after each lock is granted, the next statement reads the
 * latest committed data. (A REPEATABLE READ snapshot is taken by the first statement,
 * before a lock wait ends, so it could miss what the lock waited for; the read-side
 * snapshot API of [GrantScopeService] serves reads, FT-013 [ReadAccess].)
 *
 * [begin] (person, union, child link, event and citation writes):
 * 1. The tree is read (404). Its owner can't change (there is no transfer).
 * 2. The owner takes [GraphWriteLock] shared when the write already did (graph writers,
 *    merges' counterparts), or nothing, exactly as before FT-012. Anyone else takes it
 *    **exclusive**: no person, union or child-link writer, merge, member link,
 *    invitation redemption or grant create/update of this tree runs until this commits,
 *    so the branches, the account links and the caller's unattached people it decides
 *    on are the ones the change commits against.
 * 3. The caller's membership, `FOR SHARE` for a non-owner (an owner relink or status
 *    change waits; see [activeMembership] for the owner); not ACTIVE is the usual
 *    "Not a member" 403.
 * 4. For a non-owner, the caller's grants `FOR SHARE` (a grant update or delete waits)
 *    and the [EditAuthority]: every grant's scope ([GrantScopeService.scopesForWriter]),
 *    the home branch ([HomeBranchService.homeBranchForWriter]), the people with an
 *    account and the caller's own unattached people. A member who can't change
 *    anything is a 403.
 *
 * Then the service resolves the records in the tree as before and asks [WriteScope]
 * about the exact people the stored record and the replacement involve. A refusal
 * throws inside the transaction, so it rolls back with nothing written or audited.
 *
 * [beginTreeWide] (places, sources, media uploads): membership and grants `FOR SHARE`,
 * then the owner or a whole-tree admin; no graph lock, since branches don't matter.
 */
internal object WriteAccess {

    fun noEditAccessMessage(treeId: String) = TreeService.noEditAccessMessage(treeId)

    fun outsideBranchMessage(treeId: String) =
        "This change is outside the branches you can edit in tree $treeId; it needs the tree owner, an admin or the branch owner"

    fun widensReachMessage(treeId: String) =
        "This change would let you edit people outside your branches in tree $treeId; ask the tree owner or an admin to make it"

    /**
     * FT-013: a write that would reveal living people's details the caller can't see: a
     * graph change widening their read scope, marking a hidden person deceased, or moving a
     * union or event (with its citations and scans) away from a hidden person.
     */
    fun revealsPrivateMessage(treeId: String) =
        "This change would reveal private details you can't see in tree $treeId; ask the tree owner or an admin to make it"

    /** FT-014: 403 for a member who reviews nothing (not the owner, no BRANCH_OWNER grant). */
    fun notReviewerMessage(treeId: String) = "Only the tree owner or a branch owner can review change proposals in tree $treeId"

    /** FT-014: 403 when a reviewer doesn't own the branch of every person a proposal affects. */
    fun outsideReviewMessage(treeId: String) =
        "This proposal affects people outside the branches you own in tree $treeId; it needs the tree owner or their branch owner"

    /**
     * FT-014: 403 when a branch owner's approval would let the proposer edit or see people
     * they couldn't before; only the tree owner grants that ("Owning a higher branch must be
     * granted by the tree owner or the owner of that branch"; FT-012, FT-013 reach rules).
     */
    fun reviewWidensProposerMessage(treeId: String) =
        "Approving this proposal would widen the proposer's access in tree $treeId; only the tree owner can approve it"

    /**
     * See the class comment. [ownerTakesSharedLock]: the owner path's previous [GraphWriteLock] use.
     * [mode] (FT-014): [WriteMode.PROPOSE] queues out-of-branch and reach-widening changes
     * instead of refusing them (proposals enabled); [WriteMode.DIRECT] refuses as FT-012/FT-013.
     */
    fun begin(treeUuid: Uuid, caller: AccountId, ownerTakesSharedLock: Boolean, mode: WriteMode = WriteMode.DIRECT): WriteScope {
        require(mode != WriteMode.REVIEW) { "Use beginReview" }
        checkReadCommitted()
        val tree = TreeRepository.findById(treeUuid) ?: throw NotFoundException("No tree with id $treeUuid")
        val isOwner = tree.ownerAccountId == caller
        when {
            !isOwner -> GraphWriteLock.exclusive(treeUuid)
            ownerTakesSharedLock -> GraphWriteLock.shared(treeUuid)
        }
        val membership = activeMembership(tree, caller, lock = !isOwner)
        if (isOwner) return WriteScope(tree, treeUuid, caller, membership, emptyList(), emptyList(), EditAuthority.owner(), mode)
        val callerUuid = Uuid.parse(caller.value)
        val grants = lockGrants(treeUuid, callerUuid)
        val scoped = scopedGrantsFor(treeUuid, grants)
        val authority = authorityFor(treeUuid, callerUuid, membership, scoped)
        if (!authority.canContribute) throw ForbiddenException(noEditAccessMessage(tree.id.value))
        return WriteScope(tree, treeUuid, caller, membership, grants, scoped, authority, mode)
    }

    /**
     * FT-014: a review (approve or reject) by [reviewer], first in its READ COMMITTED
     * transaction. Takes [GraphWriteLock] before anything else, shared for the owner and
     * exclusive for anyone else (as [begin]), so the branches decided on can't change
     * before the commit; then the reviewer's membership and grants `FOR SHARE` (non-owner).
     * A member with no effective BRANCH_OWNER grant is a 403. Which proposal they may
     * review is decided per proposal ([WriteScope.requireReviewable]) and, while it is
     * applied, per person by every check of the replayed write.
     */
    fun beginReview(treeUuid: Uuid, reviewer: AccountId): WriteScope {
        checkReadCommitted()
        val tree = TreeRepository.findById(treeUuid) ?: throw NotFoundException("No tree with id $treeUuid")
        val isOwner = tree.ownerAccountId == reviewer
        if (isOwner) GraphWriteLock.shared(treeUuid) else GraphWriteLock.exclusive(treeUuid)
        val membership = activeMembership(tree, reviewer, lock = !isOwner)
        if (isOwner) return WriteScope(tree, treeUuid, reviewer, membership, emptyList(), emptyList(), EditAuthority.owner(), WriteMode.REVIEW)
        val reviewerUuid = Uuid.parse(reviewer.value)
        val grants = lockGrants(treeUuid, reviewerUuid)
        val scoped = scopedGrantsFor(treeUuid, grants)
        val scope = WriteScope(tree, treeUuid, reviewer, membership, grants, scoped, authorityFor(treeUuid, reviewerUuid, membership, scoped), WriteMode.REVIEW)
        if (!scope.review.isReviewer) throw ForbiddenException(notReviewerMessage(tree.id.value))
        return scope
    }

    /**
     * FT-014: the proposer's authority and visibility for a branch owner's approval, under
     * the review's exclusive graph lock: their membership and grants locked `FOR SHARE`
     * like [begin]. Null when they are no longer an active member (nothing to widen).
     */
    internal fun proposerShadow(tree: Tree, treeUuid: Uuid, proposer: Uuid): ProposerShadow? {
        if (tree.ownerAccountId.value == proposer.toString()) return null
        val membership = lockMembership(treeUuid, proposer) ?: return null
        if (!TreePermissions.canRead(tree, AccountId(proposer.toString()), membership)) return null
        val grants = lockGrants(treeUuid, proposer)
        val scoped = scopedGrantsFor(treeUuid, grants)
        return ProposerShadow(proposer, membership, grants, authorityFor(treeUuid, proposer, membership, scoped), PersonVisibility.member(scoped))
    }

    /** A proposer as they were when a branch owner's review began; see [proposerShadow]. */
    internal class ProposerShadow(
        val account: Uuid,
        val membership: Membership,
        val grants: List<Grant>,
        val authority: EditAuthority,
        val visibility: PersonVisibility,
    )

    /** Tree-wide records: the owner or a whole-tree admin, decided under the membership and grant locks. */
    fun beginTreeWide(treeUuid: Uuid, caller: AccountId): Tree {
        checkReadCommitted()
        val tree = TreeRepository.findById(treeUuid) ?: throw NotFoundException("No tree with id $treeUuid")
        val isOwner = tree.ownerAccountId == caller
        val membership = activeMembership(tree, caller, lock = !isOwner)
        if (isOwner) return tree
        val grants = lockGrants(treeUuid, Uuid.parse(caller.value))
        if (!TreePermissions.canWriteTreeWide(tree, caller, membership, grants)) {
            throw ForbiddenException(
                if (TreePermissions.canEditSomewhere(tree, caller, membership, grants)) {
                    TreeService.treeWideOnlyMessage(tree.id.value)
                } else {
                    noEditAccessMessage(tree.id.value)
                },
            )
        }
        return tree
    }

    /**
     * Access management (invitations, grant writes): the owner or a whole-tree admin
     * (the role table: an admin invites and manages grants). Run first in the write's
     * READ COMMITTED transaction.
     * - The owner keeps the graph lock the write took before FT-012 ([ownerTakesSharedLock]).
     * - An admin takes [GraphWriteLock] exclusive first, then their membership and grants
     *   `FOR SHARE` (same statement). The exclusive lock serializes every admin's
     *   management writes with each other, so two admins changing each other's grants
     *   can't each hold their own grant while waiting for the other's (a lock cycle); it
     *   also waits for redemptions and graph writers, which hold it shared.
     * Returns the tree.
     */
    fun beginManagement(treeUuid: Uuid, caller: AccountId, ownerTakesSharedLock: Boolean): Tree {
        checkReadCommitted()
        val tree = TreeRepository.findById(treeUuid) ?: throw NotFoundException("No tree with id $treeUuid")
        val isOwner = tree.ownerAccountId == caller
        when {
            !isOwner -> GraphWriteLock.exclusive(treeUuid)
            ownerTakesSharedLock -> GraphWriteLock.shared(treeUuid)
        }
        val membership = activeMembership(tree, caller, lock = !isOwner)
        if (isOwner) return tree
        val grants = lockGrants(treeUuid, Uuid.parse(caller.value))
        if (!TreePermissions.canWriteTreeWide(tree, caller, membership, grants)) {
            throw ForbiddenException(
                if (TreePermissions.canEditSomewhere(tree, caller, membership, grants)) {
                    TreeService.managerOnlyMessage(tree.id.value)
                } else {
                    noEditAccessMessage(tree.id.value)
                },
            )
        }
        return tree
    }

    /**
     * Redemption: whether the invitation's creator [inviter] can still manage access to
     * [tree] (its active owner, or an active member with a whole-tree ADMIN grant). A
     * non-owner's membership and grants are locked `FOR SHARE` by the statements that read
     * them, so a revocation waits for the redemption, and one that committed first voids
     * the invitation. Call after the redemption's [GraphWriteLock] and invitation lock.
     */
    fun inviterCanManage(tree: Tree, inviter: AccountId): Boolean {
        val treeUuid = Uuid.parse(tree.id.value)
        val inviterUuid = Uuid.parse(inviter.value)
        if (tree.ownerAccountId == inviter) {
            return TreePermissions.canWrite(tree, inviter, MembershipRepository.find(treeUuid, inviterUuid))
        }
        val membership = lockMembership(treeUuid, inviterUuid) ?: return false
        return TreePermissions.canWriteTreeWide(tree, inviter, membership, lockGrants(treeUuid, inviterUuid))
    }

    /**
     * The people each of the locked [grants] covers in the graph as this transaction sees
     * it now ([GrantScopeService.scopesForWriter]; [begin] has taken the locks).
     */
    internal fun scopedGrantsFor(treeUuid: Uuid, grants: List<Grant>): List<EditAuthority.ScopedGrant> {
        val scopes = GrantScopeService.scopesForWriter(treeUuid, grants)
        return grants.map { grant -> EditAuthority.ScopedGrant(grant.role, grant.branch, scopes.getValue(grant.id).toPersonIds()) }
    }

    /** The caller's authority from [scoped] (their locked grants) and the current graph. */
    internal fun authorityFor(treeUuid: Uuid, callerUuid: Uuid, membership: Membership, scoped: List<EditAuthority.ScopedGrant>): EditAuthority =
        EditAuthority(
            isOwner = false,
            grants = scoped,
            homeBranch = HomeBranchService.homeBranchForWriter(treeUuid, membership).toPersonIds(),
            accountHolders = linkedPeople(treeUuid).toPersonIds(),
            ownUnattached = unattachedCreatedBy(treeUuid, callerUuid).toPersonIds(),
        )

    /**
     * The caller's ACTIVE membership, or the "Not a member" 403. A non-owner's is locked
     * `FOR SHARE` first, so a status change or relink waits for this write. The owner's
     * is only read: its status never changes (there is no transfer or deactivation, and
     * redeeming or linking keeps an active owner active), its person doesn't matter for
     * the owner's authority, and a lock here could deadlock with a merge, which locks the
     * memberships of the merged people after the people themselves while an owner write
     * that takes no graph lock (person update, event delete, citations, places, sources)
     * would hold the membership and then wait for a person or event row.
     */
    private fun activeMembership(tree: Tree, caller: AccountId, lock: Boolean): Membership {
        val treeUuid = Uuid.parse(tree.id.value)
        val callerUuid = Uuid.parse(caller.value)
        val membership = if (lock) lockMembership(treeUuid, callerUuid) else MembershipRepository.find(treeUuid, callerUuid)
        if (membership == null || !TreePermissions.canRead(tree, caller, membership)) {
            throw ForbiddenException("Not a member of tree ${tree.id.value}")
        }
        return membership
    }

    /**
     * The membership of [accountUuid] in [treeUuid], read and locked `FOR SHARE` by the
     * same statement. Evaluating the row the lock returned (not a second read) matters:
     * under READ COMMITTED a later statement sees rows committed after the lock was
     * taken, which would then decide without being locked.
     */
    private fun lockMembership(treeUuid: Uuid, accountUuid: Uuid): Membership? = withJdbc { connection ->
        connection.prepareStatement(
            "select tree_id, account_id, person_id, status, version from membership where tree_id = ? and account_id = ? for share",
        ).use { st ->
            st.setUuid(1, treeUuid)
            st.setUuid(2, accountUuid)
            st.executeQuery().use { rs ->
                if (!rs.next()) {
                    null
                } else {
                    Membership(
                        treeId = TreeId(rs.getString("tree_id")),
                        accountId = AccountId(rs.getString("account_id")),
                        personId = rs.getString("person_id")?.let(::PersonId),
                        status = MembershipStatus.valueOf(rs.getString("status")),
                        version = rs.getLong("version"),
                    )
                }
            }
        }
    }

    /**
     * The grants of [accountUuid] in [treeUuid], read and locked `FOR SHARE` in ID order
     * by one statement (a grant update or delete waits). Only these rows decide: a grant
     * committed while this statement waited isn't in its snapshot and isn't used, so it
     * can't authorize the write without being locked (the caller's next request sees it).
     */
    private fun lockGrants(treeUuid: Uuid, accountUuid: Uuid): List<Grant> = withJdbc { connection ->
        connection.prepareStatement(
            """
            select id, tree_id, account_id, role, anchor_person_id, direction, depth, include_partners, version
            from tree_grant where tree_id = ? and account_id = ? order by id for share
            """.trimIndent(),
        ).use { st ->
            st.setUuid(1, treeUuid)
            st.setUuid(2, accountUuid)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val depth = rs.getInt("depth").takeUnless { rs.wasNull() }
                        add(
                            Grant(
                                id = GrantId(rs.getString("id")),
                                treeId = TreeId(rs.getString("tree_id")),
                                accountId = AccountId(rs.getString("account_id")),
                                role = GrantRole.valueOf(rs.getString("role")),
                                branch = Branch(
                                    anchorPersonId = PersonId(rs.getString("anchor_person_id")),
                                    direction = BranchDirection.valueOf(rs.getString("direction")),
                                    depth = depth,
                                    includePartners = rs.getBoolean("include_partners"),
                                ),
                                version = rs.getLong("version"),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** People of [treeUuid] that any membership (whatever its status) is linked to. */
    private fun linkedPeople(treeUuid: Uuid): Set<Uuid> = withJdbc { connection ->
        connection.prepareStatement("select person_id from membership where tree_id = ? and person_id is not null").use { st ->
            st.setUuid(1, treeUuid)
            st.executeQuery().use { rs -> buildSet { while (rs.next()) add(Uuid.parse(rs.getString(1))) } }
        }
    }

    /** People of [treeUuid] created by [callerUuid] with no union and no parent link. */
    private fun unattachedCreatedBy(treeUuid: Uuid, callerUuid: Uuid): Set<Uuid> = withJdbc { connection ->
        connection.prepareStatement(
            """
            select p.id from person p
            where p.tree_id = ? and p.created_by_account_id = ?
              and not exists (select 1 from union_record u where u.tree_id = p.tree_id and (u.partner1_id = p.id or u.partner2_id = p.id))
              and not exists (select 1 from union_child c where c.tree_id = p.tree_id and c.child_id = p.id)
            """.trimIndent(),
        ).use { st ->
            st.setUuid(1, treeUuid)
            st.setUuid(2, callerUuid)
            st.executeQuery().use { rs -> buildSet { while (rs.next()) add(Uuid.parse(rs.getString(1))) } }
        }
    }

    /** Every person of [treeUuid] with whether they are living, for the reach comparison after a graph change. */
    internal fun peopleOf(treeUuid: Uuid): Map<Uuid, Boolean> = withJdbc { connection ->
        connection.prepareStatement("select id, is_living from person where tree_id = ?").use { st ->
            st.setUuid(1, treeUuid)
            st.executeQuery().use { rs -> buildMap { while (rs.next()) put(Uuid.parse(rs.getString(1)), rs.getBoolean(2)) } }
        }
    }

    private fun checkReadCommitted() {
        val isolation = TransactionManager.currentOrNull()?.transactionIsolation
        check(isolation == Connection.TRANSACTION_READ_COMMITTED) {
            "Write authorization must run first in a READ COMMITTED write transaction"
        }
    }
}


private fun Uuid.toPersonId() = PersonId(toString())

private fun Set<Uuid>.toPersonIds(): Set<PersonId> = mapTo(mutableSetOf()) { it.toPersonId() }
