package org.openbridge.android

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.openbridge.ui.Palette

/** The four places of the app, in the bottom bar's order. */
enum class Tab { HOME, ACTIVITY, TEXTS, SETTINGS }

/** Settings' own pages, over the Settings screen. */
enum class Page { NONE, TRUSTED, NOTIFICATIONS, HOTSPOT, HELP }

/**
 * The whole app: the look's backdrop, the current tab, Settings' pages on top, the floating bottom bar, and the pairing
 * request over everything. Back goes from a page to Settings (following the finger, with predictive back), from a tab
 * to Home, and from Home out of the app.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun App(model: AppModel, actions: Actions, startTab: Tab = Tab.HOME, startPage: Page = Page.NONE) {
    var tab by rememberSaveable { mutableStateOf(startTab) }
    var page by rememberSaveable { mutableStateOf(startPage) }
    val motion = LocalMotion.current
    val share = model.share
    val pairing = model.pairing
    val hero = rememberHero(share.running || share.byCable, share.connected, visible = tab == Tab.HOME && pairing == null && !model.qr.scanning)
    val ime = WindowInsets.isImeVisible

    // Activity's badge: events that arrived since it was last opened.
    val events = model.events
    var seen by rememberSaveable { mutableLongStateOf(events.maxOfOrNull { it.id } ?: 0L) }
    if (tab == Tab.ACTIVITY) LaunchedEffect(events) { seen = events.maxOfOrNull { it.id } ?: seen }
    val unseen = remember(events, seen) { events.count { it.id > seen } }

    BackHandler(enabled = tab != Tab.HOME && page == Page.NONE) { tab = Tab.HOME }

    val density = LocalDensity.current
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // Room under each screen's content for the floating bar.
    val bottom = if (ime) 16.dp else navBottom + 100.dp
    val padding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 8.dp, bottom = bottom)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    AppBackdrop(energy = if (share.connected) 1f else if (share.sharing) 0.45f else 0f, level = model.collecting, modifier = Modifier.fillMaxSize()) {
        AnimatedContent(tab, Modifier.fillMaxSize(), label = "tab", transitionSpec = {
            if (motion.still) EnterTransition.None togetherWith ExitTransition.None
            else {
                val forward = (targetState.ordinal > initialState.ordinal) != rtl
                val sign = if (forward) 1 else -1
                (slideInHorizontally(spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)) { sign * it / 5 } +
                    fadeIn(tween(220, delayMillis = 60)) + scaleIn(fluid(), initialScale = 0.98f)) togetherWith
                    (slideOutHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { -sign * it / 8 } + fadeOut(tween(140)))
            }
        }) { shown ->
            when (shown) {
                Tab.HOME -> HomeScreen(model, actions, hero, padding) { tab = it }
                Tab.ACTIVITY -> ActivityScreen(model, actions, padding)
                Tab.TEXTS -> TextsScreen(model, actions, padding)
                Tab.SETTINGS -> Box(Modifier.fillMaxSize()) {
                    SettingsScreen(model, actions, padding) { page = it }
                    SettingsPages(page, model, actions, padding) { page = Page.NONE }
                }
            }
        }

        // The status bar's backdrop: content scrolls softly under it.
        Box(Modifier.fillMaxWidth().height(top + 12.dp).background(Brush.verticalGradient(listOf(Palette.night.copy(alpha = 0.85f), Color.Transparent))))

        AnimatedVisibility(!ime, Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(motion()) { it } + fadeIn(motion()), exit = slideOutVertically(motion()) { it } + fadeOut(motion())) {
            BottomBar(tab, unseen, Modifier.padding(start = 16.dp, end = 16.dp, bottom = navBottom + 12.dp)) {
                if (it == tab && it == Tab.SETTINGS) page = Page.NONE
                tab = it
            }
        }

        PairingDialog(pairing, actions)
        QrPairingLayer(model.qr, model.now, actions)
    }
}

/** Settings' pages slide in over Settings, and follow a predictive back gesture out. */
@Composable
private fun SettingsPages(page: Page, model: AppModel, actions: Actions, padding: PaddingValues, onBack: () -> Unit) {
    val motion = LocalMotion.current
    val back = remember { Animatable(0f) }
    PredictiveBackHandler(enabled = page != Page.NONE) { progress ->
        try {
            progress.collect { back.snapTo(it.progress) }
            onBack()
        } catch (_: CancellationException) {
            back.animateTo(0f, fluid())
        }
    }
    LaunchedEffect(page) { if (page != Page.NONE) back.snapTo(0f) }
    AnimatedContent(page, label = "page", transitionSpec = {
        if (motion.still) EnterTransition.None togetherWith ExitTransition.None
        else if (targetState != Page.NONE) (slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn()) togetherWith fadeOut(tween(90))
        else EnterTransition.None togetherWith (slideOutHorizontally(spring(stiffness = Spring.StiffnessMedium)) { it } + fadeOut(tween(250)))
    }) { shown ->
        if (shown == Page.NONE) Spacer(Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize()
            .graphicsLayer {
                val p = back.value
                scaleX = 1f - 0.08f * p; scaleY = scaleX
                translationX = p * 40.dp.toPx()
                shape = RoundedCornerShape(32.dp * p); clip = p > 0f
            }
            .background(Palette.sky)
            .semantics { paneTitle = pageTitle(shown) }) {
            when (shown) {
                Page.TRUSTED -> TrustedPage(model, actions, padding, onBack)
                Page.NOTIFICATIONS -> NotificationsPage(model, actions, padding, onBack)
                Page.HOTSPOT -> HotspotPage(model, actions, padding, onBack)
                Page.HELP -> HelpPage(model, actions, padding, onBack)
                Page.NONE -> {}
            }
        }
    }
}

