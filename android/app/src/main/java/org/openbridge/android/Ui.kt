package org.openbridge.android

import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.openbridge.ui.Palette

// The phone app's design system: type, motion, glass and the controls every screen is built from. Colours come only
// from Palette, so each look restyles everything.

/**
 * How things move. [reduced]: Android's "Remove animations" is on, so nothing moves but what must. [frozen]: a still
 * picture of the settled state (screenshot tests).
 */
@Immutable
data class Motion(val reduced: Boolean = false, val frozen: Boolean = false) {
    /** No animation at all. */
    val still get() = reduced || frozen
}

val LocalMotion = staticCompositionLocalOf { Motion() }

/** Android's animator duration scale: 0 means the user turned animations off. */
@Composable
fun systemMotion(): Motion {
    val context = LocalContext.current
    return remember { Motion(reduced = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f) }
}

/** Springs: [fluid] for things that grow, shrink and slide; [bouncy] for presses and pops. */
fun <T> fluid(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)
fun <T> bouncy(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium)

/** [spec], or an instant change when motion is off. */
@Composable
fun <T> motion(spec: FiniteAnimationSpec<T> = fluid()): FiniteAnimationSpec<T> = if (LocalMotion.current.still) snap() else spec

/** A type scale: big, confident titles and calm body text. Line heights leave room at 200% font size. */
object Type {
    val display = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
    val title = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
    val headline = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)
    val bodyStrong = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
    val label = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val overline = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
    val mono = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
}

val cardShape = RoundedCornerShape(26.dp)
val tileShape = RoundedCornerShape(20.dp)

/** Frosted glass: a soft tint over the backdrop, a lighter top edge, and [strong] for panels over busy content. */
fun Modifier.glass(shape: Shape = cardShape, strong: Boolean = false, edge: Color? = null) =
    clip(shape)
        .background(
            if (strong) Brush.verticalGradient(listOf(Palette.plum.copy(alpha = 0.97f), Palette.night.copy(alpha = 0.98f)))
            else Brush.verticalGradient(listOf(Palette.fg.copy(alpha = 0.12f), Palette.fg.copy(alpha = 0.05f))))
        .border(1.dp, if (edge != null) Brush.verticalGradient(listOf(edge, edge.copy(alpha = edge.alpha * 0.4f)))
            else Brush.verticalGradient(listOf(Palette.fg.copy(alpha = 0.22f), Palette.fg.copy(alpha = 0.05f))), shape)

/** Material pieces (text fields, progress, swipe boxes) in the current look's colours. */
@Composable
fun OpenBridgeTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Palette.amber, onPrimary = Palette.night, secondary = Palette.pink, onSecondary = Palette.night,
        tertiary = Palette.violet, background = Palette.night, onBackground = Palette.fg, surface = Palette.dusk, onSurface = Palette.fg,
        surfaceVariant = Palette.plum, onSurfaceVariant = Palette.muted, outline = Palette.muted, outlineVariant = Palette.fg.copy(alpha = 0.2f),
        error = Palette.pink, onError = Palette.night)
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Haptic ticks for the moments that matter. Silent when the user turned touch feedback off. */
class Haptics(private val view: android.view.View) {
    fun tap() = view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    fun tick() = view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    fun confirm() = view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
    fun reject() = view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
    fun heavy() = view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

/** Shrinks a little while pressed and springs back. */
fun Modifier.pressScale(interaction: MutableInteractionSource, pressed: Float = 0.96f) = composed {
    val down by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (down && !LocalMotion.current.still) pressed else 1f, bouncy(), label = "press")
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Fades and rises into place, [index] steps after the first, the first time it's shown. */
fun Modifier.entrance(index: Int, enabled: Boolean = true) = composed {
    if (!enabled || LocalMotion.current.still) return@composed this
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index.coerceAtMost(10) * 45L)
        shown.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow))
    }
    graphicsLayer {
        alpha = shown.value.coerceIn(0f, 1f)
        translationY = (1 - shown.value) * 28.dp.toPx()
    }
}

/** True for a screen's first second: its rows rise in once, not again while scrolling. */
@Composable
fun rememberFirstShow(): Boolean {
    var first by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(1200); first = false }
    return first
}

