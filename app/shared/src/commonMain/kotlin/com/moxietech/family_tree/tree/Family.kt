package com.moxietech.family_tree.tree

import com.moxietech.family_tree.api.TreeGraphDto

/**
 * A partner and the type of their union. The type is shown even when the
 * partner is a redacted placeholder: the server keeps a union's type and links
 * visible and hides only its dates (FT-066).
 */
data class PartnerLink(val personId: String, val unionType: String)

/** A person's immediate family as read from the bundle's unions, for the side panel. */
data class Family(val parents: List<String>, val partners: List<PartnerLink>, val children: List<String>)

fun familyOf(graph: TreeGraphDto, personId: String): Family {
    val known = graph.people.mapTo(HashSet()) { it.id }
    val parents = graph.unions
        .filter { union -> union.children.any { it.childId == personId } }
        .flatMap { it.partners() }
    val own = graph.unions.filter { personId in it.partners() }
    val partners = own.flatMap { union ->
        union.partners().filter { it != personId && it in known }.map { PartnerLink(it, union.type) }
    }.distinct()
    val children = own.flatMap { union -> union.children.map { it.childId } }
    fun List<String>.clean() = filter { it in known && it != personId }.distinct()
    return Family(parents.clean(), partners, children.clean())
}
