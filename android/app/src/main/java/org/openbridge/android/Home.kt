package org.openbridge.android

import android.text.format.Formatter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import org.openbridge.protocol.Outbox
import org.openbridge.ui.Palette

/**
 * Home: a live dashboard. Off, the look's hero is big and central with one large Start sharing button. Once sharing
 * starts, the hero glides up and shrinks (a shared element) and the button becomes the sharing dashboard: who's
 * connected and how, where computers find the phone, and what's waiting to go.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun HomeScreen(model: AppModel, actions: Actions, hero: HeroState, padding: PaddingValues, openTab: (Tab) -> Unit) {
    val share = model.share
    val motion = LocalMotion.current
    SharedTransitionLayout(Modifier.fillMaxSize()) {
        AnimatedContent(share.running, Modifier.fillMaxSize(), label = "home", transitionSpec = {
            if (motion.still) (EnterTransition.None togetherWith ExitTransition.None)
            else (fadeIn(tween(320, delayMillis = 90)) togetherWith fadeOut(tween(160))) using SizeTransform(clip = false)
        }) { live ->
            val scopes = Scopes(this@SharedTransitionLayout, this)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Header(share)
                UpdatePill(model.update, actions)
                Hints(share, actions)
                if (live) LiveDashboard(model, actions, hero, scopes) else IdleHero(share, actions, hero, scopes)
                // Pair with QR code, while off or sharing (not while starting); once connected, Trusted computers has it.
                AnimatedVisibility(share.phase == Phase.OFF || share.phase == Phase.WAITING,
                    enter = fadeIn(motion()) + expandVertically(motion()), exit = fadeOut(motion()) + shrinkVertically(motion())) {
                    PairWithQrRow(actions)
                }
                QuickActions(actions, openTab)
                val outbox = model.outbox
                AnimatedVisibility(outbox.items.isNotEmpty(), enter = fadeIn(motion()) + expandVertically(motion()), exit = fadeOut(motion()) + shrinkVertically(motion())) {
                    OutboxCard(outbox, share.sharing, actions)
                }
                RecentActivity(model, actions, openTab)
                if (share.cable == Cable.NONE && !live) CableTip()
            }
        }
    }
}

/** The shared-element scopes for the hero and the button, so they can morph between the two layouts. */
@OptIn(ExperimentalSharedTransitionApi::class)
private class Scopes(val shared: SharedTransitionScope, val visibility: AnimatedVisibilityScope)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Modifier.sharedHero(scopes: Scopes): Modifier = with(scopes.shared) {
    this@sharedHero.sharedElement(rememberSharedContentState("hero"), scopes.visibility,
        boundsTransform = { _, _ -> spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow) })
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Modifier.sharedButton(scopes: Scopes): Modifier = with(scopes.shared) {
    this@sharedButton.sharedBounds(rememberSharedContentState("button"), scopes.visibility,
        boundsTransform = { _, _ -> spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow) },
        resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds)
}

/** One line saying what's happening, for the header and the hero's state. */
fun statusLine(share: ShareUi): String = when {
    share.byCable && share.phase != Phase.CONNECTED -> S.byCable
    share.phase == Phase.CONNECTED -> S.connectedTo(share.computer)
    share.sharing && share.cableOnly -> S.sharingOverCableTitle
    share.sharing -> S.waiting
    share.phase == Phase.STARTING -> S.starting
    else -> S.tapToShare
}

