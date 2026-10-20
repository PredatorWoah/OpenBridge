package org.openbridge.desktop

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.openbridge.ui.Backdrop
import org.openbridge.ui.Look
import org.openbridge.ui.Palette
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.io.File
import javax.swing.JOptionPane
import javax.swing.UIManager

fun main() {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    Palette.look = AppState.rememberedLook()
    val state = AppState()
    state.start()
    application {
        val window = rememberWindowState(size = DpSize(1060.dp, 700.dp))
        val searchFocus = remember { FocusRequester() }
        Window(onCloseRequest = ::exitApplication, title = "OpenBridge", state = window, icon = painterResource("icon.png"),
            onKeyEvent = { shortcut(state, it, searchFocus) }) {   // after the focused field, so typing in search never triggers these
            var dropping by remember { mutableStateOf(false) }
            DisposableEffect(Unit) {
                // Files dragged from the file manager upload into the open phone folder.
                this@Window.window.contentPane.dropTarget = DropTarget(this@Window.window.contentPane, DnDConstants.ACTION_COPY, object : DropTargetAdapter() {
                    override fun dragEnter(event: DropTargetDragEvent) { dropping = state.source != null }
                    override fun dragExit(event: DropTargetEvent) { dropping = false }
                    override fun drop(event: DropTargetDropEvent) {
                        dropping = false
                        event.acceptDrop(DnDConstants.ACTION_COPY)
                        @Suppress("UNCHECKED_CAST")
                        val files = runCatching { event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File> }.getOrNull()
                        event.dropComplete(files != null)
                        if (files != null && state.source != null) state.upload(files)
                    }
                })
                onDispose { }
            }
            App(state, dropping, searchFocus)
        }
    }
}

/**
 * The browser's keys, as in a file manager: arrows (with Shift), Home/End, Page Up/Down, Enter, Backspace or Alt+Up,
 * Delete (asks first), F2, Ctrl+A, Ctrl+F, F5 and Esc. A focused button or field gets its keys first.
 */
private fun shortcut(state: AppState, event: KeyEvent, searchFocus: FocusRequester): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (state.panel != null) {
        if (event.key == Key.Escape) { state.panel = null; return true }
        return false
    }
    if (state.source == null) return false
    val shift = event.isShiftPressed
    val grid = state.showGrid && state.results == null
    return when {
        event.isCtrlPressed && event.key == Key.A -> { state.selectAll(); true }
        event.isCtrlPressed && event.key == Key.F -> { runCatching { searchFocus.requestFocus() }; true }
        // In the grid, up and down move a row of tiles, left and right one tile.
        event.key == Key.DirectionDown && !event.isAltPressed -> { state.move(if (grid) state.columns else 1, shift); true }
        event.key == Key.DirectionUp && !event.isAltPressed -> { state.move(if (grid) -state.columns else -1, shift); true }
        event.key == Key.DirectionRight && grid -> { state.move(1, shift); true }
        event.key == Key.DirectionLeft && grid -> { state.move(-1, shift); true }
        event.key == Key.PageDown -> { state.move(10, shift); true }
        event.key == Key.PageUp -> { state.move(-10, shift); true }
        event.key == Key.MoveHome -> { state.move(0, shift, index = 0); true }
        event.key == Key.MoveEnd -> { state.move(0, shift, index = -1); true }
        event.key == Key.Enter || event.key == Key.NumPadEnter -> { state.openSelected(); true }
        event.key == Key.Escape -> state.escape()
        event.key == Key.Backspace || (event.isAltPressed && event.key == Key.DirectionUp) -> { state.up(); true }
        event.key == Key.F5 -> { state.refresh(); true }
        event.key == Key.Delete && !state.busy -> { confirmDelete(state); true }
        event.key == Key.F2 && !state.busy -> { promptRename(state); true }
        else -> false
    }
}

/** Asks before deleting anything on the phone: there's no recycle bin there. */
fun confirmDelete(state: AppState) {
    val doomed = state.selected
    if (doomed.isEmpty()) return
    val answer = JOptionPane.showConfirmDialog(null, S.deleteConfirm(doomed.size, doomed.first().name), S.delete.trimEnd('…'),
        JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)
    if (answer == JOptionPane.OK_OPTION) state.deleteSelected()
}

fun promptRename(state: AppState) {
    val entry = state.selected.singleOrNull() ?: return
    val name = JOptionPane.showInputDialog(null, S.newName, entry.name) ?: return
    state.rename(entry, name)
}

/**
 * The window: the look's background and its shell (each look arranges the same parts its own way), the sheets,
 * and the toasts. Switching looks crossfades between shells; everything they show lives in [AppState], so the folder,
 * selection and transfers carry over.
 */
@Composable
fun App(state: AppState, dropping: Boolean, searchFocus: FocusRequester) {
    val connected = state.source != null
    val look = Palette.look
    // The background livens up while a phone is connected (or pairing). Tide's water follows the whole queue, and
    // rises a little to meet files dragged over the window.
    val progress = state.transfer?.takeIf { !it.done }?.let(::overallProgress) ?: 0f
    val level = if (dropping && connected) maxOf(progress, 0.16f) else progress
    Backdrop(Modifier.fillMaxSize(), energy = if (connected || state.pairingCode != null) 1f else 0f, level = level, still = Platform.reduceMotion) {
        Crossfade(look, animationSpec = tween(if (Platform.reduceMotion) 0 else 450)) { shown ->
            when (shown) {
                Look.DUSK -> DuskShell(state, dropping, searchFocus)
                Look.AURORA -> AuroraShell(state, dropping, searchFocus)
                Look.TIDE -> TideShell(state, dropping, searchFocus)
            }
        }
        // Pairing with another phone from a shell's switcher, while one is open: the code to compare.
        if (connected && state.pairingCode != null) PairingCard(state, Modifier.align(Alignment.Center))
        when (state.panel) {
            Panel.TEXTS -> TextsPanel(state)
            Panel.SETTINGS -> SettingsPanel(state) {
                SystemClipboard.put(diagnostics(state))
                state.show(S.diagnosticsCopied)
            }
            null -> {}
        }
        state.toast?.let { message ->
            // Above Tide's dock of transfers when it's showing.
            val lift = if (look == Look.TIDE && connected && state.transfer != null) 118.dp else 22.dp
            Text(message, color = Palette.fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = lift).glass(CircleShape)
                    .semantics { liveRegion = LiveRegionMode.Polite }.padding(horizontal = 18.dp, vertical = 10.dp))
        }
    }
}
