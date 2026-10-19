package org.openbridge.android

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openbridge.ui.Palette

// Glass pieces shared by every screen. Colours come only from Palette, so each look restyles them.

val glassShape = RoundedCornerShape(22.dp)
val sheetShape = RoundedCornerShape(28.dp)
private val topSheetShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)

/** A spring for things that grow, shrink and slide: quick, with a hint of overshoot. */
fun <T> fluid() = spring<T>(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)

/**
 * Frosted glass: a light tint over the backdrop and a hairline edge. [solid] is for sheets over busy content: the
 * look's own dusk and night, nearly opaque.
 */
fun Modifier.glass(shape: Shape = glassShape, border: Color = Palette.fg.copy(alpha = 0.16f), solid: Boolean = false) =
    clip(shape)
        .background(if (solid) Brush.verticalGradient(listOf(Palette.plum.copy(alpha = 0.96f), Palette.night.copy(alpha = 0.98f)))
            else Brush.linearGradient(listOf(Palette.fg.copy(alpha = 0.13f), Palette.fg.copy(alpha = 0.04f))))
        .border(1.dp, border, shape)

/** Material components (switches, text fields, progress) in the current look's colours. */
@Composable
fun OpenBridgeTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Palette.amber, onPrimary = Palette.night, secondary = Palette.pink, onSecondary = Palette.night,
        tertiary = Palette.violet, background = Palette.night, onBackground = Palette.fg, surface = Palette.dusk, onSurface = Palette.fg,
        surfaceVariant = Palette.plum, onSurfaceVariant = Palette.muted, outline = Palette.muted, outlineVariant = Palette.fg.copy(alpha = 0.2f),
        error = Palette.pink, onError = Palette.night)
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Android's "Remove animations" setting: still backdrops, a still hero, no sliding. */
@Composable
fun reduceMotion(): Boolean {
    val context = LocalContext.current
    return remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(modifier.heightIn(min = 48.dp).clip(CircleShape).background(Palette.fg.copy(alpha = 0.08f))
        .border(1.dp, Palette.fg.copy(alpha = 0.16f), CircleShape)
        .clickable(enabled = enabled, onClick = onClick, role = Role.Button).padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) Palette.fg else Palette.muted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

@Composable
fun AccentButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(modifier.heightIn(min = 48.dp).clip(CircleShape).background(if (enabled) Palette.sunset else Brush.linearGradient(listOf(Palette.plum, Palette.plum)))
        .clickable(enabled = enabled, onClick = onClick, role = Role.Button).padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center) {
        Text(text, color = Palette.night, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

/** A small text action inside a card, with a full-size touch target. */
@Composable
fun SmallAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Palette.amber, enabled: Boolean = true) {
    Box(modifier.minimumInteractiveComponentSize().clip(CircleShape).clickable(enabled = enabled, onClick = onClick, role = Role.Button)
        .padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) color else Palette.muted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** An icon-only button: always labelled for screen readers. */
@Composable
fun IconAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Palette.fg) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp)) {
        Icon(icon, contentDescription = label, tint = tint)
    }
}

/** A setting that switches on and off; the whole row is the touch target. */
@Composable
fun ToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp))
        .toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Palette.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Palette.muted, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onCheckedChange = null, colors = SwitchDefaults.colors(
            checkedTrackColor = Palette.pink, checkedThumbColor = Palette.cream,
            uncheckedTrackColor = Palette.fg.copy(alpha = 0.12f), uncheckedThumbColor = Palette.muted, uncheckedBorderColor = Color.Transparent))
    }
}

/** A glass card whose body opens and closes fluidly when its header is tapped. */
@Composable
fun ExpandableCard(title: String, summary: String?, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier,
                   badge: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val still = reduceMotion()
    val turn by animateFloatAsState(if (expanded) 180f else 0f, if (still) snap() else fluid(), label = "chevron")
    Column(modifier.fillMaxWidth().glass().then(if (still) Modifier else Modifier.animateContentSize(fluid()))) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(onClickLabel = if (expanded) S.collapse else S.expand, role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = if (expanded) S.expanded else S.collapsed }
            .padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = Palette.fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                    if (badge != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(badge, color = Palette.night, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(CircleShape).background(Palette.amber).padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
                if (summary != null) Text(summary, color = Palette.muted, fontSize = 13.sp)
            }
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Palette.muted, modifier = Modifier.size(28.dp).rotate(turn))
        }
        if (expanded) Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 16.dp), content = content)
    }
}

private fun <T> snap() = androidx.compose.animation.core.snap<T>()

/** A hint or problem in a glass strip, with a way to dismiss it. */
@Composable
fun NoteStrip(text: String, onDismiss: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp), Palette.amber.copy(alpha = 0.4f)).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Palette.cream, fontSize = 13.5.sp, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        if (onDismiss != null) IconAction(Icons.Filled.Close, S.dismiss, onDismiss, tint = Palette.muted)
    }
}

/**
 * A sheet that slides up over the screen, on a dim scrim; Back or the scrim closes it. Its content scrolls, so large
 * text never gets cut off.
 */
@Composable
fun BoxScope.Sheet(visible: Boolean, onClose: () -> Unit, title: String, content: @Composable ColumnScope.() -> Unit) {
    val still = reduceMotion()
    BackHandler(enabled = visible, onBack = onClose)
    AnimatedVisibility(visible, Modifier.matchParentSize(), enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(Palette.night.copy(alpha = 0.55f))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = S.close, onClick = onClose))
    }
    AnimatedVisibility(visible, Modifier.align(Alignment.BottomCenter),
        enter = if (still) fadeIn() else slideInVertically(fluid()) { it } + fadeIn(),
        exit = if (still) fadeOut() else slideOutVertically { it } + fadeOut()) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).glass(topSheetShape, Palette.fg.copy(alpha = 0.2f), solid = true)
            // Taps inside the sheet stay in the sheet.
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Palette.fg, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).semantics { heading() })
                IconAction(Icons.Filled.Close, S.close, onClose, tint = Palette.muted)
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
        }
    }
}

/** A section heading inside a sheet. */
@Composable
fun SectionTitle(text: String) {
    Text(text.uppercase(), color = Palette.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() })
}
