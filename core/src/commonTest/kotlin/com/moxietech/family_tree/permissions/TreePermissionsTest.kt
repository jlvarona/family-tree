package com.moxietech.family_tree.permissions

import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.Membership
import com.moxietech.family_tree.domain.MembershipStatus
import com.moxietech.family_tree.domain.Tree
import com.moxietech.family_tree.domain.TreeId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TreePermissionsTest {

    private val owner = AccountId("owner")
    private val relative = AccountId("relative")
    private val tree = Tree(id = TreeId("tree-a"), name = "Family A", ownerAccountId = owner, version = 1)

    private fun membership(account: AccountId, status: MembershipStatus, treeId: TreeId = tree.id) =
        Membership(treeId = treeId, accountId = account, personId = null, status = status)

    @Test
    fun `active owner can read and write`() {
        val m = membership(owner, MembershipStatus.ACTIVE)
        assertTrue(TreePermissions.canRead(tree, owner, m))
        assertTrue(TreePermissions.canWrite(tree, owner, m))
    }

    @Test
    fun `active non-owner can read but not write`() {
        val m = membership(relative, MembershipStatus.ACTIVE)
        assertTrue(TreePermissions.canRead(tree, relative, m))
        assertFalse(TreePermissions.canWrite(tree, relative, m))
    }

    @Test
    fun `invited account can neither read nor write`() {
        val m = membership(relative, MembershipStatus.INVITED)
        assertFalse(TreePermissions.canRead(tree, relative, m))
        assertFalse(TreePermissions.canWrite(tree, relative, m))
    }

    @Test
    fun `an owner whose membership is only invited cannot write`() {
        val m = membership(owner, MembershipStatus.INVITED)
        assertFalse(TreePermissions.canRead(tree, owner, m))
        assertFalse(TreePermissions.canWrite(tree, owner, m))
    }

    @Test
    fun `no membership grants nothing`() {
        assertFalse(TreePermissions.canRead(tree, relative, null))
        assertFalse(TreePermissions.canWrite(tree, owner, null))
    }

    @Test
    fun `a membership for another tree or account does not count`() {
        val otherTree = membership(relative, MembershipStatus.ACTIVE, treeId = TreeId("tree-b"))
        assertFalse(TreePermissions.canRead(tree, relative, otherTree))
        val someoneElse = membership(owner, MembershipStatus.ACTIVE)
        assertFalse(TreePermissions.canRead(tree, relative, someoneElse))
    }
}
