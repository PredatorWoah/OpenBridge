package org.openbridge.windows

import org.openbridge.protocol.Pairing
import org.openbridge.protocol.Wire
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

data class Entry(val id: String, val name: String, val directory: Boolean, val size: Long)

/** A problem to show the user as is. `lost` means the phone can't be reached any more (stopped sharing, left the Wi‑Fi). */
class BridgeException(message: String, val lost: Boolean = false) : IOException(message)

/**
 * The OB01 client (see docs/protocol.md): one authenticated request per TLS connection, pinned to the phone's
 * certificate learned by 6-digit pairing. Blocking: call from a background thread.
 */
class BridgeClient internal constructor(
    val host: String, val port: Int, private val fingerprint: ByteArray, private val secret: ByteArray,
) {
    companion object {
        private const val TIMEOUT = 30_000

        /**
         * Opens TLS to the phone. With a pin, any other certificate fails before anything is sent.
         * A phone that is busy with other requests drops the extra connection during the handshake, before any
         * request is sent, so that is retried a few times.
         */
        private fun connect(host: String, port: Int, pin: ByteArray?, seen: (ByteArray) -> Unit = {}): SSLSocket {
            var attempt = 0
            while (true) {
                try { return open(host, port, pin, seen) }
                catch (e: BridgeException) {
                    if (e.message == S.certificateChanged || ++attempt >= 4) throw e
                    Thread.sleep(250L * attempt)
                }
            }
        }

        private fun open(host: String, port: Int, pin: ByteArray?, seen: (ByteArray) -> Unit): SSLSocket {
            val trust = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException()
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    val digest = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                    seen(digest)
                    if (pin != null && !MessageDigest.isEqual(pin, digest)) throw CertificateException(S.certificateChanged)
                }
                override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
            }
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
            val socket = context.socketFactory.createSocket() as SSLSocket
            socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            socket.soTimeout = TIMEOUT
            try {
                socket.connect(InetSocketAddress(host, port), 10_000)
                socket.startHandshake()
            } catch (e: Exception) {
                socket.close()
                throw if (e is javax.net.ssl.SSLHandshakeException && e.cause is CertificateException) BridgeException(S.certificateChanged, lost = true)
                else BridgeException(S.unreachable("$host:$port"), lost = true)
            }
            return socket
        }

        /** Only private Wi‑Fi addresses, as on the phone and the Mac. */
        fun checkAddress(host: String, port: String): Int {
            val parts = host.split(".")
            val valid = parts.size == 4 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) && it.toInt() in 0..255 && it == it.toInt().toString() }
            if (!valid || !Wire.isPrivateIPv4(InetAddress.getByName(host))) throw BridgeException(S.badAddress)
            return port.toIntOrNull()?.takeIf { it in 1..65535 } ?: throw BridgeException(S.badPort)
        }

        /**
         * OBP1 numeric-comparison pairing: `show` displays the 6-digit code; the phone's user taps Pair if it matches.
         * Returns a client pinned to the certificate that the code covered.
         */
        fun pair(host: String, portText: String, opened: (Closeable) -> Unit = {}, show: (String) -> Unit): BridgeClient =
            pairAt(host, checkAddress(host, portText), opened, show)

        /** Pairing without the address check, for loopback tests. `opened` gets the connection, so Cancel can close it. */
        internal fun pairAt(host: String, port: Int, opened: (Closeable) -> Unit = {}, show: (String) -> Unit): BridgeClient {
            var fingerprint: ByteArray? = null
            connect(host, port, null) { fingerprint = it }.also(opened).use { socket ->
                val f = fingerprint ?: throw BridgeException("The phone sent no certificate.")
                val input = DataInputStream(socket.inputStream.buffered())
                val output = DataOutputStream(socket.outputStream.buffered())
                output.write(Pairing.MAGIC); output.flush()
                status(input)
                val commitment = ByteArray(32).also(input::readFully)
                val macNonce = ByteArray(Pairing.NONCE).also { SecureRandom().nextBytes(it) }
                output.write(macNonce); output.flush()
                val phoneNonce = ByteArray(Pairing.NONCE).also(input::readFully)
                if (!MessageDigest.isEqual(commitment, Pairing.commitment(f, phoneNonce)))
                    throw BridgeException(S.pairingTampered)
                show(Pairing.code(f, macNonce, phoneNonce))
                socket.soTimeout = 75_000   // the phone's user has about a minute to compare and tap Pair
                status(input)
                val secret = ByteArray(32).also(input::readFully)
                return BridgeClient(host, port, f, secret)
            }
        }

        private fun status(input: DataInputStream) {
            when (input.readUnsignedByte()) {
                0 -> return
                1 -> throw BridgeException(Wire.readString(input, 512))
                else -> throw BridgeException("The phone sent an invalid reply.")
            }
        }
    }

    private fun <T> request(operation: Int, id: String, extra: (DataOutputStream) -> Unit = {}, body: (DataInputStream, DataOutputStream) -> T): T =
        connect(host, port, fingerprint).use { socket ->
            try {
                val input = DataInputStream(socket.inputStream.buffered(1 shl 16))
                val output = DataOutputStream(socket.outputStream.buffered(1 shl 16))
                output.write(Wire.MAGIC); output.write(secret); output.writeByte(operation); Wire.writeString(output, id)
                extra(output)
                output.flush()
                body(input, output)
            } catch (e: BridgeException) { throw e }
            // Reset, timed out or closed mid-request: the phone stopped sharing or left the network.
            catch (e: IOException) { throw BridgeException(S.dropped, lost = true) }
        }

    fun list(id: String = ""): List<Entry> = request(1, id) { input, _ ->
        status(input)
        val count = input.readInt()
        if (count !in 0..Wire.MAX_ENTRIES) throw BridgeException("Folder has too many entries.")
        val seen = HashSet<String>()
        List(count) {
            val entryId = Wire.readString(input, 128)
            val name = Wire.readString(input, 255)
            Wire.validateName(name)
            val directory = when (input.readUnsignedByte()) { 0 -> false; 1 -> true; else -> throw BridgeException("Invalid directory entry.") }
            val size = input.readLong()
            if (entryId.isEmpty() || !seen.add(entryId) || size < 0) throw BridgeException("Invalid directory entry.")
            Entry(entryId, name, directory, size)
        }
    }

    /** Downloads `entry`, continuing an earlier interrupted download to the same place when there is one. */
    fun download(entry: Entry, destination: File, cancelled: () -> Boolean = { false }, progress: (Long, Long) -> Unit) {
        if (destination.exists()) throw BridgeException("${destination.name} already exists. Choose a new name; OpenBridge never replaces files.")
        val partial = Partial.location(destination, entry.size)
        var offset = if (partial.exists() && partial.length() in 1 until entry.size) partial.length() else 0L
        if (offset == 0L) partial.delete()
        try {
            val operation = if (offset > 0) 6 else 2
            request(operation, entry.id, { if (offset > 0) it.writeLong(offset) }) { input, _ ->
                try { status(input) } catch (e: BridgeException) {
                    if (offset > 0 && e.message?.contains("Unsupported operation") == true) { offset = -1; return@request }   // old phone app
                    throw e
                }
                val total = input.readLong()
                if (total != entry.size) throw BridgeException(Partial.CHANGED)
                RandomAccessFile(partial, "rw").use { file ->
                    file.seek(offset)
                    var received = offset
                    val buffer = ByteArray(1 shl 16)
                    progress(received, total)
                    var reported = received
                    while (received < total) {
                        if (cancelled()) throw InterruptedException()
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), total - received).toInt())
                        if (read < 0) throw BridgeException(S.dropped, lost = true)
                        file.write(buffer, 0, read)
                        received += read
                        if (received - reported >= 1 shl 20 || received == total) { reported = received; progress(received, total) }
                    }
                    file.fd.sync()
                }
            }
            if (offset < 0) { partial.delete(); return download(entry, destination, cancelled, progress) }
            // Never replaces: without REPLACE_EXISTING the move fails if the destination appeared meanwhile.
            // (ATOMIC_MOVE would replace it on Windows.)
            Files.move(partial.toPath(), destination.toPath())
        } catch (e: InterruptedException) {
            partial.delete(); throw e
        } catch (e: BridgeException) {
            if (e.message == Partial.CHANGED) partial.delete()
            throw e
        }
    }

    fun upload(source: File, directoryId: String, cancelled: () -> Boolean = { false }, progress: (Long, Long) -> Unit) {
        val name = source.name
        Wire.validateName(name)
        if (!source.isFile) throw BridgeException("Choose a regular file.")
        val size = source.length()
        if (size > Wire.MAX_FILE) throw BridgeException("Files up to 64 GiB can be sent.")
        request(3, directoryId, { Wire.writeString(it, name); it.writeLong(size) }) { input, output ->
            status(input)   // ready: the phone checked the name and space before any data is sent
            source.inputStream().buffered(1 shl 16).use { stream ->
                val buffer = ByteArray(1 shl 16)
                var sent = 0L; var reported = 0L
                progress(0, size)
                while (sent < size) {
                    if (cancelled()) throw InterruptedException()
                    val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), size - sent).toInt())
                    if (read < 0) throw BridgeException("$name changed during the upload.")
                    output.write(buffer, 0, read)
                    sent += read
                    if (sent - reported >= 1 shl 20 || sent == size) { reported = sent; progress(sent, size) }
                }
            }
            output.flush()
            status(input)   // published: a completed send is not success until the phone confirms
        }
    }

    fun makeFolder(name: String, directoryId: String): Entry {
        Wire.validateName(name)
        return request(4, directoryId, { Wire.writeString(it, name) }) { input, _ ->
            status(input)
            Entry(Wire.readString(input, 128), name, true, 0)
        }
    }

    fun thumbnail(entry: Entry, size: Int = 256): ByteArray = request(5, entry.id, { it.writeInt(size.coerceIn(32, 1024)) }) { input, _ ->
        status(input)
        val length = input.readInt()
        if (length !in 1..Wire.MAX_THUMBNAIL) throw BridgeException("Invalid preview.")
        ByteArray(length).also(input::readFully)
    }

    fun clipboard(): String = request(7, "") { input, _ -> status(input); Wire.readString(input, Wire.MAX_CLIPBOARD) }

    fun setClipboard(text: String) {
        if (text.toByteArray().size > Wire.MAX_CLIPBOARD) throw BridgeException("That text is too long for the phone's clipboard.")
        request(8, "", { Wire.writeString(it, text) }) { input, _ -> status(input) }
    }
}

/** An interrupted download, kept beside its destination: `.<name>.<size>.openbridge-part`, as on the Mac. */
object Partial {
    const val CHANGED = "The file on the phone changed since the download started. Download it again."
    fun location(destination: File, size: Long) = File(destination.parentFile, ".${destination.name}.$size.openbridge-part")
}
