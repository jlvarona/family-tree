package com.moxietech.family_tree.state

import com.moxietech.family_tree.TREE_ID
import com.moxietech.family_tree.api.ChangeProposalDto
import com.moxietech.family_tree.api.ProposalReferenceDto
import com.moxietech.family_tree.graph
import com.moxietech.family_tree.union
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * FT-014 review fix: a reviewer must be able to tell which place, source, event or child
 * link a proposal names. Described from the bundle and the server's redacted references;
 * anything else stays [ProposalSubject.Unknown], never a raw ID.
 */
class ProposalContextTest {

    private val tree = graph(listOf("mum", "dad", "kid"), union("u1", "mum", "dad", "kid"))

    private fun proposal(operation: String, target: String?, vararg references: ProposalReferenceDto) = ChangeProposalDto(
        id = "p1", treeId = TREE_ID, operation = operation, targetId = target, status = "PENDING", reason = "OUTSIDE_BRANCH",
        proposerAccountId = "me", createdAt = "2026-11-22T00:00:00Z", fields = emptyList(), version = 1,
        references = references.toList(),
    )

    @Test
    fun placesAndSourcesAreDistinguishedByTheirNames() {
        val p = proposal(
            "UPDATE_EVENT", "e1",
            ProposalReferenceDto("lima", "PLACE", name = "Lima", detail = "Peru"),
            ProposalReferenceDto("cusco", "PLACE", name = "Cusco", detail = "Peru"),
            ProposalReferenceDto("census", "SOURCE", name = "Census 1940", detail = "CENSUS"),
        )
        val lima = ProposalContext.reference("lima", p, tree)
        val cusco = ProposalContext.reference("cusco", p, tree)
        assertEquals(ProposalSubject.OfPlace("Lima", "Peru"), lima)
        assertNotEquals(lima, cusco)
        assertEquals(ProposalSubject.OfSource("Census 1940", "CENSUS"), ProposalContext.reference("census", p, tree))
    }

    @Test
    fun anEventTargetNamesItsTypeDateAndPersonFromTheTree() {
        val p = proposal("DELETE_EVENT", "e1", ProposalReferenceDto("e1", "EVENT", eventType = "BIRTH", eventDate = "1915", personId = "kid"))
        val event = assertIs<ProposalSubject.OfEvent>(ProposalContext.target(p, tree))
        assertEquals("BIRTH", event.type)
        assertEquals("1915", event.date)
        assertEquals("kid", assertIs<ProposalSubject.OfPerson>(event.subject).person.id)
        // An event of a union names the union's partners.
        val ofUnion = proposal("DELETE_EVENT", "e2", ProposalReferenceDto("e2", "EVENT", eventType = "MARRIAGE", unionId = "u1"))
        val partners = assertIs<ProposalSubject.OfUnion>(assertIs<ProposalSubject.OfEvent>(ProposalContext.target(ofUnion, tree)).subject)
        assertEquals(listOf("mum", "dad"), partners.partners.map { it.id })
    }

    @Test
    fun aChildLinkTargetNamesTheChildAndTheUnion() {
        val link = assertIs<ProposalSubject.OfChildLink>(ProposalContext.target(proposal("DELETE_CHILD", "u1:kid"), tree))
        assertEquals("kid", link.child?.id)
        assertEquals(listOf("mum", "dad"), link.union?.partners?.map { it.id })
        // A new child link's target is its union.
        assertIs<ProposalSubject.OfUnion>(ProposalContext.target(proposal("ADD_CHILD", "u1"), tree))
    }

    @Test
    fun whatTheServerLeftOutStaysUnknown() {
        // A hidden event has no reference; another tree's or unknown IDs aren't guessed.
        assertEquals(ProposalSubject.Unknown, ProposalContext.target(proposal("DELETE_EVENT", "hidden-event"), tree))
        assertEquals(ProposalSubject.Unknown, ProposalContext.reference("some-place", proposal("CREATE_EVENT", null), tree))
        assertEquals(ProposalSubject.Unknown, ProposalContext.target(proposal("DELETE_CHILD", "malformed"), tree))
        assertEquals(null, ProposalContext.target(proposal("CREATE_UNION", null), tree))
        // An event whose person isn't in the tree is still named by type and date.
        val orphan = proposal("UPDATE_EVENT", "e3", ProposalReferenceDto("e3", "EVENT", eventType = "BIRTH", eventDate = "1900", personId = "nobody"))
        assertEquals(ProposalSubject.OfEvent("BIRTH", "1900", null), ProposalContext.target(orphan, tree))
    }
}
