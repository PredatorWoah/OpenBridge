package org.openbridge.desktop

import java.io.File

/** A phone on the cable with USB debugging, as `adb devices -l` lists it. `state` is adb's: device, unauthorized… */
data class AdbDevice(val serial: String, val state: String, val model: String? = null)

/** What Fast USB needs from adb. [AdbTool] runs the real one; tests use a stand-in. */
interface Adb {
    fun devices(): List<AdbDevice>
    /** `adb -s <serial> forward tcp:0 tcp:<remote>`: the port on this computer that now reaches the phone's `remote`. */
    fun forward(serial: String, remote: Int): Int?
    fun removeForward(serial: String, local: Int)
    /** Starts OpenBridge sharing over the cable only. False when OpenBridge isn't installed on the phone. */
    fun startSharing(serial: String): Boolean
}

/** The adb program (bundled with scrcpy, or on PATH). Each call is a short process. */
class AdbTool(private val adb: File) : Adb {
    /** Whether adb's server ran before this app first asked; if not, this app started it and stops it on quit. */
    private val serverWasRunning = BridgeClient.reachable("127.0.0.1", 5037, 500)

    private fun run(vararg arguments: String, seconds: Long = 10): String? = Platform.output(listOf(adb.path) + arguments, seconds)

    override fun devices(): List<AdbDevice> = parseDevices(run("devices", "-l").orEmpty())

    override fun forward(serial: String, remote: Int): Int? = run("-s", serial, "forward", "tcp:0", "tcp:$remote")?.trim()?.toIntOrNull()

    override fun removeForward(serial: String, local: Int) { run("-s", serial, "forward", "--remove", "tcp:$local") }

    override fun startSharing(serial: String): Boolean {
        val output = run("-s", serial, "shell", "am", "start", "-n", "org.openbridge.android/.MainActivity", "-a", "org.openbridge.android.SHARE_USB") ?: return true
        return !output.contains("does not exist") && !output.contains("Error type 3")
    }

    /** Stops the adb server this app started (it keeps running otherwise, and would hold the app's files open). */
    fun stopOwnServer() { if (!serverWasRunning) run("kill-server") }

    companion object {
        /**
         * `adb devices -l`: "serial  state  usb:… product:… model:… device:…". States with spaces ("no permissions
         * (missing udev rules?)…") come first-word-only as "no". Wireless debugging (ip:port, mDNS names) isn't a cable.
         */
        fun parseDevices(output: String): List<AdbDevice> = output.lines().dropWhile { !it.startsWith("List of devices") }.drop(1).mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 2) return@mapNotNull null
            val serial = parts[0]
            if (serial.contains(':') || serial.contains("._adb")) return@mapNotNull null
            val state = if (line.contains("no permissions")) "no permissions" else parts[1]
            AdbDevice(serial, state, parts.firstOrNull { it.startsWith("model:") }?.substringAfter(':')?.replace('_', ' '))
        }
    }
}

/** A phone on the cable, as Fast USB sees it. READY with a [phone] is a saved phone; READY without one can pair. */
class UsbLink(val serial: String, val name: String, val state: State, val port: Int = 0, val fingerprint: ByteArray? = null, val phone: SavedPhone? = null) {
    enum class State { UNAUTHORIZED, NO_PERMISSION, STARTING, NOT_SHARING, NO_APP, READY }
    val ready: Boolean get() = state == State.READY
    override fun equals(other: Any?) = other is UsbLink && other.serial == serial && other.state == state && other.port == port &&
        other.phone == phone && other.name == name && (other.fingerprint contentEquals fingerprint)
    override fun hashCode() = serial.hashCode() * 31 + state.hashCode()
}

/**
 * Fast USB (see docs/protocol.md): for each phone on the cable, forwards a port on this computer to each of the
 * phone's loopback ports 41987–41990, and reads the certificate that answers. The port whose certificate is a saved
 * phone's wins; otherwise the first that answers can pair. When nothing answers, OpenBridge is started on the phone
 * once (it then shares over the cable only), and Fast USB keeps looking for a while.
 * Not thread-safe by itself: one thread scans; [close] may come from another and waits for the scan.
 */
