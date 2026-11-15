package com.moxietech.family_tree.permissions

import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.Person
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.Sex
import com.moxietech.family_tree.domain.Tree
import com.moxietech.family_tree.domain.TreeId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersonVisibilityTest {

    private val owner = AccountId("owner")
    private val relative = AccountId("relative")
    private val tree = Tree(id = TreeId("tree-a"), name = "Family A", ownerAccountId = owner, version = 1)

    private fun person(isLiving: Boolean) = Person(
        id = PersonId("p"),
        treeId = tree.id,
        givenNames = "Ana",
        surnamePaternal = "García",
        surnameMaternal = null,
        sex = Sex.FEMALE,
        isLiving = isLiving,
        profileMediaId = null,
        bio = null,
        createdByAccountId = owner,
        version = 1,
    )

    @Test
    fun `the owner sees living and deceased people`() {
        assertTrue(PersonVisibility.canSeeDetails(tree, owner, person(isLiving = true)))
        assertTrue(PersonVisibility.canSeeDetails(tree, owner, person(isLiving = false)))
    }

    @Test
    fun `another member sees deceased people but not living ones`() {
        assertTrue(PersonVisibility.canSeeDetails(tree, relative, person(isLiving = false)))
        assertFalse(PersonVisibility.canSeeDetails(tree, relative, person(isLiving = true)))
    }
}
