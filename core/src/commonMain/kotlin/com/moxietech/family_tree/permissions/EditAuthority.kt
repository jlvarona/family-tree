package com.moxietech.family_tree.permissions

import com.moxietech.family_tree.domain.Branch
import com.moxietech.family_tree.domain.GrantRole
import com.moxietech.family_tree.domain.PersonId

/** FT-012: how a member may change one person (or relationship). */
enum class EditAccess {
    /** The change applies immediately. */
    LIVE,

    /**
     * The member can edit somewhere, but not here: outside their home branch and grants,
     * or a person with an account inside a contributor grant. FT-014 turns these changes
     * into change proposals; until then they are refused with nothing written.
     */
    OUTSIDE_BRANCH,

    /** The member can't change anything in the tree (viewer-only or no grants). */
    NONE,
}

/**
 * FT-012: what one ACTIVE member may change in a tree, decided from data the caller
 * read in one consistent view (on the server: the write's own transaction, under its
 * locks). Shared by the server (enforcement) and, later, the clients (edit controls).
 *
 * Rules (concept-architecture.md "Roles", "Rules", "Edit approval"; decisions table
 * "Grant-based authorization (FT-012)"):
 * - The tree owner, and an ADMIN whose grant covers the whole tree ([GrantRules]), edit
 *   everyone live. These are the only "tree-wide" members; tree-wide records (places,
 *   sources, media uploads) need one of them.
 * - Grants union and the highest role on a person wins ([GrantRules.rank]). A VIEWER
 *   grant never allows a change.
 * - A member "can contribute" when they are tree-wide or hold at least one grant whose
 *   role can edit (CONTRIBUTOR, BRANCH_OWNER, a whole-tree ADMIN). Only then do the home
 *   branch and the rules below apply; a member with no such grant changes nothing.
 * - BRANCH_OWNER: everyone in the branch, live.
 * - CONTRIBUTOR: people in the branch without an account (no membership is linked to
 *   them: deceased relatives, children), live. People with an account there are
 *   [EditAccess.OUTSIDE_BRANCH] unless another rule allows them.
 * - Home branch ([HomeBranch], from the member's linked person): everyone in it, live,
 *   accounts included.
 * - A person this member created who has no relationship yet (no union, no parents) and
 *   no account is theirs to edit, so they can add a person and then attach them to
 *   their branch ("A person added inside a branch is automatically inside it").
 * - A relationship (union, child link, an event or citation on a union) needs live
 *   access to at least one of its people ("Editing a relationship needs edit rights on
 *   at least one end"). A replacement needs it before and after.
 * - A change to the graph must not give the member live access to anyone they couldn't
 *   edit before ([gainedBy]): attaching an existing person to a branch is how a
 *   member could otherwise widen their own reach, so such a change is
 *   [EditAccess.OUTSIDE_BRANCH] too (a proposal in FT-014).
 *
 * @param grants every grant the member holds, with its stored role and branch and the
 *   people its branch resolves to; grants that carry no authority are ignored here.
 * @param homeBranch the member's resolved home branch (empty when not linked).
 * @param accountHolders people any membership of the tree is linked to.
 * @param ownUnattached people this member created that have no union or parent link.
 */
class EditAuthority(
    val isOwner: Boolean,
    grants: List<ScopedGrant>,
    private val homeBranch: Set<PersonId>,
    private val accountHolders: Set<PersonId>,
    private val ownUnattached: Set<PersonId>,
) {
    /** A stored grant and the people its branch resolves to. */
    data class ScopedGrant(val role: GrantRole, val branch: Branch, val scope: Set<PersonId>)

    private val effective: List<Pair<GrantRole, Set<PersonId>>> =
        grants.mapNotNull { grant -> GrantRules.effectiveRole(grant.role, grant.branch)?.let { it to grant.scope } }

    /** An ADMIN grant over the whole tree. */
    val isAdmin: Boolean = effective.any { it.first == GrantRole.ADMIN }

    /** The owner or a whole-tree admin: edits everyone and tree-wide records. */
    val isTreeWide: Boolean = isOwner || isAdmin

    /** Whether this member may change anything at all. */
    val canContribute: Boolean = isTreeWide || effective.any { GrantRules.canEditWith(it.first) }

    /** The highest effective role among the grants covering [person], or null. */
    fun roleOn(person: PersonId): GrantRole? =
        effective.filter { person in it.second }.map { it.first }.maxByOrNull(GrantRules::rank)

    fun personAccess(person: PersonId): EditAccess = when {
        isTreeWide -> EditAccess.LIVE
        !canContribute -> EditAccess.NONE
        person in homeBranch -> EditAccess.LIVE
        person in ownUnattached && person !in accountHolders -> EditAccess.LIVE
        else -> when (roleOn(person)) {
            GrantRole.ADMIN, GrantRole.BRANCH_OWNER -> EditAccess.LIVE
            GrantRole.CONTRIBUTOR -> if (person in accountHolders) EditAccess.OUTSIDE_BRANCH else EditAccess.LIVE
            GrantRole.VIEWER, null -> EditAccess.OUTSIDE_BRANCH
        }
    }

    /**
     * The people this member created that are still unattached and have no account, which
     * they edit live by that rule alone (the create-then-attach workflow).
     */
    val ownUnattachedEditable: Set<PersonId> = ownUnattached - accountHolders

    /** A relationship between [people] (its partners, a child): live when any one of them is. */
    fun relationshipAccess(people: Collection<PersonId>): EditAccess = when {
        isTreeWide -> EditAccess.LIVE
        !canContribute -> EditAccess.NONE
        people.any { personAccess(it) == EditAccess.LIVE } -> EditAccess.LIVE
        else -> EditAccess.OUTSIDE_BRANCH
    }

    /** Among [people], those this member can edit live. */
    fun liveAmong(people: Collection<PersonId>): Set<PersonId> =
        people.filterTo(mutableSetOf()) { personAccess(it) == EditAccess.LIVE }

    /**
     * Among [people], those [after] (this member's authority recomputed after a graph
     * change) can edit live but this authority couldn't: the reach the change would
     * add. Empty means the change doesn't widen what the member can edit.
     */
    fun gainedBy(after: EditAuthority, people: Collection<PersonId>): Set<PersonId> =
        after.liveAmong(people) - liveAmong(people)

    companion object {
        /** The tree owner: everything, without reading grants or branches. */
        fun owner(): EditAuthority = EditAuthority(true, emptyList(), emptySet(), emptySet(), emptySet())
    }
}
