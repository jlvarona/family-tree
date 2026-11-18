package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.api.CitationDto
import com.moxietech.family_tree.api.DuplicateCandidateDto
import com.moxietech.family_tree.api.EventDto
import com.moxietech.family_tree.api.PersonDto
import com.moxietech.family_tree.api.UnionDto
import com.moxietech.family_tree.domain.Person
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.Sex
import com.moxietech.family_tree.domain.Union
import com.moxietech.family_tree.domain.UnionChild
import com.moxietech.family_tree.format.DuplicatePersonScorer
import com.moxietech.family_tree.permissions.PersonVisibility
import com.moxietech.family_tree.server.media.MediaAttachment

/**
 * The one place responses are shaped for living-person privacy (FT-066, FT-013).
 * Every graph read builds one of these for the caller in one tree, and projects
 * people, unions, events and duplicate candidates through it, so routes can't drift
 * apart. Who is hidden comes from `:core` [PersonVisibility]: since FT-013 the owner,
 * deceased people, and living people one of the caller's effective grants covers are
 * visible. Build it with `ReadAccess` (reads: the grant scopes of the same snapshot)
 * or `WriteScope.viewer` (writes: the scopes under the write's locks), never from the
 * tree alone.
 *
 * What a hidden (living, not visible to this caller) person looks like:
 * - Person: a placeholder that keeps the ID, tree, `isLiving` and version so
 *   the tree still renders, with name "Private", sex `UNKNOWN`, no surnames, bio or
 *   profile media, and `redacted = true`, which clients render as their localized label.
 * - Their events are omitted entirely (no dates, places or notes).
 * - A union with a hidden partner keeps its partner and child links and its
 *   type, but its start and end dates are removed.
 * - They are never offered as duplicate candidates, so a score can't reveal
 *   who they are.
 * - Citations on them and on a union where they are a partner are withheld
 *   (FT-002, [citationsOn]); an event of either reads as missing on the
 *   citation route, as it is omitted everywhere else.
 */
class ViewerPolicy(private val visibility: PersonVisibility) {

    fun canSee(person: Person): Boolean = visibility.canSeeDetails(person)

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
     * The citations on one fact (FT-002) this caller may see. [subjects] are the
     * people the fact is about: the cited person; a cited union's partners; a
     * cited event's person, or its union's partners. A citation's page, note,
     * confidence, source and media can describe those people, so the citations
     * are returned only when every subject is visible, and none otherwise, the
     * same way [eventsOf] treats a hidden person and [project] hides the dates of
     * a union with a hidden partner. [citations] isn't read when withheld.
     * (The citation route reports an event with a hidden subject as missing, since
     * every other response omits that event.)
     */
    fun citationsOn(subjects: List<Person>, citations: () -> List<CitationDto>): List<CitationDto> =
        if (canSeeAll(subjects)) citations() else emptyList()

    /** Whether this caller can see every one of [people]. */
    fun canSeeAll(people: List<Person>): Boolean = people.all(::canSee)

    /**
     * FT-008: whether this caller may read a verified image, from what it is attached
     * to. A profile photo follows its person ([project] already drops the photo ID of a
     * hidden person); a citation scan follows the citation ([citationsOn]: every person
     * the fact is about must be visible). An unattached image (not used yet, or replaced)
     * is the tree owner's only, whatever grants the caller holds (FT-013).
     */
    fun canSeeMedia(attachment: MediaAttachment): Boolean = when (attachment) {
        is MediaAttachment.ProfilePhotoOf -> canSee(attachment.person)
        is MediaAttachment.ScanOf -> canSeeAll(attachment.subjects)
        MediaAttachment.Unattached -> visibility.isOwner
    }

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
    redacted = true,
)

/**
 * Wire value kept for older clients, which detected a placeholder by this name
 * together with `isLiving`. Current clients use `PersonDto.redacted` (FT-013) and
 * show their own localized label.
 */
internal const val PRIVATE_NAME = "Private"
