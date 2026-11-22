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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import com.moxietech.family_tree.domain.SourceType
import com.moxietech.family_tree.state.CitationEntry
import com.moxietech.family_tree.state.CitedFact
import com.moxietech.family_tree.state.FamilyTreeController
import com.moxietech.family_tree.state.LifeFacts
import com.moxietech.family_tree.state.Loadable
import com.moxietech.family_tree.state.PersonHeader
import com.moxietech.family_tree.state.PersonProfile
import com.moxietech.family_tree.state.PersonScreen
import com.moxietech.family_tree.state.PersonStatus
import com.moxietech.family_tree.state.PhotoError
import com.moxietech.family_tree.state.PhotoKey
import com.moxietech.family_tree.state.PhotoUpload
import com.moxietech.family_tree.state.PersonSources
import com.moxietech.family_tree.state.RelativeLink
import com.moxietech.family_tree.state.SourcesView
import com.moxietech.family_tree.state.Timeline
import com.moxietech.family_tree.state.TimelineEntry
import com.moxietech.family_tree.state.Viewer
import com.moxietech.family_tree.state.valueOrNull
import family_tree.app.shared.generated.resources.Res
import family_tree.app.shared.generated.resources.back_to_tree
import family_tree.app.shared.generated.resources.cancel
import family_tree.app.shared.generated.resources.error_forbidden
import family_tree.app.shared.generated.resources.error_not_found
import family_tree.app.shared.generated.resources.error_server
import family_tree.app.shared.generated.resources.error_unavailable
import family_tree.app.shared.generated.resources.photo_add
import family_tree.app.shared.generated.resources.photo_alt
import family_tree.app.shared.generated.resources.photo_change
import family_tree.app.shared.generated.resources.photo_error_changed
import family_tree.app.shared.generated.resources.photo_error_invalid
import family_tree.app.shared.generated.resources.photo_error_size
import family_tree.app.shared.generated.resources.photo_error_type
import family_tree.app.shared.generated.resources.photo_error_upload
import family_tree.app.shared.generated.resources.photo_hint
import family_tree.app.shared.generated.resources.photo_load_failed
import family_tree.app.shared.generated.resources.photo_remove
import family_tree.app.shared.generated.resources.photo_saving
import family_tree.app.shared.generated.resources.children
import family_tree.app.shared.generated.resources.citation_confidence
import family_tree.app.shared.generated.resources.citation_detail
import family_tree.app.shared.generated.resources.citation_supports
import family_tree.app.shared.generated.resources.citation_supports_person
import family_tree.app.shared.generated.resources.citation_supports_union
import family_tree.app.shared.generated.resources.citation_web_address
import family_tree.app.shared.generated.resources.citations_hide
import family_tree.app.shared.generated.resources.citations_show
import family_tree.app.shared.generated.resources.scan_alt
import family_tree.app.shared.generated.resources.scan_hide
import family_tree.app.shared.generated.resources.scan_load_failed
import family_tree.app.shared.generated.resources.scan_show
import family_tree.app.shared.generated.resources.source_count_many
import family_tree.app.shared.generated.resources.source_count_one
import family_tree.app.shared.generated.resources.source_none_yet
import family_tree.app.shared.generated.resources.source_type_census
import family_tree.app.shared.generated.resources.source_type_church_record
import family_tree.app.shared.generated.resources.source_type_family_bible
import family_tree.app.shared.generated.resources.source_type_newspaper
import family_tree.app.shared.generated.resources.source_type_oral_history
import family_tree.app.shared.generated.resources.source_type_other
import family_tree.app.shared.generated.resources.source_type_vital_record
import family_tree.app.shared.generated.resources.source_unknown
import family_tree.app.shared.generated.resources.sources
import family_tree.app.shared.generated.resources.sources_empty
import family_tree.app.shared.generated.resources.sources_incomplete
import family_tree.app.shared.generated.resources.sources_private
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

    /** The mockup's "No source yet". */
    val noSource = Color(0xFFA9561A)
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
 * text only. The header shows the profile photo (FT-008) when the caller may see the
 * person, and the owner can add, change or remove it in place.
 *
 * Citations (FT-006 follow-up, read-only): timeline events say how many sources
 * support them and expand to list them; the Sources section lists every citation on
 * the person, their events and their unions with visible partners ([PersonSources]).
 * Citation scans are fetched only when opened. Source web addresses are inert text.
 * The photo gallery is not shown yet.
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
    val photos = controller.state.collectAsState().value.photos
    val sources = remember(person, viewer, events, screen.graph, screen.sources, screen.citations) {
        PersonSources.view(person, viewer, events, screen.graph, screen.sources, screen.citations)
    }
    val scans = ScanControls(
        treeId = screen.treeId,
        open = screen.openScans,
        photos = photos,
        onShow = controller::showScan,
        onHide = controller::hideScan,
        onRetry = controller::retry,
    )
    // FT-008: the server sends a photo ID only when this caller may see the person.
    val photo = person.profileMediaId?.takeIf { header.status != PersonStatus.PRIVATE }?.let { photos[PhotoKey(screen.treeId, it)] }
    val photoControls = PhotoControls(
        editable = viewer.canWrite && header.status != PersonStatus.PRIVATE,
        hasPhoto = person.profileMediaId != null,
        state = screen.photo,
        onChoose = controller::choosePhoto,
        onRemove = controller::removePhoto,
        onRetry = controller::retryPhoto,
        onDismiss = controller::dismissPhotoError,
    )

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 900.dp
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier.widthIn(max = 1280.dp).fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Breadcrumb(header, onBack = controller::backToTree)
                HeaderCard(header, facts, wide, photo, photoControls, onShowInTree = controller::showInTree)
                val story: @Composable () -> Unit = { StorySection(header) }
                val timelineSection: @Composable () -> Unit = {
                    TimelineSection(header, events, timeline, sources, scans, onRetry = controller::retry)
                }
                val family: @Composable () -> Unit = {
                    FamilySection(screen.graph, person, viewer, onOpen = controller::openPerson, onRetry = controller::retry)
                }
                val sourcesSection: @Composable () -> Unit = { SourcesSection(sources, scans, onRetry = controller::retry) }
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            story()
                            timelineSection()
                        }
                        Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            family()
                            sourcesSection()
                        }
                    }
                } else {
                    story()
                    timelineSection()
                    family()
                    sourcesSection()
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

/** FT-008: the owner's photo actions on the header, and the state of the last change. */
private class PhotoControls(
    val editable: Boolean,
    val hasPhoto: Boolean,
    val state: PhotoUpload?,
    val onChoose: () -> Unit,
    val onRemove: () -> Unit,
    val onRetry: () -> Unit,
    val onDismiss: () -> Unit,
)

@Composable
private fun HeaderCard(
    header: PersonHeader,
    facts: LifeFacts,
    wide: Boolean,
    photo: Loadable<ByteArray>?,
    controls: PhotoControls,
    onShowInTree: () -> Unit,
) {
    val content: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val avatar: @Composable () -> Unit = {
                Avatar(header.initials, size = 120, isPrivate = header.status == PersonStatus.PRIVATE)
            }
            val bytes = photo?.valueOrNull
            if (bytes != null && header.name != null) {
                StoredPhoto(bytes, stringResource(Res.string.photo_alt, header.name), 120, RoundedCornerShape(16.dp), fallback = avatar)
            } else {
                avatar()
            }
            if (photo is Loadable.Failed) Text(stringResource(Res.string.photo_load_failed), fontSize = 12.sp, color = Palette.muted)
            if (controls.editable) PhotoActions(controls)
        }
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

/**
 * FT-008: add/change/remove the profile photo, in the page (no dialogs), with the
 * localized progress, hint and error, and a retry that resumes the failed step.
 */
@Composable
private fun PhotoActions(controls: PhotoControls) {
    val state = controls.state
    val busy = state?.busy == true
    Column(Modifier.widthIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (busy) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(Res.string.photo_saving), fontSize = 12.sp, color = Palette.muted)
            }
        } else {
            // The click opens the browser's file chooser directly (a user gesture is required).
            TextButton(onClick = controls.onChoose) {
                Text(stringResource(if (controls.hasPhoto) Res.string.photo_change else Res.string.photo_add))
            }
            if (controls.hasPhoto) TextButton(onClick = controls.onRemove) { Text(stringResource(Res.string.photo_remove)) }
        }
        val error = state?.error
        if (error != null && !busy) {
            Text(stringResource(photoErrorMessage(error)), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state?.pending != null || state?.removing == true) TextButton(onClick = controls.onRetry) { Text(stringResource(Res.string.retry)) }
                TextButton(onClick = controls.onDismiss) { Text(stringResource(Res.string.cancel)) }
            }
        } else if (!busy) {
            Text(stringResource(Res.string.photo_hint), fontSize = 11.sp, color = Palette.muted)
        }
    }
}

