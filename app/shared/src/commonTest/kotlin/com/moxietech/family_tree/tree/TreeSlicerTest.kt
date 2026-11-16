package com.moxietech.family_tree.tree

import com.moxietech.family_tree.api.GedcomDateDto
import com.moxietech.family_tree.graph
import com.moxietech.family_tree.person
import com.moxietech.family_tree.union
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TreeSlicerTest {

    // Grandparents gp1+gp2 → mum; mum+dad → kid; dad+second → half; kid+spouse → grandkid.
    private val family = graph(
        listOf("gp1", "gp2", "mum", "dad", "second", "kid", "half", "spouse", "grandkid"),
        union("u1", "gp1", "gp2", "mum"),
        union("u2", "mum", "dad", "kid"),
        union("u3", "dad", "second", "half"),
        union("u4", "kid", "spouse", "grandkid"),
    )

    @Test
    fun ancestorsKeepTheRootTheirAncestorsAndTheUnionsBetweenThem() {
        val slice = TreeSlicer.ancestors(family, "kid", listOf("mum", "dad", "gp1", "gp2"))

        assertEquals(listOf("gp1", "gp2", "mum", "dad", "kid"), slice.personIds)
        assertEquals(listOf("u1", "u2"), slice.unions.map { it.id })
        assertEquals(listOf("kid"), slice.unions.single { it.id == "u2" }.children.map { it.childId })
    }

    @Test
    fun descendantsShowPartnersButNotTheirOtherFamilies() {
        val slice = TreeSlicer.descendants(family, "mum", listOf("kid", "grandkid"))

        assertEquals(listOf("mum", "dad", "kid", "spouse", "grandkid"), slice.personIds)
        assertEquals(listOf("u2", "u4"), slice.unions.map { it.id })
        assertTrue("half" !in slice.personIds && "second" !in slice.personIds)
    }

    @Test
    fun idsMissingFromTheBundleAreIgnored() {
        val slice = TreeSlicer.ancestors(family, "kid", listOf("mum", "added-after-the-bundle"))

        assertEquals(listOf("mum", "kid"), slice.personIds)
        assertEquals(listOf("u2"), slice.unions.map { it.id })
    }

    @Test
    fun slicingKeepsTheServersRedactedUnionAsIs() {
        val redacted = family.copy(
            people = family.people.map { if (it.id == "kid") person("kid", given = "Private", living = true) else it },
            unions = family.unions.map { if (it.id == "u4") it.copy(startDate = null, endDate = null) else it },
        )
        val dated = redacted.copy(
            unions = redacted.unions.map {
                if (it.id == "u1") it.copy(startDate = GedcomDateDto("1950", "1950-01-01", "1950-12-31")) else it
            },
        )

        val slice = TreeSlicer.descendants(dated, "gp1", listOf("mum", "kid", "grandkid"))

        assertEquals(dated.unions.single { it.id == "u4" }, slice.unions.single { it.id == "u4" })
        assertEquals("1950", slice.unions.single { it.id == "u1" }.startDate?.displayText)
    }

    @Test
    fun familyListsParentsPartnersWithUnionTypeAndChildren() {
        assertEquals(
            Family(listOf("mum", "dad"), listOf(PartnerLink("spouse", "MARRIED")), listOf("grandkid")),
            familyOf(family, "kid"),
        )
        assertEquals(
            Family(emptyList(), listOf(PartnerLink("mum", "MARRIED"), PartnerLink("second", "MARRIED")), listOf("kid", "half")),
            familyOf(family, "dad"),
        )
    }

    @Test
    fun redactedPartnerKeepsTheUnionType() {
        // As the server sends it to a non-owner: placeholder partner, union type kept, dates removed.
        val redacted = graph(
            listOf("me", "hidden"),
            union("u", "me", "hidden", type = "DIVORCED"),
        ).let { g -> g.copy(people = g.people.map { if (it.id == "hidden") person("hidden", "Private", living = true) else it }) }

        assertEquals(listOf(PartnerLink("hidden", "DIVORCED")), familyOf(redacted, "me").partners)
    }
}
