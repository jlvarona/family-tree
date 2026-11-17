package com.moxietech.family_tree.state

import com.moxietech.family_tree.api.EventDto
import com.moxietech.family_tree.api.GedcomDateDto
import com.moxietech.family_tree.api.PersonDto
import com.moxietech.family_tree.api.TreeGraphDto
import com.moxietech.family_tree.api.UnionDto
import com.moxietech.family_tree.api.displayTextOrNull
import com.moxietech.family_tree.domain.EventType

/** How a person is presented: the private placeholder, or living / deceased with details. */
enum class PersonStatus { PRIVATE, LIVING, DECEASED }

/**
 * The top of the person page. Every field that could identify a hidden living
 * person is null when [status] is [PersonStatus.PRIVATE]; the UI shows the
 * localized placeholder instead.
 */
data class PersonHeader(
    val personId: String,
    /** Formatted through `:core`; null for the private placeholder. */
    val name: String?,
    val status: PersonStatus,
    val givenNames: String?,
    val surnamePaternal: String?,
    val surnameMaternal: String?,
    val initials: String?,
    /** The bio as plain text after [PersonProfile.cleanText]; null when private or empty. */
    val bio: String?,
)

/**
 * A relative on the person page.
 * - Partners carry the union's [unionType] even when the partner is private
 *   (the server keeps type and links); [unionStart] / [unionEnd] only when both
 *   people are visible, matching the server's rule that a hidden partner's
 *   union loses its dates (FT-066).
 * - Parents and children carry the child's [relation] in that union
 *   (`BIRTH`, `ADOPTED`, `STEP`, `FOSTER`).
 */
data class RelativeLink(
    val personId: String,
    val name: String?,
    val status: PersonStatus,
    val initials: String?,
    val unionType: String? = null,
    val unionStart: String? = null,
    val unionEnd: String? = null,
    val relation: String? = null,
)

data class FamilyLinks(
    val parents: List<RelativeLink>,
    val partners: List<RelativeLink>,
    val children: List<RelativeLink>,
) {
    val isEmpty: Boolean get() = parents.isEmpty() && partners.isEmpty() && children.isEmpty()
}

data class TimelineEntry(
    val eventId: String,
    /** The API's event type (`BIRTH`, …); unknown values are shown as "Other". */
    val type: String,
    val date: String?,
    val place: String?,
    val notes: String?,
)

sealed interface Timeline {
    /** The person is hidden from the caller: no events are shown, whatever was received. */
    data object Private : Timeline

    data class Entries(val entries: List<TimelineEntry>) : Timeline
}

/** Summary facts for the header, taken from the visible timeline only. */
data class LifeFacts(val born: String?, val died: String?, val birthplace: String?)

/**
 * Builds the person page from API data, applying the same `:core` visibility
 * and name rules as the tree ([Viewer]). The server has already redacted
 * everything the caller may not see; these functions also refuse to present
 * private details if a response ever carried them, and drop records that
 * belong to another person or tree.
 */
object PersonProfile {

    fun header(person: PersonDto, viewer: Viewer): PersonHeader {
        if (!viewer.canSeeDetails(person)) {
            return PersonHeader(person.id, null, PersonStatus.PRIVATE, null, null, null, null, null)
        }
        return PersonHeader(
            personId = person.id,
            name = displayName(person),
            status = if (person.isLiving) PersonStatus.LIVING else PersonStatus.DECEASED,
            givenNames = cleanLine(person.givenNames),
            surnamePaternal = cleanLine(person.surnamePaternal),
            surnameMaternal = cleanLine(person.surnameMaternal),
            initials = initials(person),
            bio = cleanText(person.bio),
        )
    }

    fun family(graph: TreeGraphDto, person: PersonDto, viewer: Viewer): FamilyLinks {
        if (!graph.treeId.equals(person.treeId, ignoreCase = true)) return FamilyLinks(emptyList(), emptyList(), emptyList())
        // Nested records are checked too: another tree's people or unions are never shown.
        fun sameTree(treeId: String) = treeId.equals(person.treeId, ignoreCase = true)
        val people = graph.people.filter { sameTree(it.treeId) }.associateBy { it.id }
        val unions = graph.unions.filter { sameTree(it.treeId) }
        val personVisible = viewer.canSeeDetails(person)
        fun link(id: String) = people[id]?.takeIf { it.id != person.id }?.let { relativeOf(it, viewer) }

        val parents = unions.flatMap { union ->
            val relation = union.children.firstOrNull { it.childId == person.id }?.relation
                ?: return@flatMap emptyList<RelativeLink>()
            union.partnerIds().mapNotNull { id -> link(id)?.copy(relation = relation) }
        }.distinctBy { it.personId }

        val own = unions.filter { person.id in it.partnerIds() }
        val partners = own.mapNotNull { union ->
            val partner = union.partnerIds().firstOrNull { it != person.id }?.let { people[it] } ?: return@mapNotNull null
            val datesVisible = personVisible && viewer.canSeeDetails(partner)
            link(partner.id)?.copy(
                unionType = union.type,
                unionStart = if (datesVisible) union.startDate.text() else null,
                unionEnd = if (datesVisible) union.endDate.text() else null,
            )
        }

        val children = own.flatMap { union ->
            union.children.mapNotNull { child -> link(child.childId)?.copy(relation = child.relation) }
        }.distinctBy { it.personId }

        return FamilyLinks(parents, partners, children)
    }