/** The main action: the look's accent gradient, a springy press and, when [breathing], a slow glow. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null,
                  enabled: Boolean = true, breathing: Boolean = false, large: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    val still = LocalMotion.current.still
    val glow = if (breathing && !still) {
        val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "breathe")
        transition.animateFloat(0.25f, 0.85f, androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(1600, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            androidx.compose.animation.core.RepeatMode.Reverse), label = "glow")
    } else null
    Box(modifier.pressScale(interaction)
        .drawBehind {
            val k = glow?.value ?: if (breathing) 0.6f else 0f
            if (k > 0f && enabled) {
                // A soft halo: rings of the button's own shape, each a little wider and fainter.
                val step = 3.dp.toPx() * (0.6f + 0.6f * k)
                for (i in 1..7) {
                    val grow = step * i
                    drawRoundRect(lerpColor(Palette.pink, Palette.amber, i / 7f).copy(alpha = 0.07f * k * (1 - i / 8f)),
                        topLeft = Offset(-grow, -grow * 0.8f),
                        size = androidx.compose.ui.geometry.Size(size.width + 2 * grow, size.height + 1.6f * grow),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2 + grow))
                }
            }
        }
        .heightIn(min = if (large) 64.dp else 52.dp)
        .clip(CircleShape)
        .background(if (enabled) Palette.sunset else Brush.linearGradient(listOf(Palette.fg.copy(alpha = 0.12f), Palette.fg.copy(alpha = 0.12f))))
        .clickable(interaction, ripple(color = Palette.cream), enabled = enabled, role = Role.Button) { haptics.tap(); onClick() }
        .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) {
                Icon(icon, null, tint = if (enabled) Palette.night else Palette.muted, modifier = Modifier.size(if (large) 24.dp else 20.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(text, style = if (large) Type.headline.copy(fontWeight = FontWeight.Bold) else Type.bodyStrong.copy(fontWeight = FontWeight.Bold),
                color = if (enabled) Palette.night else Palette.muted, textAlign = TextAlign.Center)
        }
    }
}

/** A quieter action: glass with a hairline edge. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true,
                    tint: Color = Palette.fg) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    Box(modifier.pressScale(interaction).heightIn(min = 52.dp).glass(CircleShape)
        .clickable(interaction, ripple(color = Palette.fg), enabled = enabled, role = Role.Button) { haptics.tap(); onClick() }
        .padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = if (enabled) tint else Palette.muted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = Type.bodyStrong, color = if (enabled) tint else Palette.muted, textAlign = TextAlign.Center)
        }
    }
}

/** A small text action inside a card, with a full-size touch target. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Palette.amber, enabled: Boolean = true) {
    val haptics = rememberHaptics()
    Box(modifier.minimumInteractiveComponentSize().clip(CircleShape)
        .clickable(enabled = enabled, role = Role.Button) { haptics.tap(); onClick() }
        .padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, style = Type.label.copy(fontWeight = FontWeight.Bold), color = if (enabled) color else Palette.muted)
    }
}

/** An icon-only button: always labelled for screen readers, always 48 dp. */
@Composable
fun IconAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Palette.fg,
               filled: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    Box(modifier.size(48.dp).pressScale(interaction, 0.88f).clip(CircleShape)
        .then(if (filled) Modifier.glass(CircleShape) else Modifier)
        .clickable(interaction, ripple(bounded = false, radius = 24.dp, color = tint), role = Role.Button, onClickLabel = null) { haptics.tap(); onClick() }
        .semantics { this.contentDescription = label },
        contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** An icon in a soft tinted circle, for rows and cards. */
@Composable
fun IconBadge(icon: ImageVector, tint: Color = Palette.amber, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(CircleShape)
        .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.28f), tint.copy(alpha = 0.10f))))
        .border(1.dp, tint.copy(alpha = 0.3f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** An on/off switch whose thumb springs across and whose track fills with the accent. */
@Composable
fun Toggle(checked: Boolean, modifier: Modifier = Modifier) {
    val spec = motion<Dp>(spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium))
    val offset by animateDpAsState(if (checked) 22.dp else 0.dp, spec, label = "thumb")
    val thumb by animateDpAsState(if (checked) 24.dp else 18.dp, spec, label = "thumbSize")
    val off = Palette.fg.copy(alpha = 0.14f)
    Box(modifier.size(54.dp, 32.dp).clip(CircleShape)
        .background(if (checked) Palette.sunset else Brush.linearGradient(listOf(off, off)))
        .border(1.dp, Palette.fg.copy(alpha = if (checked) 0f else 0.18f), CircleShape)
        .padding(4.dp), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.offset { androidx.compose.ui.unit.IntOffset((offset + (24.dp - thumb) / 2).roundToPx(), 0) }.size(thumb).clip(CircleShape)
            .background(if (checked) Palette.cream else Palette.muted))
    }
}

