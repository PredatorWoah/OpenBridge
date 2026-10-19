package org.openbridge.desktop

import org.openbridge.protocol.Wire
import java.net.InetAddress
import javax.jmdns.JmmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

/** A phone announcing itself while sharing on this Wi‑Fi (DNS-SD `_openbridge._tcp`). */
data class NearbyPhone(val name: String, val host: String, val port: String, val id: String? = null)

/** Lists phones sharing with OpenBridge on the local network, so nobody types an address. */
class Discovery(private val changed: (List<NearbyPhone>) -> Unit) {
    private val type = "_openbridge._tcp.local."
    private val phones = LinkedHashMap<String, NearbyPhone>()
    private var dns: JmmDNS? = null

    private val listener = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) { dns?.requestServiceInfo(event.type, event.name, 2_000) }
        override fun serviceRemoved(event: ServiceEvent) = update {
            if (phones.remove(event.name) != null) Log.add("Discovery: “${event.name}” stopped announcing")
        }
        override fun serviceResolved(event: ServiceEvent) {
            val info = event.info
            val host = info.getPropertyString("ip") ?: return
            // Anyone on the network can announce: only private Wi‑Fi addresses are worth trying, as when typed.
            if (!privateIPv4(host)) return
            val port = info.getPropertyString("port") ?: info.port.toString()
            val phone = NearbyPhone(event.name, host, port, info.getPropertyString("id"))
            update { if (phones.put(event.name, phone) != phone) Log.add("Discovery: “${event.name}” at $host:$port") }
        }
    }

    private fun update(change: () -> Unit) {
        val snapshot = synchronized(phones) { change(); phones.values.sortedBy { it.name } }
        changed(snapshot)
    }

    /** Runs until the app quits. JmmDNS follows network changes itself, like joining another Wi‑Fi. */
    fun start() {
        if (dns != null) return
        Thread {
            runCatching { JmmDNS.Factory.getInstance().also { dns = it; it.addServiceListener(type, listener) } }
                .onFailure { Log.add("Discovery could not start: ${it.message}") }
        }.apply { isDaemon = true }.start()
    }
}

/** A dotted IPv4 literal in a private range (no name lookups). */
fun privateIPv4(host: String): Boolean {
    val parts = host.split(".")
    val literal = parts.size == 4 && parts.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) && it.toInt() in 0..255 && it == it.toInt().toString() }
    return literal && Wire.isPrivateIPv4(InetAddress.getByName(host))
}
