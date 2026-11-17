package com.moxietech.family_tree.state

import com.moxietech.family_tree.TREE_ID
import com.moxietech.family_tree.api.AccountDto
import com.moxietech.family_tree.api.CitationDto
import com.moxietech.family_tree.api.GedcomDateDto
import com.moxietech.family_tree.api.SourceDto
import com.moxietech.family_tree.api.TreeDto
import com.moxietech.family_tree.api.TreeGraphDto
import com.moxietech.family_tree.client.ApiFailure
import com.moxietech.family_tree.domain.CitationTarget
import com.moxietech.family_tree.domain.EventId
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.UnionId
import com.moxietech.family_tree.event
import com.moxietech.family_tree.person
import com.moxietech.family_tree.union
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersonSourcesTest {

    private val tree = TreeDto(TREE_ID, "Romero", "owner", 0)
    private val owner = Viewer(tree, AccountDto("owner", "owner@example.com", "Owner", "en"))
    private val member = Viewer(tree, AccountDto("member", "member@example.com", "Member", "en"))

    private val antonio = person("antonio", "Antonio", paternal = "Romero")
    private val carmen = person("carmen", "Carmen", paternal = "Díaz")
    private val javier = person("javier", "Javier", living = true, paternal = "Romero")

    private val birth = event("birth", "antonio", "BIRTH", "1930", "1930-05-01")
    private val death = event("death", "antonio", "DEATH", "2004", "2004-03-01")
    private val moved = event("moved", "antonio", "RESIDENCE", "1962", "1962-01-01")

    private val graph = TreeGraphDto(
        TREE_ID,
        listOf(antonio, carmen, javier),
        listOf(union("u1", "antonio", "carmen", "javier", type = "MARRIED"), union("u2", "antonio", "javier", type = "PARTNERS")),
    )

    private val registry = SourceDto(
        id = "s1", treeId = TREE_ID, title = "Birth certificate", type = "VITAL_RECORD", author = null,
        publisher = null, repository = "Civil Registry,\n Toledo", url = "javascript:alert(1)",
        date = GedcomDateDto("1930", "1930-01-01", "1930-12-31"), version = 1,
    )
    private val parish = SourceDto("s2", TREE_ID, "Parish book", "CHURCH_RECORD", null, null, null, null, null, 1)

    /** The API's `targetType` values, to build both the `:core` targets and the DTOs. */
    private enum class CitationKind { PERSON, EVENT, UNION }

    private fun cite(id: String, kind: CitationKind, target: String, source: String = "s1", confidence: Int = 3, page: String? = null, note: String? = null, mediaId: String? = null) =
        CitationDto(id, source, kind.name, target, page, confidence, note, mediaId)

    private fun target(kind: CitationKind, id: String): CitationTarget = when (kind) {
        CitationKind.PERSON -> CitationTarget.OnPerson(PersonId(id))
        CitationKind.EVENT -> CitationTarget.OnEvent(EventId(id))
        CitationKind.UNION -> CitationTarget.OnUnion(UnionId(id))
    }

    private fun ready(vararg citations: CitationDto) = Loadable.Ready(citations.toList())

    /** Every fact of Antonio's page answered: [citations] by target, the rest empty. */
    private fun allLoaded(citations: Map<CitationTarget, Loadable<List<CitationDto>>>): Map<CitationTarget, Loadable<List<CitationDto>>> =
        listOf(
            target(CitationKind.PERSON, "antonio"),
            target(CitationKind.EVENT, "birth"),
            target(CitationKind.EVENT, "moved"),
            target(CitationKind.EVENT, "death"),
            target(CitationKind.UNION, "u1"),
            target(CitationKind.UNION, "u2"),
        ).associateWith { Loadable.Ready(emptyList<CitationDto>()) } + citations

    @Test
    fun ownerSeesEveryCitationWithItsSourceInPageOrder() {
        val citations = allLoaded(
            mapOf(
                target(CitationKind.UNION, "u1") to ready(cite("c-union", CitationKind.UNION, "u1", source = "s2", confidence = 2)),
                target(CitationKind.EVENT, "birth") to ready(
                    cite("c-birth", CitationKind.EVENT, "birth", page = " Vol. 3,\r\n p. 12 ", note = "‮Copied‬ by <b>Carmen</b>", mediaId = "scan-1"),
                    // Another target's citation in this list is dropped.
                    cite("c-stray", CitationKind.EVENT, "death"),
                ),
                target(CitationKind.PERSON, "antonio") to ready(cite("c-person", CitationKind.PERSON, "antonio", source = "gone", confidence = 9)),
            ),
        )

        val view = PersonSources.view(antonio, owner, Loadable.Ready(listOf(death, moved, birth)), Loadable.Ready(graph), Loadable.Ready(listOf(registry, parish)), citations)

        assertIs<SourcesView.Listed>(view)
        assertEquals(listOf("c-person", "c-birth", "c-union"), view.entries.map { it.citationId })
        assertEquals(setOf("birth", "moved", "death"), view.loadedEvents)
        assertTrue(!view.loading && view.failure == null && !view.incomplete)

        val person = view.entries[0]
        assertNull(person.sourceTitle, "a source that isn't in the tree's list is shown as unknown")
        assertEquals(PersonSources.MAX_CONFIDENCE, person.confidence)
        assertIs<CitedFact.Profile>(person.fact)

        val birthCitation = view.entries[1]
        assertEquals("Birth certificate", birthCitation.sourceTitle)
        assertEquals("VITAL_RECORD", birthCitation.sourceType)
        assertEquals(listOf("Civil Registry, Toledo", "1930"), birthCitation.sourceDetails)
        // Shown as inert text only; the value is kept as written, never opened.
        assertEquals("javascript:alert(1)", birthCitation.sourceUrl)
        assertEquals("Vol. 3, p. 12", birthCitation.page)
        assertEquals("Copied by <b>Carmen</b>", birthCitation.note)
        assertEquals("scan-1", birthCitation.mediaId)
        assertEquals(CitedFact.Event(CitationTarget.OnEvent(EventId("birth")), "BIRTH", "1930"), birthCitation.fact)
        assertEquals(listOf(birthCitation), view.forEvent("birth"))
        assertTrue(view.forEvent("death").isEmpty(), "the stray citation isn't attached to death either")

        val unionCitation = view.entries[2]
        assertEquals("Parish book", unionCitation.sourceTitle)
        assertEquals(CitedFact.Union(CitationTarget.OnUnion(UnionId("u1")), "MARRIED", "Carmen Díaz"), unionCitation.fact)
    }

    @Test
    fun aPageWithNoCitationsIsEmptyOnlyOnceEverythingHasAnswered() {
        val events = Loadable.Ready(listOf(birth))
        val loaded = PersonSources.view(antonio, owner, events, Loadable.Ready(graph), Loadable.Ready(listOf(registry)), allLoaded(emptyMap()))
        assertIs<SourcesView.Listed>(loaded)
        assertTrue(loaded.isEmpty)
        assertEquals(setOf("birth"), loaded.loadedEvents, "the timeline can say 'No source yet'")

        // The u2 union's citations haven't answered yet: not "no sources".
        val pending = PersonSources.view(
            antonio, owner, events, Loadable.Ready(graph), Loadable.Ready(listOf(registry)),
            allLoaded(emptyMap()) - target(CitationKind.UNION, "u2"),
        ) as SourcesView.Listed
        assertTrue(pending.loading && !pending.isEmpty)

        // Citations arrived but the source list hasn't: nothing is listed under "Unknown source" meanwhile.
        val noSources = PersonSources.view(
            antonio, owner, events, Loadable.Ready(graph), Loadable.Loading,
            allLoaded(mapOf(target(CitationKind.PERSON, "antonio") to ready(cite("c1", CitationKind.PERSON, "antonio")))),
        ) as SourcesView.Listed
        assertTrue(noSources.entries.isEmpty() && noSources.loading && noSources.loadedEvents.isEmpty())
    }

    @Test
    fun hiddenLivingPersonShowsNoCitationsEvenIfSomeArrive() {
        val citations = mapOf(target(CitationKind.PERSON, "javier") to ready(cite("c1", CitationKind.PERSON, "javier", note = "secret")))

        assertEquals(SourcesView.Private, PersonSources.view(javier, member, Loadable.Ready(emptyList()), Loadable.Ready(graph), Loadable.Ready(listOf(registry)), citations))
        assertTrue(PersonSources.citedFacts(javier, emptyList(), graph, member).isEmpty(), "nothing to request")
        // The owner sees the same person's citations.
        val forOwner = PersonSources.view(javier, owner, Loadable.Ready(emptyList()), Loadable.Ready(graph), Loadable.Ready(listOf(registry)), citations)
        assertEquals(listOf("c1"), (forOwner as SourcesView.Listed).entries.map { it.citationId })
    }

    @Test
    fun memberSeesADeceasedPersonsCitationsButNotThoseOfAUnionWithAHiddenPartner() {
        // As the server sends it to a non-owner: Javier is a placeholder (FT-066).
        val redacted = graph.copy(people = listOf(antonio, carmen, person("javier", "Private", living = true)))
        val citations = allLoaded(
            mapOf(
                target(CitationKind.UNION, "u1") to ready(cite("c-u1", CitationKind.UNION, "u1")),
                // Should the server ever answer for the hidden partner's union, it still isn't shown.
                target(CitationKind.UNION, "u2") to ready(cite("c-u2", CitationKind.UNION, "u2", note = "about Javier")),
                target(CitationKind.EVENT, "birth") to ready(cite("c-birth", CitationKind.EVENT, "birth")),
            ),
        )

        val view = PersonSources.view(antonio, member, Loadable.Ready(listOf(birth)), Loadable.Ready(redacted), Loadable.Ready(listOf(registry)), citations)

        assertEquals(listOf("c-birth", "c-u1"), (view as SourcesView.Listed).entries.map { it.citationId })
        assertEquals(
            listOf(target(CitationKind.PERSON, "antonio"), target(CitationKind.EVENT, "birth"), target(CitationKind.UNION, "u1")),
            PersonSources.citedFacts(antonio, listOf(birth), redacted, member).map { it.target },
            "the hidden partner's union isn't requested",
        )
        // The family section keeps that union's type, as before.
        val partner = PersonProfile.family(redacted, antonio, member).partners.single { it.personId == "javier" }
        assertEquals("PARTNERS" to PersonStatus.PRIVATE, partner.unionType to partner.status)
    }

    @Test
    fun sectionFailuresAndMissingSectionsStayInTheSourcesSection() {
        val citations = allLoaded(
            mapOf(
                target(CitationKind.PERSON, "antonio") to ready(cite("c1", CitationKind.PERSON, "antonio")),
                target(CitationKind.UNION, "u1") to Loadable.Failed(ApiFailure.UNAVAILABLE),
            ),
        )

        val view = PersonSources.view(
            antonio, owner, Loadable.Failed(ApiFailure.SERVER), Loadable.Ready(graph), Loadable.Ready(listOf(registry)), citations,
        ) as SourcesView.Listed

        assertEquals(listOf("c1"), view.entries.map { it.citationId }, "what loaded is still listed")
        assertEquals(ApiFailure.UNAVAILABLE, view.failure)
        assertTrue(view.incomplete, "the failed timeline's events can't be listed yet")
        assertTrue(view.loadedEvents.isEmpty())

        val sourcesFailed = PersonSources.view(antonio, owner, Loadable.Ready(emptyList()), Loadable.Ready(graph), Loadable.Failed(ApiFailure.FORBIDDEN), allLoaded(emptyMap()))
        assertEquals(ApiFailure.FORBIDDEN, (sourcesFailed as SourcesView.Listed).failure)
    }

    @Test
    fun recordsFromAnotherTreeAreIgnored() {
        val foreignGraph = graph.copy(treeId = "tree-2")
        assertEquals(
            listOf(target(CitationKind.PERSON, "antonio")),
            PersonSources.citedFacts(antonio, listOf(event("x", "antonio", "BIRTH", treeId = "tree-2")), foreignGraph, owner).map { it.target },
        )

        val foreignSource = registry.copy(treeId = "tree-2")
        val view = PersonSources.view(
            antonio, owner, Loadable.Ready(emptyList()), Loadable.Ready(foreignGraph), Loadable.Ready(listOf(foreignSource)),
            mapOf(target(CitationKind.PERSON, "antonio") to ready(cite("c1", CitationKind.PERSON, "antonio"))),
        ) as SourcesView.Listed
        assertNull(view.entries.single().sourceTitle, "another tree's source doesn't label this citation")
    }
}