/** A setting that switches on and off; the whole row is the touch target. */
@Composable
fun ToggleRow(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier,
              icon: ImageVector? = null, tint: Color = Palette.amber) {
    val haptics = rememberHaptics()
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(18.dp))
        .toggleable(checked, role = Role.Switch) { haptics.tick(); onChange(it) }
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null && roomy()) { IconBadge(icon, tint); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.bodyStrong, color = Palette.fg)
            if (detail != null) Text(detail, style = Type.label.copy(fontWeight = FontWeight.Normal), color = Palette.muted)
        }
        Spacer(Modifier.width(12.dp))
        Toggle(checked)
    }
}

/** A row that opens something: icon, title, summary and a chevron (or [trailing]). */
@Composable
fun NavRow(title: String, summary: String?, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Palette.amber,
           trailing: (@Composable () -> Unit)? = null) {
    val haptics = rememberHaptics()
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(18.dp))
        .clickable(role = Role.Button) { haptics.tap(); onClick() }
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (roomy()) { IconBadge(icon, tint); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.bodyStrong, color = Palette.fg)
            if (summary != null) Text(summary, style = Type.label.copy(fontWeight = FontWeight.Normal), color = Palette.muted)
        }
        Spacer(Modifier.width(8.dp))
        if (trailing != null) trailing()
        else Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Palette.muted, modifier = Modifier.size(24.dp))
    }
}

/** A thin line between rows in a card. */
@Composable
fun Divider() {
    Box(Modifier.fillMaxWidth().padding(start = 70.dp, end = 16.dp).height(1.dp).background(Palette.fg.copy(alpha = 0.08f)))
}

/** A card of rows. */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().glass().padding(vertical = 4.dp), content = content)
}

/** A section heading. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 6.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = Type.overline, color = Palette.muted, modifier = Modifier.weight(1f).semantics { heading() })
        action?.invoke()
    }
}

/** A hint or problem as a glass card, with a way to dismiss it. */
@Composable
fun HintCard(text: String, icon: ImageVector, onDismiss: (() -> Unit)?, modifier: Modifier = Modifier, tint: Color = Palette.amber) {
    Row(modifier.fillMaxWidth().glass(tileShape, edge = tint.copy(alpha = 0.45f)).padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, tint, 36.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, style = Type.label.copy(fontWeight = FontWeight.Normal), color = Palette.cream, modifier = Modifier.weight(1f).padding(vertical = 4.dp))
        if (onDismiss != null) IconAction(Icons.Rounded.Close, S.dismiss, onDismiss, tint = Palette.muted)
        else Spacer(Modifier.width(10.dp))
    }
}

/** A hint that slides away when dismissed. */
@Composable
fun DismissibleHint(visible: Boolean, text: String, icon: ImageVector, onDismiss: (() -> Unit)?, tint: Color = Palette.amber) {
    AnimatedVisibility(visible, enter = fadeIn(motion()) + expandVertically(motion()), exit = fadeOut(motion()) + shrinkVertically(motion())) {
        HintCard(text, icon, onDismiss, tint = tint)
    }
}

/** A status chip: a dot (glowing when [lit]) and a label, or an icon. */
@Composable
fun StatusChip(text: String, lit: Boolean, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val dot by animateColorAsState(if (lit) Palette.ok else Palette.muted, motion(), label = "dot")
    Row(modifier.glass(CircleShape, edge = if (lit) Palette.ok.copy(alpha = 0.5f) else null)
        .padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(icon, null, tint = if (lit) Palette.ok else Palette.muted, modifier = Modifier.size(16.dp))
        else Box(Modifier.size(8.dp).drawBehind {
            if (lit) drawCircle(dot.copy(alpha = 0.35f), radius = size.minDimension)
            drawCircle(dot)
        })
        Spacer(Modifier.width(8.dp))
        Text(text, style = Type.label.copy(fontWeight = FontWeight.SemiBold), color = Palette.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A screen title, big, with an optional back button and actions. */
@Composable
fun ScreenTitle(title: String, modifier: Modifier = Modifier, subtitle: String? = null, onBack: (() -> Unit)? = null,
                actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconAction(Icons.AutoMirrored.Rounded.ArrowBack, S.back, onBack, filled = true)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = if (onBack != null) Type.title else Type.display, color = Palette.fg, modifier = Modifier.semantics { heading() })
            if (subtitle != null) Text(subtitle, style = Type.label.copy(fontWeight = FontWeight.Normal), color = Palette.muted)
        }
        actions()
    }
}

