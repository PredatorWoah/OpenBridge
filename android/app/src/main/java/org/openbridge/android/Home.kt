package org.openbridge.android

import android.os.Build
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import org.openbridge.protocol.Outbox
import org.openbridge.ui.Backdrop
import org.openbridge.ui.Palette
import org.openbridge.ui.SunEngine
import org.openbridge.ui.drawHero

/** What the screens ask the activity to do: permissions, pickers, other apps, the clipboard. */
interface Actions {
    /** Starts sharing (asking for the folder or permission first when needed), or stops it. */
    fun toggleSharing()
    fun chooseWholePhone()
    fun chooseFolder()
    fun hotspot(on: Boolean)
    fun update()
    fun open(url: String)
    fun copy(text: String, sensitive: Boolean = false)
    fun sendClipboard()
    fun copyDiagnostics()
}

/** The main screen: the hero to share, where computers reach the phone, and cards for computers, sending and texts. */
@Composable
fun Home(actions: Actions) {
    val model = Sharing
    var settings by rememberSaveable { mutableStateOf(false) }
    val still = reduceMotion()
    val sharing = model.server != null
    val byCable = model.cable == Cable.FILE_TRANSFER
    // The hero lights up for a paired computer, or for one plugged in by cable in File transfer mode.
    val active = sharing || model.busy || byCable
    val connected = model.paired || byCable
    val status = status(model)
    val collecting = PhoneOutbox.progress.values.maxOrNull() ?: 0f
    Backdrop(Modifier.fillMaxSize(), energy = if (connected) 1f else if (sharing) 0.45f else 0f, level = collecting, still = still) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Header(status, still) { settings = true }
            UpdatePill(actions)
            Hero(active, connected, sharing || model.busy, status, still, actions::toggleSharing)
            Where(model)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().then(if (still) Modifier else Modifier.animateContentSize(fluid())),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                model.note?.let { NoteStrip(it, model::dismissNote) }
                CableNote(model)
                if (sharing || model.busy) GhostButton(S.stopSharing, actions::toggleSharing, Modifier.fillMaxWidth())
                else ShareChoice(model) { settings = true }
                ComputersCard()
                SendCard(actions)
                TextsCard(actions)
                if (model.cable == Cable.NONE) CableCard()
            }
            Spacer(Modifier.height(20.dp))
        }
        Sheet(settings, { settings = false }, S.settings) { SettingsContent(actions) }
        PairingSheet(model)
    }
}

/** One line saying what's happening, for the header and the hero's state. */
private fun status(model: Sharing): String = when {
    model.cable == Cable.FILE_TRANSFER && !model.paired -> S.byCable
    model.paired -> S.connectedTo(model.computer)
    model.server?.cableOnly == true -> S.sharingOverCableTitle
    model.server != null -> S.waiting
    model.busy -> S.starting
    else -> S.tapToShare
}

@Composable
private fun Header(status: String, still: Boolean, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("OpenBridge", color = Palette.fg, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            AnimatedContent(status, label = "status", transitionSpec = {
                if (still) fadeIn() togetherWith fadeOut()
                else (slideInVertically(fluid()) { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
            }) { text ->
                Text(text, color = Palette.muted, fontSize = 14.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        IconAction(Icons.Filled.Settings, S.settings, onSettings, tint = Palette.fg)
    }
}

/** The big button: the current look's hero, driven by the shared engine. Tapping starts or stops sharing. */
@Composable
private fun Hero(active: Boolean, connected: Boolean, sharing: Boolean, status: String, still: Boolean, onTap: () -> Unit) {
    val engine = remember { SunEngine() }
    val view = LocalView.current
    engine.onConnect = { view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY) }
    val activeNow by rememberUpdatedState(active)
    val connectedNow by rememberUpdatedState(connected)
    var tick by remember { mutableLongStateOf(0L) }
    var frame by remember { mutableStateOf(SunEngine.Frame()) }
    if (still) LaunchedEffect(active, connected) {
        // A still picture of where things stand: no orbit, no breathing, no ripples.
        repeat(90) { frame = engine.step(if (engine.state == SunEngine.State.SCAN) 0.0 else 1.0 / 60, active, connected, true) }
        frame = SunEngine.Frame(glow = if (connected) 0.5f else 0f)
        tick++
    } else LaunchedEffect(engine) {
        var last = 0L
        while (true) withFrameNanos { now ->
            val dt = if (last == 0L) 0.0 else ((now - last) / 1e9).coerceAtMost(1.0 / 30)
            last = now
            frame = engine.step(dt, activeNow, connectedNow, false)
            tick = now
        }
    }
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !still) 0.95f else 1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "press")
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        val side = min(maxWidth * 0.8f, 290.dp)
        Box(Modifier.size(side).graphicsLayer { scaleX = scale; scaleY = scale }) {
            // Drawn without clipping, so glows and ripples spread past the button.
            Canvas(Modifier.matchParentSize()) {
                tick   // redraw every frame
                drawHero(engine, frame, Offset(size.width / 2, size.height / 2), size.minDimension * 0.33f, size.minDimension * 1.1f)
            }
            Box(Modifier.matchParentSize().clip(CircleShape)
                .clickable(press, indication = null, role = Role.Button, onClickLabel = if (sharing) S.stopSharing else S.startSharing, onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onTap()
                })
                .semantics { contentDescription = if (sharing) S.stopSharing else S.startSharing; stateDescription = status })
        }
    }
}

