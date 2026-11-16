package com.moxietech.family_tree.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moxietech.family_tree.api.AccountDto
import com.moxietech.family_tree.state.FamilyTreeController
import com.moxietech.family_tree.state.Loadable
import com.moxietech.family_tree.state.PersonScreen
import com.moxietech.family_tree.state.Viewer
import family_tree.app.shared.generated.resources.Res
import family_tree.app.shared.generated.resources.back_to_tree
import family_tree.app.shared.generated.resources.deceased
import family_tree.app.shared.generated.resources.living
import family_tree.app.shared.generated.resources.private_detail
import family_tree.app.shared.generated.resources.private_person
import org.jetbrains.compose.resources.stringResource

/**
 * The person destination opened from the tree (`#/trees/{treeId}/people/{personId}`).
 * It loads the person on its own, so a reload or a shared link works, and shows
 * the localized private placeholder for a redacted record. FT-006 adds the
 * profile, story, family links and timeline.
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
            val name = viewer.displayName(person.value)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(onClick = controller::backToTree) { Text(stringResource(Res.string.back_to_tree)) }
                    Text(name ?: stringResource(Res.string.private_person), fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(
                            when {
                                name == null -> Res.string.private_detail
                                person.value.isLiving -> Res.string.living
                                else -> Res.string.deceased
                            },
                        ),
                        color = Palette.muted,
                    )
                }
            }
        }
        else -> LoadingPane()
    }
}
