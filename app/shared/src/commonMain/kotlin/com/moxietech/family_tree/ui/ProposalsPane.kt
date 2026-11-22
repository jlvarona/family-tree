package com.moxietech.family_tree.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moxietech.family_tree.api.ChangeProposalDto
import com.moxietech.family_tree.api.PersonDto
import com.moxietech.family_tree.api.ProposalFieldDto
import com.moxietech.family_tree.api.TreeGraphDto
import com.moxietech.family_tree.domain.ChildRelation
import com.moxietech.family_tree.domain.Sex
import com.moxietech.family_tree.state.FamilyTreeController
import com.moxietech.family_tree.state.Loadable
import com.moxietech.family_tree.state.PersonProfile
import com.moxietech.family_tree.state.ProposalError
import com.moxietech.family_tree.state.ProposalOutcome
import com.moxietech.family_tree.state.ProposalsState
import com.moxietech.family_tree.state.ProposalContext
import com.moxietech.family_tree.state.ProposalSubject
import family_tree.app.shared.generated.resources.Res
import family_tree.app.shared.generated.resources.deceased
import family_tree.app.shared.generated.resources.error_forbidden
import family_tree.app.shared.generated.resources.error_invalid
import family_tree.app.shared.generated.resources.error_not_found
import family_tree.app.shared.generated.resources.error_server
import family_tree.app.shared.generated.resources.error_unavailable
import family_tree.app.shared.generated.resources.given_names
import family_tree.app.shared.generated.resources.life_story
import family_tree.app.shared.generated.resources.living
import family_tree.app.shared.generated.resources.loading
import family_tree.app.shared.generated.resources.merge_living_status
import family_tree.app.shared.generated.resources.partners
import family_tree.app.shared.generated.resources.proposal_about
import family_tree.app.shared.generated.resources.proposal_approve
import family_tree.app.shared.generated.resources.proposal_approve_selected
import family_tree.app.shared.generated.resources.proposal_blank
import family_tree.app.shared.generated.resources.proposal_changed
import family_tree.app.shared.generated.resources.proposal_child_in
import family_tree.app.shared.generated.resources.proposal_current
import family_tree.app.shared.generated.resources.proposal_event_of
import family_tree.app.shared.generated.resources.proposal_error_changed
import family_tree.app.shared.generated.resources.proposal_error_no_fields
import family_tree.app.shared.generated.resources.proposal_field_event_date
import family_tree.app.shared.generated.resources.proposal_field_event_notes
import family_tree.app.shared.generated.resources.proposal_field_event_place
import family_tree.app.shared.generated.resources.proposal_field_event_subject
import family_tree.app.shared.generated.resources.proposal_field_event_type
import family_tree.app.shared.generated.resources.proposal_field_record
import family_tree.app.shared.generated.resources.proposal_field_relation
import family_tree.app.shared.generated.resources.proposal_field_union_dates
import family_tree.app.shared.generated.resources.proposal_field_union_type
import family_tree.app.shared.generated.resources.proposal_op_add_child
import family_tree.app.shared.generated.resources.proposal_op_create_citation
import family_tree.app.shared.generated.resources.proposal_op_create_event
import family_tree.app.shared.generated.resources.proposal_op_create_union
import family_tree.app.shared.generated.resources.proposal_op_delete_child
import family_tree.app.shared.generated.resources.proposal_op_delete_event
import family_tree.app.shared.generated.resources.proposal_op_delete_person
import family_tree.app.shared.generated.resources.proposal_op_delete_union
import family_tree.app.shared.generated.resources.proposal_op_other
import family_tree.app.shared.generated.resources.proposal_op_update_child
import family_tree.app.shared.generated.resources.proposal_op_update_event
import family_tree.app.shared.generated.resources.proposal_op_update_person
import family_tree.app.shared.generated.resources.proposal_op_update_union
import family_tree.app.shared.generated.resources.proposal_other_record
import family_tree.app.shared.generated.resources.proposal_outcome_applied
import family_tree.app.shared.generated.resources.proposal_outcome_rejected
import family_tree.app.shared.generated.resources.proposal_outcome_withdrawn
import family_tree.app.shared.generated.resources.proposal_proposed
import family_tree.app.shared.generated.resources.proposal_reason_outside_branch
import family_tree.app.shared.generated.resources.proposal_reason_reveals_private
import family_tree.app.shared.generated.resources.proposal_reason_widens_reach
import family_tree.app.shared.generated.resources.proposal_reject
import family_tree.app.shared.generated.resources.proposal_select_fields
import family_tree.app.shared.generated.resources.proposal_stale
import family_tree.app.shared.generated.resources.proposal_status_applied
import family_tree.app.shared.generated.resources.proposal_status_pending
import family_tree.app.shared.generated.resources.proposal_status_rejected
import family_tree.app.shared.generated.resources.proposal_status_withdrawn
import family_tree.app.shared.generated.resources.proposal_unchanged
import family_tree.app.shared.generated.resources.proposal_union_of
import family_tree.app.shared.generated.resources.proposal_withdraw
import family_tree.app.shared.generated.resources.proposals_close
import family_tree.app.shared.generated.resources.proposals_empty
import family_tree.app.shared.generated.resources.proposals_intro
import family_tree.app.shared.generated.resources.proposals_refresh
import family_tree.app.shared.generated.resources.proposals_title
import family_tree.app.shared.generated.resources.relation_adopted
import family_tree.app.shared.generated.resources.relation_birth
import family_tree.app.shared.generated.resources.relation_foster
import family_tree.app.shared.generated.resources.relation_step
import family_tree.app.shared.generated.resources.retry
import family_tree.app.shared.generated.resources.sex
import family_tree.app.shared.generated.resources.sex_female
import family_tree.app.shared.generated.resources.sex_male
import family_tree.app.shared.generated.resources.sex_unknown
import family_tree.app.shared.generated.resources.surname_maternal
import family_tree.app.shared.generated.resources.surname_paternal
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * FT-014: the change-proposal panel on the tree screen. It renders what the server sent
 * and decides nothing itself:
 * - only proposals the server listed for this account (its own, or ones it may review);
 * - proposed values (the author's input) and, only when the server included them, the
 *   values stored now with a "changed" mark; a hidden person's or event's values never
 *   reach the client, so nothing is guessed here;
 * - Approve / Reject only when the server set `canReview`, Withdraw only with
 *   `canWithdraw`; updates show a checkbox per field (grouped fields such as a union's
 *   partners come as one field), and the approval sends the proposal's version, so a
 *   proposal decided or a record changed meanwhile is a `409` that reloads the list.
 * All values are plain text after [PersonProfile.cleanText]; IDs show as the person's
 * name (or the localized private label) from the tree, never as raw identifiers.
 */
@Composable
internal fun ProposalsPane(
    panel: ProposalsState,
    graph: TreeGraphDto,
    label: (PersonDto) -> String,
    controller: FamilyTreeController,
) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), contentAlignment = Alignment.TopCenter) {
        Surface(
            modifier = Modifier.widthIn(max = 820.dp).fillMaxWidth(),
            color = Palette.surface,
            border = BorderStroke(1.dp, Palette.border),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(Res.string.proposals_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(Res.string.proposals_intro), color = Palette.muted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = controller::closeProposals, enabled = panel.busyId == null) {
                        Text(stringResource(Res.string.proposals_close))
                    }
                    TextButton(onClick = controller::refreshProposals, enabled = panel.busyId == null) {
                        Text(stringResource(Res.string.proposals_refresh))
                    }
                }
                panel.outcome?.let { Text(stringResource(outcomeMessage(it)), color = Palette.accent) }
                panel.error?.let { Text(stringResource(proposalErrorMessage(it)), color = MaterialTheme.colorScheme.error) }
                when (val list = panel.list) {
                    Loadable.Loading -> Text(stringResource(Res.string.loading), color = Palette.muted)
                    is Loadable.Failed -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(failureMessage(list.failure)), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = controller::refreshProposals) { Text(stringResource(Res.string.retry)) }
                    }
                    is Loadable.Ready ->
                        if (list.value.isEmpty()) {
                            Text(stringResource(Res.string.proposals_empty), color = Palette.muted)
                        } else {
                            list.value.forEach { proposal ->
                                HorizontalDivider(color = Palette.border)
                                ProposalCard(proposal, panel, graph, label, controller)
                            }
                        }
                }
            }
        }
    }
}