private fun photoErrorMessage(error: PhotoError): StringResource = when (error) {
    PhotoError.UNSUPPORTED_TYPE -> Res.string.photo_error_type
    PhotoError.TOO_LARGE -> Res.string.photo_error_size
    PhotoError.INVALID_IMAGE -> Res.string.photo_error_invalid
    PhotoError.CHANGED -> Res.string.photo_error_changed
    PhotoError.UPLOAD_FAILED -> Res.string.photo_error_upload
    PhotoError.FORBIDDEN -> Res.string.error_forbidden
    PhotoError.NOT_FOUND -> Res.string.error_not_found
    PhotoError.UNAVAILABLE -> Res.string.error_unavailable
    PhotoError.SERVER -> Res.string.error_server
}

/** The photo placeholder: initials, or a blank tile for a private person. */
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
private fun TimelineSection(
    header: PersonHeader,
    events: Loadable<List<EventDto>>,
    timeline: Timeline?,
    sources: SourcesView,
    scans: ScanControls,
    onRetry: () -> Unit,
) {
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
                    // Null until this event's citations (and the source list) have loaded.
                    val citations = (sources as? SourcesView.Listed)
                        ?.takeIf { entry.eventId in it.loadedEvents }
                        ?.forEvent(entry.eventId)
                    TimelineRow(entry, citations, scans)
                    if (index < timeline.entries.lastIndex) HorizontalDivider(color = Palette.border)
                }
            }
            else -> SectionLoading()
        }
    }
}

