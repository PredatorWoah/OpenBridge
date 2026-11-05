package org.openbridge.android

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.openbridge.ui.Palette

/**
 * Texts as a chat: what the phone sent on the right, what computers sent on the left, newest at the bottom. Tapping a
 * bubble copies it. The bar at the bottom sends a new text or the clipboard.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TextsScreen(model: AppModel, actions: Actions, padding: PaddingValues) {
    val history = model.texts
    val now = model.now
    val waiting = model.outbox.texts
    val first = rememberFirstShow()
    val layout = androidx.compose.ui.platform.LocalLayoutDirection.current
    Column(Modifier.fillMaxSize().padding(start = padding.calculateStartPadding(layout), end = padding.calculateEndPadding(layout),
        top = padding.calculateTopPadding())) {
        ScreenTitle(S.texts, subtitle = if (history.isEmpty()) null else S.tapToCopy) {
            if (history.isNotEmpty()) IconAction(Icons.Rounded.DeleteSweep, S.clearTexts, actions::clearTexts, tint = Palette.muted)
        }
        AnimatedVisibility(waiting > 0, enter = fadeIn(motion()) + expandVertically(motion()), exit = fadeOut(motion()) + shrinkVertically(motion())) {
            HintCard(S.textsWaiting(waiting), Icons.Rounded.Schedule, null, Modifier.padding(bottom = 8.dp), tint = Palette.violet)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (history.isEmpty()) EmptyState(S.textsEmpty, S.textsHint, Icons.Rounded.ChatBubble, Modifier.align(Alignment.Center))
            // Newest first in the store; drawn bottom-up, so the newest sits just above the bar.
            else LazyColumn(Modifier.fillMaxSize(), reverseLayout = true, contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom)) {
                itemsIndexed(history, key = { _, entry -> "${entry.time}-${entry.incoming}-${entry.text.hashCode()}" }) { index, entry ->
                    Bubble(entry, now, actions, Modifier.animateItem().entrance(index, first))
                }
            }
        }
        ComposeBar(actions, Modifier.padding(bottom = padding.calculateBottomPadding()).imePadding())
    }
}

@Composable
private fun Bubble(entry: Texts.Entry, now: Long, actions: Actions, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    var copied by remember { mutableStateOf(false) }
    if (copied) LaunchedEffect(Unit) { delay(1600); copied = false }
    val mine = !entry.incoming
    val shape = if (mine) RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp) else RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        Column(Modifier.align(if (mine) Alignment.CenterEnd else Alignment.CenterStart).widthIn(max = maxWidth * 0.84f),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            if (!mine) Text(entry.computer ?: S.fromComputer(null), style = Type.caption.copy(fontWeight = FontWeight.Bold), color = Palette.ok,
                modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
            val interaction = remember { MutableInteractionSource() }
            Box(Modifier.pressScale(interaction, 0.97f)
                .then(if (mine) Modifier.clip(shape).background(Palette.sunset) else Modifier.glass(shape))
                .clickable(interaction, ripple(), role = Role.Button, onClickLabel = S.copy) {
                    haptics.confirm(); actions.copy(entry.text); copied = true
                }
                .semantics { contentDescription = (if (mine) S.toComputer else S.fromComputer(entry.computer)) + ": " + entry.text }
                .padding(horizontal = 16.dp, vertical = 11.dp)) {
                Text(entry.text, style = Type.body, color = if (mine) Palette.night else Palette.fg, maxLines = 14, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                AnimatedVisibility(copied, enter = scaleIn(bouncy()) + fadeIn(), exit = fadeOut()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Check, null, tint = Palette.ok, modifier = Modifier.size(14.dp))
                        Text(S.copied, style = Type.caption.copy(fontWeight = FontWeight.Bold), color = Palette.ok, modifier = Modifier.padding(start = 3.dp, end = 8.dp))
                    }
                }
                Text(ago(entry.time, now), style = Type.caption, color = Palette.muted)
            }
        }
    }
}

/** The compose bar: Send clipboard, the text field and the round send button, which pops when there's something to send. */
@Composable
private fun ComposeBar(actions: Actions, modifier: Modifier = Modifier) {
    var draft by rememberSaveable { mutableStateOf("") }
    var tooLong by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()
    val ready = draft.isNotBlank()
    val pop by animateFloatAsState(if (ready) 1f else 0.82f, motion(bouncy()), label = "send")
    Column(modifier.fillMaxWidth().padding(top = 8.dp)) {
        AnimatedVisibility(tooLong, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Text(S.textTooLong, style = Type.caption, color = Palette.pink, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
        }
        Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(30.dp), strong = true).padding(6.dp), verticalAlignment = Alignment.Bottom) {
            IconAction(Icons.Rounded.ContentPaste, S.sendClipboard, actions::sendClipboard, tint = Palette.amber)
            Box(Modifier.weight(1f).heightIn(min = 48.dp).padding(horizontal = 6.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (draft.isEmpty()) Text(S.textForComputer, style = Type.body, color = Palette.muted)
                BasicTextField(draft, { draft = it; tooLong = false }, Modifier.fillMaxWidth().semantics { contentDescription = S.textForComputer },
                    textStyle = Type.body.copy(color = Palette.fg), cursorBrush = SolidColor(Palette.amber), maxLines = 5,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            }
            Box(Modifier.size(48.dp).graphicsLayer { scaleX = pop; scaleY = pop }.clip(CircleShape)
                .background(if (ready) Palette.sunset else Brush.linearGradient(listOf(Palette.fg.copy(alpha = 0.12f), Palette.fg.copy(alpha = 0.12f))))
                .clickable(enabled = ready, role = Role.Button, onClickLabel = S.sendText) {
                    if (actions.sendText(draft)) { haptics.confirm(); draft = "" } else { haptics.reject(); tooLong = true }
                }
                .semantics { contentDescription = S.sendText }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.Send, null, tint = if (ready) Palette.night else Palette.muted, modifier = Modifier.size(22.dp))
            }
        }
    }
}

