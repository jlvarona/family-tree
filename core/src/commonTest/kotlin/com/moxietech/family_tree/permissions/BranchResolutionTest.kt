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
import com.moxietech.family_tree.validation.Validation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fixture (generations top to bottom):
 *
 *   gg1 + gg2
 *       |
 *   gp1 + gp2          gpX (gp2's other partner, no children)
 *       |
 *   mum + dad          dadParent (dad's own parent: partner lineage)
 *       |
 *   me + spouse        spouseKid (spouse's child with exSpouse)
 *       |
 *   kid
 *       |  (adopted)
 *   grandkid
 *
 * plus an unrelated "stranger". The side tests add aunt (gp1 + gp2's other child),
 * cousin (aunt's child) and spouseMum (spouse's parent).
 */
class BranchResolutionTest {

    private val tree = TreeId("t")
    private fun p(name: String) = PersonId(name)
    private val people = listOf(
        "gg1", "gg2", "gp1", "gp2", "gpX", "mum", "dad", "dadParent", "me", "spouse", "exSpouse",
        "spouseKid", "kid", "grandkid", "stranger",
    ).map(::p).toMutableList()

    private val unions = mutableListOf<Union>()
    private val children = mutableListOf<UnionChild>()

    private fun union(id: String, a: String, b: String?, vararg kids: String, relation: ChildRelation = ChildRelation.BIRTH) {
        unions += Union(UnionId(id), tree, p(a), b?.let(::p), UnionType.MARRIED, null, null, 1)
        kids.forEach { children += UnionChild(UnionId(id), p(it), relation, 1) }
    }

    init {
        union("u-gg", "gg1", "gg2", "gp1")
        union("u-gpX", "gpX", "gp2")
        union("u-gp", "gp1", "gp2", "mum")
        union("u-dad", "dadParent", null, "dad")
        union("u-parents", "mum", "dad", "me")
        union("u-me", "me", "spouse", "kid")
        union("u-ex", "spouse", "exSpouse", "spouseKid")
        union("u-kid", "kid", null, "grandkid", relation = ChildRelation.ADOPTED)
    }

    private fun addSideRelatives() {
        people += listOf("aunt", "cousin", "spouseMum").map(::p)
        children += UnionChild(UnionId("u-gp"), p("aunt"), ChildRelation.BIRTH, 1)
        union("u-aunt", "aunt", null, "cousin")
        union("u-spouseMum", "spouseMum", null, "spouse")
    }

    private fun resolve(anchor: String, direction: BranchDirection, depth: Int? = null, partners: Boolean = false) =
        BranchResolution.resolve(Branch(p(anchor), direction, depth, partners), people, unions, children).map { it.value }.toSet()

    @Test
    fun ancestorsFollowBothPartnersUpToTheDepth() {
        assertEquals(setOf("me", "mum", "dad"), resolve("me", BranchDirection.ANCESTORS, depth = 1))
        assertEquals(setOf("me", "mum", "dad", "gp1", "gp2", "dadParent"), resolve("me", BranchDirection.ANCESTORS, depth = 2))
        assertEquals(
            setOf("me", "mum", "dad", "gp1", "gp2", "dadParent", "gg1", "gg2"),
            resolve("me", BranchDirection.ANCESTORS),
        )
    }

    @Test
    fun descendantsIncludeEveryChildRelationUpToTheDepth() {
        assertEquals(setOf("me", "kid"), resolve("me", BranchDirection.DESCENDANTS, depth = 1))
        assertEquals(setOf("me", "kid", "grandkid"), resolve("me", BranchDirection.DESCENDANTS))
    }

    @Test
    fun partnersAreOnlyDirectPartnersOfBloodMembers() {
        // Spouse is in; the spouse's other child and other partner are not (partner lineage).
        assertEquals(setOf("me", "spouse", "kid", "grandkid"), resolve("me", BranchDirection.DESCENDANTS, partners = true))
        // gp2's first partner joins as a partner of an ancestor; nobody above gpX does.
        val up = resolve("mum", BranchDirection.ANCESTORS, partners = true)
        assertTrue("gpX" in up && "gp1" in up && "dad" in up, up.toString())
        assertTrue("dadParent" !in up && "me" !in up, up.toString())
    }

    @Test
    fun depthLimitsPartnersToo() {
        // Depth 1 from gp1: mum only; mum's partner dad joins, gp2 as gp1's partner.
        assertEquals(setOf("gp1", "gp2", "mum", "dad"), resolve("gp1", BranchDirection.DESCENDANTS, depth = 1, partners = true))
    }

    @Test
    fun sideCapsOnlyTheAscentAndTakesEveryDescendantOfThoseAncestors() {
        addSideRelatives()
        // Depth 1: parents are roots; grandparents (just beyond the cap), aunt and cousin are out;
        // grandkid, two generations below me, is in.
        assertEquals(setOf("me", "mum", "dad", "kid", "grandkid"), resolve("me", BranchDirection.SIDE, depth = 1))
        // Depth 2: grandparents join, and so do their other descendants (aunt, cousin); gg1 stays out.
        val two = setOf("me", "mum", "dad", "gp1", "gp2", "dadParent", "aunt", "cousin", "kid", "grandkid")
        assertEquals(two, resolve("me", BranchDirection.SIDE, depth = 2))
        assertEquals(two + setOf("gg1", "gg2"), resolve("me", BranchDirection.SIDE))
        // Partners come last and only directly: spouse and gpX, never the spouse's own family.
        assertEquals(two + setOf("spouse", "gpX"), resolve("me", BranchDirection.SIDE, depth = 2, partners = true))
    }

    @Test
    fun sideIncludesTheAnchorsDescendantsWithoutRecordedParents() {
        addSideRelatives()
        assertEquals(setOf("spouseMum", "spouse", "kid", "grandkid", "spouseKid"), resolve("spouseMum", BranchDirection.SIDE, depth = 3))
        assertEquals(setOf("stranger"), resolve("stranger", BranchDirection.SIDE, partners = true))
    }

    @Test
    fun wholeTreeIsEveryoneWhateverTheDepth() {
        assertEquals(people.map { it.value }.toSet(), resolve("me", BranchDirection.WHOLE_TREE))
        assertEquals(people.map { it.value }.toSet(), resolve("me", BranchDirection.WHOLE_TREE, depth = 1))
    }

    @Test
    fun cyclesEndTheWalk() {
        // grandkid recorded as gg1's parent closes a loop.
        union("u-loop", "grandkid", null, "gg1")
        assertEquals(setOf("me", "kid", "grandkid", "gg1", "gp1", "mum"), resolve("me", BranchDirection.DESCENDANTS))
        assertTrue("grandkid" in resolve("me", BranchDirection.ANCESTORS))
        assertTrue("dad" in resolve("me", BranchDirection.SIDE, depth = 1))
    }

    @Test
    fun aHugeFiniteDepthOnACycleIsTheUnlimitedResult() {
        people += listOf(p("a"), p("b"))
        union("u-a", "a", null, "b")
        union("u-b", "b", null, "a")
        for (direction in listOf(BranchDirection.ANCESTORS, BranchDirection.DESCENDANTS, BranchDirection.SIDE)) {
            assertEquals(setOf("a", "b"), resolve("a", direction, depth = Int.MAX_VALUE), direction.name)
            assertEquals(resolve("a", direction), resolve("a", direction, depth = Int.MAX_VALUE), direction.name)
        }
    }

    @Test
    fun missingAnchorAndForeignLinksResolveToNothingExtra() {
        assertEquals(emptySet(), resolve("ghost", BranchDirection.DESCENDANTS))
        union("u-foreign", "me", "outsider", "outsiderKid")
        assertEquals(setOf("me", "kid", "grandkid"), resolve("me", BranchDirection.DESCENDANTS))
        assertEquals(setOf("me", "spouse", "kid", "grandkid"), resolve("me", BranchDirection.DESCENDANTS, partners = true))
    }

    @Test
    fun depthMustBeEmptyOrPositive() {
        assertEquals(emptyList(), Validation.validateBranchDepth(null))
        assertEquals(emptyList(), Validation.validateBranchDepth(Int.MAX_VALUE))
        assertEquals(1, Validation.validateBranchDepth(0).size)
    }
}
