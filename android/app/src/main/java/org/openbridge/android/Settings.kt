package org.openbridge.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openbridge.ui.Look
import org.openbridge.ui.Palette
import org.openbridge.ui.SunEngine
import org.openbridge.ui.drawBackdrop
import org.openbridge.ui.drawHero

private const val PROJECT = "https://github.com/PredatorWoah/OpenBridge"

/** The Settings sheet: look, what to share, background sharing, the phone hotspot, updates, diagnostics, about. */
@Composable
fun ColumnScope.SettingsContent(actions: Actions) {
    SectionTitle(S.look)
    LookPicker()

    SectionTitle(S.whatToShare)
    val sharing = Sharing.server != null || Sharing.busy
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val folder = Sharing.folder?.lastPathSegment?.substringAfterLast(':')?.ifEmpty { S.storage } ?: S.oneFolderDetail
        // Side by side when there's room; stacked on narrow screens or with large text.
        if (maxWidth >= 340.dp) Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChoiceTile(S.wholePhone, S.wholePhoneDetail, Sharing.wholePhone, !sharing, actions::chooseWholePhone, Modifier.weight(1f).fillMaxHeight())
            ChoiceTile(S.oneFolder, folder, !Sharing.wholePhone, !sharing, actions::chooseFolder, Modifier.weight(1f).fillMaxHeight())
        } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ChoiceTile(S.wholePhone, S.wholePhoneDetail, Sharing.wholePhone, !sharing, actions::chooseWholePhone, Modifier.fillMaxWidth())
            ChoiceTile(S.oneFolder, folder, !Sharing.wholePhone, !sharing, actions::chooseFolder, Modifier.fillMaxWidth())
        }
    }
    if (sharing) Text(S.stopToChange, color = Palette.muted, fontSize = 13.sp)
    ToggleRow(S.keepInBackground, if (Sharing.inBackground) S.backgroundOn else S.backgroundOff, Sharing.inBackground, Sharing::keepInBackground)

    SectionTitle(S.notificationsOnComputers)
    NotificationsPanel(actions)

    SectionTitle(S.phoneHotspot)
    HotspotPanel(actions)

    SectionTitle(S.updates)
    Updates(actions)

    SectionTitle(S.help)
    Text(S.diagnosticsDetail, color = Palette.muted, fontSize = 13.sp)
    GhostButton(S.copyDiagnostics, actions::copyDiagnostics, Modifier.fillMaxWidth())

    SectionTitle(S.about)
    Text("OpenBridge ${BuildConfig.VERSION_NAME}", color = Palette.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Text(S.aboutDetail, color = Palette.muted, fontSize = 13.sp)
    SmallAction(S.githubPage, { actions.open(PROJECT) }, Modifier.semantics { contentDescription = S.openGithub })
}

/** One card per look, each with its own live backdrop and hero; drafts say "Preview". */
@Composable
private fun LookPicker() {
    val still = reduceMotion()
    val engines = remember { Look.entries.associateWith { SunEngine() } }
    var time by remember { mutableFloatStateOf(7f) }
    var frames by remember { mutableStateOf(Look.entries.associateWith { SunEngine.Frame() }) }
    LaunchedEffect(still) {
        // Settle each hero into its connected state, then keep it moving (about 30 frames a second).
        frames = engines.mapValues { (_, engine) -> var f = SunEngine.Frame(); repeat(150) { f = engine.step(1.0 / 60, true, true, still) }; f }
        if (still) return@LaunchedEffect
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
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 300.dp
        val cards: @Composable (Modifier) -> Unit = { modifier ->
            for (look in Look.entries) LookCard(look, engines.getValue(look), frames.getValue(look), time, modifier)
        }
        if (stacked) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { cards(Modifier.fillMaxWidth()) }
        else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { cards(Modifier.weight(1f)) }
    }
}

@Composable
private fun LookCard(look: Look, engine: SunEngine, frame: SunEngine.Frame, time: Float, modifier: Modifier) {
    val selected = Palette.look == look
    val shape = RoundedCornerShape(18.dp)
    Column(modifier.clip(shape)
        .then(if (selected) Modifier.border(2.dp, Palette.sunset, shape) else Modifier.border(1.dp, Palette.fg.copy(alpha = 0.16f), shape))
        .selectable(selected, role = Role.RadioButton) { Sharing.chooseLook(look) }
        .background(Palette.fg.copy(alpha = 0.05f))
        .padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(13.dp))) {
            drawBackdrop(look, time, energy = 1f, level = 0.35f)
            val center = Offset(size.width / 2, size.height * 0.45f)
            val r = size.minDimension * 0.27f
            if (look == Palette.look) drawHero(engine, frame, center, r, r * 2.5f, look)
            else {
                // Dusk's hero takes its colours from the current look: draw it with its own, in a throwaway snapshot
                // so nothing else sees the change.
                val preview = Snapshot.takeMutableSnapshot()
                try { preview.enter { Palette.look = look; drawHero(engine, frame, center, r, r * 2.5f, look) } } finally { preview.dispose() }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(S.lookName(look), color = Palette.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        if (look.draft) Text(S.preview, color = Palette.night, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 2.dp, bottom = 2.dp).clip(CircleShape).background(Palette.amber).padding(horizontal = 8.dp, vertical = 1.dp))
    }
}

