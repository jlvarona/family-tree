package com.moxietech.family_tree.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import com.moxietech.family_tree.api.PersonDto
import com.moxietech.family_tree.api.TreeGraphDto
import com.moxietech.family_tree.domain.Sex
import com.moxietech.family_tree.merge.MergeConflict
import com.moxietech.family_tree.merge.MergeField
import com.moxietech.family_tree.merge.PersonMerge
import com.moxietech.family_tree.state.FamilyTreeController
import com.moxietech.family_tree.state.Loadable
import com.moxietech.family_tree.state.MergeError
import com.moxietech.family_tree.state.MergeRecords
import com.moxietech.family_tree.state.MergeState
import com.moxietech.family_tree.state.PersonProfile
import com.moxietech.family_tree.state.Viewer
import family_tree.app.shared.generated.resources.Res
import family_tree.app.shared.generated.resources.cancel
import family_tree.app.shared.generated.resources.choose_person
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
import family_tree.app.shared.generated.resources.merge_blank
import family_tree.app.shared.generated.resources.merge_blocked
import family_tree.app.shared.generated.resources.merge_conflict_ancestor
import family_tree.app.shared.generated.resources.merge_conflict_parent
import family_tree.app.shared.generated.resources.merge_conflict_partners
import family_tree.app.shared.generated.resources.merge_conflict_relation
import family_tree.app.shared.generated.resources.merge_confirm
import family_tree.app.shared.generated.resources.merge_error_changed
import family_tree.app.shared.generated.resources.merge_error_choices
import family_tree.app.shared.generated.resources.merge_error_confirm
import family_tree.app.shared.generated.resources.merge_error_same
import family_tree.app.shared.generated.resources.merge_differs_invisibly
import family_tree.app.shared.generated.resources.merge_error_survivor
import family_tree.app.shared.generated.resources.merge_keep_value
import family_tree.app.shared.generated.resources.merge_record_label
import family_tree.app.shared.generated.resources.merge_first_record
import family_tree.app.shared.generated.resources.merge_intro
import family_tree.app.shared.generated.resources.merge_keep_record
import family_tree.app.shared.generated.resources.merge_living_status
import family_tree.app.shared.generated.resources.merge_result_name
import family_tree.app.shared.generated.resources.merge_same_value
import family_tree.app.shared.generated.resources.merge_second_record
import family_tree.app.shared.generated.resources.merge_submit
import family_tree.app.shared.generated.resources.merge_title
import family_tree.app.shared.generated.resources.merge_values
import family_tree.app.shared.generated.resources.retry
import family_tree.app.shared.generated.resources.sex
import family_tree.app.shared.generated.resources.sex_female
import family_tree.app.shared.generated.resources.sex_male
import family_tree.app.shared.generated.resources.sex_unknown
import family_tree.app.shared.generated.resources.surname_maternal
import family_tree.app.shared.generated.resources.surname_paternal
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * FT-007: the owner reviews two records of one person side by side, chooses the one
 * to keep, chooses a value for every field that differs (equal fields are shown and
 * kept), and confirms which record is removed. All text from the records is plain
 * text after [PersonProfile.cleanText]; names are formatted through `:core`.
 */
@Composable
internal fun MergePeoplePane(
    merge: MergeState,
    graph: TreeGraphDto,
    label: (PersonDto) -> String,
    controller: FamilyTreeController,
) {
    val records = merge.records
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), contentAlignment = Alignment.TopCenter) {
        Surface(
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
            color = Palette.surface,
            border = BorderStroke(1.dp, Palette.border),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(stringResource(Res.string.merge_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(Res.string.merge_intro), color = Palette.muted, fontSize = 13.sp)
                PeoplePickers(merge, graph, label, controller)
                when (records) {
                    null -> Unit
                    Loadable.Loading -> Text(stringResource(Res.string.loading), color = Palette.muted)
                    is Loadable.Failed -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(failureMessage(records.failure)), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = controller::retry) { Text(stringResource(Res.string.retry)) }
                    }
                    is Loadable.Ready -> Review(merge, records.value, graph, label, controller)
                }
                merge.error?.let { Text(stringResource(mergeErrorMessage(it)), color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = controller::cancelMerge, enabled = !merge.submitting) { Text(stringResource(Res.string.cancel)) }
                    Button(
                        onClick = controller::submitMerge,
                        enabled = !merge.submitting && records is Loadable.Ready,
                    ) { Text(stringResource(Res.string.merge_submit)) }
                }
            }
        }
    }
}

