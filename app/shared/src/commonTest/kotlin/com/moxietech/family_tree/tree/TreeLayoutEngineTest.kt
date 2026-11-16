package com.moxietech.family_tree.tree

import com.moxietech.family_tree.union
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TreeLayoutEngineTest {

    private val config = LayoutConfig()

    private fun layout(people: List<String>, vararg unions: com.moxietech.family_tree.api.UnionDto) =
        TreeLayoutEngine.layout(GraphSlice(people, unions.toList()), config)

    private fun TreeLayout.at(id: String) = people.single { it.personId == id }

    private fun TreeLayout.assertNoOverlaps() {
        val byRow = people.groupBy { it.generation }
        for ((_, row) in byRow) {
            val sorted = row.sortedBy { it.x }
            sorted.zipWithNext { a, b ->
                assertTrue(b.x - a.x >= config.cardWidth, "cards overlap: ${a.personId} and ${b.personId}")
            }
        }
        people.forEach { assertTrue(it.x >= 0f && it.y >= 0f && it.x + config.cardWidth <= width && it.y + config.cardHeight <= height) }
    }

    @Test
    fun emptySliceHasNothingToDraw() {
        assertEquals(TreeLayout.EMPTY, layout(emptyList()))
    }

    @Test
    fun partnersShareARowAndChildrenSitBelowCentred() {
        val result = layout(listOf("mum", "dad", "a", "b"), union("u", "mum", "dad", "a", "b"))

        assertEquals(0, result.at("mum").generation)
        assertEquals(0, result.at("dad").generation)
        assertEquals(1, result.at("a").generation)
        assertEquals(1, result.at("b").generation)
        val parentsMid = (result.at("mum").x + result.at("dad").x) / 2
        val childrenMid = (result.at("a").x + result.at("b").x) / 2
        assertTrue(abs(parentsMid - childrenMid) < 1f, "parents should be centred over their children")
        assertEquals(0, result.skippedLinks)
        result.assertNoOverlaps()
        // Partner line, drop from it, sibling bar, and one line down to each child.
        assertEquals(5, result.segments.size)
    }

    @Test
    fun spouseWithoutParentsJoinsTheirPartnersGeneration() {
        val result = layout(
            listOf("gp1", "gp2", "mum", "dad", "kid"),
            union("u1", "gp1", "gp2", "mum"),
            union("u2", "mum", "dad", "kid"),
        )

        assertEquals(1, result.at("mum").generation)
        assertEquals(1, result.at("dad").generation)
        assertEquals(2, result.at("kid").generation)
        result.assertNoOverlaps()
    }

    @Test
    fun childOfTwoUnionsSitsBelowTheLowerParents() {
        // Birth parents in generation 0; adoptive parent is a grandchild-generation member.
        val result = layout(
            listOf("b1", "b2", "g", "adoptive", "kid"),
            union("birth", "b1", "b2", "kid"),
            union("up", "g", null, "adoptive"),
            union("adoption", "adoptive", null, "kid"),
        )

        assertEquals(2, result.at("kid").generation)
        assertTrue(result.at("kid").generation > result.at("adoptive").generation)
        assertTrue(result.at("kid").generation > result.at("b1").generation)
        assertEquals(0, result.skippedLinks)
        result.assertNoOverlaps()
    }

    @Test
    fun loopInTheDataIsSkippedInsteadOfHanging() {
        // a is b's parent and b is a's parent.
        val result = layout(listOf("a", "b"), union("u1", "a", null, "b"), union("u2", "b", null, "a"))

        assertEquals(2, result.people.size)
        assertEquals(1, result.skippedLinks)
        assertTrue(result.at("a").generation != result.at("b").generation)
        result.assertNoOverlaps()
    }

    @Test
    fun longerLoopAndSelfParentingAreBothSkipped() {
        val result = layout(
            listOf("a", "b", "c"),
            union("u1", "a", null, "b"),
            union("u2", "b", null, "c"),
            union("u3", "c", null, "a"),
            union("u4", "a", "b", "a"),
        )

        assertEquals(2, result.skippedLinks)
        result.assertNoOverlaps()
    }

    @Test
    fun partnerWhoIsAlsoADescendantFallsBackToPlainGenerations() {
        val result = layout(listOf("a", "b", "c"), union("u1", "a", null, "b"), union("u2", "b", null, "c"), union("u3", "a", "c"))

        assertEquals(0, result.at("a").generation)
        assertEquals(1, result.at("b").generation)
        assertEquals(2, result.at("c").generation)
        result.assertNoOverlaps()
    }

    @Test
    fun linksToPeopleOutsideTheSliceAreIgnored() {
        val result = layout(listOf("mum", "kid"), union("u", "mum", "hidden-partner", "kid", "outside"))

        assertEquals(setOf("mum", "kid"), result.people.map { it.personId }.toSet())
        assertEquals(0, result.skippedLinks)
        // Single visible partner: drop from the card, bar, one child line.
        assertEquals(1, result.at("kid").generation)
    }

    @Test
    fun remarriageKeepsAllPartnersOnOneRow() {
        val result = layout(
            listOf("first", "p", "second", "a", "b"),
            union("u1", "first", "p", "a"),
            union("u2", "p", "second", "b"),
        )

        val row = listOf("first", "p", "second").map { result.at(it) }
        assertTrue(row.all { it.generation == 0 })
        val order = row.sortedBy { it.x }.map { it.personId }
        assertEquals("p", order[1], "the person with two partners sits between them")
        result.assertNoOverlaps()
    }

    @Test
    fun layoutIsDeterministic() {
        val people = listOf("gp1", "gp2", "mum", "dad", "kid", "aunt")
        val unions = arrayOf(union("u1", "gp1", "gp2", "mum", "aunt"), union("u2", "mum", "dad", "kid"))
        assertEquals(layout(people, *unions), layout(people, *unions))
    }
}
