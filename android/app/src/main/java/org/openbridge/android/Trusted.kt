package org.openbridge.android

import android.content.Context
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import org.openbridge.protocol.Trust
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** A computer that paired with this phone and may reconnect without a code until it's forgotten. */
data class Computer(val id: String, val name: String, val paired: Long, val seen: Long)

/**
 * The computers this phone trusts. Each has its own random secret; only its SHA-256 is stored, so the list can't
 * be used to impersonate a computer. App data is excluded from backups (allowBackup="false").
 */
object Trusted : Trust {
    private class Record(val computer: Computer, val hash: ByteArray)

    private lateinit var context: Context
    private val records = mutableListOf<Record>()
    private val random = SecureRandom()
    /** For the screen: the trusted computers, most recently used first. */
    var computers by mutableStateOf(emptyList<Computer>()); private set

    fun attach(app: Context) {
        if (::context.isInitialized) return
        context = app.applicationContext
        val saved = runCatching { JSONArray(preferences().getString("computers", "[]")) }.getOrDefault(JSONArray())
        synchronized(this) {
            for (i in 0 until saved.length()) {
                val item = saved.getJSONObject(i)
                val hash = runCatching { Base64.decode(item.getString("hash"), Base64.NO_WRAP) }.getOrNull() ?: continue
                if (hash.size != 32) continue
                records += Record(Computer(item.getString("id"), item.getString("name"), item.getLong("paired"), item.optLong("seen")), hash)
            }
        }
        publish()
    }

    override fun computer(secret: ByteArray): String? {
        val digest = sha256(secret)
        val match = synchronized(this) {
            // Compare with every record, so the time taken doesn't reveal which one matched.
            var found: Record? = null
            for (record in records) if (MessageDigest.isEqual(record.hash, digest)) found = record
            found
        } ?: return null
        val now = System.currentTimeMillis()
        // Note when it was last used, without rewriting storage for every preview it asks for.
        if (now - match.computer.seen > 10 * 60_000) {
            synchronized(this) {
                val index = records.indexOf(match)
                if (index >= 0) records[index] = Record(match.computer.copy(seen = now), match.hash)
                save()
            }
            publish()
        }
        return match.computer.name
    }

    override fun admit(computer: String): ByteArray {
        val secret = ByteArray(32).also(random::nextBytes)
        val now = System.currentTimeMillis()
        synchronized(this) {
            // Pairing again from the same computer replaces its old secret.
            records.removeAll { it.computer.name == computer }
            records += Record(Computer(UUID.randomUUID().toString(), computer, now, now), sha256(secret))
            save()
        }
        publish()
        return secret
    }

    fun forget(id: String) {
        synchronized(this) { records.removeAll { it.computer.id == id }; save() }
        publish()
    }

    private fun publish() {
        val list = synchronized(this) { records.map { it.computer }.sortedByDescending { it.seen } }
        android.os.Handler(android.os.Looper.getMainLooper()).post { computers = list }
    }

    private fun save() {
        val array = JSONArray()
        for (record in records) array.put(JSONObject()
            .put("id", record.computer.id).put("name", record.computer.name)
            .put("paired", record.computer.paired).put("seen", record.computer.seen)
            .put("hash", Base64.encodeToString(record.hash, Base64.NO_WRAP)))
        preferences().edit().putString("computers", array.toString()).apply()
    }

    private fun preferences() = context.getSharedPreferences("trusted", Context.MODE_PRIVATE)

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
}
