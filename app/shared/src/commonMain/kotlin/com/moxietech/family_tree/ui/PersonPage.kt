package com.moxietech.family_tree.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moxietech.family_tree.api.AccountDto
import com.moxietech.family_tree.api.EventDto
import com.moxietech.family_tree.api.PersonDto
import com.moxietech.family_tree.api.TreeGraphDto
import com.moxietech.family_tree.client.ApiFailure
import com.moxietech.family_tree.domain.ChildRelation
import com.moxietech.family_tree.domain.EventType
import com.moxietech.family_tree.state.FamilyTreeController
import com.moxietech.family_tree.state.LifeFacts
import com.moxietech.family_tree.state.Loadable
import com.moxietech.family_tree.state.PersonHeader
import com.moxietech.family_tree.state.PersonProfile
import com.moxietech.family_tree.state.PersonScreen
import com.moxietech.family_tree.state.PersonStatus
import com.moxietech.family_tree.state.RelativeLink
import com.moxietech.family_tree.state.Timeline
import com.moxietech.family_tree.state.TimelineEntry
import com.moxietech.family_tree.state.Viewer
import com.moxietech.family_tree.state.valueOrNull
import family_tree.app.shared.generated.resources.Res
import family_tree.app.shared.generated.resources.back_to_tree
import family_tree.app.shared.generated.resources.children
import family_tree.app.shared.generated.resources.date_from
import family_tree.app.shared.generated.resources.date_range
import family_tree.app.shared.generated.resources.date_unknown
import family_tree.app.shared.generated.resources.date_until
import family_tree.app.shared.generated.resources.deceased
import family_tree.app.shared.generated.resources.event_baptism
import family_tree.app.shared.generated.resources.event_birth
import family_tree.app.shared.generated.resources.event_burial
import family_tree.app.shared.generated.resources.event_death
import family_tree.app.shared.generated.resources.event_divorce
import family_tree.app.shared.generated.resources.event_emigration
import family_tree.app.shared.generated.resources.event_immigration
import family_tree.app.shared.generated.resources.event_marriage
import family_tree.app.shared.generated.resources.event_occupation
import family_tree.app.shared.generated.resources.event_other
import family_tree.app.shared.generated.resources.event_residence
import family_tree.app.shared.generated.resources.fact_birthplace
import family_tree.app.shared.generated.resources.fact_born
import family_tree.app.shared.generated.resources.fact_died
import family_tree.app.shared.generated.resources.family
import family_tree.app.shared.generated.resources.family_empty
import family_tree.app.shared.generated.resources.given_names
import family_tree.app.shared.generated.resources.life_story
import family_tree.app.shared.generated.resources.living
import family_tree.app.shared.generated.resources.loading
import family_tree.app.shared.generated.resources.name_part
import family_tree.app.shared.generated.resources.none_recorded
import family_tree.app.shared.generated.resources.open_page
import family_tree.app.shared.generated.resources.parents
import family_tree.app.shared.generated.resources.partners
import family_tree.app.shared.generated.resources.photo_placeholder
import family_tree.app.shared.generated.resources.private_detail
import family_tree.app.shared.generated.resources.private_person
import family_tree.app.shared.generated.resources.relation_adopted
import family_tree.app.shared.generated.resources.relation_foster
import family_tree.app.shared.generated.resources.relation_step
import family_tree.app.shared.generated.resources.retry
import family_tree.app.shared.generated.resources.show_in_tree
import family_tree.app.shared.generated.resources.story_empty
import family_tree.app.shared.generated.resources.story_private
import family_tree.app.shared.generated.resources.surname_maternal
import family_tree.app.shared.generated.resources.surname_paternal
import family_tree.app.shared.generated.resources.timeline
import family_tree.app.shared.generated.resources.timeline_empty
import family_tree.app.shared.generated.resources.timeline_private
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Colours from the person page mockup (docs/mockups/person-page.dc.html) not already in [Palette]. */
private object PersonColors {
    val avatar = Color(0xFFDCE6F0)
    val avatarText = Color(0xFF2B5C8A)
    val avatarPrivate = Color(0xFFE3E7E2)
}