@Composable
private fun ChoiceTile(title: String, detail: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.glass(border = if (selected) Palette.amber.copy(alpha = 0.7f) else Palette.fg.copy(alpha = 0.16f))
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = if (enabled || selected) Palette.fg else Palette.muted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Box(Modifier.size(18.dp).clip(CircleShape).then(
                if (selected) Modifier.background(Palette.sunset) else Modifier.border(2.dp, Palette.fg.copy(alpha = 0.3f), CircleShape)))
        }
        Spacer(Modifier.height(3.dp))
        Text(detail, color = Palette.muted, fontSize = 12.5.sp, maxLines = 3)
    }
}

/**
 * Notifications on computers: the switch (which asks for Android's notification access when it's missing), whether
 * access is allowed, and the apps whose notifications computers see.
 */
@Composable
private fun NotificationsPanel(actions: Actions) {
    var appsOpen by rememberSaveable { mutableStateOf(false) }
    val on = Mirror.enabled && Mirror.access
    ToggleRow(S.mirrorTitle, when {
        Mirror.enabled && !Mirror.access -> S.mirrorNeedsAccess
        on -> S.mirrorOn
        else -> S.mirrorOff
    }, on, { enable ->
        Mirror.enable(enable)
        if (enable && !Mirror.access) actions.notificationAccess()
    })
    Text(S.mirrorExplain, color = Palette.muted, fontSize = 13.sp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (Mirror.access) S.accessAllowed else S.accessMissing, color = if (Mirror.access) Palette.ok else Palette.muted, fontSize = 13.sp,
            modifier = Modifier.weight(1f))
        SmallAction(S.notificationAccess, actions::notificationAccess)
    }
    if (Mirror.enabled) {
        val apps = Mirror.seen.sortedBy { Mirror.label(it).lowercase() }
        ExpandableCard(S.mirrorApps, S.mirrorAppsSummary(apps.count { it in Mirror.excluded }, apps.size), appsOpen, { appsOpen = !appsOpen }) {
            for (app in apps) AppRow(app)
        }
    }
}

/** One app in the Apps list: its icon and name, and whether its notifications go to computers. */
@Composable
private fun AppRow(packageName: String) {
    val label = remember(packageName) { Mirror.label(packageName) }
    val icon = remember(packageName) {
        runCatching { Mirror.icon(packageName)?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull()
    }
    val shown = packageName !in Mirror.excluded
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(shown, role = Role.Checkbox) { Mirror.include(packageName, it) }
        .semantics { contentDescription = S.showApp(label) }
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
        else Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(Palette.fg.copy(alpha = 0.1f)))
        Spacer(Modifier.width(12.dp))
        Text(label, color = Palette.fg, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
        Checkbox(shown, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = Palette.pink, uncheckedColor = Palette.muted,
            checkmarkColor = Palette.cream))
    }
}

/** The phone's own hotspot: its name and password, big, for joining it from the computer. */
@Composable
private fun HotspotPanel(actions: Actions) {
    val network = Hotspot.network
    val served = Sharing.endpoints.firstOrNull { it.via == Via.HOTSPOT }
    Column(Modifier.fillMaxWidth().glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            network != null -> {
                Text(S.hotspotJoin, color = Palette.fg, fontSize = 14.sp)
                Secret(S.networkName, network.name) { actions.copy(network.name) }
                network.password?.let { password -> Secret(S.password, password) { actions.copy(password, sensitive = true) } }
                Text(when {
                    served != null -> S.hotspotServing(served.host, served.port)
                    Sharing.server == null -> S.hotspotStartSharing
                    else -> S.starting
                }, color = Palette.muted, fontSize = 13.sp)
                GhostButton(S.turnOffHotspot, { actions.hotspot(false) }, Modifier.fillMaxWidth())
            }
            else -> {
                // The phone's own hotspot first: the user picks its name and password once, and the computer remembers it.
                Text(S.hotspotOwn, color = Palette.muted, fontSize = 13.sp)
                if (served != null) Text(S.hotspotServing(served.host, served.port), color = Palette.ok, fontSize = 13.sp)
                AccentButton(S.openHotspotSettings, { actions.hotspotSettings() }, Modifier.fillMaxWidth())
                if (Hotspot.supported) {
                    Text(S.temporaryHotspotExplain, color = Palette.muted, fontSize = 13.sp)
                    GhostButton(if (Hotspot.starting) S.starting else S.temporaryHotspot, { actions.hotspot(true) }, Modifier.fillMaxWidth(),
                        enabled = !Hotspot.starting)
                }
            }
        }
        Hotspot.problem?.let { Text(it, color = Palette.amber, fontSize = 13.sp) }
    }
}

/** A value to read out and type on the computer, with Copy. */
@Composable
private fun Secret(label: String, value: String, onCopy: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Palette.muted, fontSize = 12.sp)
            SelectionContainer { Text(value, color = Palette.fg, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) }
        }
        SmallAction(S.copy, onCopy, Modifier.semantics { contentDescription = S.copyThing(label) })
    }
}

@Composable
private fun Updates(actions: Actions) {
    val release = Updater.available
    when {
        release != null -> {
            Text(S.update(release.version), color = Palette.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AccentButton(S.updateNow, actions::update, Modifier.weight(1f), enabled = !Updater.busy)
                GhostButton(S.whatsNew, { actions.open(release.page) }, Modifier.weight(1f))
            }
            Updater.problem?.let { Text(it, color = Palette.amber, fontSize = 13.sp) }
        }
        else -> {
            if (Updater.upToDate) Text(S.upToDate, color = Palette.ok, fontSize = 13.sp)
            GhostButton(if (Updater.checking) S.checking else S.checkForUpdates, { Updater.check(manual = true) }, Modifier.fillMaxWidth(),
                enabled = !Updater.checking)
        }
    }
}
