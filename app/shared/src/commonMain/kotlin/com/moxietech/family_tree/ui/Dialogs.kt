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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moxietech.family_tree.api.PersonDto
import com.moxietech.family_tree.api.TreeGraphDto
import com.moxietech.family_tree.api.UnionDto
import com.moxietech.family_tree.domain.ChildRelation
import com.moxietech.family_tree.domain.Sex
import com.moxietech.family_tree.domain.UnionType
import com.moxietech.family_tree.state.FamilyTreeController
import com.moxietech.family_tree.state.FormState
import com.moxietech.family_tree.state.LinkChildInput
import com.moxietech.family_tree.state.PersonInput
import com.moxietech.family_tree.state.UnionChoice
import family_tree.app.shared.generated.resources.Res
import family_tree.app.shared.generated.resources.add_person
import family_tree.app.shared.generated.resources.cancel
import family_tree.app.shared.generated.resources.child
import family_tree.app.shared.generated.resources.child_relation
import family_tree.app.shared.generated.resources.choose_person
import family_tree.app.shared.generated.resources.choose_union
import family_tree.app.shared.generated.resources.form_union_created
import family_tree.app.shared.generated.resources.given_names
import family_tree.app.shared.generated.resources.is_living
import family_tree.app.shared.generated.resources.link_child
import family_tree.app.shared.generated.resources.link_child_hint
import family_tree.app.shared.generated.resources.new_union
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
import family_tree.app.shared.generated.resources.union
import family_tree.app.shared.generated.resources.union_type
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
private fun TreeFormPane(
    title: String,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    submitting: Boolean,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), contentAlignment = Alignment.TopCenter) {
        Surface(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            color = Palette.surface,
            border = BorderStroke(1.dp, Palette.border),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                content()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = onCancel, enabled = !submitting) { Text(stringResource(Res.string.cancel)) }
                    Button(onClick = onSubmit, enabled = !submitting) { Text(stringResource(Res.string.save)) }
                }
            }
        }
    }
}

/** Create a person (`POST …/people`); names are stored in their three parts. */
@Composable
internal fun AddPersonForm(
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

    TreeFormPane(
        title = stringResource(Res.string.add_person),
        onSubmit = { onSubmit(PersonInput(givenNames, paternal, maternal, sex, living)) },
        onCancel = onDismiss,
        submitting = form.submitting,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledTextField(Res.string.given_names, givenNames, isError = form.error != null) {
                    givenNames = it
                    onEdit()
                }
                LabeledTextField(Res.string.surname_paternal, paternal) { paternal = it }
                LabeledTextField(Res.string.surname_maternal, maternal) { maternal = it }
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
    )
}

/** What the union picker shows: one of the parents' unions, or "new union". */
private sealed interface UnionSelection {
    data class Existing(val unionId: String) : UnionSelection

    data object NewUnion : UnionSelection
}

/**
 * Link a child to one or two parents through a union the form names: one of
 * the parents' existing unions (a pair can have several, e.g. married, divorced
 * and remarried), or a new union of the chosen type. The choice is required
 * when the parents have more than one union.
 */