    fun timeline(person: PersonDto, events: List<EventDto>, viewer: Viewer): Timeline {
        if (!viewer.canSeeDetails(person)) return Timeline.Private
        val entries = personEvents(person, events)
            .map { event ->
                TimelineEntry(
                    eventId = event.id,
                    type = event.type,
                    date = event.date.text(),
                    place = event.place?.let { place ->
                        listOfNotNull(cleanLine(place.name), cleanLine(place.hierarchy)).distinct().joinToString(", ").ifEmpty { null }
                    },
                    notes = cleanText(event.notes),
                )
            }
        return Timeline.Entries(entries)
    }

    /** [person]'s own events in this tree, in timeline order (no visibility check). */
    internal fun personEvents(person: PersonDto, events: List<EventDto>): List<EventDto> = events
        .filter { it.personId == person.id && it.treeId.equals(person.treeId, ignoreCase = true) }
        .sortedWith(eventOrder)

    fun lifeFacts(timeline: Timeline): LifeFacts {
        val entries = (timeline as? Timeline.Entries)?.entries ?: return LifeFacts(null, null, null)
        val birth = entries.firstOrNull { it.type == EventType.BIRTH.name }
        val death = entries.firstOrNull { it.type == EventType.DEATH.name }
        return LifeFacts(birth?.date, death?.date, birth?.place)
    }

    /**
     * Free text from family members (bios, notes) is untrusted: it is only ever
     * shown as plain text, never parsed as HTML, Markdown or links. This also
     * normalizes line breaks and removes control characters and the bidi
     * override/isolate marks that could make text display differently from
     * what was written. Blank text becomes null.
     */
    fun cleanText(text: String?): String? {
        if (text == null) return null
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val cleaned = buildString(normalized.length) {
            normalized.forEach { c -> if (c == '\n' || c == '\t' || !isUnsafe(c)) append(c) }
        }
        return cleaned.lines().joinToString("\n") { it.trimEnd() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
            .ifEmpty { null }
    }

    /** A bio split into paragraphs on blank lines. */
    fun paragraphs(text: String): List<String> = text.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }

    /** The `:core`-formatted name, as plain text on one line. */
    internal fun displayName(person: PersonDto): String = Viewer.fullName(person).let { cleanLine(it) ?: it }

    /** [cleanText] on one line: runs of whitespace, line breaks included, become one space. */
    internal fun cleanLine(text: String?): String? = cleanText(text)?.replace(Regex("\\s+"), " ")

    private fun isUnsafe(c: Char): Boolean =
        c < ' ' || c in '\u007F'..'\u009F' || c in '‪'..'‮' || c in '⁦'..'⁩'

    private fun relativeOf(person: PersonDto, viewer: Viewer): RelativeLink =
        if (viewer.canSeeDetails(person)) {
            RelativeLink(
                personId = person.id,
                name = displayName(person),
                status = if (person.isLiving) PersonStatus.LIVING else PersonStatus.DECEASED,
                initials = initials(person),
            )
        } else {
            RelativeLink(person.id, null, PersonStatus.PRIVATE, null)
        }

    /** First letters of the given names and first surname (the mockup's "AR"). */
    internal fun initials(person: PersonDto): String? {
        val surname = person.surnamePaternal?.takeIf { it.isNotBlank() } ?: person.surnameMaternal
        return listOfNotNull(person.givenNames, surname)
            .mapNotNull { part -> part.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() }
            .joinToString("")
            .ifEmpty { null }
    }

    private fun UnionDto.partnerIds(): List<String> = listOfNotNull(partner1Id, partner2Id)

    private fun GedcomDateDto?.text(): String? = this?.displayTextOrNull()?.let(::cleanLine)

    /** A date's display text as one cleaned line, or null. */
    internal fun dateText(date: GedcomDateDto?): String? = date.text()

    /**
     * Birth first, death then burial last; within each group, dated events by
     * their earliest bound, or the latest bound for an open-ended date such as
     * "bef 1900" (handling years before 1 and beyond 9999), then events with
     * no bound at all, then by ID so the order is stable.
     */
    private val eventOrder: Comparator<EventDto> = compareBy<EventDto> { rank(it.type) }
        .thenComparator { a, b -> compareDates(sortKey(a), sortKey(b)) }
        .thenBy { it.id }

    private fun sortKey(event: EventDto): DateKey? = isoKey(event.date?.earliestIso) ?: isoKey(event.date?.latestIso)

    private fun rank(type: String): Int = when (type) {
        EventType.BIRTH.name -> 0
        EventType.DEATH.name -> 2
        EventType.BURIAL.name -> 3
        else -> 1
    }

    private data class DateKey(val year: Long, val month: Int, val day: Int)

    private val isoDate = Regex("^([+-]?\\d+)-(\\d{2})-(\\d{2})")

    private fun isoKey(iso: String?): DateKey? {
        val match = iso?.let { isoDate.find(it) } ?: return null
        val (year, month, day) = match.destructured
        return DateKey(year.removePrefix("+").toLongOrNull() ?: return null, month.toInt(), day.toInt())
    }

    private fun compareDates(a: DateKey?, b: DateKey?): Int = when {
        a == null && b == null -> 0
        a == null -> 1
        b == null -> -1
        else -> compareValuesBy(a, b, { it.year }, { it.month }, { it.day })
    }
}
