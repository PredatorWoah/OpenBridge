package org.openbridge.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.openbridge.protocol.Clipboard
import org.openbridge.protocol.Pairing
import org.openbridge.protocol.Session
import org.openbridge.protocol.Store
import org.openbridge.protocol.Trust
import org.openbridge.protocol.Wire
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Date
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.security.auth.x500.X500Principal

/** How a computer reaches the phone. The labels are for diagnostics; the screen says it in the user's language. */
enum class Via(val label: String) { WIFI("Wi-Fi"), TETHER("USB tethering"), HOTSPOT("phone hotspot"), USB("Fast USB") }

/** One address the phone is listening on. */
data class Endpoint(val via: Via, val host: String, val port: Int)

/**
 * Serves OB01 on a few specific addresses at once, never on all of them: the Wi-Fi network (or USB tethering link) that
 * was connected when sharing started, the phone's own hotspot while it's on, and loopback for Fast USB, which only a
 * computer using USB debugging reaches. Every address has the same TLS identity and the same paired computers.
 */
class SharingServer(private val context: Context, private val store: Store, private val trust: Trust, private val approver: Pairing.Approver,
                    private val clipboard: Clipboard?,
                    /** Fast USB only, as asked over USB debugging: loopback, and nothing on any network. */
                    val cableOnly: Boolean,
                    private val events: Events) : Closeable {
    interface Events {
        /** The addresses changed; [problem] says why one went away, when one did. Any thread. */
        fun changed(problem: String?)
        /** Sharing ended by itself, for [reason]. Any thread. */
        fun stopped(reason: String)
        /** A paired computer made a request through [via]. On a server thread. */
        fun seen(computer: String, via: Via)
    }

    companion object {
        /** Tried first, so a computer that saved this phone finds it at the same address next time. */
        const val PREFERRED_PORT = 41987
        /** Fast USB listens on the first free one of these, so computers know where to look. */
        val FAST_USB_PORTS = 41987..41990
        /**
         * The hotspot catch-all listens here: on every IPv4 address, taking only connections that arrive on an interface
         * that isn't one of Android's networks (the phone's hotspot or tethering). Computers on a phone's hotspot try the
         * gateway on this port too.
         */
        const val HOTSPOT_PORT = 41986
        /** The phone's TLS identity: made once and kept, so paired computers recognize it later. */
        private const val IDENTITY = "openbridge-identity"
    }

    /** A listening socket on one address, with its own session so requests are told apart by how they came in. */
    private class Door(val via: Via, val address: InetAddress, val socket: SSLServerSocket, val session: Session) {
        @Volatile var closed = false
        /** The catch-all: every address, until a connection shows which one the hotspot has. */
        val wildcard get() = address.isAnyLocalAddress
        @Volatile var learned: InetAddress? = null
        val shown get() = !wildcard || learned != null
        val endpoint get() = Endpoint(via, (learned ?: address).hostAddress!!, socket.localPort)
    }

    /** The addresses of Android's own networks (Wi-Fi, mobile data, VPNs): the catch-all never serves those. */
    @Volatile private var androidAddresses: Set<InetAddress> = emptySet()

    private fun readAndroidAddresses() {
        @Suppress("DEPRECATION")
        androidAddresses = runCatching {
            connectivity.allNetworks.mapNotNull { connectivity.getLinkProperties(it) }.filterNot { Hotspot.isAccessPoint(it.interfaceName) }
                .flatMap { it.linkAddresses.map { link -> link.address } }.toSet()
        }.getOrDefault(androidAddresses)
    }

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val nsd = context.getSystemService(NsdManager::class.java)
    /** The name computers see in their list of phones, like "vivo X300 Pro". */
    private val deviceName = Settings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
        ?: "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
    private val closed = AtomicBoolean(false)
    private val sockets = ConcurrentHashMap.newKeySet<SSLSocket>()
    // Four requests at a time (a transfer, browsing and previews together), and a few more wait their turn instead of
    // being dropped. Anything beyond that is refused, so a misbehaving client can't pile up threads.
    private val workers = ThreadPoolExecutor(4, 4, 10, TimeUnit.SECONDS, ArrayBlockingQueue(8)).apply { allowCoreThreadTimeOut(true) }
    // Tethering and hotspot interfaces are not ConnectivityManager networks, so addresses are also checked every two seconds.
    private val watcher = Executors.newSingleThreadScheduledExecutor { Thread(it, "openbridge-watch").apply { isDaemon = true } }
    private val doors = CopyOnWriteArrayList<Door>()
    private val tls: SSLContext
    /** SHA-256 of this phone's certificate; computers pin it. */
    private val fingerprint: ByteArray
    private var started = false
    private var callbackRegistered = false
    /** The Wi-Fi network being served, while one is. */
    private var wifi: Network? = null
    /**
     * Wi-Fi or tethering was served and went away. Sharing carries on (Fast USB, the hotspot), and comes back on the same
     * Wi-Fi when it returns, but doesn't follow the phone onto another Wi-Fi by itself.
     */
    private var networkLost = false
    /** The Wi-Fi address being served, or the one lost, to recognize the same network when it comes back. */
    private var wifiLink: LinkAddress? = null
    /** What's announced on each network (Wi-Fi and the hotspot), with the listener to withdraw it. */
    private val announcements = HashMap<Via, Pair<Endpoint, NsdManager.RegistrationListener>>()

    /** Where computers can reach the phone now: Wi-Fi or tethering first, then the hotspot, then Fast USB. */
    @Volatile var endpoints: List<Endpoint> = emptyList(); private set

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = check()
        override fun onLost(network: Network) = check()
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = check()
    }

    init {
        // One identity for as long as the app is installed, so paired computers reconnect without a code. The private
        // key never leaves the AndroidKeyStore. Earlier versions made a new one on every start; drop that one.
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias("openbridge-session")) keyStore.deleteEntry("openbridge-session")
        if (!keyStore.containsAlias(IDENTITY)) KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(IDENTITY, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                .setCertificateSubject(X500Principal("CN=OpenBridge"))
                .setCertificateNotBefore(Date(System.currentTimeMillis() - 86_400_000))
                .setCertificateNotAfter(Date(System.currentTimeMillis() + 25L * 365 * 86_400_000))
                .build())
            generateKeyPair()
        }
        fingerprint = MessageDigest.getInstance("SHA-256").digest(keyStore.getCertificate(IDENTITY).encoded)
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, null) }
        tls = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, SecureRandom()) }
        try {
            synchronized(this) {
                // Never 0.0.0.0, cellular or a public interface, and no port mapping.
                if (open(Via.USB, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), FAST_USB_PORTS.toList()) == null && cableOnly)
                    throw IllegalStateException(S.fastUsbBusy)
                if (!cableOnly) { readAndroidAddresses(); network(); hotspot(); open(Via.HOTSPOT, InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)), listOf(HOTSPOT_PORT)) }
                if (doors.isEmpty()) throw IllegalStateException(S.couldNotStart)
                publish()
            }
        } catch (e: Exception) { close(); throw e }
    }

    fun start() = synchronized(this) {
        if (closed.get() || started) return
        started = true
        doors.forEach(::accept)
        if (cableOnly) return
        runCatching {
            connectivity.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
            callbackRegistered = true
        }
        watcher.scheduleWithFixedDelay(::refresh, 2, 2, TimeUnit.SECONDS)
        announce()
    }

    /** Looks at the phone's addresses again soon, for example when the hotspot came up. Any thread. */
    fun check() {
        if (!closed.get()) runCatching { watcher.execute(::refresh) }
    }

    /** Brings the listeners in line with the phone's addresses: drops the ones that went away, adds new ones. */
    private fun refresh() {
        var problem: String? = null
        var changed: Boolean
        synchronized(this) {
            if (closed.get() || cableOnly) return
            val current = doors.firstOrNull { it.via == Via.WIFI || it.via == Via.TETHER }
            if (current != null && !present(current)) {
                shut(current); wifi = null; networkLost = true
                problem = if (current.via == Via.TETHER) S.cableRemoved else S.wifiDisconnected
                Log.add("${current.via.label} address went away")
            }
            readAndroidAddresses()
            network()
            hotspot()
            // Losing Wi-Fi doesn't end sharing: many phones drop it as their hotspot starts, Fast USB keeps working,
            // and the same Wi-Fi is picked up again when it comes back.
            changed = publish()
            announce()
        }
        if (changed) events.changed(problem)
    }

    /** Wi-Fi or tethering, if none is served yet: after Wi-Fi was lost, only the same Wi-Fi coming back. */
    private fun network() {
        if (doors.any { it.via == Via.WIFI || it.via == Via.TETHER }) return
        // A USB tethering link is preferred: it only reaches the cable-connected computer.
        usbTetherAddress()?.let { open(Via.TETHER, it, listOf(PREFERRED_PORT, 0)); return }
        @Suppress("DEPRECATION") for (candidate in connectivity.allNetworks) {
            val caps = connectivity.getNetworkCapabilities(candidate) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue
            val properties = connectivity.getLinkProperties(candidate) ?: continue
            if (Hotspot.isAccessPoint(properties.interfaceName)) continue   // the phone's own hotspot, served as such
            val link = properties.linkAddresses.firstOrNull { Wire.isPrivateIPv4(it.address) } ?: continue
            if (networkLost && !sameNetwork(wifiLink, link)) continue
            if (open(Via.WIFI, link.address, listOf(PREFERRED_PORT, 0)) != null) { wifi = candidate; wifiLink = link; networkLost = false }
            return
        }
    }

    /** The same subnet as the Wi-Fi served before: the home network coming back, not a café's. */
    private fun sameNetwork(before: LinkAddress?, now: LinkAddress): Boolean {
        before ?: return false
        if (before.prefixLength != now.prefixLength) return false
        val a = before.address.address; val b = now.address.address
        if (a.size != b.size) return false
        var bits = before.prefixLength
        for (i in a.indices) {
            if (bits <= 0) break
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((a[i].toInt() and mask) != (b[i].toInt() and mask)) return false
            bits -= 8
        }
        return true
    }

    /** The phone's own hotspot, while it's on (see [Hotspot.address]). */
    private fun hotspot() {
        val address = Hotspot.address(context)
        val served = doors.firstOrNull { it.via == Via.HOTSPOT && !it.wildcard }
        if (served != null && served.address == address) return
        if (served != null) { shut(served); Log.add("Hotspot address went away") }
        if (address != null) { unrecognized = 0; open(Via.HOTSPOT, address, listOf(PREFERRED_PORT, 0)) }
        else if (Hotspot.on && !interfacesLogged && ++unrecognized >= 3) {
            // Only once it's had a few seconds to get its address, so a hotspot that's still starting isn't reported.
            // For diagnostics: what this phone calls its interfaces, when its hotspot can't be told apart.
            interfacesLogged = true
            val seen = runCatching { java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())
                .joinToString { "${it.name}${if (it.inetAddresses.toList().any { a -> a is java.net.Inet4Address && Wire.isPrivateIPv4(a) }) "*" else ""}" }
            Log.add("Hotspot interface not recognized; interfaces: ${seen.ifEmpty { "none visible" }}")
        }
    }
    private var interfacesLogged = false
    private var unrecognized = 0

    private fun present(door: Door): Boolean = when (door.via) {
        Via.TETHER -> usbTetherAddress() == door.address
        Via.WIFI -> wifi?.let { connectivity.getLinkProperties(it) }?.linkAddresses?.any { it.address == door.address } == true
        else -> true
    }

    /** Listens on [address], on the first of [ports] that's free (0: any). Null when none is. */
    private fun open(via: Via, address: InetAddress, ports: List<Int>): Door? {
        for (port in ports) {
            val socket = (tls.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
                enabledProtocols = supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
                // Lets sharing restart on the same port while the last session's closed connections linger (TIME_WAIT).
                // It never lets a second listener take a port that's in use.
                reuseAddress = true
            }
            try { socket.bind(InetSocketAddress(address, port), 8) } catch (_: IOException) { socket.close(); continue }
            val door = Door(via, address, socket, session(via))
            doors += door
            Log.add("Listening on ${address.hostAddress}:${socket.localPort} (${via.label})")
            if (started) accept(door)
            return door
        }
        Log.add("Couldn't listen for ${via.label}: ports in use")
        return null
    }

    private fun shut(door: Door) {
        door.closed = true
        doors.remove(door)
        runCatching { door.socket.close() }
    }

    /** The same paired computers on every address; requests are told apart by the door they came through. */
    private fun session(via: Via) = Session(store, object : Trust {
        override fun computer(secret: ByteArray): String? = trust.computer(secret)?.also { seen(it, via) }
        override fun admit(computer: String): ByteArray = trust.admit(computer).also { seen(computer, via) }
    }, fingerprint, approver, clipboard, PhoneOutbox).describe(BuildConfig.VERSION_NAME, deviceName)

    private fun seen(computer: String, via: Via) = events.seen(computer, via)

    private fun accept(door: Door) = Thread({
        try {
            while (!closed.get() && !door.closed) {
                val socket = door.socket.accept() as SSLSocket
                // Fast USB takes only connections from the phone itself (USB debugging forwards them there); the
                // network addresses only from private addresses.
                val local = socket.localAddress
                val allowed = when {
                    door.via == Via.USB -> socket.inetAddress.isLoopbackAddress
                    // The catch-all serves only interfaces that aren't Android networks: never Wi-Fi, mobile data or a VPN.
                    door.wildcard -> Wire.isPrivateIPv4(socket.inetAddress) && Wire.isPrivateIPv4(local) && local !in androidAddresses
                    else -> Wire.isPrivateIPv4(socket.inetAddress)
                }
                if (!allowed) { socket.close(); continue }
                if (door.wildcard && door.learned != local) {
                    door.learned = local
                    Log.add("A computer reached the phone hotspot at ${local.hostAddress}")
                    check()
                }
                socket.soTimeout = 30_000
                sockets.add(socket)
                try {
                    workers.execute {
                        try {
                            socket.use {
                                it.startHandshake()
                                door.session.serve(it.inputStream, it.outputStream)
                            }
                        } catch (_: Exception) {
                            // Client receives a protocol error or detects EOF. Never log secrets.
                        } finally { sockets.remove(socket) }
                    }
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    sockets.remove(socket); socket.close()
                }
            }
        } catch (e: Exception) {
            if (!closed.get() && !door.closed) { Log.add("${door.via.label} listener failed: ${e.javaClass.simpleName}"); stop(S.serverStopped) }
        }
    }, "openbridge-${door.via.name.lowercase()}").start()

    private fun publish(): Boolean {
        val now = doors.filter { it.shown }.sortedWith(compareBy({ it.via.ordinal }, { it.wildcard })).map { it.endpoint }.distinctBy { it.via to it.host }
        if (now == endpoints) return false
        endpoints = now
        return true
    }

    private fun stop(reason: String) { close(); events.stopped(reason) }

    /**
     * Lets computers on the same network list the phone (DNS-SD `_openbridge._tcp`), so nobody types an address: on
     * Wi-Fi, or on the phone's hotspot when there's no Wi-Fi; never for tethering or Fast USB. It reveals only the
     * phone's name, address, port and a short ID derived from its certificate (so a computer that saved the phone
     * recognizes it at a new address); pairing still needs the 6-digit code.
     */
    private fun announce() {
        if (closed.get() || !started) return
        val wifi = doors.firstOrNull { it.via == Via.WIFI }?.endpoint
        val hotspot = doors.filter { it.via == Via.HOTSPOT && it.shown }.minByOrNull { if (it.wildcard) 1 else 0 }?.endpoint
        announceOn(Via.WIFI, wifi, deviceName)
        // A computer on the hotspot can't reach the Wi-Fi address, so the hotspot gets its own announcement.
        announceOn(Via.HOTSPOT, hotspot, if (wifi != null) "$deviceName (${S.hotspotLabel})" else deviceName)
    }

    private fun announceOn(via: Via, target: Endpoint?, name: String) {
        if (announcements[via]?.first == target) return
        announcements.remove(via)?.let { runCatching { nsd.unregisterService(it.second) } }
        if (target == null) return
        val info = NsdServiceInfo().apply {
            serviceName = name.take(60)
            serviceType = "_openbridge._tcp"
            port = target.port
            setAttribute("ip", target.host)
            setAttribute("port", target.port.toString())
            setAttribute("v", "1")
            setAttribute("id", fingerprint.take(8).joinToString("") { "%02x".format(it) })
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) { Log.add("Announcing on the ${via.label} failed ($error)") }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {}
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener); announcements[via] = target to listener }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        watcher.shutdownNow()
        synchronized(this) {
            announcements.values.forEach { runCatching { nsd.unregisterService(it.second) } }
            announcements.clear()
            if (callbackRegistered) runCatching { connectivity.unregisterNetworkCallback(callback) }
            callbackRegistered = false
            doors.forEach { it.closed = true; runCatching { it.socket.close() } }
            doors.clear()
        }
        sockets.forEach { runCatching { it.close() } }
        workers.shutdownNow()
        Log.add("Stopped listening")
    }
}

/** Private IPv4 address of an active USB tethering interface, if any. */
private fun usbTetherAddress(): InetAddress? = runCatching {
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && Wire.isUsbTetherInterface(it.name) }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && Wire.isPrivateIPv4(it) }
}.getOrNull()