/** Where computers reach the phone: one pill per address, the connected one lit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Where(model: Sharing) {
    val endpoints = model.endpoints
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            model.paired -> Pill("${model.computer ?: S.connectedComputer}${model.transport?.let { " · ${S.via(it)}" } ?: ""}", lit = true)
            model.cable == Cable.FILE_TRANSFER -> Pill(S.cableFileTransfer, lit = true)
            model.busy -> Pill(S.starting, lit = false)
            endpoints.isEmpty() -> Pill(S.notSharing, lit = false)
        }
        if (!model.paired) for (endpoint in endpoints) Pill(if (endpoint.via == Via.USB) S.fastUsb else "${S.via(endpoint.via)} · ${endpoint.host}", lit = false)
    }
    val network = endpoints.firstOrNull { it.via != Via.USB }
    AnimatedVisibility(network != null && !model.paired, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        if (network != null) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 12.dp)) {
            Text(S.typeOnComputer, color = Palette.muted, fontSize = 13.sp)
            SelectionContainer {
                Text(network.host, color = Palette.fg, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            Text(S.port(network.port), color = Palette.muted, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun Pill(text: String, lit: Boolean) {
    Row(Modifier.glass(CircleShape, if (lit) Palette.ok.copy(alpha = 0.5f) else Palette.fg.copy(alpha = 0.16f))
        .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (lit) Palette.ok else Palette.muted))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Palette.fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A newer release: Update or What's new, then download progress, all in one pill. */