@Composable
private fun PeoplePickers(merge: MergeState, graph: TreeGraphDto, label: (PersonDto) -> String, controller: FamilyTreeController) {
    val people = graph.people
    val choose = stringResource(Res.string.choose_person)
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(Res.string.merge_first_record), fontSize = 13.sp, color = Palette.muted)
            Picker(
                selected = people.firstOrNull { it.id == merge.firstId },
                options = people.filter { it.id != merge.secondId },
                label = label,
                placeholder = choose,
                onSelect = { controller.chooseFirstForMerge(it.id) },
                enabled = !merge.submitting,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(Res.string.merge_second_record), fontSize = 13.sp, color = Palette.muted)
            Picker(
                selected = people.firstOrNull { it.id == merge.secondId },
                options = people.filter { it.id != merge.firstId },
                label = label,
                placeholder = choose,
                onSelect = { controller.chooseSecondForMerge(it.id) },
                enabled = !merge.submitting,
            )
        }
    }
}

@Composable
private fun Review(
    merge: MergeState,
    records: MergeRecords,
    graph: TreeGraphDto,
    label: (PersonDto) -> String,
    controller: FamilyTreeController,
) {
    val first = records.first
    val second = records.second
    val differing = FamilyTreeController.differingFields(first, second)
    val survivor = records.byId(merge.survivorId)
    val retired = records.other(merge.survivorId)
    // Numbered labels ("Record 1: Ana") tell the two records apart even when their names match.
    val firstLabel = stringResource(Res.string.merge_record_label, 1, label(first))
    val secondLabel = stringResource(Res.string.merge_record_label, 2, label(second))
    fun recordLabel(person: PersonDto) = if (person.id == first.id) firstLabel else secondLabel
    fun recordNumber(person: PersonDto) = if (person.id == first.id) 1 else 2

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.merge_keep_record), fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(first, second).forEach { person ->
                FilterChip(
                    selected = merge.survivorId == person.id,
                    onClick = { controller.chooseSurvivor(person.id) },
                    enabled = !merge.submitting,
                    label = { Text(recordLabel(person)) },
                )
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(Res.string.merge_values), fontWeight = FontWeight.SemiBold)
        Row {
            Spacer(Modifier.width(FIELD_WIDTH))
            Text(firstLabel, Modifier.weight(1f).padding(end = 12.dp), fontSize = 13.sp, color = Palette.muted)
            Text(secondLabel, Modifier.weight(1f), fontSize = 13.sp, color = Palette.muted)
        }
        HorizontalDivider(color = Palette.border)
        MergeField.entries.forEach { field ->
            Row(verticalAlignment = Alignment.Top) {
                Text(stringResource(fieldLabel(field)), Modifier.width(FIELD_WIDTH), fontSize = 13.sp)
                if (field in differing) {
                    // Every difference needs a deliberate choice, including blank against a value.
                    // Each value is shown in full, as plain text in the page, above its choice:
                    // long bios that start alike are told apart by their endings.
                    listOf(first, second).forEach { person ->
                        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(valueText(person, field), fontSize = 13.sp)
                            FilterChip(
                                selected = merge.choices[field] == person.id,
                                onClick = { controller.chooseMergeValue(field, person.id) },
                                enabled = !merge.submitting,
                                label = { Text(stringResource(Res.string.merge_keep_value, recordNumber(person))) },
                            )
                        }
                    }
                } else {
                    Text(
                        stringResource(Res.string.merge_same_value, valueText(first, field)),
                        Modifier.weight(2f),
                        fontSize = 13.sp,
                        color = Palette.muted,
                    )
                }
            }
            if (field in differing && valueText(first, field) == valueText(second, field)) {
                // Stored values that differ only in spacing or removed control characters.
                Text(
                    stringResource(Res.string.merge_differs_invisibly),
                    Modifier.padding(start = FIELD_WIDTH),
                    fontSize = 12.sp,
                    color = Palette.muted,
                )
            }
            HorizontalDivider(color = Palette.subtle)
        }
    }

    if (survivor != null && retired != null) {
        val result = resultName(survivor, retired, merge, differing)
        if (result != null) Text(stringResource(Res.string.merge_result_name, result), fontSize = 13.sp)

        val conflicts = PersonMerge.relationshipConflicts(survivor.id, retired.id, graph.unions)
        if (conflicts.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(Res.string.merge_blocked), color = MaterialTheme.colorScheme.error)
                conflicts.forEach { Text("• " + stringResource(conflictMessage(it)), color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            }
        }

        // The whole row is one checkbox whose accessible name is the confirmation text:
        // clicking or pressing Space on the text toggles it, and screen readers announce both.
        Row(
            Modifier.fillMaxWidth().toggleable(
                value = merge.retiredConfirmed,
                enabled = !merge.submitting,
                role = Role.Checkbox,
                onValueChange = controller::confirmRetired,
            ).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = merge.retiredConfirmed, onCheckedChange = null, enabled = !merge.submitting)
            Text(stringResource(Res.string.merge_confirm, recordLabel(retired), recordLabel(survivor)))
        }
    }
}