@Composable
private fun Header(share: ShareUi) {
    val what = if (share.wholePhone) S.wholePhone else share.folder ?: S.oneFolder
    // Off, it says what tapping does; sharing, what is shared (the dashboard below says who is connected).
    val status = if (share.running) S.sharesShort(what) else statusLine(share)
    val still = LocalMotion.current.still
    val title: @Composable () -> Unit = {
        Text("OpenBridge", style = Type.title, color = Palette.fg, modifier = Modifier.semantics { heading() })
        AnimatedContent(status, label = "status", transitionSpec = {
            if (still) EnterTransition.None togetherWith ExitTransition.None
            else (slideInVertically(fluid()) { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
        }) { text ->
            Text(text, style = Type.label.copy(fontWeight = FontWeight.Normal), color = Palette.muted,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
    val chip: @Composable () -> Unit = {
        StatusChip(when (share.phase) {
            Phase.CONNECTED -> S.connectedHeadline
            Phase.WAITING -> S.sharingHeadline
            Phase.STARTING -> S.starting
            Phase.OFF -> if (share.byCable) S.connectedHeadline else S.off
        }, lit = share.connected || share.sharing)
    }
    // With very large text the chip goes under the title instead of squeezing it.
    if (roomy()) Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { title() }
        chip()
    } else Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        title()
        chip()
    }
}

/** Problems and hints from sharing and the cable, each dismissible. */
@Composable
private fun Hints(share: ShareUi, actions: Actions) {
    var cableDismissed by rememberSaveable { mutableStateOf<String?>(null) }
    val cable = when {
        share.cable == Cable.FILE_TRANSFER && (!share.sharing || share.phase == Phase.CONNECTED) -> S.openOnComputer
        share.cable == Cable.CHARGING && !share.sharing -> if (share.usbDebugging) S.fastUsbReady else S.chargingOnly
        else -> null
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DismissibleHint(share.note != null, share.note ?: "", Icons.Rounded.Info, actions::dismissNote)
        DismissibleHint(cable != null && cable != cableDismissed, cable ?: "", Icons.Rounded.Usb, { cableDismissed = cable }, tint = Palette.violet)
    }
}

/** Off: the big hero, a title and the Start sharing button, and what will be shared. */
@Composable
private fun IdleHero(share: ShareUi, actions: Actions, hero: HeroState, scopes: Scopes) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val side = min(min(maxWidth * 0.78f, 290.dp), windowHeight() * 0.36f)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            HeroButton(hero, side, S.startSharing, statusLine(share), actions::toggleSharing, Modifier.sharedHero(scopes))
            Text(S.heroTitle, style = Type.display, color = Palette.fg, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(S.heroDetail, style = Type.body, color = Palette.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp))
            Spacer(Modifier.height(20.dp))
            PrimaryButton(S.startSharing, actions::toggleSharing, Modifier.fillMaxWidth().sharedButton(scopes),
                icon = Icons.Rounded.PowerSettingsNew, large = true)
            Spacer(Modifier.height(14.dp))
            WhatToShare(share, actions)
        }
    }
}

/** Whole phone or one folder, side by side (stacked when the text is large). */
@Composable
private fun WhatToShare(share: ShareUi, actions: Actions) {
    val big = LocalDensity.current.fontScale > 1.3f
    BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup().semantics { contentDescription = S.shareWhat }) {
        val folder = share.folder ?: S.oneFolderDetail
        if (maxWidth >= 320.dp && !big) Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChoiceTile(S.wholePhone, S.wholePhoneDetail, Icons.Rounded.PhoneAndroid, share.wholePhone, actions::chooseWholePhone, Modifier.weight(1f).fillMaxHeight())
            ChoiceTile(S.oneFolder, folder, Icons.Rounded.Folder, !share.wholePhone, actions::chooseFolder, Modifier.weight(1f).fillMaxHeight())
        } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ChoiceTile(S.wholePhone, S.wholePhoneDetail, Icons.Rounded.PhoneAndroid, share.wholePhone, actions::chooseWholePhone, Modifier.fillMaxWidth())
            ChoiceTile(S.oneFolder, folder, Icons.Rounded.Folder, !share.wholePhone, actions::chooseFolder, Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun ChoiceTile(title: String, detail: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
               enabled: Boolean = true) {
    val haptics = rememberHaptics()
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(modifier.pressScale(interaction)
        .glass(tileShape, edge = if (selected) Palette.amber.copy(alpha = 0.8f) else null)
        .selectable(selected, interaction, androidx.compose.material3.ripple(), enabled = enabled, role = Role.RadioButton) { haptics.tick(); onClick() }
        .padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, if (selected) Palette.amber else Palette.muted, 34.dp)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(20.dp).clip(CircleShape).then(
                if (selected) Modifier.background(Palette.sunset) else Modifier.border(2.dp, Palette.fg.copy(alpha = 0.3f), CircleShape)),
                contentAlignment = Alignment.Center) {
                if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.night))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(title, style = Type.bodyStrong, color = if (enabled || selected) Palette.fg else Palette.muted)
        Text(detail, style = Type.caption, color = Palette.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** Sharing: the hero, smaller, over who's connected and how; the button, now Stop; where computers find the phone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveDashboard(model: AppModel, actions: Actions, hero: HeroState, scopes: Scopes) {
    val still = LocalMotion.current.still
    val share = model.share
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val side = min(min(maxWidth * 0.56f, 220.dp), windowHeight() * 0.3f)
            HeroButton(hero, side, S.stopSharing, statusLine(share), actions::toggleSharing, Modifier.sharedHero(scopes))
        }
        // Who and how, in big type, changing smoothly.
        AnimatedContent(share.phase to share.computer, label = "who", transitionSpec = {
            if (still) EnterTransition.None togetherWith ExitTransition.None
            else (fadeIn(tween(260)) + slideInVertically(fluid()) { it / 3 }) togetherWith fadeOut(tween(120))
        }) { (phase, computer) ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(when (phase) {
                    Phase.CONNECTED -> computer ?: S.connectedComputer
                    Phase.STARTING -> S.starting
                    else -> if (share.cableOnly) S.sharingOverCableTitle else S.waiting
                }, style = Type.display, color = Palette.fg, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (phase == Phase.CONNECTED) Text(S.connectedNow(share.transport), style = Type.body, color = Palette.ok, textAlign = TextAlign.Center)
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (share.byCable) StatusChip(S.cableFileTransfer, lit = true, icon = Icons.Rounded.Cable)
            if (share.inBackground) StatusChip(S.inBackground, lit = false, icon = Icons.Rounded.Schedule)
        }
        PrimaryButton(S.stopSharing, actions::toggleSharing, Modifier.fillMaxWidth().sharedButton(scopes),
            icon = Icons.Rounded.PowerSettingsNew, breathing = share.sharing, large = true)
        Endpoints(share, actions)
    }
}

