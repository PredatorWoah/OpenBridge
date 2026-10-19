package org.openbridge.desktop

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.util.Base64

/** A text sent between the phone and this computer. */
data class TextItem(val text: String, val fromPhone: Boolean, val at: Long)

/**
 * The last texts sent either way, newest first, in texts.dat in the data folder (readable by this user only).
 * One line each: direction, time and the text in Base64.
 */
object TextHistory {
    const val KEEP = 30
    private val file get() = File(Platform.dataDir, "texts.dat")

    fun load(from: File = file): List<TextItem> = runCatching {
        from.readLines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 3 || parts[0] !in setOf("P", "C")) return@mapNotNull null
            TextItem(String(Base64.getDecoder().decode(parts[2]), Charsets.UTF_8), parts[0] == "P", parts[1].toLongOrNull() ?: return@mapNotNull null)
        }.take(KEEP)
    }.getOrDefault(emptyList())

    fun save(items: List<TextItem>, to: File = file) {
        val lines = items.take(KEEP).joinToString("\n") { item ->
            "${if (item.fromPhone) "P" else "C"}\t${item.at}\t${Base64.getEncoder().encodeToString(item.text.toByteArray())}"
        }
        runCatching { Platform.writePrivate(to, lines.toByteArray()) }
    }
}

/** The system clipboard's plain text. Another program may hold the clipboard for a moment; that reads as nothing. */
object SystemClipboard {
    fun text(): String? = runCatching { Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String }.getOrNull()
    fun put(text: String) { runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) } }
}

/**
 * Decides which clipboard changes go to the phone when "Send copied text automatically" is on: new text only, never
 * what was there when watching started, never text that just came from the phone, never more than the phone takes.
 */
class ClipboardWatch(private val limit: Int = org.openbridge.protocol.Wire.MAX_CLIPBOARD) {
    private var last: String? = null
    private var fromPhone: String? = null

    /** Starts from what's on the clipboard now, so it isn't sent. */
    @Synchronized fun reset(current: String?) { last = current }

    /** Text that arrived from the phone and is now on the clipboard: not sent back. */
    @Synchronized fun arrived(text: String) { fromPhone = text; last = text }

    /** What to send for the clipboard's `current` text, or null. */
    @Synchronized fun changed(current: String?): String? {
        if (current == null || current == last) return null
        last = current
        if (current == fromPhone || current.isBlank() || current.toByteArray().size > limit) return null
        return current
    }
}