@Composable
private fun ProposalCard(
    proposal: ChangeProposalDto,
    panel: ProposalsState,
    graph: TreeGraphDto,
    label: (PersonDto) -> String,
    controller: FamilyTreeController,
) {
    val pending = proposal.status == "PENDING"
    val busy = panel.busyId != null
    val selectable = pending && proposal.canReview && FamilyTreeController.isPerField(proposal)
    val selected = panel.selected[proposal.id].orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(operationLabel(proposal.operation)), fontWeight = FontWeight.SemiBold)
        Text(
            listOf(stringResource(statusLabel(proposal.status)), stringResource(reasonLabel(proposal.reason))).joinToString(" · "),
            color = Palette.muted,
            fontSize = 13.sp,
        )
        targetText(proposal, graph, label)?.let { Text(stringResource(Res.string.proposal_about, it), fontSize = 13.sp) }
        if (proposal.stale == true && pending) {
            Text(stringResource(Res.string.proposal_stale), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }
        if (selectable) Text(stringResource(Res.string.proposal_select_fields), fontSize = 13.sp, color = Palette.muted)
        proposal.fields.forEach { field ->
            FieldRow(proposal, field, selectable, field.field in selected, busy, graph, label, controller)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pending && proposal.canReview) {
                Button(onClick = { controller.approveProposal(proposal.id) }, enabled = !busy) {
                    Text(stringResource(if (selectable) Res.string.proposal_approve_selected else Res.string.proposal_approve))
                }
                OutlinedButton(onClick = { controller.rejectProposal(proposal.id) }, enabled = !busy) {
                    Text(stringResource(Res.string.proposal_reject))
                }
            }
            if (pending && proposal.canWithdraw) {
                OutlinedButton(onClick = { controller.withdrawProposal(proposal.id) }, enabled = !busy) {
                    Text(stringResource(Res.string.proposal_withdraw))
                }
            }
        }
        if (panel.busyId == proposal.id) Text(stringResource(Res.string.loading), color = Palette.muted, fontSize = 13.sp)
    }
}

