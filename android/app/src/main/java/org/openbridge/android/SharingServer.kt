package org.openbridge.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.openbridge.protocol.Session
import org.openbridge.protocol.Store
import org.openbridge.protocol.Wire
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
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

class SharingServer(context: Context, store: Store, private val stopped: (String) -> Unit) : Closeable {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val closed = AtomicBoolean(false)
    private val sockets = ConcurrentHashMap.newKeySet<SSLSocket>()
    private val workers = ThreadPoolExecutor(0, 2, 10, TimeUnit.SECONDS, SynchronousQueue())
    private val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val session = Session(store, secret)
    private var callbackRegistered = false
    private val network: Network
    private val address: InetAddress
    private val listener: SSLServerSocket
    val host: String get() = address.hostAddress!!
    val port: Int get() = listener.localPort
    val pairingSecret: String = secret.hex()
    val fingerprint: String

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(lost: Network) { if (lost == network) stop("Wi-Fi disconnected") }
        override fun onLinkPropertiesChanged(changed: Network, properties: LinkProperties) {
            if (changed == network && properties.linkAddresses.none { it.address == address })
                stop("Wi-Fi address changed")
        }
    }

    init {
        val candidate = connectivity.allNetworks.firstNotNullOfOrNull { candidate ->
            val caps = connectivity.getNetworkCapabilities(candidate)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                connectivity.getLinkProperties(candidate)?.linkAddresses
                    ?.firstOrNull { Wire.isPrivateIPv4(it.address) }?.let { candidate to it.address }
            } else null
        } ?: throw IllegalStateException("Connect to Wi-Fi with a private IPv4 address first")
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
        fingerprint = MessageDigest.getInstance("SHA-256").digest(keyStore.getCertificate(alias).encoded).hex()
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, null) }
        val tls = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, SecureRandom()) }
        listener = (tls.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
            enabledProtocols = supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            reuseAddress = false
        }
        try {
            // Never bind 0.0.0.0, cellular, or a public interface. No discovery or port mapping.
            listener.bind(InetSocketAddress(address, 0), 2)
            connectivity.registerNetworkCallback(NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
            callbackRegistered = true
            if (connectivity.getLinkProperties(network)?.linkAddresses?.none { it.address == address } != false)
                throw IllegalStateException("Wi-Fi disconnected")
        } catch (e: Exception) { close(); throw e }
    }

    fun start() {
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
    }

    private fun stop(reason: String) { close(); stopped(reason) }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (callbackRegistered) connectivity.unregisterNetworkCallback(callback)
        listener.close()
        sockets.forEach { runCatching { it.close() } }
        workers.shutdownNow()
        secret.fill(0)
    }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
