package org.openbridge.android

import android.content.Context
import android.net.ConnectivityManager
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
        /** The phone's TLS identity: made once and kept, so paired computers recognize it later. */
        private const val IDENTITY = "openbridge-identity"
    }

    /** A listening socket on one address, with its own session so requests are told apart by how they came in. */
    private class Door(val via: Via, val address: InetAddress, val socket: SSLServerSocket, val session: Session) {
        @Volatile var closed = false
        val endpoint get() = Endpoint(via, address.hostAddress!!, socket.localPort)
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
    /** Wi-Fi or tethering was served and went away: sharing doesn't follow the phone onto another network by itself. */
    private var networkLost = false
    /** A computer used Fast USB during this sharing, so losing Wi-Fi doesn't end it. */
    @Volatile private var usedCable = false
    private var announcement: NsdManager.RegistrationListener? = null
    private var announced: Endpoint? = null

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
                if (!cableOnly) { network(); hotspot() }
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
        var end: String? = null
        var changed: Boolean
        synchronized(this) {
            if (closed.get() || cableOnly) return
            val current = doors.firstOrNull { it.via == Via.WIFI || it.via == Via.TETHER }
            if (current != null && !present(current)) {
                shut(current); wifi = null; networkLost = true
                problem = if (current.via == Via.TETHER) S.cableRemoved else S.wifiDisconnected
                Log.add("${current.via.label} address went away")
            }
            network()
            hotspot()
            // Losing the network ends sharing, as it always has, unless the hotspot or Fast USB is what's being used.
            if (problem != null && doors.none { it.via == Via.HOTSPOT } && !usedCable) end = problem
            changed = publish()
            if (end == null) announce()
        }
        end?.let { stop(it); return }
        if (changed) events.changed(problem)
    }

    /** Wi-Fi or tethering, if none is served yet and none was lost during this sharing. */
    private fun network() {
        if (networkLost || doors.any { it.via == Via.WIFI || it.via == Via.TETHER }) return
        // A USB tethering link is preferred: it only reaches the cable-connected computer.
        usbTetherAddress()?.let { open(Via.TETHER, it, listOf(PREFERRED_PORT, 0)); return }
        @Suppress("DEPRECATION") for (candidate in connectivity.allNetworks) {
            val caps = connectivity.getNetworkCapabilities(candidate) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue
            val address = connectivity.getLinkProperties(candidate)?.linkAddresses?.firstOrNull { Wire.isPrivateIPv4(it.address) }?.address ?: continue
            if (open(Via.WIFI, address, listOf(PREFERRED_PORT, 0)) != null) wifi = candidate
            return
        }
    }

    /** The phone's own hotspot, while it's on (see [Hotspot.address]). */
    private fun hotspot() {
        val address = Hotspot.address(context)
        val served = doors.firstOrNull { it.via == Via.HOTSPOT }
        if (served != null && served.address == address) return
        if (served != null) { shut(served); Log.add("Hotspot address went away") }
        if (address != null) open(Via.HOTSPOT, address, listOf(PREFERRED_PORT, 0))
    }

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

    private fun seen(computer: String, via: Via) {
        if (via == Via.USB) usedCable = true
        events.seen(computer, via)
    }

    private fun accept(door: Door) = Thread({
        try {
            while (!closed.get() && !door.closed) {
                val socket = door.socket.accept() as SSLSocket
                // Fast USB takes only connections from the phone itself (USB debugging forwards them there); the
                // network addresses only from private addresses.
                val allowed = if (door.via == Via.USB) socket.inetAddress.isLoopbackAddress else Wire.isPrivateIPv4(socket.inetAddress)
                if (!allowed) { socket.close(); continue }
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
        val now = doors.sortedBy { it.via.ordinal }.map { it.endpoint }
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
        val target = doors.firstOrNull { it.via == Via.WIFI }?.endpoint ?: doors.firstOrNull { it.via == Via.HOTSPOT }?.endpoint
        if (target == announced) return
        announcement?.let { runCatching { nsd.unregisterService(it) } }
        announcement = null; announced = null
        if (target == null || closed.get() || !started) return
        val info = NsdServiceInfo().apply {
            serviceName = deviceName.take(60)
            serviceType = "_openbridge._tcp"
            port = target.port
            setAttribute("ip", target.host)
            setAttribute("port", target.port.toString())
            setAttribute("v", "1")
            setAttribute("id", fingerprint.take(8).joinToString("") { "%02x".format(it) })
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) { Log.add("Announcing on the network failed ($error)") }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {}
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener); announcement = listener; announced = target }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        watcher.shutdownNow()
        synchronized(this) {
            announcement?.let { runCatching { nsd.unregisterService(it) } }
            announcement = null
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
