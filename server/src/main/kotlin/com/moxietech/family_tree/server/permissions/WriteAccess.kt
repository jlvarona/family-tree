package com.moxietech.family_tree.server.permissions

import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.Branch
import com.moxietech.family_tree.domain.BranchDirection
import com.moxietech.family_tree.domain.Grant
import com.moxietech.family_tree.domain.GrantId
import com.moxietech.family_tree.domain.GrantRole
import com.moxietech.family_tree.domain.Membership
import com.moxietech.family_tree.domain.MembershipStatus
import com.moxietech.family_tree.domain.Person
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.Tree
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.permissions.EditAccess
import com.moxietech.family_tree.permissions.EditAuthority
import com.moxietech.family_tree.permissions.TreePermissions
import com.moxietech.family_tree.server.errors.ForbiddenException
import com.moxietech.family_tree.server.errors.NotFoundException
import com.moxietech.family_tree.server.graph.GraphWriteLock
import com.moxietech.family_tree.server.graph.ViewerPolicy
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
 * snapshot API of [GrantScopeService] stays for reads, FT-013.)
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

    /** See the class comment. [ownerTakesSharedLock]: the owner path's previous [GraphWriteLock] use. */
    fun begin(treeUuid: Uuid, caller: AccountId, ownerTakesSharedLock: Boolean): WriteScope {
        checkReadCommitted()
        val tree = TreeRepository.findById(treeUuid) ?: throw NotFoundException("No tree with id $treeUuid")
        val isOwner = tree.ownerAccountId == caller
        when {
            !isOwner -> GraphWriteLock.exclusive(treeUuid)
            ownerTakesSharedLock -> GraphWriteLock.shared(treeUuid)
        }
        val membership = activeMembership(tree, caller, lock = !isOwner)
        if (isOwner) return WriteScope(tree, treeUuid, caller, membership, emptyList(), EditAuthority.owner())
        val callerUuid = Uuid.parse(caller.value)
        val grants = lockGrants(treeUuid, callerUuid)
        val authority = authorityFor(treeUuid, callerUuid, membership, grants)
        if (!authority.canContribute) throw ForbiddenException(noEditAccessMessage(tree.id.value))
        return WriteScope(tree, treeUuid, caller, membership, grants, authority)
    }

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

    /** The caller's authority from the current graph; [begin] has taken the locks. */
    internal fun authorityFor(treeUuid: Uuid, callerUuid: Uuid, membership: Membership, grants: List<Grant>): EditAuthority {
        val scopes = GrantScopeService.scopesForWriter(treeUuid, grants)
        return EditAuthority(
            isOwner = false,
            grants = grants.map { grant ->
                EditAuthority.ScopedGrant(grant.role, grant.branch, scopes.getValue(grant.id).toPersonIds())
            },
            homeBranch = HomeBranchService.homeBranchForWriter(treeUuid, membership).toPersonIds(),
            accountHolders = linkedPeople(treeUuid).toPersonIds(),
            ownUnattached = unattachedCreatedBy(treeUuid, callerUuid).toPersonIds(),
        )
    }

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

    /** Every person ID of [treeUuid], for the reach comparison after a graph change. */
    internal fun peopleOf(treeUuid: Uuid): Set<Uuid> = withJdbc { connection ->
        connection.prepareStatement("select id from person where tree_id = ?").use { st ->
            st.setUuid(1, treeUuid)
            st.executeQuery().use { rs -> buildSet { while (rs.next()) add(Uuid.parse(rs.getString(1))) } }
        }
    }

    private fun checkReadCommitted() {
        val isolation = TransactionManager.currentOrNull()?.transactionIsolation
        check(isolation == Connection.TRANSACTION_READ_COMMITTED) {
            "Write authorization must run first in a READ COMMITTED write transaction"
        }
    }
}

/**
 * FT-012: one write's caller and their [EditAuthority], valid only inside the
 * transaction [WriteAccess.begin] started. Every check throws a [ForbiddenException]
 * that rolls the transaction back.
 */
internal class WriteScope(
    val tree: Tree,
    private val treeUuid: Uuid,
    val caller: AccountId,
    private val membership: Membership,
    private val grants: List<Grant>,
    private val authority: EditAuthority,
) {
    val isOwner: Boolean get() = authority.isOwner
    val isTreeWide: Boolean get() = authority.isTreeWide

    fun personAccess(person: Uuid): EditAccess = authority.personAccess(person.toPersonId())

    fun relationshipAccess(people: Collection<Uuid>): EditAccess = authority.relationshipAccess(people.map { it.toPersonId() })

    /** Live access to [person], or 403. */
    fun requirePerson(person: Uuid) = enforce(personAccess(person))

    /** Live access to at least one of [people] (a union's partners, a child link's parents and child), or 403. */
    fun requireRelationship(people: Collection<Uuid>) = enforce(relationshipAccess(people))

    /**
     * A fact about [subjects] (an event: its person or its union's partners; a citation
     * target's people): live access to at least one of them, or 403. When refused and
     * [missing] is given, a fact the caller can't see at all ([ViewerPolicy.canSeeAll],
     * the rule every read uses to omit it) throws [missing] instead, so the refusal
     * doesn't confirm that a private event exists (as the FT-002 citation route does).
     */
    fun requireFact(subjects: List<Person>, missing: (() -> Throwable)? = null) {
        val access = relationshipAccess(subjects.map { Uuid.parse(it.id.value) })
        if (access == EditAccess.LIVE) return
        if (missing != null && !ViewerPolicy(tree, caller).canSeeAll(subjects)) throw missing()
        enforce(access)
    }

    /** Whether this caller may attach media: tree-wide members only (FT-012). */
    fun requireTreeWide() {
        if (!isTreeWide) throw ForbiddenException(TreeService.treeWideOnlyMessage(tree.id.value))
    }

    /**
     * After a graph change in this transaction (and its generation advance): 403 when
     * the caller could now edit anyone live they couldn't before, which rolls the change
     * back. Nothing to check for tree-wide callers, who edit everyone either way.
     */
    fun requireNoGainedReach() {
        if (authority.isTreeWide) return
        val after = WriteAccess.authorityFor(treeUuid, Uuid.parse(caller.value), membership, grants)
        val people = WriteAccess.peopleOf(treeUuid).toPersonIds()
        if (authority.gainedBy(after, people).isNotEmpty()) {
            throw ForbiddenException(WriteAccess.widensReachMessage(tree.id.value))
        }
    }

    private fun enforce(access: EditAccess) = when (access) {
        EditAccess.LIVE -> Unit
        EditAccess.OUTSIDE_BRANCH -> throw ForbiddenException(WriteAccess.outsideBranchMessage(tree.id.value))
        EditAccess.NONE -> throw ForbiddenException(WriteAccess.noEditAccessMessage(tree.id.value))
    }
}

private fun Uuid.toPersonId() = PersonId(toString())

private fun Set<Uuid>.toPersonIds(): Set<PersonId> = mapTo(mutableSetOf()) { it.toPersonId() }