class FastUsb(
    private val adb: Adb,
    private val probe: (Int) -> ByteArray? = { BridgeClient.fingerprintAt("127.0.0.1", it, 2_000) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        val PORTS = 41987..41990
        /** How long to keep looking after starting OpenBridge on the phone. */
        const val START_WAIT = 10_000L
    }

    private class Tunnel {
        val forwards = sortedMapOf<Int, Int>()
        var started = false
        var missing = false
        var waitUntil = 0L
        var last: UsbLink? = null
    }

    private val tunnels = HashMap<String, Tunnel>()

    /**
     * One look at the cable. `known` are the saved phones; `inUse` is the serial whose tunnel is connected now, which
     * is left alone (its requests show whether it still works). Phones that went away lose their forwards.
     */
    @Synchronized
    fun scan(known: List<SavedPhone>, inUse: String? = null): List<UsbLink> {
        val devices = adb.devices()
        for (gone in tunnels.keys - devices.map { it.serial }.toSet()) {
            val tunnel = tunnels.remove(gone) ?: continue
            tunnel.forwards.values.forEach { adb.removeForward(gone, it) }
        }
        return devices.mapNotNull { device ->
            val name = device.model ?: device.serial
            when (device.state) {
                "unauthorized" -> UsbLink(device.serial, name, UsbLink.State.UNAUTHORIZED)
                "no permissions" -> UsbLink(device.serial, name, UsbLink.State.NO_PERMISSION)
                "device" -> look(device.serial, name, known, device.serial == inUse)
                else -> null   // offline, recovery, bootloader…: nothing to do yet
            }
        }
    }

    /** Whether any phone is starting OpenBridge, so the next scan should come sooner. */
    val starting: Boolean @Synchronized get() = tunnels.values.any { it.last?.state == UsbLink.State.STARTING }

    private fun look(serial: String, name: String, known: List<SavedPhone>, inUse: Boolean): UsbLink {
        val tunnel = tunnels.getOrPut(serial) { Tunnel() }
        if (inUse) tunnel.last?.let { return it }
        for (port in PORTS) if (port !in tunnel.forwards) adb.forward(serial, port)?.let { tunnel.forwards[port] = it }
        var first: Pair<Int, ByteArray>? = null
        var match: Triple<Int, ByteArray, SavedPhone>? = null
        for ((_, local) in tunnel.forwards) {
            val fingerprint = probe(local) ?: continue
            val phone = known.firstOrNull { it.fingerprint contentEquals fingerprint }
            if (phone != null) { match = Triple(local, fingerprint, phone); break }
            if (first == null) first = local to fingerprint
        }
        val link = when {
            match != null -> UsbLink(serial, match.third.name, UsbLink.State.READY, match.first, match.second, match.third)
            first != null -> UsbLink(serial, name, UsbLink.State.READY, first.first, first.second)
            !tunnel.started -> {
                tunnel.started = true
                tunnel.missing = !adb.startSharing(serial)
                tunnel.waitUntil = clock() + START_WAIT
                Log.add("Fast USB: nothing answered on $name; ${if (tunnel.missing) "OpenBridge is not installed" else "started sharing over USB"}")
                UsbLink(serial, name, if (tunnel.missing) UsbLink.State.NO_APP else UsbLink.State.STARTING)
            }
            tunnel.missing -> UsbLink(serial, name, UsbLink.State.NO_APP)
            clock() < tunnel.waitUntil -> UsbLink(serial, name, UsbLink.State.STARTING)
            else -> UsbLink(serial, name, UsbLink.State.NOT_SHARING)
        }
        if (link.state != tunnel.last?.state) Log.add("Fast USB: $name is ${link.state.name.lowercase().replace('_', ' ')}${if (link.ready) " (known: ${link.phone != null})" else ""}")
        tunnel.last = link
        return link
    }

    /** Removes every forward, when the app quits. */
    @Synchronized
    fun close() {
        for ((serial, tunnel) in tunnels) tunnel.forwards.values.forEach { adb.removeForward(serial, it) }
        tunnels.clear()
    }
}
