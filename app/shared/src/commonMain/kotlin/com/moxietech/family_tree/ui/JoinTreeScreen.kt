package com.moxietech.family_tree.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moxietech.family_tree.navigation.Route
import com.moxietech.family_tree.state.FamilyTreeController
import com.moxietech.family_tree.state.JoinError
import com.moxietech.family_tree.state.JoinScreen
import family_tree.app.shared.generated.resources.Res
import family_tree.app.shared.generated.resources.error_server
import family_tree.app.shared.generated.resources.error_unavailable
import family_tree.app.shared.generated.resources.join_accept
import family_tree.app.shared.generated.resources.join_accepting
import family_tree.app.shared.generated.resources.join_body
import family_tree.app.shared.generated.resources.join_cannot_accept
import family_tree.app.shared.generated.resources.join_missing
import family_tree.app.shared.generated.resources.join_not_valid
import family_tree.app.shared.generated.resources.join_title
import family_tree.app.shared.generated.resources.my_trees
import org.jetbrains.compose.resources.stringResource

/**
 * FT-011: the join page (`#/trees/{treeId}/join`) for a signed-in account. Nothing is
 * accepted until the user presses the button; the tree's name isn't shown because the
 * caller can't read the tree before accepting.
 */
@Composable
internal fun JoinTreeScreen(screen: JoinScreen, controller: FamilyTreeController) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 480.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(Res.string.join_title), fontSize = 20.sp, textAlign = TextAlign.Center)
            val error = screen.error
            if (error != null) {
                Text(
                    stringResource(
                        when (error) {
                            JoinError.NOT_VALID -> Res.string.join_not_valid
                            JoinError.CANNOT_ACCEPT -> Res.string.join_cannot_accept
                            JoinError.UNAVAILABLE -> Res.string.error_unavailable
                            JoinError.SERVER -> Res.string.error_server
                        },
                    ),
                    modifier = Modifier.fillMaxWidth().background(Palette.noticeBackground, MaterialTheme.shapes.medium).padding(12.dp),
                    color = Palette.accent,
                    textAlign = TextAlign.Center,
                )
            }
            when {
                screen.hasInvitation -> {
                    Text(stringResource(Res.string.join_body), color = Palette.muted, textAlign = TextAlign.Center)
                    Button(onClick = controller::acceptInvitation, enabled = !screen.accepting) {
                        Text(stringResource(if (screen.accepting) Res.string.join_accepting else Res.string.join_accept))
                    }
                }
                error == null -> Text(stringResource(Res.string.join_missing), color = Palette.muted, textAlign = TextAlign.Center)
            }
            OutlinedButton(onClick = { controller.navigate(Route.Trees) }, enabled = !screen.accepting) {
                Text(stringResource(Res.string.my_trees))
            }
        }
    }
}
