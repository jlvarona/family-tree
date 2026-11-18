package com.moxietech.family_tree.permissions

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FT-012: the action / role / target table of [EditAuthority], [GrantRules] and the
 * grant-aware [TreePermissions] gates. Branch scopes are given directly (they come from
 * [BranchResolution] or the server's CTE), so each case shows one rule.
 *
 * People: `grandma` (deceased, no account), `aunt` (has an account), `cousin` (no
 * account), `brother` (has an account), `me` (the member's own person), `stranger`
 * (other side, no account), `draft` (created by the member, unattached).
 */
class EditAuthorityTest {

    private val grandma = PersonId("grandma")
    private val aunt = PersonId("aunt")
    private val cousin = PersonId("cousin")
    private val brother = PersonId("brother")
    private val me = PersonId("me")
    private val stranger = PersonId("stranger")
    private val draft = PersonId("draft")
    private val everyone = listOf(grandma, aunt, cousin, brother, me, stranger, draft)

    private val accounts = setOf(aunt, brother, me)
    private val home = setOf(me, brother)
    private val grandmaSide = setOf(grandma, aunt, cousin)

    private fun branch(direction: BranchDirection, depth: Int? = null) = Branch(grandma, direction, depth, includePartners = false)
    private fun grant(role: GrantRole, scope: Set<PersonId>, direction: BranchDirection = BranchDirection.SIDE) =
        EditAuthority.ScopedGrant(role, branch(direction), scope)

    private fun member(
        vararg grants: EditAuthority.ScopedGrant,
        homeBranch: Set<PersonId> = home,
        unattached: Set<PersonId> = setOf(draft),
    ) = EditAuthority(isOwner = false, grants.toList(), homeBranch, accounts, unattached)

    @Test
    fun `the owner edits everyone live and is tree-wide`() {
        val owner = EditAuthority.owner()
        assertTrue(owner.isTreeWide)
        assertTrue(everyone.all { owner.personAccess(it) == EditAccess.LIVE })
        assertEquals(EditAccess.LIVE, owner.relationshipAccess(listOf(stranger)))
    }

    @Test
    fun `a whole-tree admin edits everyone live, accounts included`() {
        val admin = member(grant(GrantRole.ADMIN, everyone.toSet(), BranchDirection.WHOLE_TREE), homeBranch = emptySet())
        assertTrue(admin.isAdmin)
        assertTrue(admin.isTreeWide)
        assertTrue(everyone.all { admin.personAccess(it) == EditAccess.LIVE })
    }

    @Test
    fun `an admin grant on a limited branch fails closed and carries no authority at all`() {
        val stored = member(grant(GrantRole.ADMIN, grandmaSide, BranchDirection.SIDE))
        assertFalse(stored.isAdmin)
        assertFalse(stored.isTreeWide)
        assertFalse(stored.canContribute)
        // Not even the home branch: the member holds no grant that can edit.
        assertTrue(everyone.all { stored.personAccess(it) == EditAccess.NONE })
        assertNull(stored.roleOn(grandma))
    }

    @Test
    fun `a branch owner edits everyone in the branch live, accounts included`() {
        val owner = member(grant(GrantRole.BRANCH_OWNER, grandmaSide))
        assertEquals(EditAccess.LIVE, owner.personAccess(grandma))
        assertEquals(EditAccess.LIVE, owner.personAccess(aunt))
        assertEquals(EditAccess.LIVE, owner.personAccess(cousin))
        assertEquals(EditAccess.OUTSIDE_BRANCH, owner.personAccess(stranger))
        assertFalse(owner.isTreeWide)
    }

    @Test
    fun `a contributor edits people without an account in the branch, and accounts there need a proposal`() {
        val contributor = member(grant(GrantRole.CONTRIBUTOR, grandmaSide))
        assertEquals(EditAccess.LIVE, contributor.personAccess(grandma))
        assertEquals(EditAccess.LIVE, contributor.personAccess(cousin))
        assertEquals(EditAccess.OUTSIDE_BRANCH, contributor.personAccess(aunt))
        assertEquals(EditAccess.OUTSIDE_BRANCH, contributor.personAccess(stranger))
    }

    @Test
    fun `a contributor edits their whole home branch live, including relatives with accounts and themselves`() {
        val contributor = member(grant(GrantRole.CONTRIBUTOR, grandmaSide))
        assertEquals(EditAccess.LIVE, contributor.personAccess(me))
        assertEquals(EditAccess.LIVE, contributor.personAccess(brother))
    }

    @Test
    fun `a viewer changes nothing, not even in the home branch`() {
        val viewer = member(grant(GrantRole.VIEWER, everyone.toSet(), BranchDirection.WHOLE_TREE))
        assertFalse(viewer.canContribute)
        assertTrue(everyone.all { viewer.personAccess(it) == EditAccess.NONE })
        assertEquals(EditAccess.NONE, viewer.relationshipAccess(listOf(me, brother)))
    }

    @Test
    fun `a member with no grants changes nothing`() {
        val nobody = member()
        assertFalse(nobody.canContribute)
        assertEquals(EditAccess.NONE, nobody.personAccess(me))
    }

    @Test
    fun `mixed grants union and the highest role on a person wins`() {
        val mixed = member(
            grant(GrantRole.VIEWER, everyone.toSet(), BranchDirection.WHOLE_TREE),
            grant(GrantRole.CONTRIBUTOR, grandmaSide),
            grant(GrantRole.BRANCH_OWNER, setOf(aunt), BranchDirection.DESCENDANTS),
        )
        assertEquals(GrantRole.BRANCH_OWNER, mixed.roleOn(aunt))
        assertEquals(GrantRole.CONTRIBUTOR, mixed.roleOn(cousin))
        assertEquals(GrantRole.VIEWER, mixed.roleOn(stranger))
        assertEquals(EditAccess.LIVE, mixed.personAccess(aunt))
        assertEquals(EditAccess.LIVE, mixed.personAccess(cousin))
        assertEquals(EditAccess.OUTSIDE_BRANCH, mixed.personAccess(stranger))
    }

    @Test
    fun `an ignored limited admin grant doesn't lower another grant`() {
        val m = member(grant(GrantRole.ADMIN, grandmaSide), grant(GrantRole.CONTRIBUTOR, grandmaSide))
        assertEquals(GrantRole.CONTRIBUTOR, m.roleOn(cousin))
        assertEquals(EditAccess.OUTSIDE_BRANCH, m.personAccess(aunt))
        assertFalse(m.isTreeWide)
    }

    @Test
    fun `a person the member created and hasn't attached yet is theirs, unless it has an account`() {
        val contributor = member(grant(GrantRole.CONTRIBUTOR, grandmaSide))
        assertEquals(EditAccess.LIVE, contributor.personAccess(draft))
        val linked = EditAuthority(false, listOf(grant(GrantRole.CONTRIBUTOR, grandmaSide)), home, accounts + draft, setOf(draft))
        assertEquals(EditAccess.OUTSIDE_BRANCH, linked.personAccess(draft))
    }

    @Test
    fun `a relationship needs live access to at least one of its people`() {
        val contributor = member(grant(GrantRole.CONTRIBUTOR, grandmaSide))
        assertEquals(EditAccess.LIVE, contributor.relationshipAccess(listOf(cousin, stranger)))
        assertEquals(EditAccess.LIVE, contributor.relationshipAccess(listOf(stranger, draft)))
        assertEquals(EditAccess.OUTSIDE_BRANCH, contributor.relationshipAccess(listOf(aunt, stranger)))
        assertEquals(EditAccess.OUTSIDE_BRANCH, contributor.relationshipAccess(emptyList()))
    }

    @Test
    fun `gained reach after a graph change is what a reparenting or retargeting would add`() {
        val before = member(grant(GrantRole.CONTRIBUTOR, grandmaSide))
        // The stranger was attached under grandma: the side now covers them.
        val after = member(grant(GrantRole.CONTRIBUTOR, grandmaSide + stranger), unattached = emptySet())
        assertEquals(setOf(stranger), before.gainedBy(after, everyone))
        // Attaching the member's own draft adds nobody new.
        val withDraft = member(grant(GrantRole.CONTRIBUTOR, grandmaSide + draft), unattached = emptySet())
        assertEquals(emptySet<PersonId>(), before.gainedBy(withDraft, everyone))
        // Losing reach is never a gain.
        val smaller = member(grant(GrantRole.CONTRIBUTOR, setOf(grandma)))
        assertEquals(emptySet<PersonId>(), before.gainedBy(smaller, everyone))
    }

    @Test
    fun `grant rules accept admin only over the whole tree and keep every other role as stored`() {
        assertEquals(listOf(GrantRules.ADMIN_SCOPE_MESSAGE), GrantRules.validate(GrantRole.ADMIN, branch(BranchDirection.SIDE)))
        assertEquals(listOf(GrantRules.ADMIN_SCOPE_MESSAGE), GrantRules.validate(GrantRole.ADMIN, branch(BranchDirection.DESCENDANTS, 2)))
        assertEquals(emptyList<String>(), GrantRules.validate(GrantRole.ADMIN, branch(BranchDirection.WHOLE_TREE)))
        for (role in listOf(GrantRole.BRANCH_OWNER, GrantRole.CONTRIBUTOR, GrantRole.VIEWER)) {
            for (direction in BranchDirection.entries) {
                assertEquals(emptyList<String>(), GrantRules.validate(role, branch(direction)), "$role $direction")
                assertEquals(role, GrantRules.effectiveRole(role, branch(direction)))
            }
        }
    }

    // TreePermissions gates over stored grants.

    private val ownerAccount = AccountId("owner")
    private val relative = AccountId("relative")
    private val tree = Tree(TreeId("tree-a"), "Family A", ownerAccount, 1)
    private val active = Membership(tree.id, relative, me, MembershipStatus.ACTIVE)
    private val ownerMembership = Membership(tree.id, ownerAccount, null, MembershipStatus.ACTIVE)

    private fun stored(role: GrantRole, direction: BranchDirection, treeId: TreeId = tree.id, account: AccountId = relative) =
        Grant(GrantId("g-$role-$direction"), treeId, account, role, branch(direction), 1)

    @Test
    fun `edit and tree-wide gates follow the roles`() {
        assertTrue(TreePermissions.canEditSomewhere(tree, ownerAccount, ownerMembership, emptyList()))
        assertTrue(TreePermissions.canWriteTreeWide(tree, ownerAccount, ownerMembership, emptyList()))

        val contributor = listOf(stored(GrantRole.CONTRIBUTOR, BranchDirection.SIDE))
        assertTrue(TreePermissions.canEditSomewhere(tree, relative, active, contributor))
        assertFalse(TreePermissions.canWriteTreeWide(tree, relative, active, contributor))
        assertFalse(TreePermissions.canWrite(tree, relative, active))

        val admin = listOf(stored(GrantRole.ADMIN, BranchDirection.WHOLE_TREE))
        assertTrue(TreePermissions.canWriteTreeWide(tree, relative, active, admin))
        // Ownership and lifecycle operations stay with the owner.
        assertFalse(TreePermissions.canWrite(tree, relative, active))

        val limitedAdmin = listOf(stored(GrantRole.ADMIN, BranchDirection.SIDE))
        assertFalse(TreePermissions.canEditSomewhere(tree, relative, active, limitedAdmin))
        assertFalse(TreePermissions.canWriteTreeWide(tree, relative, active, limitedAdmin))

        val viewer = listOf(stored(GrantRole.VIEWER, BranchDirection.WHOLE_TREE))
        assertFalse(TreePermissions.canEditSomewhere(tree, relative, active, viewer))
        assertFalse(TreePermissions.canEditSomewhere(tree, relative, active, emptyList()))
    }

    @Test
    fun `grants never open a tree without an active membership, and only grants for this tree and caller count`() {
        val admin = listOf(stored(GrantRole.ADMIN, BranchDirection.WHOLE_TREE))
        val invited = active.copy(status = MembershipStatus.INVITED)
        assertFalse(TreePermissions.canEditSomewhere(tree, relative, invited, admin))
        assertFalse(TreePermissions.canWriteTreeWide(tree, relative, null, admin))
        val foreign = listOf(stored(GrantRole.ADMIN, BranchDirection.WHOLE_TREE, treeId = TreeId("tree-b")))
        assertFalse(TreePermissions.canWriteTreeWide(tree, relative, active, foreign))
        val someoneElses = listOf(stored(GrantRole.ADMIN, BranchDirection.WHOLE_TREE, account = AccountId("other")))
        assertFalse(TreePermissions.canEditSomewhere(tree, relative, active, someoneElses))
    }
}