private fun pageTitle(page: Page) = when (page) {
    Page.TRUSTED -> S.trusted
    Page.NOTIFICATIONS -> S.notificationsOnComputers
    Page.HOTSPOT -> S.phoneHotspot
    Page.HELP -> S.helpDiagnostics
    Page.NONE -> S.settings
}

private class TabItem(val tab: Tab, val icon: ImageVector, val label: () -> String)

private val tabs = listOf(
    TabItem(Tab.HOME, Icons.Rounded.Home) { S.tabHome },
    TabItem(Tab.ACTIVITY, Icons.Rounded.Bolt) { S.tabActivity },
    TabItem(Tab.TEXTS, Icons.Rounded.ChatBubble) { S.tabTexts },
    TabItem(Tab.SETTINGS, Icons.Rounded.Settings) { S.settings },
)

/** The floating glass bar: the selected tab's pill slides under it on a spring, and its icon pops. */
@Composable
private fun BottomBar(selected: Tab, unseen: Int, modifier: Modifier = Modifier, onSelect: (Tab) -> Unit) {
    val haptics = rememberHaptics()
    BoxWithConstraints(modifier.fillMaxWidth().widthIn(max = 520.dp)
        .drawBehind {
            // A soft shadow of night under the glass.
            drawRoundRect(Brush.verticalGradient(listOf(Color.Transparent, Palette.night.copy(alpha = 0.6f))),
                topLeft = androidx.compose.ui.geometry.Offset(0f, size.height * 0.2f),
                size = androidx.compose.ui.geometry.Size(size.width, size.height * 1.0f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
        }
        .clip(RoundedCornerShape(34.dp))
        .background(Brush.verticalGradient(listOf(Palette.plum, Palette.dusk)))
        .glass(RoundedCornerShape(34.dp))
        .padding(6.dp)) {
        val item = maxWidth / tabs.size
        // Labels grow with the font size up to 130%: past that they would no longer fit four across (screen readers still read them).
        val scale = LocalDensity.current.fontScale
        val labelSize = (Type.caption.fontSize.value * minOf(scale, 1.3f) / scale).sp
        val index = tabs.indexOfFirst { it.tab == selected }
        val x by animateDpAsState(item * index, motion(spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)), label = "indicator")
        // The indicator pill, under the items, as tall as they are.
        Box(Modifier.matchParentSize()) {
            Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(x.roundToPx(), 0) }.width(item).fillMaxHeight().clip(RoundedCornerShape(28.dp))
                .background(Brush.verticalGradient(listOf(Palette.amber.copy(alpha = 0.20f), Palette.fg.copy(alpha = 0.06f)))))
        }
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            for (entry in tabs) {
                val isSelected = entry.tab == selected
                val pop by animateFloatAsState(if (isSelected) 1.12f else 1f, motion(bouncy()), label = "pop")
                val tint by animateColorAsState(if (isSelected) Palette.amber else Palette.muted, motion(), label = "tint")
                val label = entry.label()
                Column(Modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedCornerShape(28.dp))
                    .selectable(isSelected, role = Role.Tab) { if (!isSelected) haptics.tick(); onSelect(entry.tab) }
                    .semantics { if (entry.tab == Tab.ACTIVITY && unseen > 0) contentDescription = "$label, ${S.newItems(unseen)}" }
                    .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box {
                        Icon(entry.icon, null, tint = tint, modifier = Modifier.size(24.dp).graphicsLayer { scaleX = pop; scaleY = pop })
                        if (entry.tab == Tab.ACTIVITY && unseen > 0) Box(Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp)
                            .clip(CircleShape).background(Palette.pink).padding(horizontal = 5.dp)) {
                            Text(if (unseen > 9) "9+" else unseen.toString(), style = Type.caption.copy(fontWeight = FontWeight.Bold), color = Palette.night)
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(label, style = Type.caption.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, fontSize = labelSize), color = tint,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/**
 * A computer asks to pair: the six digits to compare pop in one by one, a line counts down the minute it has, and Pair
 * or Cancel decides. Over everything; Back cancels.
 */
@Composable
private fun BoxScope.PairingDialog(pairing: PairingUi?, actions: Actions) {
    // Keep the last request on screen while the dialog leaves.
    var last by remember { mutableStateOf(pairing) }
    if (pairing != null) last = pairing
    val shown = pairing ?: last ?: return
    val motion = LocalMotion.current
    val haptics = rememberHaptics()
    BackHandler(enabled = pairing != null) { haptics.reject(); actions.decidePairing(false) }
    LaunchedEffect(pairing?.code) { if (pairing != null && !motion.still) haptics.heavy() }
    AnimatedVisibility(pairing != null, Modifier.matchParentSize(), enter = fadeIn(motion()), exit = fadeOut(motion())) {
        Box(Modifier.fillMaxSize().background(Palette.night.copy(alpha = 0.72f))
            .clickable(remember { MutableInteractionSource() }, indication = null) {})
    }
    AnimatedVisibility(pairing != null, Modifier.align(Alignment.Center),
        enter = if (motion.still) fadeIn(motion()) else scaleIn(spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.85f) + fadeIn(),
        exit = if (motion.still) fadeOut(motion()) else scaleOut(targetScale = 0.92f) + fadeOut()) {
        Column(Modifier.padding(20.dp).widthIn(max = 440.dp).fillMaxWidth()
            .glass(RoundedCornerShape(32.dp), strong = true, edge = Palette.amber.copy(alpha = 0.45f))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 26.dp)
            .semantics { paneTitle = S.pairingRequest },
            horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                PulseRing(still = motion.still)
                IconBadge(Icons.Rounded.Laptop, Palette.amber, 60.dp)
            }
            Spacer(Modifier.height(16.dp))
            Text(S.pairingRequest.uppercase(), style = Type.overline, color = Palette.amber)
            Spacer(Modifier.height(6.dp))
            Text(S.pairWith(shown.computer), style = Type.title, color = Palette.fg, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(S.sameCode, style = Type.body, color = Palette.muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            CodeDigits(shown.code, Modifier.semantics(mergeDescendants = true) {
                contentDescription = S.codeForReader(shown.code); liveRegion = LiveRegionMode.Assertive
            })
            Spacer(Modifier.height(18.dp))
            Countdown(shown.code, still = motion.still)
            Spacer(Modifier.height(12.dp))
            Text(S.codesDiffer, style = Type.caption, color = Palette.muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                SecondaryButton(S.cancel, { haptics.reject(); actions.decidePairing(false) }, Modifier.weight(1f))
                PrimaryButton(S.pair, { haptics.confirm(); actions.decidePairing(true) }, Modifier.weight(1.3f))
            }
        }
    }
}

/** Two groups of three digits, each popping in after the one before. */
@Composable
private fun CodeDigits(code: String, modifier: Modifier = Modifier) {
    val motion = LocalMotion.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        code.forEachIndexed { index, digit ->
            if (index == 3) Spacer(Modifier.width(12.dp))
            val pop = remember(code) { Animatable(if (motion.still) 1f else 0f) }
            LaunchedEffect(code) {
                if (!motion.still) { delay(80L * index + 120); pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)) }
            }
            Box(Modifier.weight(1f, fill = false).widthIn(min = 38.dp, max = 54.dp).padding(horizontal = 3.dp)
                .graphicsLayer { scaleX = 0.6f + 0.4f * pop.value; scaleY = scaleX; alpha = pop.value.coerceIn(0f, 1f) }
                .glass(RoundedCornerShape(14.dp), edge = Palette.amber.copy(alpha = 0.35f))
                .padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text(digit.toString(), style = Type.mono.copy(fontSize = Type.mono.fontSize * 1.1f), color = Palette.fg, textAlign = TextAlign.Center)
            }
        }
    }
}

/** The minute a request lasts, shrinking. */
@Composable
private fun Countdown(code: String, still: Boolean) {
    val left = remember(code) { Animatable(1f) }
    LaunchedEffect(code) {
        if (still) left.snapTo(0.78f) else left.animateTo(0f, tween(60_000, easing = LinearEasing))
    }
    Box(Modifier.fillMaxWidth(0.6f).height(4.dp).clip(CircleShape).background(Palette.fg.copy(alpha = 0.10f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(left.value).clip(CircleShape).background(Palette.sunset))
    }
}

/** Rings that spread from the computer icon, like a call coming in. */
@Composable
private fun PulseRing(still: Boolean) {
    val transition = if (still) null else androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
    val t = transition?.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "t")
    Box(Modifier.size(110.dp).drawBehind {
        val p = t?.value ?: 0.45f
        for (k in 0 until 2) {
            val q = (p + k * 0.5f) % 1f
            drawCircle(Palette.amber.copy(alpha = 0.35f * (1 - q)), radius = size.minDimension * (0.28f + 0.22f * q),
                style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
    })
}