@Composable
private fun TimelineRow(entry: TimelineEntry, citations: List<CitationEntry>?, scans: ScanControls) {
    var expanded by remember(entry.eventId) { mutableStateOf(false) }
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
            when {
                citations == null -> Unit
                citations.isEmpty() -> Text(
                    stringResource(Res.string.source_none_yet),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PersonColors.noSource,
                )
                else -> {
                    SourceCountChip(citations.size, expanded) { expanded = !expanded }
                    if (expanded) {
                        Column(Modifier.padding(start = 4.dp)) {
                            citations.forEach { CitationRow(it, showSupports = false, scans = scans) }
                        }
                    }
                }
            }
        }
    }
}

/** The mockup's "2 sources" pill: toggles the event's citations in place. */
@Composable
private fun SourceCountChip(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val label = if (count == 1) stringResource(Res.string.source_count_one) else stringResource(Res.string.source_count_many, count)
    Text(
        label,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = Palette.accent,
        modifier = Modifier
            .background(Palette.noticeBackground, RoundedCornerShape(999.dp))
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(if (expanded) Res.string.citations_hide else Res.string.citations_show),
                onClick = onToggle,
            )
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** FT-006 follow-up: what the citation rows need to open, show and close scans. */
private class ScanControls(
    val treeId: String,
    val open: List<String>,
    val photos: Map<PhotoKey, Loadable<ByteArray>>,
    val onShow: (String) -> Unit,
    val onHide: (String) -> Unit,
    val onRetry: () -> Unit,
)

/**
 * Every citation on the person, their timeline events and their unions with visible
 * partners. Loads and fails on its own: what arrived is listed, with a loading line
 * or a failure and retry below it.
 */
@Composable
private fun SourcesSection(view: SourcesView, scans: ScanControls, onRetry: () -> Unit) {
    SectionCard(Res.string.sources, small = true) {
        when (view) {
            // Decided from the person, not the responses: nothing is listed for a private person.
            SourcesView.Private -> Text(stringResource(Res.string.sources_private), color = Palette.muted)
            is SourcesView.Listed -> {
                view.entries.forEachIndexed { index, entry ->
                    CitationRow(entry, showSupports = true, scans = scans)
                    if (index < view.entries.lastIndex) HorizontalDivider(color = Palette.border)
                }
                if (view.isEmpty) Text(stringResource(Res.string.sources_empty), color = Palette.muted)
                if (view.incomplete) Text(stringResource(Res.string.sources_incomplete), fontSize = 13.sp, color = Palette.muted)
                view.failure?.let { SectionFailure(it, onRetry) }
                if (view.loading) SectionLoading()
            }
        }
    }
}

/**
 * One citation, read-only: the source's title, type and details, what it supports,
 * the cited detail and note, the confidence, and its scan on demand. All text is plain
 * `Text`; the source's web address is shown but never opened or linked.
 */
@Composable
private fun CitationRow(entry: CitationEntry, showSupports: Boolean, scans: ScanControls) {
    val title = entry.sourceTitle ?: stringResource(Res.string.source_unknown)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        val meta = buildList {
            entry.sourceType?.let { add(stringResource(sourceTypeLabel(it))) }
            addAll(entry.sourceDetails)
        }
        if (meta.isNotEmpty()) Text(meta.joinToString(" · "), fontSize = 12.sp, color = Palette.muted)
        if (showSupports) Text(stringResource(Res.string.citation_supports, factLabel(entry.fact)), fontSize = 12.sp, color = Palette.muted)
        entry.page?.let { Text(stringResource(Res.string.citation_detail, it), fontSize = 12.sp, color = Palette.muted) }
        entry.note?.let { Text(it, fontSize = 13.sp) }
        entry.sourceUrl?.let { Text(stringResource(Res.string.citation_web_address, it), fontSize = 12.sp, color = Palette.muted) }
        ConfidenceLine(entry.confidence)
        entry.mediaId?.let { ScanView(it, title, scans) }
    }
}