/** An empty screen: a small drawing of a phone and a computer with things flowing between them, a title and a line. */
@Composable
fun EmptyState(title: String, detail: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(180.dp, 120.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize()) {
                val w = size.width; val h = size.height
                val u = h / 120f
                drawCircle(Brush.radialGradient(listOf(Palette.violet.copy(alpha = 0.35f), Color.Transparent), center = center, radius = w * 0.55f), w * 0.55f, center)
                // Laptop on the left, phone on the right.
                val lap = androidx.compose.ui.geometry.Rect(Offset(w * 0.08f, h * 0.30f), androidx.compose.ui.geometry.Size(w * 0.38f, h * 0.38f))
                drawRoundRect(Palette.fg.copy(alpha = 0.10f), lap.topLeft, lap.size, androidx.compose.ui.geometry.CornerRadius(8 * u))
                drawRoundRect(Palette.fg.copy(alpha = 0.55f), lap.topLeft, lap.size, androidx.compose.ui.geometry.CornerRadius(8 * u), style = Stroke(2.5f * u))
                drawRoundRect(Palette.fg.copy(alpha = 0.55f), Offset(w * 0.03f, lap.bottom + 4 * u), androidx.compose.ui.geometry.Size(w * 0.48f, 6 * u),
                    androidx.compose.ui.geometry.CornerRadius(3 * u))
                val phone = androidx.compose.ui.geometry.Rect(Offset(w * 0.68f, h * 0.22f), androidx.compose.ui.geometry.Size(w * 0.20f, h * 0.58f))
                drawRoundRect(Palette.fg.copy(alpha = 0.10f), phone.topLeft, phone.size, androidx.compose.ui.geometry.CornerRadius(10 * u))
                drawRoundRect(Palette.fg.copy(alpha = 0.55f), phone.topLeft, phone.size, androidx.compose.ui.geometry.CornerRadius(10 * u), style = Stroke(2.5f * u))
                // Dots travelling the bridge between them.
                for (i in 0 until 5) {
                    val x = w * (0.50f + 0.035f * i)
                    val y = h * 0.49f - kotlin.math.sin(i / 4f * Math.PI).toFloat() * 14 * u
                    drawCircle(androidx.compose.ui.graphics.lerp(Palette.pink, Palette.amber, i / 4f), (2.5f + i * 0.4f) * u, Offset(x, y))
                }
            }
            Box(Modifier.offset(x = (-40).dp, y = (-2).dp)) { Icon(icon, null, tint = Palette.amber, modifier = Modifier.size(26.dp)) }
        }
        Spacer(Modifier.height(12.dp))
        Text(title, style = Type.headline, color = Palette.fg, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(detail, style = Type.body, color = Palette.muted, textAlign = TextAlign.Center)
    }
}

/** A slim progress bar in the accent gradient, its fill springing to [progress]. */
@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier) {
    val shown by animateFloatAsState(progress.coerceIn(0f, 1f), motion(spring(stiffness = Spring.StiffnessLow)), label = "progress")
    Box(modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Palette.fg.copy(alpha = 0.10f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(shown).clip(CircleShape).background(Palette.sunset))
    }
}

/** A gentle shimmer for things on their way (Starting…). */
@Composable
fun Placeholder(modifier: Modifier = Modifier) {
    val still = LocalMotion.current.still
    val alpha = if (still) null else {
        val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "shimmer")
        transition.animateFloat(0.05f, 0.14f, androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "alpha")
    }
    // Read in the draw phase only: the shimmer redraws without recomposing.
    Box(modifier.clip(tileShape).drawBehind { drawRect(Palette.fg.copy(alpha = alpha?.value ?: 0.08f)) })
}

/** Mixes two colours. */
fun lerpColor(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)

/** Room for an icon beside a row's text: not when the text is very large. */
@Composable
fun roomy() = androidx.compose.ui.platform.LocalDensity.current.fontScale <= 1.5f

/** The window's height, to keep the hero from filling a short screen (no limit before the window is measured). */
@Composable
fun windowHeight(): Dp {
    val height = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height
    return if (height <= 0) 2000.dp else with(androidx.compose.ui.platform.LocalDensity.current) { height.toDp() }
}