/**
 * The person page (`#/trees/{treeId}/people/{personId}`, FT-006), laid out as
 * in the mockup: a header card, the life story and timeline on the left and
 * family links on the right. It loads everything from the URL, so a reload
 * or a shared link opens the same person.
 *
 * Privacy follows the server's redaction and the `:core` rules through
 * [PersonProfile]: a hidden living person shows only the localized placeholder,
 * with no story or timeline, and union dates appear only when both partners are
 * visible (the union type always does). Bios and notes are rendered as plain
 * text only. Photos and source citations wait for FT-008 and FT-002.
 *
 * Everything is in-page (no popups or dialogs), so Compose Web's accessibility
 * tree stays in sync, as in FT-005's forms.
 */
@Composable
internal fun PersonPage(screen: PersonScreen, account: AccountDto, controller: FamilyTreeController) {
    val tree = screen.tree
    val person = screen.person
    when {
        tree is Loadable.Failed -> FailurePane(tree.failure, onRetry = controller::retry)
        person is Loadable.Failed -> FailurePane(person.failure, onRetry = controller::retry)
        tree is Loadable.Ready && person is Loadable.Ready -> {
            val viewer = remember(tree.value, account) { Viewer(tree.value, account) }
            PersonContent(screen, person.value, viewer, controller)
        }
        else -> LoadingPane()
    }
}

@Composable
private fun PersonContent(screen: PersonScreen, person: PersonDto, viewer: Viewer, controller: FamilyTreeController) {
    val header = remember(person, viewer) { PersonProfile.header(person, viewer) }
    val events = screen.events
    val timeline = remember(person, viewer, events) {
        events.valueOrNull?.let { PersonProfile.timeline(person, it, viewer) }
    }
    val facts = remember(timeline) { timeline?.let(PersonProfile::lifeFacts) ?: LifeFacts(null, null, null) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 900.dp
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier.widthIn(max = 1280.dp).fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Breadcrumb(header, onBack = controller::backToTree)
                HeaderCard(header, facts, wide, onShowInTree = controller::showInTree)
                val story: @Composable () -> Unit = { StorySection(header) }
                val timelineSection: @Composable () -> Unit = {
                    TimelineSection(header, events, timeline, onRetry = controller::retry)
                }
                val family: @Composable () -> Unit = {
                    FamilySection(screen.graph, person, viewer, onOpen = controller::openPerson, onRetry = controller::retry)
                }
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            story()
                            timelineSection()
                        }
                        Column(Modifier.width(380.dp)) { family() }
                    }
                } else {
                    story()
                    timelineSection()
                    family()
                }
            }
        }
    }
}

@Composable
private fun Breadcrumb(header: PersonHeader, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text(stringResource(Res.string.back_to_tree)) }
        Text("/", color = Palette.muted, fontSize = 13.sp, modifier = Modifier.clearAndSetSemantics {})
        Text(
            header.name ?: stringResource(Res.string.private_person),
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun HeaderCard(header: PersonHeader, facts: LifeFacts, wide: Boolean, onShowInTree: () -> Unit) {
    val content: @Composable () -> Unit = {
        Avatar(header.initials, size = 120, isPrivate = header.status == PersonStatus.PRIVATE)
    }
    val details: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(statusLabel(header.status)).uppercase(),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = PersonColors.avatarText,
            )
            Text(
                header.name ?: stringResource(Res.string.private_person),
                fontSize = 34.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            val factLine = buildList {
                when {
                    facts.born != null && facts.died != null -> add(stringResource(Res.string.date_range, facts.born, facts.died))
                    facts.born != null -> add(stringResource(Res.string.fact_born, facts.born))
                    facts.died != null -> add(stringResource(Res.string.fact_died, facts.died))
                }
                facts.birthplace?.let { add(stringResource(Res.string.fact_birthplace, it)) }
            }
            if (factLine.isNotEmpty()) Text(factLine.joinToString(" · "), fontSize = 15.sp, color = Palette.muted)
            val nameParts = buildList {
                header.givenNames?.let { add(stringResource(Res.string.name_part, stringResource(Res.string.given_names), it)) }
                header.surnamePaternal?.let { add(stringResource(Res.string.name_part, stringResource(Res.string.surname_paternal), it)) }
                header.surnameMaternal?.let { add(stringResource(Res.string.name_part, stringResource(Res.string.surname_maternal), it)) }
            }
            if (nameParts.isNotEmpty()) Text(nameParts.joinToString(" · "), fontSize = 13.sp, color = Palette.muted)
        }
    }
    val actions: @Composable () -> Unit = {
        Button(onClick = onShowInTree) { Text(stringResource(Res.string.show_in_tree)) }
    }
    val cardModifier = Modifier.fillMaxWidth().card().padding(28.dp)
    if (wide) {
        Row(cardModifier, horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            content()
            details(Modifier.weight(1f))
            actions()
        }
    } else {
        Column(cardModifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            content()
            details(Modifier)
            actions()
        }
    }
}

