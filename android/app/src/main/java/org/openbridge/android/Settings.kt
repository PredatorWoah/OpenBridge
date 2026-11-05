package org.openbridge.android

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openbridge.ui.Look
import org.openbridge.ui.Palette
import org.openbridge.ui.SunEngine
import org.openbridge.ui.drawBackdrop
import org.openbridge.ui.drawHero

private const val PROJECT = "https://github.com/PredatorWoah/OpenBridge"

/** Settings: the look, sharing, computers (with their own pages), updates, and help. */
@Composable
fun SettingsScreen(model: AppModel, actions: Actions, padding: PaddingValues, openPage: (Page) -> Unit) {
    val first = rememberFirstShow()
    val share = model.share
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle(S.settings)

        SectionLabel(S.look, Modifier.entrance(0, first))
        LookPicker(model.look, actions, Modifier.entrance(1, first))

        SectionLabel(S.groupSharing, Modifier.entrance(2, first))
        GroupCard(Modifier.entrance(3, first).selectableGroup()) {
            val locked = share.running
            RadioRow(S.wholePhone, S.wholePhoneDetail, Icons.Rounded.PhoneAndroid, share.wholePhone, !locked, actions::chooseWholePhone)
            Divider()
            RadioRow(S.oneFolder, share.folder ?: S.oneFolderDetail, Icons.Rounded.Folder, !share.wholePhone, !locked, actions::chooseFolder)
            AnimatedVisibility(locked, enter = fadeIn(motion()) + expandVertically(motion()), exit = fadeOut(motion()) + shrinkVertically(motion())) {
                Text(S.stopToChange, style = Type.caption, color = Palette.amber, modifier = Modifier.padding(start = 70.dp, end = 16.dp, bottom = 8.dp))
            }
            Divider()
            ToggleRow(S.keepInBackground, if (share.inBackground) S.backgroundOn else S.backgroundOff, share.inBackground, actions::keepInBackground,
                icon = Icons.Rounded.Schedule, tint = Palette.violet)
        }

        SectionLabel(S.groupComputers, Modifier.entrance(4, first))
        GroupCard(Modifier.entrance(5, first)) {
            val trusted = model.trusted
            NavRow(S.trusted, if (trusted.isEmpty()) S.noTrustedShort else S.trustedCount(trusted.size), Icons.Rounded.VerifiedUser,
                { openPage(Page.TRUSTED) }, tint = Palette.ok)
            Divider()
            val mirror = model.mirror
            NavRow(S.notificationsOnComputers, when {
                mirror.enabled && !mirror.access -> S.notificationsSummaryNeedsAccess
                mirror.on -> S.on
                else -> S.off
            }, Icons.Rounded.Notifications, { openPage(Page.NOTIFICATIONS) }, tint = Palette.pink)
            Divider()
            val hotspot = model.hotspot
            NavRow(S.phoneHotspot, hotspot.name?.let(S::hotspotSummaryOn) ?: S.hotspotSummaryOff, Icons.Rounded.WifiTethering,
                { openPage(Page.HOTSPOT) }, tint = Palette.amber)
        }

        SectionLabel(S.groupApp, Modifier.entrance(6, first))
        GroupCard(Modifier.entrance(7, first)) {
            UpdatesRow(model.update, model.version, actions)
            Divider()
            NavRow(S.helpDiagnostics, S.helpDiagnosticsDetail, Icons.Rounded.BugReport, { openPage(Page.HELP) }, tint = Palette.violet)
        }
        Text("OpenBridge ${model.version}", style = Type.caption, color = Palette.muted, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}

@Composable
private fun RadioRow(title: String, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, enabled: Boolean,
                     onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(18.dp))
        .selectable(selected, enabled = enabled, role = Role.RadioButton) { haptics.tick(); onClick() }
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (roomy()) { IconBadge(icon, if (selected) Palette.amber else Palette.muted); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.bodyStrong, color = if (enabled || selected) Palette.fg else Palette.muted)
            Text(detail, style = Type.label.copy(fontWeight = FontWeight.Normal), color = Palette.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val dot by animateFloatAsState(if (selected) 1f else 0f, motion(bouncy()), label = "radio")
        Box(Modifier.size(24.dp).clip(CircleShape).border(2.dp, if (selected) Palette.amber else Palette.fg.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center) {
            Box(Modifier.size(12.dp).graphicsLayer { scaleX = dot; scaleY = dot }.clip(CircleShape).background(Palette.sunset))
        }
    }
}

/** Updates: Check for updates, or the newer version with Update and What's new. */
@Composable
private fun UpdatesRow(update: UpdateUi, installed: String, actions: Actions) {
    val version = update.version
    Column(Modifier.fillMaxWidth().animateContentSize(motion()).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.SystemUpdate, Palette.amber)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(if (version != null) S.update(version) else S.updates, style = Type.bodyStrong, color = Palette.fg)
                Text(when {
                    update.checking -> S.checking
                    update.upToDate -> S.upToDate
                    update.step == Updater.Step.DOWNLOADING -> S.downloading((update.progress * 100).toInt())
                    else -> S.version(installed)
                }, style = Type.label.copy(fontWeight = FontWeight.Normal), color = if (update.upToDate) Palette.ok else Palette.muted)
            }
            if (version == null) TextAction(if (update.checking) S.checking else S.checkForUpdates, actions::checkForUpdates, enabled = !update.checking)
        }
        if (version != null) {
            if (update.step == Updater.Step.DOWNLOADING) ProgressLine(update.progress, Modifier.padding(top = 10.dp))
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(S.updateNow, actions::update, Modifier.weight(1f), enabled = !update.busy)
                SecondaryButton(S.whatsNew, { update.page?.let(actions::open) }, Modifier.weight(1f))
            }
        }
        update.problem?.let { Text(it, style = Type.caption, color = Palette.amber, modifier = Modifier.padding(top = 8.dp)) }
    }
}


