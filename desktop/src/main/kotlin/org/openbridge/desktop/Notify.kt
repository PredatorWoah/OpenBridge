package org.openbridge.desktop

import java.awt.TrayIcon
import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/** A button on a desktop notification, and what it does (on the UI thread). */
class NotifyAction(val id: String, val label: String, val run: () -> Unit)

/**
 * Notifications on this computer's desktop. On Linux through `notify-send` when it's there (with buttons where it
 * supports them), else as a message from the tray icon, which on Windows shows as a notification. Clicking a tray
 * message does what its first button, or `onClick`, would.
 */
object DesktopNotify {
    /** The tray icon, once it's in the tray. */
    @Volatile var tray: TrayIcon? = null
    @Volatile private var clicked: (() -> Unit)? = null
    @Volatile private var shownAt = 0L

    private val notifySend: Boolean by lazy { !Platform.windows && Platform.exitCode(listOf("notify-send", "--version"), 3) == 0 }
    private val withActions: Boolean by lazy { notifySend && Platform.output(listOf("notify-send", "--help"), 3)?.contains("--action") == true }

    /** The app icon as a file, for notify-send. */
    private val icon: File? by lazy {
        runCatching {
            val file = File(Platform.dataDir, "icon.png")
            if (!file.isFile) DesktopNotify::class.java.getResourceAsStream("/icon.png")?.use { input -> file.parentFile.mkdirs(); file.outputStream().use { input.copyTo(it) } }
            file.takeIf { it.isFile }
        }.getOrNull()
    }

    /** Shows a notification; `sound` asks for the desktop's sound where it can be chosen (Linux). */
    fun show(title: String, text: String, sound: Boolean = true, actions: List<NotifyAction> = emptyList(), onClick: (() -> Unit)? = null) {
        val heading = shorten(title.replace('\n', ' '), 80)
        val body = text.lines().joinToString(" ").trim().let { if (it.length > 240) it.take(239) + "…" else it }
        if (notifySend && linux(heading, body, sound, actions, onClick)) return
        val icon = tray
        if (icon == null) { Log.add("Notification not shown: no tray"); return }
        clicked = actions.firstOrNull()?.run ?: onClick
        shownAt = System.currentTimeMillis()
        runCatching { icon.displayMessage(heading, body, if (sound) TrayIcon.MessageType.INFO else TrayIcon.MessageType.NONE) }
    }

    /** The tray icon's action fired: if a message was just clicked, does what it offered and returns true. */
    fun trayClicked(): Boolean {
        val action = clicked ?: return false
        if (System.currentTimeMillis() - shownAt > 15_000) { clicked = null; return false }
        clicked = null
        action()
        return true
    }

    private fun linux(title: String, body: String, sound: Boolean, actions: List<NotifyAction>, onClick: (() -> Unit)?): Boolean {
        val command = mutableListOf("notify-send", "--app-name=OpenBridge")
        icon?.let { command += "--icon=${it.absolutePath}" }
        command += if (sound) listOf("--hint=string:sound-name:message-new-instant") else listOf("--hint=boolean:suppress-sound:true")
        val buttons = if (withActions) actions + listOfNotNull(onClick?.let { NotifyAction("default", S.openApp, it) }) else emptyList()
        for (action in buttons) command += "--action=${action.id}=${action.label}"
        command += listOf("--", title, body)
        return try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            if (buttons.isEmpty()) return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
            // With buttons, notify-send waits until one is chosen or the notification closes, then prints its ID.
            Thread {
                val chosen = runCatching { process.inputStream.bufferedReader().readLine()?.trim() }.getOrNull()
                if (!process.waitFor(30, TimeUnit.MINUTES)) process.destroyForcibly()
                buttons.firstOrNull { it.id == chosen }?.let { SwingUtilities.invokeLater(it.run) }
            }.apply { isDaemon = true; start() }
            true
        } catch (e: Exception) { Log.add("notify-send failed: ${e.message}"); false }
    }
}
