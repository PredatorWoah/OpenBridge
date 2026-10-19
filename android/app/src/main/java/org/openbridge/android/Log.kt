package org.openbridge.android

import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Recent events for Settings › Copy diagnostics: sharing, addresses, transports, the hotspot, updates and problems.
 * Kept in memory only (the last 500 lines) and copied only when the user asks. Never secrets, pairing codes, texts,
 * file names or file contents.
 */
object Log {
    private const val LIMIT = 500
    private val lines = ArrayDeque<String>(LIMIT)
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun add(message: String) = synchronized(this) {
        if (lines.size == LIMIT) lines.removeFirst()
        lines.addLast("${time.format(Date())}  $message")
    }

    /** What Copy diagnostics puts on the clipboard: the app, the phone, how it's sharing, and the recent lines. */
    fun report(context: Context): String {
        val server = Sharing.server
        val mode = when {
            server == null -> "off"
            server.cableOnly -> "USB cable only"
            Sharing.wholePhone -> "whole phone"
            else -> "one folder"
        }
        val header = listOf(
            "OpenBridge ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}",
            "Sharing: $mode${if (Sharing.paired) ", a computer is connected" else ""}${Sharing.transport?.let { " over ${it.label}" } ?: ""}",
            "Listening: ${server?.endpoints?.joinToString { "${it.host}:${it.port} (${it.via.label})" }?.ifEmpty { "nothing" } ?: "nothing"}",
            "Cable: ${Sharing.cable.name.lowercase()}${if (Sharing.usbDebugging) ", USB debugging" else ""}",
            "Hotspot: ${if (Hotspot.network != null) "on" else if (Hotspot.systemOn) "system hotspot on" else "off"}",
            "All files access: ${if (AllFiles.granted(context)) "yes" else "no"}, background sharing: ${if (Sharing.inBackground) "on" else "off"}",
            "Trusted computers: ${Trusted.computers.size}, waiting to send: ${PhoneOutbox.waiting.size}",
            "Look: ${org.openbridge.ui.Palette.look.id}",
        )
        val recent = synchronized(this) { lines.toList() }
        return (header + "" + "Recent events:" + recent).joinToString("\n")
    }
}