@Composable
private fun FieldRow(
    proposal: ChangeProposalDto,
    field: ProposalFieldDto,
    selectable: Boolean,
    checked: Boolean,
    busy: Boolean,
    graph: TreeGraphDto,
    label: (PersonDto) -> String,
    controller: FamilyTreeController,
) {
    val proposed = valuesText(field.proposed, proposal, graph, label)
    val current = (field.current as? JsonObject)?.let { valuesText(it, proposal, graph, label) }
    val content: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(fieldLabel(field.field)), fontWeight = FontWeight.Medium)
            Text(stringResource(Res.string.proposal_proposed, proposed), fontSize = 13.sp)
            if (current != null) Text(stringResource(Res.string.proposal_current, current), fontSize = 13.sp, color = Palette.muted)
            field.changed?.let {
                Text(stringResource(if (it) Res.string.proposal_changed else Res.string.proposal_unchanged), fontSize = 12.sp, color = Palette.muted)
            }
        }
    }
    if (selectable) {
        // One toggleable row with the checkbox role: the text is the checkbox's accessible name.
        Row(
            Modifier.fillMaxWidth().toggleable(
                value = checked,
                enabled = !busy,
                role = Role.Checkbox,
                onValueChange = { controller.toggleProposalField(proposal.id, field.field) },
            ),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(checked = checked, onCheckedChange = null, enabled = !busy)
            content()
        }
    } else {
        content()
    }
}