/** The photo placeholder until FT-008: initials, or a blank tile for a private person. */
@Composable
private fun Avatar(initials: String?, size: Int, isPrivate: Boolean, round: Boolean = false, describe: Boolean = true) {
    val description = stringResource(Res.string.photo_placeholder)
    Box(
        modifier = Modifier.size(size.dp)
            .background(
                if (isPrivate) PersonColors.avatarPrivate else PersonColors.avatar,
                if (round) CircleShape else RoundedCornerShape(16.dp),
            )
            .clearAndSetSemantics { if (describe) contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (initials != null) {
            Text(
                initials,
                fontSize = if (round) 12.sp else (size / 3).sp,
                fontWeight = FontWeight.SemiBold,
                color = if (round) Palette.text else PersonColors.avatarText,
            )
        }
    }
}

@Composable
private fun SectionCard(title: StringResource, modifier: Modifier = Modifier, small: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().card().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val text = stringResource(title)
        Text(
            if (small) text.uppercase() else text,
            fontSize = if (small) 12.sp else 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (small) Palette.muted else Palette.text,
            modifier = Modifier.semantics { heading() },
        )
        content()
    }
}

@Composable
private fun StorySection(header: PersonHeader) {
    SectionCard(Res.string.life_story) {
        val bio = header.bio
        when {
            header.status == PersonStatus.PRIVATE -> Text(stringResource(Res.string.story_private), color = Palette.muted)
            bio == null -> Text(stringResource(Res.string.story_empty, header.name.orEmpty()), color = Palette.muted)
            // Untrusted text: shown as plain Text only, never parsed as markup or links.
            else -> PersonProfile.paragraphs(bio).forEach { paragraph ->
                Text(paragraph, fontSize = 16.sp, lineHeight = 27.sp)
            }
        }
    }
}

@Composable
private fun TimelineSection(header: PersonHeader, events: Loadable<List<EventDto>>, timeline: Timeline?, onRetry: () -> Unit) {
    SectionCard(Res.string.timeline) {
        when {
            // Decided from the person, not the response: nothing is listed for a private person.
            header.status == PersonStatus.PRIVATE || timeline == Timeline.Private ->
                Text(stringResource(Res.string.timeline_private), color = Palette.muted)
            events is Loadable.Failed -> SectionFailure(events.failure, onRetry)
            timeline is Timeline.Entries && timeline.entries.isEmpty() ->
                Text(stringResource(Res.string.timeline_empty), color = Palette.muted)
            timeline is Timeline.Entries -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                timeline.entries.forEachIndexed { index, entry ->
                    TimelineRow(entry)
                    if (index < timeline.entries.lastIndex) HorizontalDivider(color = Palette.border)
                }
            }
            else -> SectionLoading()
        }
    }
}

