package com.moxietech.family_tree.permissions

import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.Person
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.Tree

/**
 * Living-person privacy (AGENTS.md): whose details one caller may see. Callers have
 * already passed [TreePermissions.canRead] for the tree (an ACTIVE membership).
 *
 * FT-013 rule (decisions table "Grant-based living-person redaction (FT-013)"):
 * - Deceased people are visible to every active member, as before.
 * - The tree owner sees everyone.
 * - Anyone else sees a living person only when one of their explicit grants with an
 *   effective role ([GrantRules.effectiveRole]) covers that person. Every effective role
 *   counts, VIEWER included; a whole-tree ADMIN covers everyone. A stored ADMIN grant on
 *   a limited branch carries nothing (FT-012 fails closed), so it reveals no one.
 * - The member's derived home branch ([HomeBranch]) is a write rule only: it does not
 *   reveal anyone by itself.
 *
 * Only the server applies this rule to data. Clients render the server's projection
 * (`PersonDto.redacted`) instead of recomputing it, because a client doesn't know the
 * caller's resolved grant scopes.
 */
class PersonVisibility private constructor(
    /** The tree owner: sees everyone. */
    val isOwner: Boolean,
    private val covered: Set<PersonId>,
) {
    fun canSeeDetails(person: Person): Boolean = canSeeDetails(person.id, person.isLiving)

    fun canSeeDetails(personId: PersonId, isLiving: Boolean): Boolean = !isLiving || isOwner || personId in covered

    /**
     * FT-013: among the [living] people, those [after] (this caller's visibility
     * recomputed after a graph change) reveals but this visibility hid. A writer's graph
     * change must not do that ([EditAuthority.gainedBy] is the matching rule for edits),
     * except for [exempt] people (the writer's own unattached creations, whose details
     * they entered themselves).
     */
    fun revealedBy(after: PersonVisibility, living: Collection<PersonId>, exempt: Set<PersonId> = emptySet()): Set<PersonId> =
        living.filterTo(mutableSetOf()) { it !in exempt && !canSeeDetails(it, true) && after.canSeeDetails(it, true) }

    /** Who both this and [other] can see (a write's response after a graph change: before and after). */
    fun both(other: PersonVisibility): PersonVisibility =
        PersonVisibility(isOwner = isOwner && other.isOwner, covered = covered intersect other.covered)

    companion object {
        /** The tree owner. */
        fun owner(): PersonVisibility = PersonVisibility(isOwner = true, covered = emptySet())

        /**
         * A non-owner member: [grants] are every grant the member holds, with its stored
         * role and branch and the people its branch resolves to. Grants without an
         * effective role are ignored.
         */
        fun member(grants: List<EditAuthority.ScopedGrant>): PersonVisibility = PersonVisibility(
            isOwner = false,
            covered = grants
                .filter { GrantRules.effectiveRole(it.role, it.branch) != null }
                .flatMapTo(mutableSetOf()) { it.scope },
        )

        /** [owner] for [tree]'s owner, otherwise [member] with [grants] (ignored for the owner). */
        fun of(tree: Tree, caller: AccountId, grants: List<EditAuthority.ScopedGrant>): PersonVisibility =
            if (tree.ownerAccountId == caller) owner() else member(grants)
    }
}
