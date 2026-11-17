package com.moxietech.family_tree.permissions

import com.moxietech.family_tree.domain.Branch
import com.moxietech.family_tree.domain.BranchDirection
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.Union
import com.moxietech.family_tree.domain.UnionChild
import com.moxietech.family_tree.domain.UnionId

/**
 * FT-010: what a [Branch] covers, as the reference rules shared by server and clients.
 * The server resolves grants with a recursive CTE (`GrantScopeRepository`); its
 * integration tests check that the CTE and [resolve] agree on the same graph.
 *
 * - The anchor is always included (when it exists in the graph).
 * - Parents are both partners of every union a person is a child of; children are the
 *   children of every union a person is a partner in. Every child relation counts
 *   (birth, adopted, step, foster), as in the ancestor and descendant views.
 * - [BranchDirection.ANCESTORS] / [BranchDirection.DESCENDANTS]: people at most
 *   [Branch.depth] generations up / down from the anchor along some path.
 * - [BranchDirection.SIDE]: the anchor and its ancestors up to [Branch.depth]
 *   generations, then every descendant of those people with no depth limit. Depth caps
 *   only the ascent (decisions table, "Side branches").
 * - [BranchDirection.WHOLE_TREE]: every person in the tree; depth is ignored.
 * - Null depth is unlimited. A cycle in the data (someone recorded as their own
 *   ancestor) ends the walk instead of looping: each person is visited once.
 * - [Branch.includePartners] then adds the direct partners (both sides of each union)
 *   of every person found above, and nothing beyond them: no partner's parents, other
 *   children, other partners or further lineage.
 */
object BranchResolution {

    /**
     * The people of [people] that [branch] covers, over the relationships in [unions]
     * and [children] (one tree's graph). Links naming someone outside [people] are
     * ignored, so a partial or foreign graph can never widen the result.
     */
    fun resolve(
        branch: Branch,
        people: Collection<PersonId>,
        unions: Collection<Union>,
        children: Collection<UnionChild>,
    ): Set<PersonId> {
        val known = people.toSet()
        if (branch.anchorPersonId !in known) return emptySet()
        if (branch.direction == BranchDirection.WHOLE_TREE) return known

        val graph = Graph(known, unions, children)
        val anchor = listOf(branch.anchorPersonId)
        val blood = when (branch.direction) {
            BranchDirection.ANCESTORS -> walk(anchor, branch.depth, graph::parentsOf)
            BranchDirection.DESCENDANTS -> walk(anchor, branch.depth, graph::childrenOf)
            BranchDirection.SIDE -> walk(walk(anchor, branch.depth, graph::parentsOf), null, graph::childrenOf)
            BranchDirection.WHOLE_TREE -> error("handled above")
        }
        if (!branch.includePartners) return blood
        return blood + blood.flatMap(graph::partnersOf)
    }

    /**
     * Breadth first from [starts]: a person's generation is the shortest path, so
     * [limit] N keeps exactly those reachable within N steps, and each person is
     * expanded once, so the work is linear in the graph whatever the limit or cycles.
     */
    private fun walk(starts: Collection<PersonId>, limit: Int?, next: (PersonId) -> List<PersonId>): Set<PersonId> {
        val seen = LinkedHashSet(starts)
        var frontier = starts.toList()
        var generation = 0
        while (frontier.isNotEmpty() && (limit == null || generation < limit)) {
            frontier = frontier.flatMap(next).filter { seen.add(it) }
            generation++
        }
        return seen
    }

    /** One tree's links, indexed once by child, by union and by partner. */
    private class Graph(known: Set<PersonId>, unions: Collection<Union>, children: Collection<UnionChild>) {
        private val partnersByUnion: Map<UnionId, List<PersonId>> =
            unions.associate { union -> union.id to listOfNotNull(union.partner1Id, union.partner2Id).filter { it in known } }
        private val unionsByPartner: Map<PersonId, List<UnionId>> = partnersByUnion.entries
            .flatMap { (union, partners) -> partners.map { it to union } }
            .groupBy({ it.first }, { it.second })
        private val links = children.filter { it.childId in known && it.unionId in partnersByUnion }
        private val unionsByChild: Map<PersonId, List<UnionId>> = links.groupBy({ it.childId }, { it.unionId })
        private val childrenByUnion: Map<UnionId, List<PersonId>> = links.groupBy({ it.unionId }, { it.childId })

        fun parentsOf(person: PersonId): List<PersonId> =
            unionsByChild[person].orEmpty().flatMap { partnersByUnion[it].orEmpty() }

        fun childrenOf(person: PersonId): List<PersonId> =
            unionsByPartner[person].orEmpty().flatMap { childrenByUnion[it].orEmpty() }

        fun partnersOf(person: PersonId): List<PersonId> =
            unionsByPartner[person].orEmpty().flatMap { partnersByUnion[it].orEmpty() }
    }
}
