package com.moxietech.family_tree.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moxietech.family_tree.api.AccountDto
import com.moxietech.family_tree.api.PersonDto
import com.moxietech.family_tree.api.TreeGraphDto
import com.moxietech.family_tree.navigation.Route
import com.moxietech.family_tree.navigation.TreeViewKind
import com.moxietech.family_tree.state.AppState
import com.moxietech.family_tree.state.FamilyTreeController
import com.moxietech.family_tree.state.Loadable
import com.moxietech.family_tree.state.Notice
import com.moxietech.family_tree.state.TreeScreen
import com.moxietech.family_tree.state.Viewer
import com.moxietech.family_tree.tree.GraphSlice
import com.moxietech.family_tree.tree.TreeLayoutEngine
import com.moxietech.family_tree.tree.TreeSlicer
import com.moxietech.family_tree.tree.familyOf
import family_tree.app.shared.generated.resources.Res
import family_tree.app.shared.generated.resources.add_person
import family_tree.app.shared.generated.resources.branch_empty_ancestors
import family_tree.app.shared.generated.resources.branch_empty_descendants
import family_tree.app.shared.generated.resources.center_here
import family_tree.app.shared.generated.resources.centered_on
import family_tree.app.shared.generated.resources.children
import family_tree.app.shared.generated.resources.choose_person
import family_tree.app.shared.generated.resources.choose_root_prompt
import family_tree.app.shared.generated.resources.deceased
import family_tree.app.shared.generated.resources.dismiss
import family_tree.app.shared.generated.resources.link_child
import family_tree.app.shared.generated.resources.living
import family_tree.app.shared.generated.resources.none_recorded
import family_tree.app.shared.generated.resources.notice_child_linked
import family_tree.app.shared.generated.resources.notice_duplicates
import family_tree.app.shared.generated.resources.open_page
import family_tree.app.shared.generated.resources.parents
import family_tree.app.shared.generated.resources.partners
import family_tree.app.shared.generated.resources.private_detail
import family_tree.app.shared.generated.resources.private_person
import family_tree.app.shared.generated.resources.select_person_hint
import family_tree.app.shared.generated.resources.skipped_links
import family_tree.app.shared.generated.resources.tree_empty
import family_tree.app.shared.generated.resources.tree_empty_owner
import family_tree.app.shared.generated.resources.view_ancestors
import family_tree.app.shared.generated.resources.view_descendants
import family_tree.app.shared.generated.resources.view_full
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private enum class TreeDialog { ADD_PERSON, LINK_CHILD }

/** The desktop-first tree page from the mockup: toolbar, full-width canvas, selected-person panel. */
@Composable
internal fun TreeScreenView(screen: TreeScreen, account: AccountDto, state: AppState, controller: FamilyTreeController) {
    val tree = screen.tree
    val graphState = screen.graph
    when {
        tree is Loadable.Failed -> FailurePane(tree.failure, onRetry = controller::retry)
        graphState is Loadable.Failed -> FailurePane(graphState.failure, onRetry = controller::retry)
        tree is Loadable.Ready && graphState is Loadable.Ready -> {
            val viewer = remember(tree.value, account) { Viewer(tree.value, account) }
            TreeContent(screen, graphState.value, viewer, state, controller)
        }
        else -> LoadingPane()
    }
}

