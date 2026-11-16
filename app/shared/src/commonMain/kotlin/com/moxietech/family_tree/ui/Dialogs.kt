package com.moxietech.family_tree.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moxietech.family_tree.api.PersonDto
import com.moxietech.family_tree.domain.ChildRelation
import com.moxietech.family_tree.domain.Sex
import com.moxietech.family_tree.domain.UnionType
import com.moxietech.family_tree.state.FormState
import com.moxietech.family_tree.state.LinkChildInput
import com.moxietech.family_tree.state.PersonInput
import family_tree.app.shared.generated.resources.Res
import family_tree.app.shared.generated.resources.add_person
import family_tree.app.shared.generated.resources.cancel
import family_tree.app.shared.generated.resources.child
import family_tree.app.shared.generated.resources.child_relation
import family_tree.app.shared.generated.resources.choose_person
import family_tree.app.shared.generated.resources.given_names
import family_tree.app.shared.generated.resources.is_living
import family_tree.app.shared.generated.resources.link_child
import family_tree.app.shared.generated.resources.link_child_hint
import family_tree.app.shared.generated.resources.no_other_parent
import family_tree.app.shared.generated.resources.other_parent
import family_tree.app.shared.generated.resources.parent
import family_tree.app.shared.generated.resources.relation_adopted
import family_tree.app.shared.generated.resources.relation_birth
import family_tree.app.shared.generated.resources.relation_foster
import family_tree.app.shared.generated.resources.relation_step
import family_tree.app.shared.generated.resources.save
import family_tree.app.shared.generated.resources.sex
import family_tree.app.shared.generated.resources.sex_female
import family_tree.app.shared.generated.resources.sex_male
import family_tree.app.shared.generated.resources.sex_unknown
import family_tree.app.shared.generated.resources.surname_maternal
import family_tree.app.shared.generated.resources.surname_paternal
import family_tree.app.shared.generated.resources.union_divorced
import family_tree.app.shared.generated.resources.union_married
import family_tree.app.shared.generated.resources.union_partners
import family_tree.app.shared.generated.resources.union_type
import family_tree.app.shared.generated.resources.union_unknown
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Create a person (`POST …/people`); names are stored in their three parts. */
@Composable
internal fun AddPersonDialog(
    form: FormState,
    onSubmit: (PersonInput) -> Unit,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
) {
    var givenNames by remember { mutableStateOf("") }
    var paternal by remember { mutableStateOf("") }
    var maternal by remember { mutableStateOf("") }
    var sex by remember { mutableStateOf(Sex.UNKNOWN) }
    var living by remember { mutableStateOf(true) }
    val labels = sexLabels()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.add_person)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = givenNames,
                    onValueChange = {
                        givenNames = it
                        onEdit()
                    },
                    label = { Text(stringResource(Res.string.given_names)) },
                    singleLine = true,
                    isError = form.error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = paternal,
                    onValueChange = { paternal = it },
                    label = { Text(stringResource(Res.string.surname_paternal)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = maternal,
                    onValueChange = { maternal = it },
                    label = { Text(stringResource(Res.string.surname_maternal)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LabeledPicker(Res.string.sex) {
                    Picker(sex, Sex.entries, { labels.getValue(it) }, "", { sex = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = living, onCheckedChange = { living = it })
                    Text(stringResource(Res.string.is_living))
                }
                FormErrorText(form)
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(PersonInput(givenNames, paternal, maternal, sex, living)) },
                enabled = !form.submitting,
            ) { Text(stringResource(Res.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/**
 * Link a child to one or two parents. The controller reuses the parents'
 * existing union, or creates one (`POST …/unions`), then adds the child
 * (`POST …/unions/{id}/children`).
 */
@Composable
internal fun LinkChildDialog(
    people: List<PersonDto>,
    label: (PersonDto) -> String,
    form: FormState,
    onSubmit: (LinkChildInput) -> Unit,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
) {
    var parent by remember { mutableStateOf<PersonDto?>(null) }
    var otherParent by remember { mutableStateOf<PersonDto?>(null) }
    var unionType by remember { mutableStateOf(UnionType.MARRIED) }
    var child by remember { mutableStateOf<PersonDto?>(null) }
    var relation by remember { mutableStateOf(ChildRelation.BIRTH) }
    val choose = stringResource(Res.string.choose_person)
    val none = stringResource(Res.string.no_other_parent)
    val unionLabels = unionTypeLabels()
    val relationLabels = relationLabels()
    // null in this list stands for "no other parent".
    val otherOptions: List<PersonDto?> = listOf<PersonDto?>(null) + people

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.link_child)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledPicker(Res.string.parent) {
                    Picker(parent, people, label, choose, {
                        parent = it
                        onEdit()
                    })
                }
                LabeledPicker(Res.string.other_parent) {
                    Picker(otherParent, otherOptions, { it?.let(label) ?: none }, none, {
                        otherParent = it
                        onEdit()
                    })
                }
                LabeledPicker(Res.string.union_type) {
                    Picker(unionType, UnionType.entries, { unionLabels.getValue(it) }, "", { unionType = it })
                }
                LabeledPicker(Res.string.child) {
                    Picker(child, people, label, choose, {
                        child = it
                        onEdit()
                    })
                }
                LabeledPicker(Res.string.child_relation) {
                    Picker(relation, ChildRelation.entries, { relationLabels.getValue(it) }, "", { relation = it })
                }
                Text(stringResource(Res.string.link_child_hint), fontSize = 13.sp, color = Palette.muted)
                FormErrorText(form)
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(LinkChildInput(parent?.id, otherParent?.id, unionType, child?.id, relation)) },
                enabled = !form.submitting,
            ) { Text(stringResource(Res.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

@Composable
private fun LabeledPicker(title: StringResource, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(title), fontSize = 13.sp, color = Palette.muted)
        content()
    }
}

@Composable
private fun FormErrorText(form: FormState) {
    form.error?.let { Text(stringResource(formErrorMessage(it)), color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun sexLabels(): Map<Sex, String> = mapOf(
    Sex.FEMALE to stringResource(Res.string.sex_female),
    Sex.MALE to stringResource(Res.string.sex_male),
    Sex.UNKNOWN to stringResource(Res.string.sex_unknown),
)

@Composable
private fun unionTypeLabels(): Map<UnionType, String> = mapOf(
    UnionType.MARRIED to stringResource(Res.string.union_married),
    UnionType.DIVORCED to stringResource(Res.string.union_divorced),
    UnionType.PARTNERS to stringResource(Res.string.union_partners),
    UnionType.UNKNOWN to stringResource(Res.string.union_unknown),
)

@Composable
private fun relationLabels(): Map<ChildRelation, String> = mapOf(
    ChildRelation.BIRTH to stringResource(Res.string.relation_birth),
    ChildRelation.ADOPTED to stringResource(Res.string.relation_adopted),
    ChildRelation.STEP to stringResource(Res.string.relation_step),
    ChildRelation.FOSTER to stringResource(Res.string.relation_foster),
)
