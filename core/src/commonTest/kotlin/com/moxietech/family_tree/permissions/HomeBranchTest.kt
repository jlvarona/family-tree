package com.moxietech.family_tree.permissions

import com.moxietech.family_tree.domain.Branch
import com.moxietech.family_tree.domain.BranchDirection
import com.moxietech.family_tree.domain.ChildRelation
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.domain.Union
import com.moxietech.family_tree.domain.UnionChild
import com.moxietech.family_tree.domain.UnionId
import com.moxietech.family_tree.domain.UnionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FT-011 home branches, on the `BranchResolutionTest` family plus aunt (gp1 + gp2's
 * other child), cousin (aunt's child) and spouseMum (spouse's parent):
 *
 *   gg1 + gg2
 *       |
 *   gp1 + gp2          gpX (gp2's other partner, no children)
 *       |
 *   mum + dad          dadParent (dad's own parent)
 *       |
 *   me + spouse        spouseKid (spouse's child with exSpouse)
 *       |
 *   kid
 *       |  (adopted)
 *   grandkid
 */
class HomeBranchTest {

    private val tree = TreeId("t")
    private fun p(name: String) = PersonId(name)
    private val people = listOf(
        "gg1", "gg2", "gp1", "gp2", "gpX", "mum", "dad", "dadParent", "me", "spouse", "exSpouse",
        "spouseKid", "kid", "grandkid", "stranger", "aunt", "cousin", "spouseMum",
    ).map(::p)

    private val unions = mutableListOf<Union>()
    private val children = mutableListOf<UnionChild>()

    private fun union(id: String, a: String, b: String?, vararg kids: String, relation: ChildRelation = ChildRelation.BIRTH) {
        unions += Union(UnionId(id), tree, p(a), b?.let(::p), UnionType.MARRIED, null, null, 1)
        kids.forEach { children += UnionChild(UnionId(id), p(it), relation, 1) }
    }

    init {
        union("u-gg", "gg1", "gg2", "gp1")
        union("u-gpX", "gpX", "gp2")
        union("u-gp", "gp1", "gp2", "mum", "aunt")
        union("u-dad", "dadParent", null, "dad")
        union("u-parents", "mum", "dad", "me")
        union("u-me", "me", "spouse", "kid")
        union("u-ex", "spouse", "exSpouse", "spouseKid")
        union("u-kid", "kid", null, "grandkid", relation = ChildRelation.ADOPTED)
        union("u-aunt", "aunt", null, "cousin")
        union("u-spouseMum", "spouseMum", null, "spouse")
    }

    private fun home(person: String) = HomeBranch.resolve(p(person), people, unions, children).map { it.value }.toSet()

    @Test
    fun isASideBranchOfOneGenerationWithPartners() {
        assertEquals(Branch(p("me"), BranchDirection.SIDE, depth = 1, includePartners = true), HomeBranch.of(p("me")))
    }

    @Test
    fun coversParentsTheirDescendantsAndPartners() {
        // Parents, own descendants (any relation, no depth limit) and the spouse; nobody
        // above the parents and nobody from the spouse's own family beyond the spouse.
        assertEquals(setOf("me", "mum", "dad", "kid", "grandkid", "spouse"), home("me"))
    }

    @Test
    fun includesSiblingsNiecesAndNephewsButNotGrandparentsLines() {
        assertEquals(
            setOf("mum", "gp1", "gp2", "aunt", "cousin", "me", "kid", "grandkid", "dad", "gpX", "spouse"),
            home("mum"),
        )
        val dad = home("dad")
        assertEquals(setOf("dad", "dadParent", "me", "kid", "grandkid", "mum", "spouse"), dad)
        assertTrue("gp1" !in dad && "aunt" !in dad, "the other parent's lineage is not in the home branch")
    }

    @Test
    fun aParentlessPersonGetsOnlyThemselvesTheirDescendantsAndPartners() {
        assertEquals(setOf("stranger"), home("stranger"))
        // spouseMum has no recorded parents: her line downwards and its partners, never the whole tree.
        assertEquals(setOf("spouseMum", "spouse", "kid", "spouseKid", "grandkid", "me", "exSpouse"), home("spouseMum"))
        assertTrue(home("gg1").size < people.size)
    }

    @Test
    fun anUnknownPersonHasNoHomeBranch() {
        assertEquals(emptySet(), home("nobody"))
    }
}