@Composable
private fun TimelineRow(entry: TimelineEntry) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            entry.date ?: stringResource(Res.string.date_unknown),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (entry.date == null) Palette.muted else Palette.text,
            modifier = Modifier.width(120.dp),
        )
        Box(
            Modifier.padding(top = 6.dp).size(10.dp).background(Palette.accent, CircleShape).clearAndSetSemantics {},
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(eventLabel(entry.type)), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            entry.place?.let { Text(it, fontSize = 13.sp, color = Palette.muted) }
            entry.notes?.let { Text(it, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun FamilySection(
    graph: Loadable<TreeGraphDto>,
    person: PersonDto,
    viewer: Viewer,
    onOpen: (String) -> Unit,
    onRetry: () -> Unit,
) {
    SectionCard(Res.string.family, small = true) {
        when (graph) {
            Loadable.Loading -> SectionLoading()
            is Loadable.Failed -> SectionFailure(graph.failure, onRetry)
            is Loadable.Ready -> {
                val links = remember(graph.value, person, viewer) { PersonProfile.family(graph.value, person, viewer) }
                if (links.isEmpty) {
                    Text(stringResource(Res.string.family_empty), color = Palette.muted)
                } else {
                    RelativeGroup(Res.string.parents, links.parents, onOpen)
                    RelativeGroup(Res.string.partners, links.partners, onOpen)
                    RelativeGroup(Res.string.children, links.children, onOpen)
                }
            }
        }
    }
}

@Composable
private fun RelativeGroup(title: StringResource, links: List<RelativeLink>, onOpen: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(title), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.muted)
        if (links.isEmpty()) Text(stringResource(Res.string.none_recorded), fontSize = 13.sp, color = Palette.muted)
        links.forEach { RelativeRow(it, onOpen) }
    }
}

@Composable
private fun RelativeRow(link: RelativeLink, onOpen: (String) -> Unit) {
    val details = buildList {
        // The union type is shown even for a private partner; dates only when present (both visible).
        link.unionType?.let { add(stringResource(unionTypeLabel(it))) }
        val start = link.unionStart
        val end = link.unionEnd
        when {
            start != null && end != null -> add(stringResource(Res.string.date_range, start, end))
            start != null -> add(stringResource(Res.string.date_from, start))
            end != null -> add(stringResource(Res.string.date_until, end))
        }
        link.relation?.let(::relationLabel)?.let { add(stringResource(it)) }
        add(stringResource(statusLabel(link.status)))
    }
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.open_page)) { onOpen(link.personId) }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(link.initials, size = 36, isPrivate = link.status == PersonStatus.PRIVATE, round = true, describe = false)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                link.name ?: stringResource(Res.string.private_person),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(details.joinToString(" · "), fontSize = 12.sp, color = Palette.muted)
        }
    }
}

@Composable
private fun SectionLoading() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(stringResource(Res.string.loading), color = Palette.muted)
    }
}

/** A section's own failure, with a retry that reloads only the failed sections. */
@Composable
private fun SectionFailure(failure: ApiFailure, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(failureMessage(failure)), color = Palette.text)
        if (failure != ApiFailure.NOT_FOUND && failure != ApiFailure.FORBIDDEN) {
            OutlinedButton(onClick = onRetry) { Text(stringResource(Res.string.retry)) }
        }
    }
}

private fun Modifier.card(): Modifier =
    background(Palette.surface, RoundedCornerShape(16.dp)).border(BorderStroke(1.dp, Palette.border), RoundedCornerShape(16.dp))

private fun statusLabel(status: PersonStatus): StringResource = when (status) {
    PersonStatus.PRIVATE -> Res.string.private_detail
    PersonStatus.LIVING -> Res.string.living
    PersonStatus.DECEASED -> Res.string.deceased
}

/** Birth links aren't labelled; the others say how the child belongs to the union. */
private fun relationLabel(relation: String): StringResource? = when (relation) {
    ChildRelation.ADOPTED.name -> Res.string.relation_adopted
    ChildRelation.STEP.name -> Res.string.relation_step
    ChildRelation.FOSTER.name -> Res.string.relation_foster
    else -> null
}

private fun eventLabel(type: String): StringResource = when (type) {
    EventType.BIRTH.name -> Res.string.event_birth
    EventType.DEATH.name -> Res.string.event_death
    EventType.MARRIAGE.name -> Res.string.event_marriage
    EventType.DIVORCE.name -> Res.string.event_divorce
    EventType.BAPTISM.name -> Res.string.event_baptism
    EventType.BURIAL.name -> Res.string.event_burial
    EventType.IMMIGRATION.name -> Res.string.event_immigration
    EventType.EMIGRATION.name -> Res.string.event_emigration
    EventType.RESIDENCE.name -> Res.string.event_residence
    EventType.OCCUPATION.name -> Res.string.event_occupation
    else -> Res.string.event_other
}
