package org.openbridge.desktop

import java.io.File

/**
 * The network around this computer. On the phone's own hotspot the phone is the default gateway, so the gateway is
 * one more place to look for a saved phone (port 41987 first); the certificate pin decides whether it's the phone.
 */
object Network {
    private var cached: String? = null
    private var checkedAt = 0L

    /** The IPv4 default gateway when it's a private address, looked up at most every 20 seconds. */
    @Synchronized fun gateway(): String? {
        val now = System.currentTimeMillis()
        if (now - checkedAt > 20_000) {
            checkedAt = now
            cached = runCatching { if (Platform.windows) windowsGateway() else linuxGateway(File("/proc/net/route").readText()) }.getOrNull()?.takeIf(::privateIPv4)
        }
        return cached
    }

    /** /proc/net/route: the default route's gateway, little-endian hex, on the route with the lowest metric. */
    fun linuxGateway(table: String): String? = table.lines().drop(1).mapNotNull { line ->
        val f = line.trim().split(Regex("\\s+"))
        if (f.size < 7 || f[1] != "00000000" || (f[3].toIntOrNull(16) ?: 0) and 2 == 0) return@mapNotNull null
        val hex = f[2].toLongOrNull(16) ?: return@mapNotNull null
        val address = (0 until 4).joinToString(".") { ((hex shr (8 * it)) and 255).toString() }
        address to (f[6].toIntOrNull() ?: 0)
    }.minByOrNull { it.second }?.first

    private fun windowsGateway(): String? = Platform.output(listOf("route", "print", "-4", "0.0.0.0"), 5)?.let(::windowsGateway)

    /** `route print -4 0.0.0.0`: "0.0.0.0  0.0.0.0  <gateway>  <interface>  <metric>" rows; the lowest metric wins. */
    fun windowsGateway(output: String): String? = output.lines().mapNotNull { line ->
        val f = line.trim().split(Regex("\\s+"))
        if (f.size < 5 || f[0] != "0.0.0.0" || f[1] != "0.0.0.0" || !f[2].matches(Regex("[0-9.]+"))) return@mapNotNull null
        f[2] to (f[4].toIntOrNull() ?: Int.MAX_VALUE)
    }.minByOrNull { it.second }?.first
}

/**
 * Notices a Wi‑Fi that keeps devices apart (client isolation): a phone that announces itself, so it is on this
 * network, but whose port can't be reached several times in a row.
 */
class Isolation(private val after: Int = 3) {
    private val misses = HashMap<String, Int>()

    /** Records an attempt to reach a phone (by its announced name or address); true once it looks isolated. */
    @Synchronized fun attempt(phone: String, reached: Boolean): Boolean {
        if (reached) { misses.clear(); return false }
        misses[phone] = (misses[phone] ?: 0) + 1
        return isolated
    }

    @Synchronized fun clear() = misses.clear()

    val isolated: Boolean @Synchronized get() = misses.values.any { it >= after }
}
