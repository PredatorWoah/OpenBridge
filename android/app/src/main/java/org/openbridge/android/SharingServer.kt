package org.openbridge.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.provider.Settings
import org.openbridge.protocol.Clipboard
import org.openbridge.protocol.Pairing
import org.openbridge.protocol.Session
import org.openbridge.protocol.Store
import org.openbridge.protocol.Trust
import org.openbridge.protocol.Wire
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.security.auth.x500.X500Principal

class SharingServer(context: Context, private val store: Store, private val trust: Trust, private val approver: Pairing.Approver,
                    private val clipboard: Clipboard?, private val stopped: (String) -> Unit) : Closeable {
    companion object {
        /** Tried first, so a computer that saved this phone finds it at the same address next time. */
        const val PREFERRED_PORT = 41987
        /** The phone's TLS identity: made once and kept, so paired computers recognize it later. */
        private const val IDENTITY = "openbridge-identity"
    }
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val nsd = context.getSystemService(NsdManager::class.java)
    /** The name Macs see in their list of phones, like "vivo X300 Pro". */
    private val deviceName = Settings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
        ?: "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
    private var announcement: NsdManager.RegistrationListener? = null
    private val closed = AtomicBoolean(false)
    private val sockets = ConcurrentHashMap.newKeySet<SSLSocket>()
    // Four requests at a time (a transfer, browsing and previews together), and a few more wait their turn instead of
    // being dropped. Anything beyond that is refused, so a misbehaving client can't pile up threads.
    private val workers = ThreadPoolExecutor(4, 4, 10, TimeUnit.SECONDS, ArrayBlockingQueue(8)).apply { allowCoreThreadTimeOut(true) }
    /** Serves paired computers, and pairs new ones by 6-digit comparison (see Pairing). */
    private val session: Session
    private var callbackRegistered = false
    /** Wi-Fi network being served, or null when serving over a USB tethering cable. */
    private val network: Network?
    private val address: InetAddress
    private var listener: SSLServerSocket
    /** SHA-256 of this phone's certificate; computers pin it. */
    private val fingerprint: ByteArray
    val usb: Boolean get() = network == null
    val host: String get() = address.hostAddress!!
    val port: Int get() = listener.localPort

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(lost: Network) { if (lost == network) stop(S.wifiDisconnected) }
        override fun onLinkPropertiesChanged(changed: Network, properties: LinkProperties) {
            if (changed == network && properties.linkAddresses.none { it.address == address })
                stop(S.wifiChanged)
        }
    }

    init {
        // A USB tethering link is preferred: it only reaches the cable-connected computer.
        val tethered = usbTetherAddress()
        val candidate = if (tethered != null) null to tethered else connectivity.allNetworks.firstNotNullOfOrNull { candidate ->
            val caps = connectivity.getNetworkCapabilities(candidate)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                connectivity.getLinkProperties(candidate)?.linkAddresses
                    ?.firstOrNull { Wire.isPrivateIPv4(it.address) }?.let { candidate to it.address }
            } else null
        } ?: throw IllegalStateException(S.needNetwork)
        network = candidate.first
        address = candidate.second

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
        session = Session(store, trust, fingerprint, approver, clipboard)
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, null) }
        val tls = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, SecureRandom()) }
        fun socket() = (tls.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
            enabledProtocols = supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            // Lets sharing restart on the same port while the last session's closed connections linger (TIME_WAIT).
            // It never lets a second listener take a port that's in use.
            reuseAddress = true
        }
        listener = socket()
        try {
            // Never bind 0.0.0.0, cellular, or a public interface. No port mapping. The same port each time when
            // it's free, so saved computers find the phone even where discovery is blocked.
            try { listener.bind(InetSocketAddress(address, PREFERRED_PORT), 8) }
            catch (_: java.io.IOException) { listener.close(); listener = socket(); listener.bind(InetSocketAddress(address, 0), 8) }
            if (network != null) {
                connectivity.registerNetworkCallback(NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
                callbackRegistered = true
                if (connectivity.getLinkProperties(network)?.linkAddresses?.none { it.address == address } != false)
                    throw IllegalStateException("Wi-Fi disconnected")
            }
        } catch (e: Exception) { close(); throw e }
    }

    fun start() {
        announce()
        Thread({
            try {
                while (!closed.get()) {
                    val socket = listener.accept() as SSLSocket
                    if (!Wire.isPrivateIPv4(socket.inetAddress)) { socket.close(); continue }
                    socket.soTimeout = 30_000
                    sockets.add(socket)
                    try {
                        workers.execute {
                            try {
                                socket.use {
                                    it.startHandshake()
                                    session.serve(it.inputStream, it.outputStream)
                                }
                            } catch (_: Exception) {
                                // Client receives a protocol error or detects EOF. Never log secrets.
                            } finally { sockets.remove(socket) }
                        }
                    } catch (_: java.util.concurrent.RejectedExecutionException) {
                        sockets.remove(socket); socket.close()
                    }
                }
            } catch (_: Exception) { if (!closed.get()) stop(S.serverStopped) }
        }, "openbridge-listener").start()
        // Tethering interfaces are not exposed as ConnectivityManager networks, so poll for cable removal.
        if (usb) Thread({
            try {
                while (!closed.get()) {
                    Thread.sleep(2_000)
                    if (!closed.get() && usbTetherAddress() != address) stop(S.cableRemoved)
                }
            } catch (_: InterruptedException) {}
        }, "openbridge-usb-watch").apply { isDaemon = true }.start()
    }

    private fun stop(reason: String) { close(); stopped(reason) }

    /**
     * Lets computers on this Wi-Fi list the phone while sharing (DNS-SD `_openbridge._tcp`), so nobody types an
     * address. It reveals only the phone's name, address, port and a short ID derived from its certificate (so a
     * computer that saved the phone recognizes it at a new address); pairing still needs the 6-digit code.
     */
    private fun announce() {
        if (usb) return
        val info = NsdServiceInfo().apply {
            serviceName = deviceName.take(60)
            serviceType = "_openbridge._tcp"
            port = this@SharingServer.port
            setAttribute("ip", this@SharingServer.host)
            setAttribute("port", this@SharingServer.port.toString())
            setAttribute("v", "1")
            setAttribute("id", fingerprint.take(8).joinToString("") { "%02x".format(it) })
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {}
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener); announcement = listener }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        announcement?.let { runCatching { nsd.unregisterService(it) } }
        announcement = null
        if (callbackRegistered) connectivity.unregisterNetworkCallback(callback)
        listener.close()
        sockets.forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }
}

/** Private IPv4 address of an active USB tethering interface, if any. */
private fun usbTetherAddress(): InetAddress? = runCatching {
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && Wire.isUsbTetherInterface(it.name) }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && Wire.isPrivateIPv4(it) }
}.getOrNull()
