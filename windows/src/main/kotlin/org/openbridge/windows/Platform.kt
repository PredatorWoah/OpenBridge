package org.openbridge.windows

import java.io.File
import java.util.concurrent.TimeUnit
import javax.jmdns.JmmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

/** A phone announcing itself while sharing on this Wi‑Fi (DNS-SD `_openbridge._tcp`). */
data class NearbyPhone(val name: String, val host: String, val port: String)

/** Lists phones sharing with OpenBridge on the local network, so nobody types an address. */
class Discovery(private val changed: (List<NearbyPhone>) -> Unit) {
    private val type = "_openbridge._tcp.local."
    private val phones = LinkedHashMap<String, NearbyPhone>()
    private var dns: JmmDNS? = null

    private val listener = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) { dns?.requestServiceInfo(event.type, event.name, 2_000) }
        override fun serviceRemoved(event: ServiceEvent) = update { phones.remove(event.name) }
        override fun serviceResolved(event: ServiceEvent) {
            val info = event.info
            val host = info.getPropertyString("ip") ?: return
            val port = info.getPropertyString("port") ?: info.port.toString()
            update { phones[event.name] = NearbyPhone(event.name, host, port) }
        }
    }

    private fun update(change: () -> Unit) {
        val snapshot = synchronized(phones) { change(); phones.values.sortedBy { it.name } }
        changed(snapshot)
    }

    fun start() {
        if (dns != null) return
        Thread {
            runCatching { JmmDNS.Factory.getInstance().also { dns = it; it.addServiceListener(type, listener) } }
        }.apply { isDaemon = true }.start()
    }

    fun stop() {
        val current = dns ?: return
        dns = null
        Thread { runCatching { current.removeServiceListener(type, listener); current.close() } }.apply { isDaemon = true }.start()
        synchronized(phones) { phones.clear() }
    }
}

/** Windows talks to phones in File transfer mode itself (MTP, through File Explorer). */
object Usb {
    private val windows = System.getProperty("os.name").startsWith("Windows")

    /** Names of phones plugged in as portable devices, or none. Takes about a second: call off the UI thread. */
    fun phones(): List<String> {
        if (!windows) return emptyList()
        return runCatching {
            val process = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command",
                "Get-PnpDevice -PresentOnly -Class WPD -Status OK | Select-Object -ExpandProperty FriendlyName")
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroy(); return emptyList() }
            output.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        }.getOrDefault(emptyList())
    }

    /** "This PC", where Windows lists the phone next to the drives. */
    fun openExplorer() {
        if (windows) runCatching { ProcessBuilder("explorer.exe", "shell:MyComputerFolder").start() }
    }
}

/** Screen mirroring with the bundled official scrcpy for Windows (Apache-2.0), which brings its own adb. */
object Mirror {
    private val folder: File? = System.getProperty("compose.application.resources.dir")?.let { File(it, "scrcpy") }
    val available: Boolean get() = folder?.resolve("scrcpy.exe")?.isFile == true

    /** Starts scrcpy in its own window; `finished` gets an error message, or null when the window closed normally. */
    fun start(finished: (String?) -> Unit) {
        val exe = folder?.resolve("scrcpy.exe")?.takeIf { it.isFile } ?: return finished(S.mirrorMissing)
        Thread {
            val result = runCatching {
                val process = ProcessBuilder(exe.path, "--window-title=OpenBridge", "--stay-awake")
                    .directory(exe.parentFile).redirectErrorStream(true).start()
                val log = process.inputStream.bufferedReader().readText()
                val code = process.waitFor()
                if (code == 0) null
                else log.lines().lastOrNull { it.contains("ERROR") }?.substringAfter("ERROR:")?.trim()
                    ?: "Screen mirroring stopped (code $code). ${S.mirrorHint}"
            }.getOrElse { it.message }
            finished(result)
        }.apply { isDaemon = true }.start()
    }
}