/** One card per look with its own live backdrop and hero; the chosen one has a glowing edge and a check. */
@Composable
private fun LookPicker(current: Look, actions: Actions, modifier: Modifier = Modifier) {
    val motion = LocalMotion.current
    val engines = remember { Look.entries.associateWith { SunEngine() } }
    var time by remember { mutableFloatStateOf(7f) }
    var frames by remember { mutableStateOf(Look.entries.associateWith { SunEngine.Frame() }) }
    LaunchedEffect(motion) {
        // Settle each hero into its connected state, then keep it moving (about 30 frames a second).
        frames = engines.mapValues { (_, engine) -> var f = SunEngine.Frame(); repeat(if (motion.frozen) 230 else 150) { f = engine.step(1.0 / 60, true, true, motion.reduced) }; f }
        if (motion.still) return@LaunchedEffect
        var last = 0L
        while (true) withFrameNanos { now ->
            if (last == 0L) last = now
            val dt = (now - last) / 1e9
            if (dt >= 1.0 / 30) {
                last = now
                time += dt.toFloat().coerceAtMost(0.1f)
                frames = engines.mapValues { (_, engine) -> engine.step(dt.coerceAtMost(1.0 / 20), true, true, false) }
            }
        }
    }
    val big = LocalDensity.current.fontScale > 1.5f
    Column(modifier.fillMaxWidth().glass().padding(12.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup()) {
            if (maxWidth < 270.dp || big) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (look in Look.entries) LookCard(look, current, engines.getValue(look), { frames.getValue(look) }, { time }, actions, Modifier.fillMaxWidth(), wide = true)
            } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (look in Look.entries) LookCard(look, current, engines.getValue(look), { frames.getValue(look) }, { time }, actions, Modifier.weight(1f))
            }
        }
        Text(S.lookHint, style = Type.caption, color = Palette.muted, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
    }
}

