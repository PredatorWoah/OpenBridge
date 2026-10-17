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
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.security.auth.x500.X500Principal

class SharingServer(context: Context, private val store: Store, private val approver: Pairing.Approver,
                    private val clipboard: Clipboard?, private val stopped: (String) -> Unit) : Closeable {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val nsd = context.getSystemService(NsdManager::class.java)
    /** The name Macs see in their list of phones, like "vivo X300 Pro". */
    private val deviceName = Settings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
        ?: "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
    private var announcement: NsdManager.RegistrationListener? = null
    private val closed = AtomicBoolean(false)
    private val sockets = ConcurrentHashMap.newKeySet<SSLSocket>()
    private val workers = ThreadPoolExecutor(0, 2, 10, TimeUnit.SECONDS, SynchronousQueue())
    private val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
    /** Serves paired Macs, and pairs new ones by 6-digit comparison (see Pairing). */
    private val session: Session
    private var callbackRegistered = false
    /** Wi-Fi network being served, or null when serving over a USB tethering cable. */
    private val network: Network?
    private val address: InetAddress
    private val listener: SSLServerSocket
    val usb: Boolean get() = network == null
    val host: String get() = address.hostAddress!!
    val port: Int get() = listener.localPort

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(lost: Network) { if (lost == network) stop("Wi-Fi disconnected") }
        override fun onLinkPropertiesChanged(changed: Network, properties: LinkProperties) {
            if (changed == network && properties.linkAddresses.none { it.address == address })
                stop("Wi-Fi address changed")
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
        } ?: throw IllegalStateException("Connect to Wi-Fi or turn on USB tethering first")
        network = candidate.first
        address = candidate.second

        // New certificate and secret on every start; nothing is paired permanently.
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "openbridge-session"
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                .setCertificateSubject(X500Principal("CN=OpenBridge session"))
                .setCertificateNotBefore(Date(System.currentTimeMillis() - 60_000))
                .setCertificateNotAfter(Date(System.currentTimeMillis() + 86_400_000))
                .build())
            generateKeyPair()
        }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(keyStore.getCertificate(alias).encoded)
        session = Session(store, secret, fingerprint, approver, clipboard)
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, null) }
        val tls = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, SecureRandom()) }
        listener = (tls.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
            enabledProtocols = supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            reuseAddress = false
        }
        try {
            // Never bind 0.0.0.0, cellular, or a public interface. No discovery or port mapping.
            listener.bind(InetSocketAddress(address, 0), 2)
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
            } catch (_: Exception) { if (!closed.get()) stop("Sharing server stopped unexpectedly") }
        }, "openbridge-listener").start()
        // Tethering interfaces are not exposed as ConnectivityManager networks, so poll for cable removal.
        if (usb) Thread({
            try {
                while (!closed.get()) {
                    Thread.sleep(2_000)
                    if (!closed.get() && usbTetherAddress() != address) stop("USB cable disconnected or tethering turned off")
                }
            } catch (_: InterruptedException) {}
        }, "openbridge-usb-watch").apply { isDaemon = true }.start()
    }

    private fun stop(reason: String) { close(); stopped(reason) }

    /**
     * Lets Macs on this Wi-Fi list the phone while sharing (DNS-SD `_openbridge._tcp`), so nobody types an
     * address. It reveals only the phone's name, address and port; pairing still needs the 6-digit code.
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
        secret.fill(0)
    }
}

/** Private IPv4 address of an active USB tethering interface, if any. */
private fun usbTetherAddress(): InetAddress? = runCatching {
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && Wire.isUsbTetherInterface(it.name) }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && Wire.isPrivateIPv4(it) }
}.getOrNull()