fun viaIcon(via: Via): ImageVector = when (via) {
    Via.WIFI -> Icons.Rounded.Wifi
    Via.TETHER -> Icons.Rounded.Cable
    Via.HOTSPOT -> Icons.Rounded.WifiTethering
    Via.USB -> Icons.Rounded.Usb
}

/** Where computers reach the phone, one card per address with Copy; shimmering placeholders while starting. */
@Composable
private fun Endpoints(share: ShareUi, actions: Actions) {
    val show = share.phase != Phase.CONNECTED
    AnimatedVisibility(show, enter = fadeIn(motion()) + expandVertically(motion()), exit = fadeOut(motion()) + shrinkVertically(motion())) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel(S.whereToFind)
            if (share.phase == Phase.STARTING) {
                Placeholder(Modifier.fillMaxWidth().height(92.dp))
                Placeholder(Modifier.fillMaxWidth().height(72.dp))
            } else if (share.endpoints.isEmpty()) Text(S.notSharing, style = Type.label, color = Palette.muted)
            else share.endpoints.forEachIndexed { index, endpoint ->
                EndpointCard(endpoint, actions, Modifier.entrance(index))
            }
        }
    }
}

@Composable
private fun EndpointCard(endpoint: Endpoint, actions: Actions, modifier: Modifier = Modifier) {
    val usb = endpoint.via == Via.USB
    Row(modifier.fillMaxWidth().glass(tileShape).padding(start = 16.dp, end = 6.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconBadge(viaIcon(endpoint.via), if (usb) Palette.violet else Palette.amber, 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(S.via(endpoint.via).uppercase(), style = Type.overline, color = Palette.muted)
            if (usb) Text(S.fastUsbReady, style = Type.label.copy(fontWeight = FontWeight.Normal), color = Palette.fg)
            else {
                Text(S.typeOnComputer, style = Type.caption, color = Palette.muted)
                SelectionContainer { Text(endpoint.host, style = Type.mono, color = Palette.fg) }
                Text(S.port(endpoint.port), style = Type.label, color = Palette.muted)
            }
        }
        if (!usb) IconAction(Icons.Rounded.ContentCopy, S.copyAddress(endpoint.host, endpoint.port), { actions.copy("${endpoint.host}:${endpoint.port}") },
            tint = Palette.amber)
    }
}

/** Three big shortcuts for sending from the phone. */
@Composable
private fun QuickActions(actions: Actions, openTab: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel(S.quickActions)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickTile(S.sendFiles, Icons.Rounded.UploadFile, Palette.amber, actions::sendFiles, Modifier.weight(1f).fillMaxHeight())
            QuickTile(S.sendClipboard, Icons.Rounded.ContentPaste, Palette.pink, actions::sendClipboard, Modifier.weight(1f).fillMaxHeight())
            QuickTile(S.writeText, Icons.AutoMirrored.Rounded.Notes, Palette.violet, { openTab(Tab.TEXTS) }, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun QuickTile(label: String, icon: ImageVector, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(modifier.pressScale(interaction, 0.93f).glass(tileShape)
        .clickable(interaction, androidx.compose.material3.ripple(color = tint), role = Role.Button) { haptics.tap(); onClick() }
        .padding(horizontal = 8.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconBadge(icon, tint, 44.dp)
        Spacer(Modifier.height(8.dp))
        Text(label, style = Type.label.copy(fontWeight = FontWeight.SemiBold), color = Palette.fg, textAlign = TextAlign.Center)
    }
}

/** What's waiting for a computer, with progress while one collects it. */
@Composable
fun OutboxCard(outbox: OutboxUi, sharing: Boolean, actions: Actions, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier.fillMaxWidth().glass().animateContentSize(motion()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(S.waitingToSend, style = Type.headline, color = Palette.fg, modifier = Modifier.weight(1f, fill = false).semantics { heading() })
                Spacer(Modifier.width(8.dp))
                // The count ticks over as items go.
                val slide = motion<androidx.compose.ui.unit.IntOffset>(bouncy())
                val fade = motion<Float>()
                AnimatedContent(outbox.items.size, label = "count", transitionSpec = {
                    (slideInVertically(slide) { -it } + fadeIn(fade)) togetherWith (slideOutVertically(slide) { it } + fadeOut(fade))
                }) { count ->
                    Text(count.toString(), style = Type.caption.copy(fontWeight = FontWeight.Bold), color = Palette.night,
                        modifier = Modifier.clip(CircleShape).background(Palette.amber).padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
            TextAction(S.clear, actions::clearOutbox, Modifier.semantics { contentDescription = S.clearWaiting })
        }
        Text(S.waitingItems(outbox.files, outbox.texts), style = Type.label.copy(fontWeight = FontWeight.Normal), color = Palette.muted)
        for (item in outbox.items.take(5)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(if (item.kind == Outbox.TEXT) Icons.AutoMirrored.Rounded.Notes else fileIcon(item.name), Palette.violet, 34.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (item.kind == Outbox.TEXT) S.aText else item.name, style = Type.bodyStrong, color = Palette.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val progress = outbox.progress[item.id]
                    if (progress != null) ProgressLine(progress, Modifier.padding(top = 6.dp))
                    else Text(Formatter.formatShortFileSize(context, item.size), style = Type.caption, color = Palette.muted)
                }
            }
        }
        if (outbox.items.size > 5) Text(S.andMore(outbox.items.size - 5), style = Type.caption, color = Palette.muted)
        Text(if (sharing) S.collectedSoon else S.startToSend, style = Type.caption, color = Palette.muted)
    }
}

/** The latest three things that happened, with a way to the whole feed. */
@Composable
private fun RecentActivity(model: AppModel, actions: Actions, openTab: (Tab) -> Unit) {
    val events = model.events
    if (events.isEmpty()) return
    val now = model.now
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(S.recentActivity) { TextAction(S.seeAll, { openTab(Tab.ACTIVITY) }) }
        Column(Modifier.fillMaxWidth().glass().padding(vertical = 4.dp)) {
            events.take(3).forEachIndexed { index, event ->
                if (index > 0) Divider()
                EventRow(event, now, actions, Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}

@Composable
private fun CableTip() {
    var hidden by rememberSaveable { mutableStateOf(false) }
    DismissibleHint(!hidden, S.cableHint, Icons.Rounded.Cable, { hidden = true }, tint = Palette.violet)
}

/** A newer release: Update or What's new, then download progress, all in one pill. */
@Composable
private fun UpdatePill(update: UpdateUi, actions: Actions) {
    val version = update.version
    var open by remember { mutableStateOf(false) }
    AnimatedVisibility(version != null, enter = fadeIn(motion()) + expandVertically(motion()), exit = fadeOut(motion()) + shrinkVertically(motion())) {
        if (version == null) return@AnimatedVisibility
        val step = update.step
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp), edge = Palette.amber.copy(alpha = 0.55f))
            .animateContentSize(motion())
            .clickable(role = Role.Button, onClickLabel = S.updateNow) {
                if (step == Updater.Step.FAILED) update.page?.let(actions::open) else if (step == Updater.Step.IDLE) open = !open
            }
            .padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (step == Updater.Step.IDLE) Icons.Rounded.SystemUpdate else Icons.Rounded.CheckCircle, null, tint = Palette.amber, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(when (step) {
                    Updater.Step.IDLE -> S.update(version)
                    Updater.Step.DOWNLOADING -> S.downloading((update.progress * 100).toInt())
                    Updater.Step.VERIFYING -> S.verifying
                    Updater.Step.INSTALLING -> S.installing
                    Updater.Step.PERMISSION -> S.allowInstall
                    Updater.Step.FAILED -> update.problem ?: S.updateFailed
                }, style = Type.label.copy(fontWeight = FontWeight.SemiBold), color = Palette.cream,
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (step == Updater.Step.DOWNLOADING) ProgressLine(update.progress, Modifier.padding(top = 10.dp))
            if (step == Updater.Step.IDLE && open) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(S.updateNow, { open = false; actions.update() }, Modifier.weight(1f))
                SecondaryButton(S.whatsNew, { update.page?.let(actions::open) }, Modifier.weight(1f))
            }
            if (step == Updater.Step.FAILED) Text(S.openReleasePage, style = Type.label.copy(fontWeight = FontWeight.Bold), color = Palette.amber,
                modifier = Modifier.padding(top = 6.dp))
        }
    }
}