/** The proposal's target (the record being edited or removed), described; null for creates. */
@Composable
private fun targetText(proposal: ChangeProposalDto, graph: TreeGraphDto, label: (PersonDto) -> String): String? =
    ProposalContext.target(proposal, graph)?.let { describe(it, label) }

/**
 * A record a proposal names, from [ProposalContext]: names from the tree, places, sources
 * and events from the server's redacted references, "another record" for anything else.
 */
@Composable
private fun describe(subject: ProposalSubject, label: (PersonDto) -> String): String {
    val other = stringResource(Res.string.proposal_other_record)
    return when (subject) {
        is ProposalSubject.OfPerson -> label(subject.person)
        is ProposalSubject.OfUnion -> stringResource(Res.string.proposal_union_of, subject.partners.joinToString(" & ") { label(it) })
        is ProposalSubject.OfChildLink -> stringResource(
            Res.string.proposal_child_in,
            subject.child?.let(label) ?: other,
            subject.union?.let { describe(it, label) } ?: other,
        )
        is ProposalSubject.OfEvent -> {
            val type = stringResource(eventLabel(subject.type))
            val named = subject.subject?.let { stringResource(Res.string.proposal_event_of, type, describe(it, label)) } ?: type
            listOfNotNull(named, PersonProfile.cleanText(subject.date)?.takeIf { it.isNotBlank() }).joinToString(" · ")
        }
        is ProposalSubject.OfPlace -> listOfNotNull(
            PersonProfile.cleanText(subject.name),
            PersonProfile.cleanText(subject.hierarchy)?.takeIf { it.isNotBlank() },
        ).joinToString(" · ").ifBlank { other }
        is ProposalSubject.OfSource -> PersonProfile.cleanText(subject.title)?.takeIf { it.isNotBlank() } ?: other
        ProposalSubject.Unknown -> other
    }
}

/** The values of one field (one or more request keys), localized and joined. */
@Composable
private fun valuesText(values: JsonObject, proposal: ChangeProposalDto, graph: TreeGraphDto, label: (PersonDto) -> String): String {
    // A citation's target kind is implied by the target it names.
    val shown = values.entries.filter { it.key != "targetType" }
    if (shown.isEmpty()) return stringResource(Res.string.proposal_blank)
    return shown.map { (key, value) -> valueText(key, value, proposal, graph, label) }.joinToString(" · ")
}