@Composable
private fun UpdatePill(actions: Actions) {
    val release = Updater.available
    val step = Updater.step
    var open by remember { mutableStateOf(false) }
    AnimatedVisibility(release != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        if (release == null) return@AnimatedVisibility
        Column(Modifier.fillMaxWidth().padding(top = 10.dp).glass(RoundedCornerShape(20.dp), Palette.amber.copy(alpha = 0.5f))
            .animateContentSize(fluid())
            .clickable(role = Role.Button, onClickLabel = S.updateNow) {
                if (step == Updater.Step.FAILED) actions.open(release.page) else if (step == Updater.Step.IDLE) open = !open
            }
            .padding(horizontal = 16.dp, vertical = 10.dp)) {
            val text = when (step) {
                Updater.Step.IDLE -> S.update(release.version)
                Updater.Step.DOWNLOADING -> S.downloading((Updater.progress * 100).toInt())
                Updater.Step.VERIFYING -> S.verifying
                Updater.Step.INSTALLING -> S.installing
                Updater.Step.PERMISSION -> S.allowInstall
                Updater.Step.FAILED -> Updater.problem ?: S.updateFailed
            }
            Text(text, color = Palette.cream, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (step == Updater.Step.DOWNLOADING) LinearProgressIndicator(progress = { Updater.progress },
                Modifier.fillMaxWidth().padding(top = 8.dp), color = Palette.amber, trackColor = Palette.fg.copy(alpha = 0.12f))
            if (step == Updater.Step.IDLE && open) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AccentButton(S.updateNow, { open = false; actions.update() }, Modifier.weight(1f))
                GhostButton(S.whatsNew, { actions.open(release.page) }, Modifier.weight(1f))
            }
            if (step == Updater.Step.FAILED) Text(S.openReleasePage, color = Palette.amber, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** What tapping the hero will share; opens Settings to change it. */
@Composable
private fun ShareChoice(model: Sharing, onChange: () -> Unit) {
    val what = if (model.wholePhone) S.wholePhone else model.folder?.lastPathSegment?.substringAfterLast(':')?.ifEmpty { S.storage } ?: S.oneFolder
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).glass().clickable(role = Role.Button, onClickLabel = S.change, onClick = onChange)
        .padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(S.shares(what), color = Palette.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(if (model.wholePhone) S.wholePhoneDetail else S.oneFolderDetail, color = Palette.muted, fontSize = 13.sp)
        }
        Text(S.change, color = Palette.amber, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CableNote(model: Sharing) {
    val text = when {
        model.cable == Cable.FILE_TRANSFER && (model.server == null || model.paired) -> S.openOnComputer
        model.cable == Cable.CHARGING && model.server == null -> if (model.usbDebugging) S.fastUsbReady else S.chargingOnly
        else -> null
    } ?: return
    Text(text, color = Palette.muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

/** Trusted computers: the connected one lit, each with Forget. */
@Composable
private fun ComputersCard() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val computers = Trusted.computers
    val summary = when {
        Sharing.paired -> S.connectedTo(Sharing.computer)
        computers.isEmpty() -> S.noTrustedShort
        else -> S.trustedCount(computers.size)
    }
    ExpandableCard(S.trusted, summary, expanded, { expanded = !expanded }) {
        Text(if (computers.isEmpty()) S.noTrusted else S.forgetHint, color = Palette.muted, fontSize = 13.sp)
        for (computer in computers) {
            val now = Sharing.paired && Sharing.computer == computer.name
            Row(Modifier.fillMaxWidth().padding(top = 10.dp)
                .glass(RoundedCornerShape(16.dp), if (now) Palette.ok.copy(alpha = 0.5f) else Palette.fg.copy(alpha = 0.12f))
                .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(computer.name, color = Palette.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (now) S.connectedNow(Sharing.transport) else S.lastSeen((System.currentTimeMillis() - computer.seen) / 86_400_000),
                        color = if (now) Palette.ok else Palette.muted, fontSize = 12.5.sp)
                }
                SmallAction(S.forget, { Trusted.forget(computer.id) }, Modifier.semantics { contentDescription = S.forgetComputer(computer.name) })
            }
        }
    }
}

/** What's waiting for a computer, with progress while one collects it, and a text box to send a text. */
@Composable
private fun SendCard(actions: Actions) {
    val waiting = PhoneOutbox.waiting
    var expanded by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val files = waiting.count { it.kind == Outbox.FILE }
    val texts = waiting.size - files
    ExpandableCard(S.sendToComputer, if (waiting.isEmpty()) S.nothingWaiting else S.waitingItems(files, texts), expanded, { expanded = !expanded },
        badge = if (waiting.isEmpty()) null else waiting.size.toString()) {
        val progress = PhoneOutbox.progress
        for (item in waiting.take(20)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (item.kind == Outbox.TEXT) S.aText else item.name, color = Palette.fg, fontSize = 14.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(Formatter.formatShortFileSize(context, item.size), color = Palette.muted, fontSize = 12.5.sp)
                }
                progress[item.id]?.let {
                    LinearProgressIndicator(progress = { it }, Modifier.fillMaxWidth().padding(top = 4.dp), color = Palette.amber,
                        trackColor = Palette.fg.copy(alpha = 0.12f))
                }
            }
        }
        if (waiting.size > 20) Text(S.andMore(waiting.size - 20), color = Palette.muted, fontSize = 12.5.sp)
        if (waiting.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (Sharing.server == null) S.startToSend else S.collectedSoon, color = Palette.muted, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
            SmallAction(S.clear, PhoneOutbox::clear, Modifier.semantics { contentDescription = S.clearWaiting })
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth(), label = { Text(S.textForComputer) }, maxLines = 5,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.amber, unfocusedBorderColor = Palette.fg.copy(alpha = 0.25f),
                focusedLabelColor = Palette.amber, unfocusedLabelColor = Palette.muted, cursorColor = Palette.amber,
                focusedTextColor = Palette.fg, unfocusedTextColor = Palette.fg))
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AccentButton(S.sendText, {
                if (Sharing.sendText(draft)) draft = ""
                else android.widget.Toast.makeText(context, S.textTooLong, android.widget.Toast.LENGTH_SHORT).show()
            }, Modifier.weight(1f), enabled = draft.isNotBlank())
            GhostButton(S.sendClipboard, actions::sendClipboard, Modifier.weight(1f))
        }
    }
}

/** Texts sent to computers and received from them, each with Copy. */
@Composable
private fun TextsCard(actions: Actions) {
    val history = Texts.history
    var expanded by rememberSaveable { mutableStateOf(false) }
    ExpandableCard(S.texts, if (history.isEmpty()) S.noTexts else S.textCount(history.size), expanded, { expanded = !expanded }) {
        if (history.isEmpty()) Text(S.textsHint, color = Palette.muted, fontSize = 13.sp)
        for (entry in history) {
            Column(Modifier.fillMaxWidth().padding(top = 10.dp).glass(RoundedCornerShape(16.dp), Palette.fg.copy(alpha = 0.12f))
                .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (entry.incoming) S.fromComputer(entry.computer) else S.toComputer, color = if (entry.incoming) Palette.ok else Palette.amber,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text(DateUtils.getRelativeTimeSpanString(entry.time).toString(), color = Palette.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    SmallAction(S.copy, { actions.copy(entry.text) })
                }
                Text(entry.text, color = Palette.fg, fontSize = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 10.dp, bottom = 8.dp))
            }
        }
        if (history.isNotEmpty()) SmallAction(S.clear, Texts::clear, Modifier.align(Alignment.End).semantics { contentDescription = S.clearTexts })
    }
}

