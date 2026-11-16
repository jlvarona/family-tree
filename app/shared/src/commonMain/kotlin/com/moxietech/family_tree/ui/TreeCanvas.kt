package com.moxietech.family_tree.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moxietech.family_tree.api.PersonDto
import com.moxietech.family_tree.state.Viewer
import com.moxietech.family_tree.tree.LayoutConfig
import com.moxietech.family_tree.tree.TreeLayout
import family_tree.app.shared.generated.resources.Res
import family_tree.app.shared.generated.resources.deceased
import family_tree.app.shared.generated.resources.living
import family_tree.app.shared.generated.resources.private_detail
import family_tree.app.shared.generated.resources.private_person
import family_tree.app.shared.generated.resources.zoom_in
import family_tree.app.shared.generated.resources.zoom_out
import family_tree.app.shared.generated.resources.zoom_reset
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

private val ZOOM_STEPS = listOf(0.5f, 0.67f, 0.8f, 1f, 1.25f, 1.5f)

/**
 * Draws a [TreeLayout]: connectors on a Canvas, people as clickable cards on
 * top, scrollable in both directions, with zoom buttons as in the mockup.
 */
@Composable
internal fun TreeCanvas(
    layout: TreeLayout,
    people: Map<String, PersonDto>,
    viewer: Viewer,
    selectedId: String?,
    rootId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    config: LayoutConfig = LayoutConfig(),
) {
    var zoom by remember { mutableStateOf(1f) }
    Box(
        modifier
            .border(BorderStroke(1.dp, Palette.border), RoundedCornerShape(16.dp))
            .background(Palette.surface, RoundedCornerShape(16.dp)),
    ) {
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
            Box(Modifier.scaled(layout.width, layout.height, zoom)) {
                Canvas(Modifier.size(layout.width.dp, layout.height.dp)) {
                    val stroke = 1.5.dp.toPx()
                    layout.segments.forEach { s ->
                        drawLine(
                            color = Palette.connector,
                            start = Offset(s.x1.dp.toPx(), s.y1.dp.toPx()),
                            end = Offset(s.x2.dp.toPx(), s.y2.dp.toPx()),
                            strokeWidth = stroke,
                        )
                    }
                }
                layout.people.forEach { placed ->
                    val person = people[placed.personId] ?: return@forEach
                    PersonCard(
                        person = person,
                        viewer = viewer,
                        selected = placed.personId == selectedId,
                        isRoot = placed.personId == rootId,
                        onClick = { onSelect(placed.personId) },
                        modifier = Modifier.offset(placed.x.dp, placed.y.dp)
                            .size(config.cardWidth.dp, config.cardHeight.dp),
                    )
                }
            }
        }
        ZoomControls(
            zoom = zoom,
            onZoom = { zoom = it },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

/** Lays the content out at its natural size and draws it scaled, reporting the scaled size to scrolling. */
private fun Modifier.scaled(width: Float, height: Float, zoom: Float): Modifier = layout { measurable, _ ->
    val w = width.dp.roundToPx().coerceAtLeast(0)
    val h = height.dp.roundToPx().coerceAtLeast(0)
    val placeable = measurable.measure(Constraints.fixed(w, h))
    layout((w * zoom).roundToInt(), (h * zoom).roundToInt()) {
        placeable.placeWithLayer(0, 0) {
            scaleX = zoom
            scaleY = zoom
            transformOrigin = TransformOrigin(0f, 0f)
        }
    }
}

@Composable
private fun PersonCard(
    person: PersonDto,
    viewer: Viewer,
    selected: Boolean,
    isRoot: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = viewer.displayName(person)
    val shape = RoundedCornerShape(12.dp)
    val borderColor = if (selected || isRoot) Palette.accent else Palette.border
    Column(
        modifier = modifier
            .background(Palette.surface, shape)
            .border(BorderStroke(if (selected) 2.dp else 1.dp, borderColor), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Text(
            name ?: stringResource(Res.string.private_person),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            stringResource(
                when {
                    name == null -> Res.string.private_detail
                    person.isLiving -> Res.string.living
                    else -> Res.string.deceased
                },
            ),
            fontSize = 12.sp,
            color = Palette.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ZoomControls(zoom: Float, onZoom: (Float) -> Unit, modifier: Modifier = Modifier) {
    val index = ZOOM_STEPS.indexOfFirst { it >= zoom }.let { if (it < 0) ZOOM_STEPS.lastIndex else it }
    val zoomIn = stringResource(Res.string.zoom_in)
    val zoomOut = stringResource(Res.string.zoom_out)
    val reset = stringResource(Res.string.zoom_reset)
    Column(
        modifier
            .background(Palette.surface, RoundedCornerShape(10.dp))
            .border(BorderStroke(1.dp, Palette.border), RoundedCornerShape(10.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TextButton(
            onClick = { onZoom(ZOOM_STEPS[(index + 1).coerceAtMost(ZOOM_STEPS.lastIndex)]) },
            enabled = index < ZOOM_STEPS.lastIndex,
            modifier = Modifier.semantics { contentDescription = zoomIn },
        ) { Text("+", fontSize = 18.sp) }
        TextButton(
            onClick = { onZoom(ZOOM_STEPS[(index - 1).coerceAtLeast(0)]) },
            enabled = index > 0,
            modifier = Modifier.semantics { contentDescription = zoomOut },
        ) { Text("−", fontSize = 18.sp) }
        TextButton(
            onClick = { onZoom(1f) },
            enabled = zoom != 1f,
            modifier = Modifier.semantics { contentDescription = reset },
        ) { Text("${(zoom * 100).roundToInt()}%", fontSize = 12.sp) }
    }
}
