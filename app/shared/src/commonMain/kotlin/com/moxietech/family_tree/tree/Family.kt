package com.moxietech.family_tree.tree

import com.moxietech.family_tree.api.TreeGraphDto

/** A person's immediate family as read from the bundle's unions, for the side panel. */
data class Family(val parents: List<String>, val partners: List<String>, val children: List<String>)

fun familyOf(graph: TreeGraphDto, personId: String): Family {
    val known = graph.people.mapTo(HashSet()) { it.id }
    val parents = graph.unions
        .filter { union -> union.children.any { it.childId == personId } }
        .flatMap { it.partners() }
    val own = graph.unions.filter { personId in it.partners() }
    val partners = own.flatMap { it.partners() }.filter { it != personId }
    val children = own.flatMap { union -> union.children.map { it.childId } }
    fun List<String>.clean() = filter { it in known && it != personId }.distinct()
    return Family(parents.clean(), partners.clean(), children.clean())
}
