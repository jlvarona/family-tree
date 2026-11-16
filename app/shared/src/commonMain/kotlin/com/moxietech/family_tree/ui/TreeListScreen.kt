package com.moxietech.family_tree.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.moxietech.family_tree.api.TreeDto
import com.moxietech.family_tree.navigation.Route
import com.moxietech.family_tree.state.FamilyTreeController
import com.moxietech.family_tree.state.FormState
import com.moxietech.family_tree.state.Loadable
import family_tree.app.shared.generated.resources.Res
import family_tree.app.shared.generated.resources.create_tree
import family_tree.app.shared.generated.resources.my_trees
import family_tree.app.shared.generated.resources.tree_name
import family_tree.app.shared.generated.resources.trees_empty
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun TreeListScreen(trees: Loadable<List<TreeDto>>, form: FormState, controller: FamilyTreeController) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(Res.string.my_trees), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            CreateTreeForm(form, controller)
            when (trees) {
                Loadable.Loading -> LoadingPane(Modifier.padding(32.dp))
                is Loadable.Failed -> FailurePane(trees.failure, onRetry = controller::retry)
                is Loadable.Ready -> if (trees.value.isEmpty()) {
                    Text(stringResource(Res.string.trees_empty), color = Palette.muted)
                } else {
                    trees.value.forEach { tree ->
                        Text(
                            tree.name,
                            modifier = Modifier.fillMaxWidth()
                                .background(Palette.surface, RoundedCornerShape(12.dp))
                                .border(BorderStroke(1.dp, Palette.border), RoundedCornerShape(12.dp))
                                .clickable(role = Role.Button) { controller.navigate(Route.Tree(tree.id)) }
                                .padding(16.dp),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateTreeForm(form: FormState, controller: FamilyTreeController) {
    var name by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
                if (form.error != null) controller.clearFormError()
            },
            label = { Text(stringResource(Res.string.tree_name)) },
            singleLine = true,
            isError = form.error != null,
            supportingText = { form.error?.let { error -> Text(stringResource(formErrorMessage(error))) } },
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = { controller.createTree(name) { name = "" } },
            enabled = !form.submitting,
        ) { Text(stringResource(Res.string.create_tree)) }
    }
}