/** The kept record's formatted name, once every name part that differs has been chosen. */
private fun resultName(survivor: PersonDto, retired: PersonDto, merge: MergeState, differing: Set<MergeField>): String? {
    val nameFields = listOf(MergeField.GIVEN_NAMES, MergeField.SURNAME_PATERNAL, MergeField.SURNAME_MATERNAL)
    fun source(field: MergeField): PersonDto? = if (field !in differing) survivor else when (merge.choices[field]) {
        survivor.id -> survivor
        retired.id -> retired
        else -> null
    }
    if (nameFields.any { source(it) == null }) return null
    val name = Viewer.fullName(
        survivor.copy(
            givenNames = source(MergeField.GIVEN_NAMES)!!.givenNames,
            surnamePaternal = source(MergeField.SURNAME_PATERNAL)!!.surnamePaternal,
            surnameMaternal = source(MergeField.SURNAME_MATERNAL)!!.surnameMaternal,
        ),
    )
    return PersonProfile.cleanText(name)
}

@Composable
private fun valueText(person: PersonDto, field: MergeField): String {
    val blank = stringResource(Res.string.merge_blank)
    // Plain text with its line breaks and inner spacing kept, so both complete values can be read.
    fun text(value: String?) = PersonProfile.cleanText(value) ?: blank
    return when (field) {
        MergeField.GIVEN_NAMES -> text(person.givenNames)
        MergeField.SURNAME_PATERNAL -> text(person.surnamePaternal)
        MergeField.SURNAME_MATERNAL -> text(person.surnameMaternal)
        MergeField.SEX -> stringResource(
            when (person.sex) {
                Sex.FEMALE.name -> Res.string.sex_female
                Sex.MALE.name -> Res.string.sex_male
                else -> Res.string.sex_unknown
            },
        )
        MergeField.IS_LIVING -> stringResource(if (person.isLiving) Res.string.living else Res.string.deceased)
        MergeField.BIO -> text(person.bio)
    }
}

private fun fieldLabel(field: MergeField): StringResource = when (field) {
    MergeField.GIVEN_NAMES -> Res.string.given_names
    MergeField.SURNAME_PATERNAL -> Res.string.surname_paternal
    MergeField.SURNAME_MATERNAL -> Res.string.surname_maternal
    MergeField.SEX -> Res.string.sex
    MergeField.IS_LIVING -> Res.string.merge_living_status
    MergeField.BIO -> Res.string.life_story
}

private fun conflictMessage(conflict: MergeConflict): StringResource = when (conflict) {
    MergeConflict.PARTNERS_OF_EACH_OTHER -> Res.string.merge_conflict_partners
    MergeConflict.PARENT_AND_CHILD -> Res.string.merge_conflict_parent
    MergeConflict.ANCESTOR_AND_DESCENDANT -> Res.string.merge_conflict_ancestor
    MergeConflict.CHILD_LINK_RELATION_DIFFERS -> Res.string.merge_conflict_relation
}

internal fun mergeErrorMessage(error: MergeError): StringResource = when (error) {
    MergeError.SAME_PERSON -> Res.string.merge_error_same
    MergeError.SURVIVOR_REQUIRED -> Res.string.merge_error_survivor
    MergeError.CHOICES_REQUIRED -> Res.string.merge_error_choices
    MergeError.CONFIRMATION_REQUIRED -> Res.string.merge_error_confirm
    MergeError.RELATIONSHIPS -> Res.string.merge_blocked
    MergeError.CHANGED -> Res.string.merge_error_changed
    MergeError.FORBIDDEN -> Res.string.error_forbidden
    MergeError.NOT_FOUND -> Res.string.error_not_found
    MergeError.INVALID -> Res.string.error_invalid
    MergeError.UNAVAILABLE -> Res.string.error_unavailable
    MergeError.SERVER -> Res.string.error_server
}

private val FIELD_WIDTH = 180.dp
