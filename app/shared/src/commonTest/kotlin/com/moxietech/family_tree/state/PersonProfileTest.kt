package com.moxietech.family_tree.state

import com.moxietech.family_tree.TREE_ID
import com.moxietech.family_tree.api.AccountDto
import com.moxietech.family_tree.api.GedcomDateDto
import com.moxietech.family_tree.api.TreeDto
import com.moxietech.family_tree.api.TreeGraphDto
import com.moxietech.family_tree.api.UnionChildDto
import com.moxietech.family_tree.event
import com.moxietech.family_tree.person
import com.moxietech.family_tree.placeholder
import com.moxietech.family_tree.union
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersonProfileTest {

    private val tree = TreeDto(TREE_ID, "Romero", "owner", 0)
    private val owner = Viewer(tree, AccountDto("owner", "owner@example.com", "Owner", "en"))
    private val member = Viewer(tree, AccountDto("member", "member@example.com", "Member", "en"))

    private val antonio = person("antonio", "Antonio", living = false, paternal = "Romero")
        .copy(surnameMaternal = "Vidal", bio = "Worked in the workshop.\r\n\r\n\r\nLoved the sea.")
    private val carmen = person("carmen", "Carmen", living = true, paternal = "Díaz")

    /** As the server sends a living person no grant of the caller covers: a placeholder (FT-066, FT-013). */
    private val carmenRedacted = placeholder("carmen")

    @Test
    fun ownerSeesTheFormattedNameNamePartsAndCleanBio() {
        val header = PersonProfile.header(antonio, owner)

        assertEquals("Antonio Romero Vidal", header.name)
        assertEquals(PersonStatus.DECEASED, header.status)
        assertEquals(Triple("Antonio", "Romero", "Vidal"), Triple(header.givenNames, header.surnamePaternal, header.surnameMaternal))
        assertEquals("AR", header.initials)
        assertEquals("Worked in the workshop.\n\nLoved the sea.", header.bio)
        assertEquals(listOf("Worked in the workshop.", "Loved the sea."), PersonProfile.paragraphs(header.bio!!))
    }

    @Test
    fun hiddenLivingPersonShowsOnlyThePlaceholderEvenIfDetailsArrive() {
        // A placeholder that wrongly carried details must still not be presented.
        val leaked = carmen.copy(bio = "secret story", redacted = true)

        val header = PersonProfile.header(leaked, member)

        assertEquals(PersonHeader("carmen", null, PersonStatus.PRIVATE, null, null, null, null, null), header)
        val timeline = PersonProfile.timeline(leaked, listOf(event("e1", "carmen", "BIRTH", "1960", "1960-01-01")), member)
        assertEquals(Timeline.Private, timeline)
        assertEquals(LifeFacts(null, null, null), PersonProfile.lifeFacts(timeline))
    }

    @Test
    fun memberSeesDeceasedPeopleInFull() {
        assertEquals("Antonio Romero Vidal", PersonProfile.header(antonio, member).name)
        assertEquals(PersonStatus.LIVING, PersonProfile.header(carmen, owner).status)
    }

    @Test
    fun theServersProjectionDecidesNotTreeOwnershipOrTheName() {
        // FT-013: a living person a member's grant covers arrives in full and is shown in full.
        val header = PersonProfile.header(carmen, member)
        assertEquals("Carmen Díaz", header.name)
        assertEquals(PersonStatus.LIVING, header.status)
        // A placeholder stays one, even for the owner.
        assertEquals(PersonStatus.PRIVATE, PersonProfile.header(carmenRedacted, owner).status)
        assertNull(owner.displayName(carmenRedacted))
        // Someone really named "Private" isn't mistaken for a placeholder.
        val named = person("p", "Private", living = true)
        assertEquals("Private", member.displayName(named))
        assertEquals(PersonStatus.LIVING, PersonProfile.header(named, member).status)
    }

    @Test
    fun unionDatesShowWhenBothPartnersArriveInFull() {
        val graph = TreeGraphDto(
            TREE_ID,
            listOf(antonio, carmen),
            listOf(union("u1", "antonio", "carmen").copy(startDate = GedcomDateDto("1955", null, null))),
        )
        assertEquals("1955", PersonProfile.family(graph, antonio, member).partners.single().unionStart)
    }

    @Test
    fun bioIsPlainTextWithControlAndBidiOverrideCharactersRemoved() {
        val bio = "  <script>alert(1)</script>\u0007 [link](http://x) ‮evil‬⁦ ok⁩\u0000  "

        val cleaned = PersonProfile.cleanText(bio)

        // Markup is kept literally (it is never interpreted), invisible controls are gone.
        assertEquals("<script>alert(1)</script> [link](http://x) evil ok", cleaned)
        assertNull(PersonProfile.cleanText(" \n\t‮ "))
        assertNull(PersonProfile.cleanText(null))
        assertEquals("a\tb\nc", PersonProfile.cleanText("a\tb\rc"))
    }

    @Test
    fun familyListsParentsPartnersAndChildrenWithTheirLinks() {
        val graph = TreeGraphDto(
            TREE_ID,
            listOf(
                person("gp1", "Abuelo"), person("gp2", "Abuela"), antonio, person("carmen", "Carmen", paternal = "Díaz"),
                person("kid", "Javier"), person("step", "Lucía"),
            ),
            listOf(
                union("u0", "gp1", "gp2").copy(children = listOf(UnionChildDto("antonio", "ADOPTED"))),
                union("u1", "antonio", "carmen", type = "MARRIED").copy(
                    startDate = GedcomDateDto("1955", "1955-01-01", "1955-12-31"),
                    endDate = GedcomDateDto("2004", "2004-01-01", "2004-12-31"),
                    children = listOf(UnionChildDto("kid", "BIRTH"), UnionChildDto("step", "STEP"), UnionChildDto("ghost", "BIRTH")),
                ),
                union("u2", "antonio", null, "kid"),
            ),
        )

        val family = PersonProfile.family(graph, antonio, owner)

        assertEquals(listOf("gp1", "gp2"), family.parents.map { it.personId })
        assertTrue(family.parents.all { it.relation == "ADOPTED" })
        val partner = family.partners.single()
        assertEquals(RelativeLink("carmen", "Carmen Díaz", PersonStatus.DECEASED, "CD", "MARRIED", "1955", "2004"), partner)
        // Unknown IDs are skipped and a child of two unions is listed once.
        assertEquals(listOf("kid" to "BIRTH", "step" to "STEP"), family.children.map { it.personId to it.relation })
    }

    @Test
    fun privatePartnerKeepsTheUnionTypeButNeverItsDates() {
        // Even if dates arrived on the union, a hidden partner's union dates aren't shown.
        val graph = TreeGraphDto(
            TREE_ID,
            listOf(antonio, carmenRedacted),
            listOf(union("u1", "antonio", "carmen", type = "DIVORCED").copy(startDate = GedcomDateDto("1955", null, null))),
        )

        val partner = PersonProfile.family(graph, antonio, member).partners.single()

        assertEquals(RelativeLink("carmen", null, PersonStatus.PRIVATE, null, "DIVORCED", null, null), partner)
        // Viewed from the hidden person's own page, the deceased partner is named; dates stay hidden.
        val fromHidden = PersonProfile.family(graph, carmenRedacted, member).partners.single()
        assertEquals("Antonio Romero Vidal", fromHidden.name)
        assertEquals("DIVORCED", fromHidden.unionType)
        assertNull(fromHidden.unionStart)
    }

    @Test
    fun familyFromAnotherTreeIsIgnored() {
        val graph = TreeGraphDto("tree-2", listOf(antonio, carmen), listOf(union("u1", "antonio", "carmen")))

        assertTrue(PersonProfile.family(graph, antonio, owner).isEmpty)
    }

    @Test
    fun timelineIsOrderedAndKeepsOnlyThisPersonsEventsInThisTree() {
        val events = listOf(
            event("death", "antonio", "DEATH", "2004", "2004-03-01"),
            event("undated", "antonio", "OCCUPATION", notes = " Carpenter "),
            event("moved", "antonio", "RESIDENCE", "1962", "1962-01-01", place = "Madrid"),
            event("married", "antonio", "MARRIAGE", "abt 1955", "1954-01-01"),
            event("ancient", "antonio", "OTHER", "500 BC", "-0499-01-01"),
            event("burial", "antonio", "BURIAL", "2004", "2004-03-05"),
            event("birth", "antonio", "BIRTH", "1930", "1930-05-01", place = "Toledo"),
            event("other-person", "carmen", "BIRTH", "1933", "1933-01-01"),
            event("other-tree", "antonio", "BIRTH", "1931", "1931-01-01", treeId = "tree-2"),
        )

        val timeline = PersonProfile.timeline(antonio, events, owner) as Timeline.Entries

        assertEquals(
            listOf("birth", "ancient", "married", "moved", "undated", "death", "burial"),
            timeline.entries.map { it.eventId },
        )
        assertEquals("Carpenter", timeline.entries.single { it.eventId == "undated" }.notes)
        assertNull(timeline.entries.single { it.eventId == "undated" }.date)
        assertEquals(LifeFacts("1930", "2004", "Toledo"), PersonProfile.lifeFacts(timeline))
    }

    @Test
    fun openEndedDatesSortByTheirAvailableBoundAndKeepTheirText() {
        val events = listOf(
            event("undated", "antonio", "RESIDENCE"),
            event("text-only", "antonio", "RESIDENCE", "sometime in the war"),
            event("after", "antonio", "RESIDENCE", "aft 1970", "1970-01-01", latestIso = null),
            event("before", "antonio", "OCCUPATION", "bef 1950", null, latestIso = "1949-12-31"),
            event("exact", "antonio", "RESIDENCE", "1960", "1960-01-01"),
        )

        val entries = (PersonProfile.timeline(antonio, events, owner) as Timeline.Entries).entries

        assertEquals(listOf("before", "exact", "after", "text-only", "undated"), entries.map { it.eventId })
        assertEquals("bef 1950", entries.first().date)
    }

    @Test
    fun familyIgnoresNestedPeopleAndUnionsFromAnotherTree() {
        // Right envelope, mixed contents: foreign records must not surface names, types or dates.
        val graph = TreeGraphDto(
            TREE_ID,
            listOf(antonio, person("carmen", "Carmen"), person("intruder", "Intruso").copy(treeId = "tree-2")),
            listOf(
                union("u1", "antonio", "carmen", type = "MARRIED"),
                union("u2", "antonio", "intruder", type = "DIVORCED"),
                union("foreign", "antonio", "carmen", "kid", type = "PARTNERS").copy(
                    treeId = "tree-2",
                    startDate = GedcomDateDto("1999", "1999-01-01", "1999-12-31"),
                ),
                union("u3", "gp", null, "antonio").copy(treeId = "tree-2"),
            ),
        )

        val family = PersonProfile.family(graph, antonio, owner)

        assertEquals(listOf("carmen" to "MARRIED"), family.partners.map { it.personId to it.unionType })
        assertNull(family.partners.single().unionStart)
        assertTrue(family.parents.isEmpty())
        assertTrue(family.children.isEmpty())
    }

    @Test
    fun visiblePersonWithNoEventsHasAnEmptyTimeline() {
        assertEquals(Timeline.Entries(emptyList()), PersonProfile.timeline(antonio, emptyList(), member))
    }
}
