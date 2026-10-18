package org.openbridge.ui

import java.net.HttpURLConnection
import java.net.URL

/** A newer OpenBridge release, to offer the user. */
data class Release(val version: String, val page: String)

/**
 * Asks GitHub for the latest OpenBridge release, once per launch. It's one anonymous request to api.github.com
 * that sends nothing about the user; any failure (offline, blocked, rate limited) is silent. Blocking: call it off
 * the main thread.
 */
object Updates {
    private const val LATEST = "https://api.github.com/repos/PredatorWoah/OpenBridge/releases/latest"
    const val RELEASES = "https://github.com/PredatorWoah/OpenBridge/releases/latest"

    fun newerThan(current: String): Release? = runCatching {
        val connection = URL(LATEST).openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "OpenBridge")
        try {
            if (connection.responseCode != 200) return null
            val body = connection.inputStream.bufferedReader().use { it.readText().take(200_000) }
            val tag = Regex("\"tag_name\"\\s*:\\s*\"v?([0-9]+(?:\\.[0-9]+){1,3})\"").find(body)?.groupValues?.get(1) ?: return null
            if (isNewer(tag, current)) Release(tag, RELEASES) else null
        } finally { connection.disconnect() }
    }.getOrNull()

    /** Compares dotted versions as numbers, so 0.9.10 is newer than 0.9.9. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