@Composable
private fun LookCard(look: Look, current: Look, engine: SunEngine, frame: () -> SunEngine.Frame, time: () -> Float, actions: Actions,
                     modifier: Modifier, wide: Boolean = false) {
    val selected = current == look
    val haptics = rememberHaptics()
    val shape = RoundedCornerShape(20.dp)
    val interaction = remember { MutableInteractionSource() }
    val lift by animateFloatAsState(if (selected) 1f else 0f, motion(bouncy()), label = "lift")
    Column(modifier.pressScale(interaction, 0.95f)
        .graphicsLayer { val s = 0.97f + 0.03f * lift; scaleX = s; scaleY = s }
        .clip(shape)
        .then(if (selected) Modifier.border(2.dp, Palette.sunset, shape) else Modifier.border(1.dp, Palette.fg.copy(alpha = 0.14f), shape))
        .selectable(selected, interaction, androidx.compose.material3.ripple(), role = Role.RadioButton) { haptics.confirm(); actions.chooseLook(look) }
        .background(Palette.fg.copy(alpha = 0.05f))
        .padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            // Drawn in the draw phase only: frames advance without recomposing.
            Canvas(Modifier.fillMaxWidth().height(if (wide) 96.dp else 128.dp).clip(RoundedCornerShape(15.dp))) {
                drawBackdrop(look, time(), energy = 1f, level = 0.35f)
                val center = Offset(size.width / 2, size.height * 0.48f)
                val r = minOf(size.width, size.height) * 0.27f
                if (look == Palette.look) drawHero(engine, frame(), center, r, r * 2.5f, look)
                else {
                    // Dusk's hero takes its colours from the current look: draw it with its own, in a throwaway snapshot.
                    val preview = Snapshot.takeMutableSnapshot()
                    try { preview.enter { Palette.look = look; drawHero(engine, frame(), center, r, r * 2.5f, look) } } finally { preview.dispose() }
                }
            }
            androidx.compose.animation.AnimatedVisibility(selected, Modifier.align(Alignment.TopEnd).padding(6.dp),
                enter = scaleIn(motion(bouncy())) + fadeIn(motion()), exit = scaleOut(motion()) + fadeOut(motion())) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(Palette.sunset), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, null, tint = Palette.night, modifier = Modifier.size(16.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(S.lookName(look), style = Type.bodyStrong, color = Palette.fg, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { if (selected) stateDescription = S.selected })
        if (look.draft) Text(S.preview, style = Type.caption.copy(fontWeight = FontWeight.Bold), color = Palette.night,
            modifier = Modifier.padding(top = 3.dp, bottom = 2.dp).clip(CircleShape).background(Palette.amber).padding(horizontal = 8.dp, vertical = 1.dp))
        else Spacer(Modifier.height(4.dp))
    }
}

/** A page's frame: its title with Back, then [content], scrolling. */
@Composable
private fun PageFrame(title: String, padding: PaddingValues, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle(title, onBack = onBack)
        content()
    }
}

/** Trusted computers: the connected one lit, each with Forget. */
@Composable
fun TrustedPage(model: AppModel, actions: Actions, padding: PaddingValues, onBack: () -> Unit) {
    val computers = model.trusted
    val share = model.share
    val now = model.now
    PageFrame(S.trusted, padding, onBack) {
        if (computers.isEmpty()) EmptyState(S.noTrustedShort, S.noTrusted, Icons.Rounded.VerifiedUser)
        else {
            Text(S.forgetHint, style = Type.body, color = Palette.muted, modifier = Modifier.padding(horizontal = 4.dp))
            computers.forEachIndexed { index, computer ->
                val connected = share.phase == Phase.CONNECTED && share.computer == computer.name
                Row(Modifier.fillMaxWidth().entrance(index).glass(tileShape, edge = if (connected) Palette.ok.copy(alpha = 0.55f) else null)
                    .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.Laptop, if (connected) Palette.ok else Palette.muted, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(computer.name, style = Type.bodyStrong, color = Palette.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (connected) S.connectedNow(share.transport) else S.lastSeen((now - computer.seen) / 86_400_000),
                            style = Type.label.copy(fontWeight = FontWeight.Normal), color = if (connected) Palette.ok else Palette.muted)
                    }
                    TextAction(S.forget, { actions.forget(computer.id) }, Modifier.semantics { contentDescription = S.forgetComputer(computer.name) },
                        color = Palette.pink)
                }
            }
        }
    }
}

/**
 * Notifications on computers: the switch (which asks for Android's notification access when it's missing), whether
 * access is allowed, and the apps whose notifications computers see, with search.
 */
