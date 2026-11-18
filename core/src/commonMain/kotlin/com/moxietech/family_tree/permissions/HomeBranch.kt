package com.moxietech.family_tree.permissions

import com.moxietech.family_tree.domain.Branch
import com.moxietech.family_tree.domain.BranchDirection
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.Union
import com.moxietech.family_tree.domain.UnionChild

/**
 * FT-011: a member's home branch, derived from the person their membership is linked
 * to (concept-architecture.md "Edit approval": "their parents and the parents'
 * descendants (siblings, nieces/nephews, own children and partners)").
 *
 * It is exactly a [BranchDirection.SIDE] branch of depth 1 with partners:
 * - the linked person and their parents (every union the person is a child of, every
 *   child relation, as in [BranchResolution]);
 * - every descendant of the parents and of the person: siblings and half-siblings,
 *   nieces and nephews, own children and grandchildren, without depth limit;
 * - the direct partners of all of those, and nobody beyond them.
 *
 * A person with no recorded parents has a home branch of themselves, their descendants
 * and those people's partners. It is never widened to the whole tree or to anyone
 * further up: grandparents' lines are reachable only through an explicit grant.
 * A member who is not linked to a person has no home branch.
 *
 * Since FT-012 a member who holds an editing grant edits their home branch live
 * ([EditAuthority]).
 */
object HomeBranch {

    /** The branch whose resolution is the home branch of a member linked to [personId]. */
    fun of(personId: PersonId): Branch = Branch(
        anchorPersonId = personId,
        direction = BranchDirection.SIDE,
        depth = 1,
        includePartners = true,
    )

    /** The people in the home branch of [personId] over one tree's graph; empty if [personId] isn't in [people]. */
    fun resolve(
        personId: PersonId,
        people: Collection<PersonId>,
        unions: Collection<Union>,
        children: Collection<UnionChild>,
    ): Set<PersonId> = BranchResolution.resolve(of(personId), people, unions, children)
}