@Composable
private fun TreeContent(
    screen: TreeScreen,
    graph: TreeGraphDto,
    viewer: Viewer,
    state: AppState,
    controller: FamilyTreeController,
) {
    var dialog by remember(screen.instance) { mutableStateOf<TreeDialog?>(null) }
    val peopleById = remember(graph) { graph.people.associateBy { it.id } }
    val privateLabel = stringResource(Res.string.private_person)
    val label: (PersonDto) -> String = { viewer.displayName(it) ?: privateLabel }

    when (dialog) {
        TreeDialog.ADD_PERSON -> AddPersonForm(
            form = state.form,
            onSubmit = { input -> controller.createPerson(input) { dialog = null } },
            onDismiss = {
                dialog = null
                controller.clearFormError()
            },
            onEdit = controller::clearFormError,
        )
        TreeDialog.LINK_CHILD -> LinkChildForm(
            graph = graph,
            label = label,
            form = state.form,
            onSubmit = { input -> controller.linkChild(input) { dialog = null } },
            onDismiss = {
                dialog = null
                controller.clearFormError()
            },
            onEdit = controller::clearFormError,
        )
        null -> Column(Modifier.fillMaxSize()) {
            Toolbar(screen, graph, viewer, label, onDialog = { dialog = it }, controller = controller)
            screen.notice?.let { NoticeBanner(it, controller::dismissNotice) }
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
                    CanvasArea(screen, graph, peopleById, viewer, controller, onAddPerson = { dialog = TreeDialog.ADD_PERSON })
                }
                VerticalDivider(color = Palette.border)
                SelectedPersonPanel(
                    person = screen.selectedPersonId?.let(peopleById::get),
                    graph = graph,
                    peopleById = peopleById,
                    viewer = viewer,
                    label = label,
                    onSelect = controller::selectPerson,
                    onOpen = { controller.navigate(Route.Person(screen.treeId, it)) },
                    onCenter = controller::centerOn,
                    modifier = Modifier.width(340.dp).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun Toolbar(
    screen: TreeScreen,
    graph: TreeGraphDto,
    viewer: Viewer,
    label: (PersonDto) -> String,
    onDialog: (TreeDialog) -> Unit,
    controller: FamilyTreeController,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TreeViewKind.entries.forEach { kind ->
            FilterChip(
                selected = screen.view == kind,
                onClick = { controller.showView(kind) },
                label = { Text(stringResource(viewLabel(kind))) },
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(stringResource(Res.string.centered_on), color = Palette.muted, fontSize = 13.sp)
        Picker(
            selected = screen.rootPersonId?.let { id -> graph.people.firstOrNull { it.id == id } },
            options = graph.people,
            label = label,
            placeholder = stringResource(Res.string.choose_person),
            onSelect = { controller.centerOn(it.id) },
            enabled = graph.people.isNotEmpty(),
        )
        if (viewer.canWrite) {
            Spacer(Modifier.width(12.dp))
            Button(onClick = { onDialog(TreeDialog.ADD_PERSON) }) { Text(stringResource(Res.string.add_person)) }
            OutlinedButton(
                onClick = { onDialog(TreeDialog.LINK_CHILD) },
                enabled = graph.people.size >= 2,
            ) { Text(stringResource(Res.string.link_child)) }
        }
    }
}

@Composable
private fun CanvasArea(
    screen: TreeScreen,
    graph: TreeGraphDto,
    peopleById: Map<String, PersonDto>,
    viewer: Viewer,
    controller: FamilyTreeController,
    onAddPerson: () -> Unit,
) {
    if (graph.people.isEmpty()) {
        MessagePane(
            message = if (viewer.canWrite) Res.string.tree_empty_owner else Res.string.tree_empty,
            actionLabel = if (viewer.canWrite) Res.string.add_person else null,
            onAction = if (viewer.canWrite) onAddPerson else null,
        )
        return
    }
    val root = screen.rootPersonId
    val slice: GraphSlice = when (screen.view) {
        TreeViewKind.FULL -> remember(graph) { TreeSlicer.full(graph) }
        else -> {
            if (root == null || root !in peopleById) {
                MessagePane(Res.string.choose_root_prompt)
                return
            }
            when (val branch = screen.branch) {
                null, Loadable.Loading -> {
                    LoadingPane()
                    return
                }
                is Loadable.Failed -> {
                    FailurePane(branch.failure, onRetry = controller::retry)
                    return
                }
                is Loadable.Ready -> remember(graph, screen.view, root, branch.value) {
                    if (screen.view == TreeViewKind.ANCESTORS) {
                        TreeSlicer.ancestors(graph, root, branch.value)
                    } else {
                        TreeSlicer.descendants(graph, root, branch.value)
                    }
                }
            }
        }
    }
    val layout = remember(slice) { TreeLayoutEngine.layout(slice) }
    Column {
        if (layout.skippedLinks > 0) {
            Text(
                stringResource(Res.string.skipped_links, layout.skippedLinks),
                color = Palette.muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        if (screen.view != TreeViewKind.FULL && slice.personIds.size <= 1) {
            Text(
                stringResource(
                    if (screen.view == TreeViewKind.ANCESTORS) Res.string.branch_empty_ancestors else Res.string.branch_empty_descendants,
                ),
                color = Palette.muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        TreeCanvas(
            layout = layout,
            people = peopleById,
            viewer = viewer,
            selectedId = screen.selectedPersonId,
            rootId = root,
            onSelect = controller::selectPerson,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun NoticeBanner(notice: Notice, onDismiss: () -> Unit) {
    val privateLabel = stringResource(Res.string.private_person)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 12.dp)
            .background(Palette.noticeBackground, MaterialTheme.shapes.medium).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val text = when (notice) {
            Notice.ChildLinked -> stringResource(Res.string.notice_child_linked)
            is Notice.PossibleDuplicates -> stringResource(Res.string.notice_duplicates) + " " +
                notice.names.joinToString(", ") { it ?: privateLabel }
        }
        Text(text, color = Palette.accent, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text(stringResource(Res.string.dismiss)) }
    }
}

@Composable
private fun SelectedPersonPanel(
    person: PersonDto?,
    graph: TreeGraphDto,
    peopleById: Map<String, PersonDto>,
    viewer: Viewer,
    label: (PersonDto) -> String,
    onSelect: (String) -> Unit,
    onOpen: (String) -> Unit,
    onCenter: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.background(Palette.surface).verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (person == null) {
            Text(stringResource(Res.string.select_person_hint), color = Palette.muted)
        } else {
            PersonSummary(person, graph, peopleById, viewer, label, onSelect, onOpen, onCenter)
        }
    }
}

@Composable
private fun PersonSummary(
    person: PersonDto,
    graph: TreeGraphDto,
    peopleById: Map<String, PersonDto>,
    viewer: Viewer,
    label: (PersonDto) -> String,
    onSelect: (String) -> Unit,
    onOpen: (String) -> Unit,
    onCenter: (String) -> Unit,
) {
    val visible = viewer.canSeeDetails(person)
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label(person), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(
                    when {
                        !visible -> Res.string.private_detail
                        person.isLiving -> Res.string.living
                        else -> Res.string.deceased
                    },
                ),
                color = Palette.muted,
                fontSize = 13.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onOpen(person.id) }) { Text(stringResource(Res.string.open_page)) }
            OutlinedButton(onClick = { onCenter(person.id) }) { Text(stringResource(Res.string.center_here)) }
        }
        val family = remember(graph, person.id) { familyOf(graph, person.id) }
        FamilySection(Res.string.parents, family.parents.map { it to null }, peopleById, label, onSelect)
        // The union type stays visible next to a private partner; only dates are redacted.
        FamilySection(
            Res.string.partners,
            family.partners.map { it.personId to stringResource(unionTypeLabel(it.unionType)) },
            peopleById,
            label,
            onSelect,
        )
        FamilySection(Res.string.children, family.children.map { it to null }, peopleById, label, onSelect)
    }
}

/** [entries] are person IDs with an optional detail shown after the name. */
@Composable
private fun FamilySection(
    title: StringResource,
    entries: List<Pair<String, String?>>,
    peopleById: Map<String, PersonDto>,
    label: (PersonDto) -> String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(title).uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.muted)
        if (entries.isEmpty()) {
            Text(stringResource(Res.string.none_recorded), fontSize = 13.sp, color = Palette.muted)
        }
        entries.forEach { (id, detail) ->
            val relative = peopleById[id] ?: return@forEach
            Text(
                if (detail == null) label(relative) else "${label(relative)} · $detail",
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onSelect(relative.id) }
                    .padding(vertical = 6.dp),
            )
        }
    }
}

private fun viewLabel(kind: TreeViewKind): StringResource = when (kind) {
    TreeViewKind.FULL -> Res.string.view_full
    TreeViewKind.ANCESTORS -> Res.string.view_ancestors
    TreeViewKind.DESCENDANTS -> Res.string.view_descendants
}