@Composable
fun NotificationsPage(model: AppModel, actions: Actions, padding: PaddingValues, onBack: () -> Unit) {
    val mirror = model.mirror
    var query by rememberSaveable { mutableStateOf("") }
    val apps = remember(mirror.apps, query) {
        if (query.isBlank()) mirror.apps else mirror.apps.filter { it.label.contains(query.trim(), ignoreCase = true) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "title") { ScreenTitle(S.notificationsOnComputers, onBack = onBack) }
        item(key = "switch") {
            GroupCard {
                ToggleRow(S.mirrorTitle, when {
                    mirror.enabled && !mirror.access -> S.mirrorNeedsAccess
                    mirror.on -> S.mirrorOn
                    else -> S.mirrorOff
                }, mirror.on, { enable ->
                    actions.mirror(enable)
                    if (enable && !mirror.access) actions.notificationAccess()
                }, icon = Icons.Rounded.Notifications, tint = Palette.pink)
                Divider()
                // Android's notification access, and the way to its screen.
                NavRow(if (mirror.access) S.accessAllowed else S.accessMissing, S.notificationAccess, Icons.Rounded.Shield, actions::notificationAccess,
                    tint = if (mirror.access) Palette.ok else Palette.muted) {
                    Icon(Icons.Rounded.OpenInNew, null, tint = Palette.amber, modifier = Modifier.size(22.dp))
                }
            }
        }
        item(key = "explain") { HintCard(S.mirrorExplain, Icons.Rounded.Info, null, tint = Palette.violet) }
        if (mirror.enabled) {
            item(key = "apps") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel(S.mirrorApps)
                    Text(S.mirrorAppsSummary(mirror.apps.count { it.packageName in mirror.excluded }, mirror.apps.size), style = Type.label.copy(fontWeight = FontWeight.Normal),
                        color = Palette.muted, modifier = Modifier.padding(horizontal = 6.dp))
                    if (mirror.apps.size > 5) SearchField(query, { query = it })
                }
            }
            if (apps.isEmpty() && mirror.apps.isNotEmpty()) item(key = "none") {
                Text(S.noAppsMatch, style = Type.body, color = Palette.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp))
            }
            items(apps, key = { it.packageName }) { app ->
                AppRow(app, app.packageName !in mirror.excluded, actions, Modifier.animateItem())
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).glass(CircleShape).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Search, null, tint = Palette.muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
            if (query.isEmpty()) Text(S.searchApps, style = Type.body, color = Palette.muted)
            BasicTextField(query, onChange, Modifier.fillMaxWidth().semantics { contentDescription = S.searchApps }, singleLine = true,
                textStyle = Type.body.copy(color = Palette.fg), cursorBrush = SolidColor(Palette.amber))
        }
    }
}

/** One app: its icon (or its initial), its name, and whether its notifications go to computers. */
@Composable
private fun AppRow(app: AppEntry, shown: Boolean, actions: Actions, modifier: Modifier = Modifier) {
    val icon: ImageBitmap? = remember(app.packageName) { runCatching { actions.appIcon(app.packageName) }.getOrNull() }
    val haptics = rememberHaptics()
    Row(modifier.fillMaxWidth().heightIn(min = 60.dp).glass(tileShape)
        .toggleable(shown, role = Role.Switch) { haptics.tick(); actions.showApp(app.packageName, it) }
        .semantics { contentDescription = S.showApp(app.label) }
        .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Image(icon, null, Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)))
        else Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Palette.sunset), contentAlignment = Alignment.Center) {
            Text(app.label.take(1).uppercase(), style = Type.bodyStrong.copy(fontWeight = FontWeight.Bold), color = Palette.night)
        }
        Spacer(Modifier.width(14.dp))
        Text(app.label, style = Type.bodyStrong, color = if (shown) Palette.fg else Palette.muted, modifier = Modifier.weight(1f), maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(10.dp))
        Toggle(shown)
    }
}

