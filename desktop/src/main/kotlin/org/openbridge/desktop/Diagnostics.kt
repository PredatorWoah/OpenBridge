package org.openbridge.desktop

import org.openbridge.ui.Palette
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * What happened recently, in memory only, for "Copy diagnostics": connection attempts, transports, discovery,
 * errors and update checks. Never secrets, pairing codes, clipboard texts or file contents; nothing is uploaded.
 */
object Log {
    private const val KEEP = 500
    private val lines = ArrayDeque<String>()
    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun add(message: String) {
        val line = "${LocalDateTime.now().format(time)}  ${message.replace('\n', ' ').take(400)}"
        synchronized(lines) {
            // The same line again and again (a phone that can't be reached every few seconds) is kept once.
            if (lines.lastOrNull()?.substringAfter("  ") == line.substringAfter("  ")) return
            lines.addLast(line)
            while (lines.size > KEEP) lines.removeFirst()
        }
    }

    fun recent(): List<String> = synchronized(lines) { lines.toList() }
}
/** The text "Copy diagnostics" puts on the clipboard, for a bug report the user writes themselves. */
fun diagnostics(state: AppState): String = buildString {
    appendLine("OpenBridge ${AppVersion.current} for ${if (Platform.windows) "Windows" else "Linux"}")
    appendLine("System: ${Platform.description}")
    appendLine("Look: ${Palette.look.label}${if (Platform.reduceMotion) " (reduced motion)" else ""}")
    val source = state.source
    if (source == null) appendLine("Connected: no")
    else {
        appendLine("Connected: over ${state.transport.english}")
        state.phoneInfo?.let { appendLine("Phone app: ${it.version} (revision ${it.revision}), ${it.name}") }
            ?: if (state.transport != Transport.USB) appendLine("Phone app: older than 0.13.0 (no INFO)") else Unit
    }
    appendLine("Saved phones: ${state.saved.size}; nearby: ${state.nearby.size}; USB debugging: ${state.usbLinks.size}; USB file transfer: ${state.wpdPhones.size}")
    appendLine("Screen mirroring: ${when { Tools.scrcpyBundled -> "bundled scrcpy"; Tools.scrcpy != null -> "scrcpy on PATH"; else -> "not available" }}; adb: ${if (Tools.adb != null) "found" else "not found"}")
    appendLine("Texts sent automatically: ${if (state.autoSendTexts) "on" else "off"}")
    appendLine()
    appendLine("Recent events:")
    Log.recent().forEach { appendLine(it) }
}