@Composable
private fun CableCard() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ExpandableCard(S.usingCable, null, expanded, { expanded = !expanded }) {
        Text(S.cableHint, color = Palette.muted, fontSize = 13.5.sp)
    }
}

/** Pairing: the 6-digit code to compare, Pair and Cancel. Over everything else. */
@Composable
private fun BoxScope.PairingSheet(model: Sharing) {
    val code = model.pairingCode
    // Keep the last code on screen while the sheet slides away.
    var last by remember { mutableStateOf("") }
    LaunchedEffect(code) { if (code != null) last = code }
    val shown = code ?: last
    val still = reduceMotion()
    BackHandler(enabled = code != null) { model.decidePairing(false) }
    AnimatedVisibility(code != null, Modifier.matchParentSize(), enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(Palette.night.copy(alpha = 0.55f)))
    }
    AnimatedVisibility(code != null, Modifier.align(Alignment.BottomCenter),
        enter = if (still) fadeIn() else slideInVertically(spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
        exit = if (still) fadeOut() else slideOutVertically { it } + fadeOut()) {
        Column(Modifier.fillMaxWidth()
            .glass(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp), Palette.fg.copy(alpha = 0.2f), solid = true)
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(40.dp, 5.dp).clip(CircleShape).background(Palette.fg.copy(alpha = 0.3f)))
            Spacer(Modifier.height(18.dp))
            Text(S.pairWith(model.pairingComputer), color = Palette.fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(S.sameCode, color = Palette.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            Row(Modifier.semantics(mergeDescendants = true) { contentDescription = S.codeForReader(shown) },
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                shown.forEachIndexed { index, digit ->
                    if (index == 3) Spacer(Modifier.width(10.dp))
                    Box(Modifier.widthIn(min = 40.dp).heightIn(min = 54.dp).glass(RoundedCornerShape(12.dp)).padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center) {
                        Text(digit.toString(), color = Palette.fg, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(S.codesDiffer, color = Palette.muted, fontSize = 12.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(S.cancel, { model.decidePairing(false) }, Modifier.weight(1f))
                AccentButton(S.pair, { model.decidePairing(true) }, Modifier.weight(1.4f))
            }
        }
    }
}
