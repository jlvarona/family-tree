package com.moxietech.family_tree.tree

import com.moxietech.family_tree.api.TreeGraphDto
import com.moxietech.family_tree.api.UnionDto

/** The people and unions one tree view draws, in the order they should be laid out. */
data class GraphSlice(
    val personIds: List<String>,
    val unions: List<UnionDto>,
)

/**
 * Cuts a view out of the whole-tree bundle (FT-004). The bundle is already
 * privacy-shaped by the server: hidden living people are placeholders and
 * unions with a hidden partner have no dates. Slicing only drops people and
 * links, never adds detail, so the union visibility policy carries through.
 *
 * Which people belong to an ancestor or descendant view comes from the
 * server's `…/ancestors` and `…/descendants` routes (the recursive CTEs).
 * Those are separate requests from the bundle, so an ID the bundle doesn't
 * know (added in between) is ignored rather than drawn without data.
 */
object TreeSlicer {

    fun full(graph: TreeGraphDto): GraphSlice = GraphSlice(graph.people.map { it.id }, graph.unions)

    /** The root, their ancestors, and the unions that make each ancestor a parent in the view. */
    fun ancestors(graph: TreeGraphDto, rootId: String, ancestorIds: Collection<String>): GraphSlice {
        val include = known(graph, ancestorIds + rootId)
        val unions = graph.unions.mapNotNull { union ->
            val partnerIn = union.partners().any { it in include }
            val children = union.children.filter { it.childId in include }
            if (partnerIn && children.isNotEmpty()) union.copy(children = children) else null
        }
        return GraphSlice(graph.people.map { it.id }.filter { it in include }, unions)
    }

    /**
     * The root, their descendants, and each one's unions with the partner shown,
     * so a child appears under both parents. A partner's other families are not
     * pulled in.
     */
    fun descendants(graph: TreeGraphDto, rootId: String, descendantIds: Collection<String>): GraphSlice {
        val line = known(graph, descendantIds + rootId)
        val unions = graph.unions.mapNotNull { union ->
            if (union.partners().none { it in line }) {
                null
            } else {
                union.copy(children = union.children.filter { it.childId in line })
            }
        }
        val known = graph.people.mapTo(HashSet()) { it.id }
        val include = line + unions.flatMap { it.partners() }.filter { it in known }
        return GraphSlice(graph.people.map { it.id }.filter { it in include }, unions)
    }

    private fun known(graph: TreeGraphDto, ids: Collection<String>): Set<String> {
        val wanted = ids.toSet()
        return graph.people.mapNotNullTo(HashSet()) { person -> person.id.takeIf { it in wanted } }
    }
}

internal fun UnionDto.partners(): List<String> = listOfNotNull(partner1Id, partner2Id)
