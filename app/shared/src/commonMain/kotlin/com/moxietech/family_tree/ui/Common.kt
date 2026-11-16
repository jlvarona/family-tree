package com.moxietech.family_tree.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moxietech.family_tree.client.ApiFailure
import com.moxietech.family_tree.domain.UnionType
import com.moxietech.family_tree.state.FormError
import family_tree.app.shared.generated.resources.Res
import family_tree.app.shared.generated.resources.error_forbidden
import family_tree.app.shared.generated.resources.error_invalid
import family_tree.app.shared.generated.resources.error_not_found
import family_tree.app.shared.generated.resources.error_server
import family_tree.app.shared.generated.resources.error_unavailable
import family_tree.app.shared.generated.resources.form_already_linked
import family_tree.app.shared.generated.resources.form_child_is_parent
import family_tree.app.shared.generated.resources.form_child_required
import family_tree.app.shared.generated.resources.form_name_required
import family_tree.app.shared.generated.resources.form_parent_required
import family_tree.app.shared.generated.resources.form_same_parent
import family_tree.app.shared.generated.resources.form_union_required
import family_tree.app.shared.generated.resources.union_divorced
import family_tree.app.shared.generated.resources.union_married
import family_tree.app.shared.generated.resources.union_partners
import family_tree.app.shared.generated.resources.union_unknown
import family_tree.app.shared.generated.resources.loading
import family_tree.app.shared.generated.resources.retry
import family_tree.app.shared.generated.resources.session_expired
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Colours from the tree mockup (docs/mockups/tree-view.dc.html). */
internal object Palette {
    val accent = Color(0xFF2F5D50)
    val background = Color(0xFFF6F6F3)
    val surface = Color(0xFFFFFFFF)
    val text = Color(0xFF1E2421)
    val muted = Color(0xFF5B635F)
    val border = Color(0xFFD9DCD7)
    val subtle = Color(0xFFECEEEA)
    val connector = Color(0xFF9AA19C)
    val noticeBackground = Color(0xFFE9F1EE)
}

@Composable
internal fun FamilyTreeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Palette.accent,
            onPrimary = Color.White,
            background = Palette.background,
            onBackground = Palette.text,
            surface = Palette.surface,
            onSurface = Palette.text,
            surfaceVariant = Palette.subtle,
            onSurfaceVariant = Palette.muted,
            outline = Palette.border,
        ),
        content = content,
    )
}

internal fun failureMessage(failure: ApiFailure): StringResource = when (failure) {
    ApiFailure.SESSION_EXPIRED -> Res.string.session_expired
    ApiFailure.FORBIDDEN -> Res.string.error_forbidden
    ApiFailure.NOT_FOUND -> Res.string.error_not_found
    ApiFailure.INVALID, ApiFailure.CONFLICT -> Res.string.error_invalid
    ApiFailure.UNAVAILABLE -> Res.string.error_unavailable
    ApiFailure.SERVER -> Res.string.error_server
}

internal fun formErrorMessage(error: FormError): StringResource = when (error) {
    FormError.NAME_REQUIRED -> Res.string.form_name_required
    FormError.PARENT_REQUIRED -> Res.string.form_parent_required
    FormError.CHILD_REQUIRED -> Res.string.form_child_required
    FormError.UNION_REQUIRED -> Res.string.form_union_required
    FormError.SAME_PARENT_TWICE -> Res.string.form_same_parent
    FormError.CHILD_IS_PARENT -> Res.string.form_child_is_parent
    FormError.ALREADY_LINKED -> Res.string.form_already_linked
    FormError.FORBIDDEN -> Res.string.error_forbidden
    FormError.NOT_FOUND -> Res.string.error_not_found
    FormError.INVALID -> Res.string.error_invalid
    FormError.UNAVAILABLE -> Res.string.error_unavailable
    FormError.SERVER -> Res.string.error_server
}

/** The localized name of a union type as the API sends it (`MARRIED`, …). */
internal fun unionTypeLabel(type: String): StringResource = when (type) {
    UnionType.MARRIED.name -> Res.string.union_married
    UnionType.DIVORCED.name -> Res.string.union_divorced
    UnionType.PARTNERS.name -> Res.string.union_partners
    else -> Res.string.union_unknown
}

@Composable
internal fun LoadingPane(modifier: Modifier = Modifier, message: StringResource = Res.string.loading) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(stringResource(message), color = Palette.muted)
        }
    }
}

@Composable
internal fun MessagePane(
    message: StringResource,
    modifier: Modifier = Modifier,
    actionLabel: StringResource? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 480.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(message), textAlign = TextAlign.Center, color = Palette.text)
            if (actionLabel != null && onAction != null) Button(onClick = onAction) { Text(stringResource(actionLabel)) }
        }
    }
}

@Composable
internal fun FailurePane(failure: ApiFailure, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val retryable = failure != ApiFailure.NOT_FOUND && failure != ApiFailure.FORBIDDEN
    MessagePane(
        message = failureMessage(failure),
        modifier = modifier,
        actionLabel = if (retryable) Res.string.retry else null,
        onAction = if (retryable) onRetry else null,
    )
}

/** A labelled choice from a short list, built from the stable M3 menu primitives. */
@Composable
internal fun <T> Picker(
    selected: T?,
    options: List<T>,
    label: (T) -> String,
    placeholder: String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier.widthIn(max = 320.dp)) {
        OutlinedButton(onClick = { expanded = !expanded }, enabled = enabled) {
            Text(
                text = selected?.let(label) ?: placeholder,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selected == null) Palette.muted else Palette.text,
            )
            Text("  ▾", color = Palette.muted)
        }
        if (expanded) {
            Surface(
                color = Palette.surface,
                border = BorderStroke(1.dp, Palette.border),
                shape = MaterialTheme.shapes.small,
            ) {
                Column(Modifier.heightIn(max = 208.dp).verticalScroll(rememberScrollState())) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            onClick = {
                                expanded = false
                                onSelect(option)
                            },
                        )
                    }
                }
            }
        }
    }
}
