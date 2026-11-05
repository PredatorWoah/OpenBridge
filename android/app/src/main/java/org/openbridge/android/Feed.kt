package org.openbridge.android

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The Activity screen's history: files and texts that went between the phone and computers, links, rings, pairing and
 * computers connecting. Newest first, the last [LIMIT], kept in app-private storage (excluded from backups) until the
 * user dismisses or clears them. Only what the phone already showed the user: names, never file contents.
 */
object Feed {
    enum class Kind { FILE_SENT, FILE_RECEIVED, TEXT_SENT, TEXT_RECEIVED, LINK, PAIRED, CONNECTED, RING }

    /**
     * One thing that happened. [name] is the file name, a text's first line, the link, or (for [Kind.CONNECTED]) the
     * transport's [Via] name. [location] is where a received file was saved (a path or content URI), for opening it
     * and its thumbnail.
     */
    @Immutable
    data class Event(val id: Long, val kind: Kind, val name: String, val computer: String?, val location: String?, val time: Long)

    private const val LIMIT = 200
    private const val SNIPPET = 160
    private var file: File? = null
    private val entries = ArrayList<Event>()
    private var nextId = 1L
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** For the screen. */
    var events by mutableStateOf(emptyList<Event>()); private set

    fun attach(context: Context) {
        if (file != null) return
        val target = File(context.filesDir, "activity.json")
        file = target
        val saved = runCatching { JSONArray(target.readText()) }.getOrDefault(JSONArray())
        synchronized(this) {
            for (i in 0 until minOf(saved.length(), LIMIT)) runCatching {
                val item = saved.getJSONObject(i)
                entries += Event(item.getLong("id"), Kind.valueOf(item.getString("kind")), item.getString("name"),
                    item.optString("computer").ifEmpty { null }, item.optString("location").ifEmpty { null }, item.getLong("time"))
            }
            nextId = (entries.maxOfOrNull { it.id } ?: 0L) + 1
        }
        // Attached on the main thread: the list is there for the first frame, so nothing old shows up as new.
        events = synchronized(this) { entries.toList() }
    }

    /** Computers name themselves "Computer" when they don't say; the screen then says "your computer". */
    private fun named(computer: String?) = computer?.takeIf { it.isNotBlank() && it != "Computer" }

    fun fileSent(name: String, computer: String?, location: String?) = add(Kind.FILE_SENT, name, computer, location)
    fun fileReceived(name: String, computer: String?, location: String?) = add(Kind.FILE_RECEIVED, name, computer, location)
    fun textSent(text: String) = add(Kind.TEXT_SENT, snippet(text), null, null)
    fun textReceived(text: String, computer: String?) = add(Kind.TEXT_RECEIVED, snippet(text), computer, null)
    fun link(url: String, computer: String?) = add(Kind.LINK, url, computer, null)
    fun paired(computer: String?) = add(Kind.PAIRED, "", computer, null)
    fun connected(computer: String?, via: Via) = add(Kind.CONNECTED, via.name, computer, null)
    fun ring(computer: String?) = add(Kind.RING, "", computer, null)

    fun remove(id: Long) {
        synchronized(this) { entries.removeAll { it.id == id }; save() }
        publish()
    }

    fun clear() {
        synchronized(this) { entries.clear(); save() }
        publish()
    }

    private fun snippet(text: String) = text.trim().replace(Regex("\\s+"), " ").take(SNIPPET)

    private fun add(kind: Kind, name: String, computer: String?, location: String?) {
        if (file == null) return
        synchronized(this) {
            entries.add(0, Event(nextId++, kind, name, named(computer), location, System.currentTimeMillis()))
            while (entries.size > LIMIT) entries.removeAt(entries.size - 1)
            save()
        }
        publish()
    }

    private fun save() {
        val array = JSONArray()
        entries.forEach {
            array.put(JSONObject().put("id", it.id).put("kind", it.kind.name).put("name", it.name)
                .put("computer", it.computer ?: "").put("location", it.location ?: "").put("time", it.time))
        }
        runCatching { file?.writeText(array.toString()) }
    }

    private fun publish() {
        val list = synchronized(this) { entries.toList() }
        main.post { events = list }
    }
}
