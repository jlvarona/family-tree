package com.moxietech.family_tree.state

import com.moxietech.family_tree.api.CitationDto
import com.moxietech.family_tree.api.EventDto
import com.moxietech.family_tree.api.PersonDto
import com.moxietech.family_tree.api.SourceDto
import com.moxietech.family_tree.api.TreeGraphDto
import com.moxietech.family_tree.client.ApiFailure
import com.moxietech.family_tree.client.apiType
import com.moxietech.family_tree.client.targetId
import com.moxietech.family_tree.domain.CitationTarget
import com.moxietech.family_tree.domain.Confidence
import com.moxietech.family_tree.domain.EventId
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.UnionId
import com.moxietech.family_tree.navigation.Route

/** A fact on the person page that citations can support (FT-002 targets, `:core` [CitationTarget]). */
sealed interface CitedFact {
    val target: CitationTarget

    /** The person's own record. */
    data class Profile(override val target: CitationTarget.OnPerson) : CitedFact

    /** One timeline event; [type] is the API's event type, [date] its cleaned display text. */
    data class Event(override val target: CitationTarget.OnEvent, val type: String, val date: String?) : CitedFact

    /**
     * One of the person's unions, listed only when every partner is visible to the caller.
     * [partnerName] is null for a union with no second partner.
     */
    data class Union(override val target: CitationTarget.OnUnion, val unionType: String, val partnerName: String?) : CitedFact
}

/**
 * One citation as the page shows it, all text cleaned to plain text
 * ([PersonProfile.cleanText]). Source fields are null or empty when the cited source
 * isn't among the tree's sources (shown as "Unknown source").
 */
data class CitationEntry(
    val citationId: String,
    val fact: CitedFact,
    val sourceTitle: String?,
    /** The API's source type (`VITAL_RECORD`, …); unknown values are shown as "Other source". */
    val sourceType: String?,
    /** Author, publisher, repository and date of the source. */
    val sourceDetails: List<String>,
    /** The source's web address, shown as inert text only: never opened or made a link. */
    val sourceUrl: String?,
    val page: String?,
    val note: String?,
    /** 0 (unreliable) to [PersonSources.MAX_CONFIDENCE] (primary evidence). */
    val confidence: Int,
    /** A verified citation scan (FT-008), fetched only when the user opens it. */
    val mediaId: String?,
)

/** The Sources section of a person page. */
sealed interface SourcesView {
    /** The person is hidden from the caller: no citations are shown, whatever was received. */
    data object Private : SourcesView

    /**
     * What has loaded so far. [loading] while any part this section needs is on its way,
     * [failure] for the first part that failed (the retry fetches only the failed parts),
     * and [incomplete] when the timeline or family failed, so their facts' citations
     * can't be requested yet. [loadedEvents] are the timeline events whose citations are
     * known (possibly none), for the timeline's "N sources" / "No source yet" labels.
     */
    data class Listed(
        val entries: List<CitationEntry>,
        val loadedEvents: Set<String>,
        val loading: Boolean,
        val failure: ApiFailure?,
        val incomplete: Boolean,
    ) : SourcesView {
        val isEmpty: Boolean get() = entries.isEmpty() && !loading && failure == null && !incomplete

        fun forEvent(eventId: String): List<CitationEntry> =
            entries.filter { (it.fact as? CitedFact.Event)?.target?.eventId?.value == eventId }
    }
}

/**
 * Builds the person page's citations (the FT-006 follow-up) from the FT-002 lists and
 * the tree's sources, with the same `:core` visibility rule as the rest of the page
 * ([Viewer]). The server has already withheld what the caller may not see; this also
 * refuses to show citations on a hidden person or on a union with a hidden partner if a
 * response ever carried them, and drops citations for another target.
 */
object PersonSources {
    val MAX_CONFIDENCE: Int = Confidence.PRIMARY.level