@Composable
private fun valueText(key: String, value: JsonElement, proposal: ChangeProposalDto, graph: TreeGraphDto, label: (PersonDto) -> String): String {
    val operation = proposal.operation
    val raw = (value as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
    if (raw.isNullOrEmpty()) return stringResource(Res.string.proposal_blank)
    return when {
        key == "isLiving" -> stringResource(if (raw == "true") Res.string.living else Res.string.deceased)
        key == "sex" -> stringResource(
            when (raw) {
                Sex.FEMALE.name -> Res.string.sex_female
                Sex.MALE.name -> Res.string.sex_male
                else -> Res.string.sex_unknown
            },
        )
        key == "type" && "UNION" in operation -> stringResource(unionTypeLabel(raw))
        key == "type" && "EVENT" in operation -> stringResource(eventLabel(raw))
        key == "relation" -> stringResource(
            when (raw) {
                ChildRelation.ADOPTED.name -> Res.string.relation_adopted
                ChildRelation.STEP.name -> Res.string.relation_step
                ChildRelation.FOSTER.name -> Res.string.relation_foster
                else -> Res.string.relation_birth
            },
        )
        // People and unions from the tree; places, sources and events from the server's
        // redacted references; never a raw ID on screen.
        key.endsWith("Id") -> describe(ProposalContext.reference(raw, proposal, graph), label)
        else -> PersonProfile.cleanText(raw) ?: stringResource(Res.string.proposal_blank)
    }
}

private fun operationLabel(operation: String): StringResource = when (operation) {
    "UPDATE_PERSON" -> Res.string.proposal_op_update_person
    "DELETE_PERSON" -> Res.string.proposal_op_delete_person
    "CREATE_UNION" -> Res.string.proposal_op_create_union
    "UPDATE_UNION" -> Res.string.proposal_op_update_union
    "DELETE_UNION" -> Res.string.proposal_op_delete_union
    "ADD_CHILD" -> Res.string.proposal_op_add_child
    "UPDATE_CHILD" -> Res.string.proposal_op_update_child
    "DELETE_CHILD" -> Res.string.proposal_op_delete_child
    "CREATE_EVENT" -> Res.string.proposal_op_create_event
    "UPDATE_EVENT" -> Res.string.proposal_op_update_event
    "DELETE_EVENT" -> Res.string.proposal_op_delete_event
    "CREATE_CITATION" -> Res.string.proposal_op_create_citation
    else -> Res.string.proposal_op_other
}

private fun statusLabel(status: String): StringResource = when (status) {
    "APPLIED" -> Res.string.proposal_status_applied
    "REJECTED" -> Res.string.proposal_status_rejected
    "WITHDRAWN" -> Res.string.proposal_status_withdrawn
    else -> Res.string.proposal_status_pending
}

private fun reasonLabel(reason: String): StringResource = when (reason) {
    "WIDENS_REACH" -> Res.string.proposal_reason_widens_reach
    "REVEALS_PRIVATE" -> Res.string.proposal_reason_reveals_private
    else -> Res.string.proposal_reason_outside_branch
}

private fun fieldLabel(field: String): StringResource = when (field) {
    "GIVEN_NAMES" -> Res.string.given_names
    "SURNAME_PATERNAL" -> Res.string.surname_paternal
    "SURNAME_MATERNAL" -> Res.string.surname_maternal
    "SEX" -> Res.string.sex
    "IS_LIVING" -> Res.string.merge_living_status
    "BIO" -> Res.string.life_story
    "PARTNERS" -> Res.string.partners
    "UNION_TYPE" -> Res.string.proposal_field_union_type
    "UNION_DATES" -> Res.string.proposal_field_union_dates
    "RELATION" -> Res.string.proposal_field_relation
    "EVENT_SUBJECT" -> Res.string.proposal_field_event_subject
    "EVENT_TYPE" -> Res.string.proposal_field_event_type
    "EVENT_DATE" -> Res.string.proposal_field_event_date
    "EVENT_PLACE" -> Res.string.proposal_field_event_place
    "EVENT_NOTES" -> Res.string.proposal_field_event_notes
    else -> Res.string.proposal_field_record
}

private fun outcomeMessage(outcome: ProposalOutcome): StringResource = when (outcome) {
    ProposalOutcome.APPLIED -> Res.string.proposal_outcome_applied
    ProposalOutcome.REJECTED -> Res.string.proposal_outcome_rejected
    ProposalOutcome.WITHDRAWN -> Res.string.proposal_outcome_withdrawn
}

internal fun proposalErrorMessage(error: ProposalError): StringResource = when (error) {
    ProposalError.CHANGED -> Res.string.proposal_error_changed
    ProposalError.NO_FIELDS -> Res.string.proposal_error_no_fields
    ProposalError.FORBIDDEN -> Res.string.error_forbidden
    ProposalError.NOT_FOUND -> Res.string.error_not_found
    ProposalError.INVALID -> Res.string.error_invalid
    ProposalError.UNAVAILABLE -> Res.string.error_unavailable
    ProposalError.SERVER -> Res.string.error_server
}
