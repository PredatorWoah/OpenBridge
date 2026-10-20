package org.openbridge.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.openbridge.ui.Look
import org.openbridge.ui.Palette

/*
 * Checkboxes for choosing files: one on each row and tile, which adds or removes just that item, and one for the
 * whole folder in the toolbar (all, none, or some). Clicking a row still selects as in a file manager.
 */

/** The folder's checkbox: on when everything shown is selected, off when nothing is, a dash in between. */
fun selectAllState(selection: Set<String>, shown: List<String>): ToggleableState {
    val chosen = shown.count { it in selection }
    return when {
        chosen == 0 -> ToggleableState.Off
        chosen == shown.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
}

/**
 * A checkbox drawn the look's way: Dusk's amber square, Aurora's mint light glowing around it, Tide's round aqua
 * bubble. `onPhoto` gives it a dark backing so it stays visible over a preview.
 */
@Composable
fun SelectBox(state: ToggleableState, look: Look, modifier: Modifier = Modifier, size: Dp = 18.dp, onPhoto: Boolean = false) {
    val on by animateFloatAsState(if (state == ToggleableState.Off) 0f else 1f, tween(if (Platform.reduceMotion) 0 else 160))
    val accent = Palette.amber
    val fg = Palette.fg
    val night = Palette.night
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = (if (look == Look.AURORA) 1.3f else 1.6f) * density
        val inset = stroke / 2
        val box = Size(s - stroke, s - stroke)
        val corner = when (look) { Look.DUSK -> CornerRadius(4f * density); Look.AURORA -> CornerRadius(5.5f * density); Look.TIDE -> CornerRadius(s / 2) }
        if (onPhoto) drawRoundRect(night.copy(alpha = 0.55f * (1f - on)), Offset(inset, inset), box, corner)
        when (look) {
            Look.DUSK -> {
                drawRoundRect(fg.copy(alpha = 0.5f * (1f - on)), Offset(inset, inset), box, corner, style = Stroke(stroke))
                drawRoundRect(accent.copy(alpha = on), Offset(0f, 0f), Size(s, s), CornerRadius(corner.x + inset))
                mark(state, night.copy(alpha = on), s, 2f * density)
            }
            Look.AURORA -> {
                // The glow: rings growing outwards, each fainter, as on Aurora's selected rows.
                for (i in 4 downTo 1) {
                    val grow = i * 1.4f * density
                    drawRoundRect(accent.copy(alpha = 0.22f * on / i), Offset(-grow, -grow), Size(s + 2 * grow, s + 2 * grow), CornerRadius(corner.x + grow))
                }
                drawRoundRect(accent.copy(alpha = 0.22f * on), Offset(inset, inset), box, corner)
                drawRoundRect(lerpColor(fg.copy(alpha = 0.4f), accent, on), Offset(inset, inset), box, corner, style = Stroke(stroke))
                mark(state, Palette.sun.copy(alpha = on), s, 1.8f * density)
            }
            Look.TIDE -> {
                drawCircle(fg.copy(alpha = 0.5f * (1f - on)), s / 2 - inset, center, style = Stroke(stroke))
                drawCircle(Brush.radialGradient(listOf(Palette.sun.copy(alpha = on), accent.copy(alpha = on)), center + Offset(-s * 0.15f, -s * 0.15f), s * 0.75f),
                    s / 2 * (0.6f + 0.4f * on), center)
                mark(state, night.copy(alpha = on), s, 2f * density)
            }
        }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t.coerceIn(0f, 1f))

/** The tick, or the dash for "some". */
private fun DrawScope.mark(state: ToggleableState, color: Color, s: Float, width: Float) {
    if (color.alpha <= 0f) return
    if (state == ToggleableState.Indeterminate) {
        drawLine(color, Offset(s * 0.28f, s / 2), Offset(s * 0.72f, s / 2), width, StrokeCap.Round)
        return
    }
    val tick = Path().apply { moveTo(s * 0.27f, s * 0.52f); lineTo(s * 0.44f, s * 0.68f); lineTo(s * 0.74f, s * 0.34f) }
    drawPath(tick, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/**
 * A row's or tile's checkbox: toggles just this item, leaving the rest of the selection. It takes the press, so the
 * row under it doesn't also select or open; screen readers get it as its own checkbox.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun EntryCheck(state: AppState, entry: Entry, look: Look, selected: Boolean, modifier: Modifier = Modifier, onPhoto: Boolean = false) {
    val description = S.selectItem(entry.name)
    Box(modifier.size(26.dp)
        .onPointerEvent(PointerEventType.Press) { event ->
            if (!event.buttons.isPrimaryPressed) return@onPointerEvent
            event.changes.forEach { it.consume() }
            state.toggle(entry)
        }
        .semantics(mergeDescendants = true) {
            role = Role.Checkbox
            toggleableState = ToggleableState(selected)
            contentDescription = description
            onClick(description) { state.toggle(entry); true }
        }, contentAlignment = Alignment.Center) {
        SelectBox(ToggleableState(selected), look, size = if (look == Look.TIDE) 19.dp else 17.dp, onPhoto = onPhoto)
    }
}

/** The folder's checkbox, in the toolbar: selects everything shown, or nothing once everything is. */
@Composable
fun SelectAllBox(state: AppState, look: Look) {
    if (state.results != null) return
    val shown = state.sorted.map { it.id }
    val tri = selectAllState(state.selection, shown)
    val label = if (tri == ToggleableState.On) S.selectNone else S.selectAll
    val chosen = shown.count { it in state.selection }
    val shape = if (look == Look.TIDE) CircleShape else RoundedCornerShape(10.dp)
    Tip(label) {
        Box(Modifier.size(34.dp).pressable(shape, enabled = shown.isNotEmpty(), role = Role.Checkbox, label = label) {
            if (tri == ToggleableState.On) state.selectNone() else state.selectAll()
        }.semantics {
            toggleableState = tri
            contentDescription = S.selectAll
            stateDescription = S.selectedCount(chosen, shown.size)
        }, contentAlignment = Alignment.Center) {
            SelectBox(tri, look, size = if (look == Look.TIDE) 19.dp else 17.dp)
        }
    }
}
