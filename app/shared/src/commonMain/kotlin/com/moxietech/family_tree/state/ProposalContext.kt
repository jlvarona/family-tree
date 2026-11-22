package com.moxietech.family_tree.state

import com.moxietech.family_tree.api.ChangeProposalDto
import com.moxietech.family_tree.api.PersonDto
import com.moxietech.family_tree.api.ProposalReferenceDto
import com.moxietech.family_tree.api.TreeGraphDto
import com.moxietech.family_tree.navigation.Route

/**
 * FT-014: a record a change proposal names, described from data the client already holds
 * as the server shaped it: people and unions from the tree bundle (hidden people stay
 * placeholders there), places, sources and events from the proposal's own
 * [ChangeProposalDto.references] (the server leaves out an event the caller can't see).
 * Anything else is [Unknown]; raw IDs are never shown and nothing extra is fetched.
 */
sealed interface ProposalSubject {
    data class OfPerson(val person: PersonDto) : ProposalSubject

    data class OfUnion(val partners: List<PersonDto>) : ProposalSubject

    /** A child link: the child and the union, each null when the tree doesn't show it. */
    data class OfChildLink(val child: PersonDto?, val union: OfUnion?) : ProposalSubject

    /** An event: its type and date as typed, and whose it is (null when not in the tree). */
    data class OfEvent(val type: String, val date: String?, val subject: ProposalSubject?) : ProposalSubject

    data class OfPlace(val name: String, val hierarchy: String?) : ProposalSubject

    data class OfSource(val title: String, val type: String?) : ProposalSubject

    data object Unknown : ProposalSubject
}

object ProposalContext {

    /** What the proposal's target is (the record its path names), or null for creates. */
    fun target(proposal: ChangeProposalDto, graph: TreeGraphDto): ProposalSubject? {
        val target = proposal.targetId ?: return null
        return when (proposal.operation) {
            "UPDATE_CHILD", "DELETE_CHILD" -> {
                val parts = target.split(':')
                if (parts.size != 2) {
                    ProposalSubject.Unknown
                } else {
                    ProposalSubject.OfChildLink(person(parts[1], graph), union(parts[0], graph))
                }
            }
            else -> reference(target, proposal, graph)
        }
    }

    /** The record [id] names in [proposal] (a value or its target). */
    fun reference(id: String, proposal: ChangeProposalDto, graph: TreeGraphDto): ProposalSubject =
        person(id, graph)?.let(ProposalSubject::OfPerson)
            ?: union(id, graph)
            ?: proposal.references.firstOrNull { Route.sameId(it.id, id) }?.let { fromReference(it, graph) }
            ?: ProposalSubject.Unknown

    private fun fromReference(reference: ProposalReferenceDto, graph: TreeGraphDto): ProposalSubject? = when (reference.kind) {
        "PLACE" -> reference.name?.let { ProposalSubject.OfPlace(it, reference.detail) }
        "SOURCE" -> reference.name?.let { ProposalSubject.OfSource(it, reference.detail) }
        "EVENT" -> reference.eventType?.let { type ->
            val subject = reference.personId?.let { person(it, graph) }?.let(ProposalSubject::OfPerson)
                ?: reference.unionId?.let { union(it, graph) }
            ProposalSubject.OfEvent(type, reference.eventDate, subject)
        }
        else -> null
    }

    private fun person(id: String, graph: TreeGraphDto): PersonDto? = graph.people.firstOrNull { Route.sameId(it.id, id) }

    private fun union(id: String, graph: TreeGraphDto): ProposalSubject.OfUnion? {
        val union = graph.unions.firstOrNull { Route.sameId(it.id, id) } ?: return null
        return ProposalSubject.OfUnion(listOfNotNull(union.partner1Id, union.partner2Id).mapNotNull { person(it, graph) })
    }
}