@Composable
private fun factLabel(fact: CitedFact): String = when (fact) {
    is CitedFact.Profile -> stringResource(Res.string.citation_supports_person)
    is CitedFact.Event -> listOfNotNull(stringResource(eventLabel(fact.type)), fact.date).joinToString(" · ")
    is CitedFact.Union -> {
        val type = stringResource(unionTypeLabel(fact.unionType))
        fact.partnerName?.let { stringResource(Res.string.citation_supports_union, type, it) } ?: type
    }
}

/** The mockup's three dots, filled up to the confidence, and the same as text. */
@Composable
private fun ConfidenceLine(confidence: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.clearAndSetSemantics {}) {
            repeat(PersonSources.MAX_CONFIDENCE) { index ->
                Box(Modifier.size(8.dp).background(if (index < confidence) Palette.accent else Palette.border, CircleShape))
            }
        }
        Text(
            stringResource(Res.string.citation_confidence, confidence, PersonSources.MAX_CONFIDENCE),
            fontSize = 12.sp,
            color = Palette.muted,
        )
    }
}

/** A citation scan: fetched only when the user opens it, then shown whole. */
@Composable
private fun ScanView(mediaId: String, sourceTitle: String, scans: ScanControls) {
    val open = mediaId in scans.open
    TextButton(onClick = { if (open) scans.onHide(mediaId) else scans.onShow(mediaId) }) {
        Text(stringResource(if (open) Res.string.scan_hide else Res.string.scan_show))
    }
    if (!open) return
    val failed: @Composable () -> Unit = {
        Text(stringResource(Res.string.scan_load_failed), fontSize = 12.sp, color = Palette.muted)
    }
    when (val image = scans.photos[PhotoKey(scans.treeId, mediaId)]) {
        is Loadable.Ready -> StoredPhoto(
            image.value,
            stringResource(Res.string.scan_alt, sourceTitle),
            280,
            RoundedCornerShape(8.dp),
            contentScale = ContentScale.Fit,
            fallback = failed,
        )
        is Loadable.Failed -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            failed()
            if (image.failure != ApiFailure.NOT_FOUND && image.failure != ApiFailure.FORBIDDEN) {
                OutlinedButton(onClick = scans.onRetry) { Text(stringResource(Res.string.retry)) }
            }
        }
        else -> SectionLoading()
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

private fun sourceTypeLabel(type: String): StringResource = when (type) {
    SourceType.VITAL_RECORD.name -> Res.string.source_type_vital_record
    SourceType.CENSUS.name -> Res.string.source_type_census
    SourceType.CHURCH_RECORD.name -> Res.string.source_type_church_record
    SourceType.NEWSPAPER.name -> Res.string.source_type_newspaper
    SourceType.FAMILY_BIBLE.name -> Res.string.source_type_family_bible
    SourceType.ORAL_HISTORY.name -> Res.string.source_type_oral_history
    else -> Res.string.source_type_other
}

internal fun eventLabel(type: String): StringResource = when (type) {
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
