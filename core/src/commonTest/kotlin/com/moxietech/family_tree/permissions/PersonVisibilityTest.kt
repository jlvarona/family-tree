package com.moxietech.family_tree.permissions

import com.moxietech.family_tree.api.PersonDto
import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.Branch
import com.moxietech.family_tree.domain.BranchDirection
import com.moxietech.family_tree.domain.GrantRole
import com.moxietech.family_tree.domain.Person
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.Sex
import com.moxietech.family_tree.domain.Tree
import com.moxietech.family_tree.domain.TreeId
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersonVisibilityTest {

    private val owner = AccountId("owner")
    private val relative = AccountId("relative")
    private val tree = Tree(id = TreeId("tree-a"), name = "Family A", ownerAccountId = owner, version = 1)

    private fun person(id: String = "p", isLiving: Boolean) = Person(
        id = PersonId(id),
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

    private fun grant(role: GrantRole, direction: BranchDirection, vararg scope: String) = EditAuthority.ScopedGrant(
        role = role,
        branch = Branch(PersonId(scope.firstOrNull() ?: "anchor"), direction, depth = null, includePartners = false),
        scope = scope.mapTo(mutableSetOf(), ::PersonId),
    )

    @Test
    fun `the owner sees living and deceased people`() {
        val owner = PersonVisibility.of(tree, owner, emptyList())
        assertTrue(owner.isOwner)
        assertTrue(owner.canSeeDetails(person(isLiving = true)))
        assertTrue(owner.canSeeDetails(person(isLiving = false)))
    }

    @Test
    fun `a member without grants sees deceased people but not living ones`() {
        val member = PersonVisibility.of(tree, relative, emptyList())
        assertFalse(member.isOwner)
        assertTrue(member.canSeeDetails(person(isLiving = false)))
        assertFalse(member.canSeeDetails(person(isLiving = true)))
    }

    @Test
    fun `every effective role reveals the living people its scope covers and no one else`() {
        for (role in listOf(GrantRole.VIEWER, GrantRole.CONTRIBUTOR, GrantRole.BRANCH_OWNER)) {
            val member = PersonVisibility.member(listOf(grant(role, BranchDirection.DESCENDANTS, "p", "kid")))
            assertTrue(member.canSeeDetails(person("p", isLiving = true)), "$role")
            assertTrue(member.canSeeDetails(person("kid", isLiving = true)), "$role")
            assertFalse(member.canSeeDetails(person("cousin", isLiving = true)), "$role")
            assertTrue(member.canSeeDetails(person("cousin", isLiving = false)), "$role")
        }
    }

    @Test
    fun `grants union`() {
        val member = PersonVisibility.member(
            listOf(grant(GrantRole.VIEWER, BranchDirection.ANCESTORS, "a"), grant(GrantRole.CONTRIBUTOR, BranchDirection.DESCENDANTS, "b")),
        )
        assertTrue(member.canSeeDetails(person("a", isLiving = true)))
        assertTrue(member.canSeeDetails(person("b", isLiving = true)))
        assertFalse(member.canSeeDetails(person("c", isLiving = true)))
    }

    @Test
    fun `a whole-tree admin sees the people its scope resolves to`() {
        val member = PersonVisibility.member(listOf(grant(GrantRole.ADMIN, BranchDirection.WHOLE_TREE, "a", "b")))
        assertTrue(member.canSeeDetails(person("a", isLiving = true)))
        assertTrue(member.canSeeDetails(person("b", isLiving = true)))
    }

    @Test
    fun `a limited ADMIN grant fails closed and reveals no one, even in its own scope`() {
        for (direction in listOf(BranchDirection.ANCESTORS, BranchDirection.DESCENDANTS, BranchDirection.SIDE)) {
            val member = PersonVisibility.member(listOf(grant(GrantRole.ADMIN, direction, "p", "kid")))
            assertFalse(member.canSeeDetails(person("p", isLiving = true)), "$direction")
            assertFalse(member.canSeeDetails(person("kid", isLiving = true)), "$direction")
            assertTrue(member.canSeeDetails(person("kid", isLiving = false)), "$direction")
        }
    }

    @Test
    fun `a limited ADMIN grant doesn't hide what another grant reveals`() {
        val member = PersonVisibility.member(
            listOf(grant(GrantRole.ADMIN, BranchDirection.SIDE, "p"), grant(GrantRole.VIEWER, BranchDirection.DESCENDANTS, "p")),
        )
        assertTrue(member.canSeeDetails(person("p", isLiving = true)))
    }

    @Test
    fun `grants passed for the owner are ignored`() {
        val owner = PersonVisibility.of(tree, owner, listOf(grant(GrantRole.ADMIN, BranchDirection.SIDE, "x")))
        assertTrue(owner.canSeeDetails(person("p", isLiving = true)))
    }

    @Test
    fun `revealedBy lists only living people the new visibility uncovers, minus exemptions`() {
        val before = PersonVisibility.member(listOf(grant(GrantRole.VIEWER, BranchDirection.DESCENDANTS, "a")))
        val after = PersonVisibility.member(listOf(grant(GrantRole.VIEWER, BranchDirection.DESCENDANTS, "a", "b", "mine")))
        val living = listOf("a", "b", "mine", "c").map(::PersonId)
        assertEquals(setOf(PersonId("b"), PersonId("mine")), before.revealedBy(after, living))
        assertEquals(setOf(PersonId("b")), before.revealedBy(after, living, exempt = setOf(PersonId("mine"))))
        assertEquals(emptySet(), after.revealedBy(before, living), "losing visibility reveals nothing")
        assertEquals(emptySet(), PersonVisibility.owner().revealedBy(PersonVisibility.owner(), living))
    }

    @Test
    fun `both keeps only people visible to each`() {
        val first = PersonVisibility.member(listOf(grant(GrantRole.VIEWER, BranchDirection.DESCENDANTS, "a", "b")))
        val second = PersonVisibility.member(listOf(grant(GrantRole.VIEWER, BranchDirection.DESCENDANTS, "b", "c")))
        val both = first.both(second)
        assertFalse(both.canSeeDetails(person("a", isLiving = true)))
        assertTrue(both.canSeeDetails(person("b", isLiving = true)))
        assertFalse(both.canSeeDetails(person("c", isLiving = true)))
        assertTrue(both.canSeeDetails(person("a", isLiving = false)))
        assertTrue(PersonVisibility.owner().both(PersonVisibility.owner()).canSeeDetails(person("x", isLiving = true)))
    }

    @Test
    fun `the redacted flag is omitted when false and read as false when absent`() {
        val dto = PersonDto("p", "t", "Ana", null, null, "Ana", "FEMALE", true, null, null, 1)
        val json = Json.encodeToString(PersonDto.serializer(), dto)
        assertFalse("redacted" in json, json)
        assertEquals(dto, Json.decodeFromString(PersonDto.serializer(), json))
        val placeholder = dto.copy(givenNames = "Private", fullName = "Private", redacted = true)
        val placeholderJson = Json.encodeToString(PersonDto.serializer(), placeholder)
        assertTrue("\"redacted\":true" in placeholderJson, placeholderJson)
        assertEquals(placeholder, Json.decodeFromString(PersonDto.serializer(), placeholderJson))
        // encodeDefaults (the server's audit JSON) still leaves it out of full records.
        assertFalse("redacted" in Json { encodeDefaults = true }.encodeToString(PersonDto.serializer(), dto))
    }
}