/** The phone hotspot: the phone's own first, then a temporary one; while on, its name and password, big. */
@Composable
fun HotspotPage(model: AppModel, actions: Actions, padding: PaddingValues, onBack: () -> Unit) {
    val hotspot = model.hotspot
    val share = model.share
    PageFrame(S.phoneHotspot, padding, onBack) {
        val name = hotspot.name
        if (name != null) Column(Modifier.fillMaxWidth().glass(edge = Palette.ok.copy(alpha = 0.5f)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.WifiTethering, Palette.ok, 48.dp)
                Spacer(Modifier.width(14.dp))
                Text(S.hotspotJoin, style = Type.body, color = Palette.fg, modifier = Modifier.weight(1f))
            }
            Secret(S.networkName, name) { actions.copy(name) }
            hotspot.password?.let { password -> Secret(S.password, password) { actions.copy(password, sensitive = true) } }
            Text(when {
                hotspot.served != null -> S.hotspotServing(hotspot.served.host, hotspot.served.port)
                !share.running -> S.hotspotStartSharing
                else -> S.starting
            }, style = Type.label.copy(fontWeight = FontWeight.Normal), color = if (hotspot.served != null) Palette.ok else Palette.muted)
            SecondaryButton(S.turnOffHotspot, { actions.hotspot(false) }, Modifier.fillMaxWidth())
        } else {
            // The phone's own hotspot first: the user picks its name and password once, and the computer remembers it.
            Column(Modifier.fillMaxWidth().glass().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                IconBadge(Icons.Rounded.WifiTethering, Palette.amber, 48.dp)
                Text(S.hotspotOwn, style = Type.body, color = Palette.fg)
                hotspot.served?.let { Text(S.hotspotServing(it.host, it.port), style = Type.label, color = Palette.ok) }
                PrimaryButton(S.openHotspotSettings, actions::hotspotSettings, Modifier.fillMaxWidth(), icon = Icons.Rounded.OpenInNew)
            }
            if (hotspot.supported) {
                SectionLabel(S.temporaryHotspot)
                Column(Modifier.fillMaxWidth().glass().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(S.temporaryHotspotExplain, style = Type.body, color = Palette.muted)
                    SecondaryButton(if (hotspot.starting) S.starting else S.temporaryHotspot, { actions.hotspot(true) }, Modifier.fillMaxWidth(),
                        enabled = !hotspot.starting, icon = Icons.Rounded.WifiTethering)
                }
            }
        }
        DismissibleHint(hotspot.problem != null, hotspot.problem ?: "", Icons.Rounded.Info, null)
    }
}

/** A value to read out and type on the computer, with Copy. */
@Composable
private fun Secret(label: String, value: String, onCopy: () -> Unit) {
    Row(Modifier.fillMaxWidth().glass(tileShape).padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label.uppercase(), style = Type.overline, color = Palette.muted)
            SelectionContainer { Text(value, style = Type.mono.copy(fontSize = Type.mono.fontSize * 0.82f), color = Palette.fg) }
        }
        IconAction(Icons.Rounded.ContentCopy, S.copyThing(label), onCopy, tint = Palette.amber)
    }
}

/** Help: using a cable, Copy diagnostics, and about. */
@Composable
fun HelpPage(model: AppModel, actions: Actions, padding: PaddingValues, onBack: () -> Unit) {
    PageFrame(S.helpDiagnostics, padding, onBack) {
        Column(Modifier.fillMaxWidth().glass().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.Cable, Palette.violet)
                Spacer(Modifier.width(14.dp))
                Text(S.usingCable, style = Type.headline, color = Palette.fg)
            }
            Text(S.cableHint, style = Type.body, color = Palette.muted)
        }
        Column(Modifier.fillMaxWidth().glass().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.BugReport, Palette.amber)
                Spacer(Modifier.width(14.dp))
                Text(S.help, style = Type.headline, color = Palette.fg)
            }
            Text(S.diagnosticsDetail, style = Type.body, color = Palette.muted)
            SecondaryButton(S.copyDiagnostics, actions::copyDiagnostics, Modifier.fillMaxWidth(), icon = Icons.Rounded.ContentCopy)
        }
        Column(Modifier.fillMaxWidth().glass().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.Info, Palette.ok)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(S.about, style = Type.headline, color = Palette.fg)
                    Text("OpenBridge ${model.version}", style = Type.label, color = Palette.muted)
                }
            }
            Text(S.aboutDetail, style = Type.body, color = Palette.muted)
            TextAction(S.githubPage, { actions.open(PROJECT) }, Modifier.semantics { contentDescription = S.openGithub })
        }
    }
}