@Composable
internal fun LinkChildForm(
    graph: TreeGraphDto,
    label: (PersonDto) -> String,
    form: FormState,
    onSubmit: (LinkChildInput) -> Unit,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
) {
    val people = graph.people
    var parent by remember { mutableStateOf<PersonDto?>(null) }
    var otherParent by remember { mutableStateOf<PersonDto?>(null) }
    var child by remember { mutableStateOf<PersonDto?>(null) }
    var relation by remember { mutableStateOf(ChildRelation.BIRTH) }
    var newType by remember { mutableStateOf(UnionType.MARRIED) }
    val matching = parent?.let { FamilyTreeController.unionsForParents(graph, it.id, otherParent?.id) }.orEmpty()
    // Reset whenever the parents change: one union is preselected, none means a new one, several need a choice.
    var selection by remember(parent?.id, otherParent?.id) {
        mutableStateOf(
            when (matching.size) {
                0 -> UnionSelection.NewUnion
                1 -> UnionSelection.Existing(matching.single().id)
                else -> null
            },
        )
    }
    // A union created by a failed attempt: retry through it instead of creating another.
    LaunchedEffect(form.createdUnionId) {
        form.createdUnionId?.let { selection = UnionSelection.Existing(it) }
    }

    val choose = stringResource(Res.string.choose_person)
    val none = stringResource(Res.string.no_other_parent)
    val newUnionLabel = stringResource(Res.string.new_union)
    val unionLabels = unionOptionLabels(matching)
    val unionTypeLabels = unionTypeLabels()
    val relationLabels = relationLabels()
    // null in this list stands for "no other parent".
    val otherOptions: List<PersonDto?> = listOf<PersonDto?>(null) + people
    val unionOptions: List<UnionSelection> = matching.map { UnionSelection.Existing(it.id) } + UnionSelection.NewUnion

    TreeFormPane(
        title = stringResource(Res.string.link_child),
        onSubmit = {
            val union = when (val current = selection) {
                is UnionSelection.Existing -> UnionChoice.Existing(current.unionId)
                UnionSelection.NewUnion -> UnionChoice.New(newType)
                null -> null
            }
            onSubmit(LinkChildInput(parent?.id, otherParent?.id, union, child?.id, relation))
        },
        onCancel = onDismiss,
        submitting = form.submitting,
        content = {
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
                if (parent != null) {
                    LabeledPicker(Res.string.union) {
                        Picker(
                            selected = selection,
                            options = unionOptions,
                            label = { option ->
                                when (option) {
                                    is UnionSelection.Existing -> unionLabels[option.unionId] ?: unionTypeLabels.getValue(newType)
                                    UnionSelection.NewUnion -> newUnionLabel
                                }
                            },
                            placeholder = stringResource(Res.string.choose_union),
                            onSelect = {
                                selection = it
                                onEdit()
                            },
                        )
                    }
                    if (selection == UnionSelection.NewUnion) {
                        LabeledPicker(Res.string.union_type) {
                            Picker(newType, UnionType.entries, { unionTypeLabels.getValue(it) }, "", { newType = it })
                        }
                    }
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
                if (form.createdUnionId != null) {
                    Text(stringResource(Res.string.form_union_created), fontSize = 13.sp, color = Palette.muted)
                }
            }
        },
    )
}

/**
 * "Married · 1990" for each union, numbered when two would read the same. The
 * type is always shown; the server omits dates when a partner is private.
 */
@Composable
private fun unionOptionLabels(unions: List<UnionDto>): Map<String, String> {
    val base = unions.map { union ->
        val type = stringResource(unionTypeLabel(union.type))
        union.id to (union.startDate?.displayText?.takeIf { it.isNotBlank() }?.let { "$type · $it" } ?: type)
    }
    val counts = base.groupingBy { it.second }.eachCount()
    val seen = HashMap<String, Int>()
    return base.associate { (id, text) ->
        if ((counts[text] ?: 0) > 1) {
            val n = (seen[text] ?: 0) + 1
            seen[text] = n
            id to "$text ($n)"
        } else {
            id to text
        }
    }
}

/**
 * An outlined text field whose label is also its accessible name, so Compose
 * for Web exposes a textbox named after the label through its DOM bridge.
 */
@Composable
internal fun LabeledTextField(
    label: StringResource,
    value: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supportingText: (@Composable () -> Unit)? = null,
    onValueChange: (String) -> Unit,
) {
    val name = stringResource(label)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(name) },
        singleLine = true,
        isError = isError,
        supportingText = supportingText,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = name },
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
private fun unionTypeLabels(): Map<UnionType, String> =
    UnionType.entries.associateWith { stringResource(unionTypeLabel(it.name)) }

@Composable
private fun relationLabels(): Map<ChildRelation, String> = mapOf(
    ChildRelation.BIRTH to stringResource(Res.string.relation_birth),
    ChildRelation.ADOPTED to stringResource(Res.string.relation_adopted),
    ChildRelation.STEP to stringResource(Res.string.relation_step),
    ChildRelation.FOSTER to stringResource(Res.string.relation_foster),
)
