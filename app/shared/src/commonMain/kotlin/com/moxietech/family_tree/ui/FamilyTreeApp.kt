package com.moxietech.family_tree.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moxietech.family_tree.api.AccountDto
import com.moxietech.family_tree.auth.AuthState
import com.moxietech.family_tree.auth.SignedOutReason
import com.moxietech.family_tree.navigation.Route
import com.moxietech.family_tree.state.AppState
import com.moxietech.family_tree.state.FamilyTreeController
import com.moxietech.family_tree.state.Loadable
import com.moxietech.family_tree.state.valueOrNull
import family_tree.app.shared.generated.resources.Res
import family_tree.app.shared.generated.resources.app_name
import family_tree.app.shared.generated.resources.my_trees
import family_tree.app.shared.generated.resources.session_expired
import family_tree.app.shared.generated.resources.sign_in
import family_tree.app.shared.generated.resources.sign_in_body
import family_tree.app.shared.generated.resources.sign_in_failed
import family_tree.app.shared.generated.resources.sign_in_not_verified
import family_tree.app.shared.generated.resources.sign_in_title
import family_tree.app.shared.generated.resources.sign_in_unavailable
import family_tree.app.shared.generated.resources.sign_out
import family_tree.app.shared.generated.resources.signing_in
import org.jetbrains.compose.resources.stringResource

/**
 * The web workflow (FT-005): sign-in, tree list, tree views and the person
 * page (FT-006). What is shown follows [AppState.auth] first, so nothing from a
 * previous session is rendered while signed out, expired or redirecting.
 */
@Composable
fun FamilyTreeApp(controller: FamilyTreeController) {
    val state by controller.state.collectAsState()
    FamilyTreeTheme {
        Surface(Modifier.fillMaxSize(), color = Palette.background) {
            when (val auth = state.auth) {
                AuthState.Starting, AuthState.Redirecting -> LoadingPane(message = Res.string.signing_in)
                is AuthState.SignedOut -> SignInScreen(auth.reason, onSignIn = controller::signIn)
                is AuthState.SignedIn -> SignedInShell(state, controller)
            }
        }
    }
}

@Composable
private fun SignInScreen(reason: SignedOutReason?, onSignIn: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 440.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(Res.string.app_name), fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(Res.string.sign_in_title), fontSize = 18.sp, textAlign = TextAlign.Center)
            Text(stringResource(Res.string.sign_in_body), color = Palette.muted, textAlign = TextAlign.Center)
            val message = when (reason) {
                SignedOutReason.SESSION_EXPIRED -> Res.string.session_expired
                SignedOutReason.SIGN_IN_FAILED -> Res.string.sign_in_failed
                SignedOutReason.SIGN_IN_NOT_VERIFIED -> Res.string.sign_in_not_verified
                SignedOutReason.BROWSER_UNSUPPORTED -> Res.string.sign_in_unavailable
                null -> null
            }
            if (message != null) {
                Text(
                    stringResource(message),
                    modifier = Modifier.fillMaxWidth().background(Palette.noticeBackground, MaterialTheme.shapes.medium)
                        .padding(12.dp),
                    color = Palette.accent,
                    textAlign = TextAlign.Center,
                )
            }
            Button(onClick = onSignIn) { Text(stringResource(Res.string.sign_in)) }
        }
    }
}

@Composable
private fun SignedInShell(state: AppState, controller: FamilyTreeController) {
    Column(Modifier.fillMaxSize()) {
        Header(state, state.account.valueOrNull, controller)
        HorizontalDivider(color = Palette.border)
        Box(Modifier.fillMaxSize()) {
            when (val account = state.account) {
                Loadable.Loading -> LoadingPane()
                is Loadable.Failed -> FailurePane(account.failure, onRetry = controller::retry)
                is Loadable.Ready -> when (val route = state.route) {
                    Route.Trees -> TreeListScreen(state.trees, state.form, controller)
                    is Route.Tree -> state.treeScreen?.takeIf { it.treeId == route.treeId }
                        ?.let { TreeScreenView(it, account.value, state, controller) }
                        ?: LoadingPane()
                    is Route.Person -> state.personScreen
                        ?.takeIf { it.treeId == route.treeId && it.personId == route.personId }
                        ?.let { PersonPage(it, account.value, controller) }
                        ?: LoadingPane()
                }
            }
        }
    }
}

@Composable
private fun Header(state: AppState, account: AccountDto?, controller: FamilyTreeController) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Palette.surface).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(Res.string.app_name),
            modifier = Modifier.clickable(role = Role.Button) { controller.navigate(Route.Trees) },
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (state.route != Route.Trees) {
            Text(
                stringResource(Res.string.my_trees),
                modifier = Modifier.clickable(role = Role.Button) { controller.navigate(Route.Trees) },
                color = Palette.accent,
                fontWeight = FontWeight.Medium,
            )
        }
        val treeName = when (state.route) {
            is Route.Tree -> state.treeScreen?.tree?.valueOrNull?.name
            is Route.Person -> state.personScreen?.tree?.valueOrNull?.name
            Route.Trees -> null
        }
        if (treeName != null) {
            Text(treeName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.weight(1f))
        if (account != null) {
            Text(account.email, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(4.dp))
        OutlinedButton(onClick = controller::signOut) { Text(stringResource(Res.string.sign_out)) }
    }
}