    /**
     * The facts of [person]'s page that can carry citations, in page order: the profile,
     * the timeline's events (once [events] loaded), then the person's unions (once
     * [graph] loaded). Nothing for a person hidden from [viewer], and no union with a
     * hidden partner (the server's `citationsOn` rule); the family section still shows
     * that union's type. The controller requests exactly these, so it waits for the account.
     */
    fun citedFacts(person: PersonDto, events: List<EventDto>?, graph: TreeGraphDto?, viewer: Viewer): List<CitedFact> {
        if (!viewer.canSeeDetails(person)) return emptyList()
        fun sameTree(treeId: String) = treeId.equals(person.treeId, ignoreCase = true)
        val facts = mutableListOf<CitedFact>(CitedFact.Profile(CitationTarget.OnPerson(PersonId(person.id))))
        events?.let { list ->
            PersonProfile.personEvents(person, list).forEach { event ->
                facts += CitedFact.Event(CitationTarget.OnEvent(EventId(event.id)), event.type, PersonProfile.dateText(event.date))
            }
        }
        if (graph != null && sameTree(graph.treeId)) {
            val people = graph.people.filter { sameTree(it.treeId) }.associateBy { it.id }
            graph.unions.filter { sameTree(it.treeId) }.forEach { union ->
                val partners = listOfNotNull(union.partner1Id, union.partner2Id)
                if (person.id !in partners) return@forEach
                val partnerId = partners.firstOrNull { it != person.id }
                val partner = partnerId?.let { people[it] }
                // An unknown or hidden partner: the union's citations would describe them.
                if (partnerId != null && (partner == null || !viewer.canSeeDetails(partner))) return@forEach
                facts += CitedFact.Union(CitationTarget.OnUnion(UnionId(union.id)), union.type, partner?.let(PersonProfile::displayName))
            }
        }
        return facts
    }

    fun view(
        person: PersonDto,
        viewer: Viewer,
        events: Loadable<List<EventDto>>,
        graph: Loadable<TreeGraphDto>,
        sources: Loadable<List<SourceDto>>?,
        citations: Map<CitationTarget, Loadable<List<CitationDto>>>,
    ): SourcesView {
        if (!viewer.canSeeDetails(person)) return SourcesView.Private
        val facts = citedFacts(person, events.valueOrNull, graph.valueOrNull, viewer)
        var loading = events == Loadable.Loading || graph == Loadable.Loading || sources == null || sources == Loadable.Loading
        var failure = (sources as? Loadable.Failed)?.failure
        val byId = sources?.valueOrNull
            ?.filter { it.treeId.equals(person.treeId, ignoreCase = true) }
            ?.associateBy { Route.canonicalId(it.id) }
        val entries = mutableListOf<CitationEntry>()
        val loadedEvents = mutableSetOf<String>()
        facts.forEach { fact ->
            when (val result = citations[fact.target]) {
                null, Loadable.Loading -> loading = true
                is Loadable.Failed -> if (failure == null) failure = result.failure
                is Loadable.Ready -> {
                    // Entries wait for the source list, so a title never appears as "unknown" first.
                    if (byId == null) return@forEach
                    if (fact is CitedFact.Event) loadedEvents += fact.target.eventId.value
                    result.value
                        .filter { it.targetType == fact.target.apiType && Route.sameId(it.targetId, fact.target.targetId) }
                        .distinctBy { it.id }
                        .forEach { entries += entry(it, fact, byId[Route.canonicalId(it.sourceId)]) }
                }
            }
        }
        return SourcesView.Listed(
            entries = entries,
            loadedEvents = loadedEvents,
            loading = loading,
            failure = failure,
            incomplete = events is Loadable.Failed || graph is Loadable.Failed,
        )
    }

    private fun entry(citation: CitationDto, fact: CitedFact, source: SourceDto?): CitationEntry = CitationEntry(
        citationId = citation.id,
        fact = fact,
        sourceTitle = source?.let { PersonProfile.cleanLine(it.title) },
        sourceType = source?.type,
        sourceDetails = source?.let {
            listOfNotNull(
                PersonProfile.cleanLine(it.author),
                PersonProfile.cleanLine(it.publisher),
                PersonProfile.cleanLine(it.repository),
                PersonProfile.dateText(it.date),
            )
        }.orEmpty(),
        sourceUrl = source?.url?.let(PersonProfile::cleanLine),
        page = PersonProfile.cleanLine(citation.page),
        note = PersonProfile.cleanText(citation.note),
        confidence = citation.confidence.coerceIn(0, MAX_CONFIDENCE),
        mediaId = citation.mediaId?.takeIf { it.isNotBlank() },
    )
}
