package org.openbridge.android

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Texts sent to computers and received from them, newest first: the last 30, kept in app-private storage (excluded
 * from backups) until the user clears them, so a text can be copied again later.
 */
object Texts {
    data class Entry(val text: String, val incoming: Boolean, val computer: String?, val time: Long)

    private const val LIMIT = 30
    private lateinit var file: File
    private val entries = mutableListOf<Entry>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** For the screen. */
    var history by mutableStateOf(emptyList<Entry>()); private set

    fun attach(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "texts.json")
        val saved = runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray())
        synchronized(this) {
            for (i in 0 until minOf(saved.length(), LIMIT)) runCatching {
                val item = saved.getJSONObject(i)
                entries += Entry(item.getString("text"), item.getBoolean("incoming"), item.optString("computer").ifEmpty { null }, item.getLong("time"))
            }
        }
        publish()
    }

    fun sent(text: String) = add(Entry(text, false, null, System.currentTimeMillis()))

    fun received(text: String, computer: String?) = add(Entry(text, true, computer, System.currentTimeMillis()))

    fun clear() {
        synchronized(this) { entries.clear(); save() }
        publish()
    }

    private fun add(entry: Entry) {
        if (entry.text.isEmpty()) return
        synchronized(this) {
            entries.add(0, entry)
            while (entries.size > LIMIT) entries.removeAt(entries.size - 1)
            save()
        }
        publish()
    }

    private fun save() {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("text", it.text).put("incoming", it.incoming).put("computer", it.computer ?: "").put("time", it.time)) }
        runCatching { file.writeText(array.toString()) }
    }

    private fun publish() {
        val list = synchronized(this) { entries.toList() }
        main.post { history = list }
    }
}
