package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.api.DuplicateCandidateDto
import com.moxietech.family_tree.api.EventDto
import com.moxietech.family_tree.api.PersonDto
import com.moxietech.family_tree.api.UnionDto
import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.Person
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.Sex
import com.moxietech.family_tree.domain.Tree
import com.moxietech.family_tree.domain.Union
import com.moxietech.family_tree.domain.UnionChild
import com.moxietech.family_tree.format.DuplicatePersonScorer
import com.moxietech.family_tree.permissions.PersonVisibility

/**
 * The one place responses are shaped for living-person privacy (FT-066).
 * Every graph read builds one of these from the tree and the caller, and
 * projects people, unions, events and duplicate candidates through it, so
 * routes can't drift apart. Who is hidden comes from [PersonVisibility] in
 * `:core`; FT-013 widens that rule with grants without changing callers.
 *
 * What a hidden (living, not visible to this caller) person looks like:
 * - Person: a placeholder that keeps the ID, tree, `isLiving` and version so
 *   the tree still renders, with name "Private", sex `UNKNOWN`, and no
 *   surnames, bio or profile media.
 * - Their events are omitted entirely (no dates, places or notes).
 * - A union with a hidden partner keeps its partner and child links and its
 *   type, but its start and end dates are removed.
 * - They are never offered as duplicate candidates, so a score can't reveal
 *   who they are.
 */
class ViewerPolicy(private val tree: Tree, private val callerAccountId: AccountId) {

    fun canSee(person: Person): Boolean = PersonVisibility.canSeeDetails(tree, callerAccountId, person)

    fun project(person: Person): PersonDto = if (canSee(person)) person.toDto() else person.toDto().redacted()

    /** IDs among [people] this caller can't see; pass every person the response may mention. */
    fun hiddenIds(people: Iterable<Person>): Set<PersonId> = people.filterNot(::canSee).mapTo(mutableSetOf()) { it.id }

    fun project(union: Union, children: List<UnionChild>, hidden: Set<PersonId>): UnionDto {
        val dto = union.toDto(children)
        val hasHiddenPartner = union.partner1Id in hidden || union.partner2Id?.let { it in hidden } == true
        return if (hasHiddenPartner) dto.copy(startDate = null, endDate = null) else dto
    }

    /** The events of [person] this caller may see: all of them, or none for a hidden person. */
    fun eventsOf(person: Person, events: () -> List<EventDto>): List<EventDto> =
        if (canSee(person)) events() else emptyList()

    /**
     * Duplicate candidates for a new person, scored only against people this
     * caller can see. Hidden people are dropped before scoring, so neither a
     * placeholder nor a score can point at a private identity.
     */
    fun duplicateCandidates(
        givenNames: String,
        surnamePaternal: String?,
        surnameMaternal: String?,
        existing: List<Person>,
    ): List<DuplicateCandidateDto> = existing.filter(::canSee).mapNotNull { candidate ->
        val score = DuplicatePersonScorer.score(
            candidateGivenNames = givenNames,
            candidateSurnamePaternal = surnamePaternal,
            candidateSurnameMaternal = surnameMaternal,
            candidateBirthDate = null,
            candidateParentIds = emptySet(),
            existing = candidate,
            existingBirthDate = null,
            existingParentIds = emptySet(),
        )
        if (score >= DuplicatePersonScorer.WARNING_THRESHOLD) DuplicateCandidateDto(project(candidate), score) else null
    }
}

/** The private placeholder for a hidden living person; see [ViewerPolicy]. */
internal fun PersonDto.redacted() = copy(
    givenNames = PRIVATE_NAME,
    surnamePaternal = null,
    surnameMaternal = null,
    fullName = PRIVATE_NAME,
    sex = Sex.UNKNOWN.name,
    profileMediaId = null,
    bio = null,
)

/**
 * Wire value only: clients detect a placeholder by this name together with
 * `isLiving` and show their own localized label (FT-005).
 */
internal const val PRIVATE_NAME = "Private"
