package org.openbridge.desktop

import org.openbridge.protocol.Pairing
import org.openbridge.protocol.Wire
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
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

/**
 * A problem to show the user as is. `lost` means the phone can't be reached any more (stopped sharing, left the
 * Wi‑Fi); `forgotten` that it no longer trusts this computer (Forget on the phone, or the app reinstalled): pair again.
 */
class BridgeException(message: String, val lost: Boolean = false, val forgotten: Boolean = false) : IOException(message) {
    /** The phone app is too old for the operation ("Unsupported operation; update OpenBridge on the phone"). */
    val unsupported: Boolean get() = message?.contains("Unsupported operation") == true
}

/** A search hit, and the folder it's in by name relative to where the search started ("DCIM/Camera"). */
data class Found(val entry: Entry, val location: String)

/** What a search found; `complete` is false when the phone stopped early (time or result limit). */
data class SearchResult(val found: List<Found>, val complete: Boolean)

/** What MEASURE found in a folder; `complete` is false when the phone stopped counting early. */
data class FolderSize(val bytes: Long, val files: Long, val complete: Boolean)

/** Something sent from the phone, waiting to be collected: a file, or a text for the clipboard. */
data class Waiting(val entry: Entry, val text: Boolean)

/** What INFO says about the phone: its OpenBridge version, the highest operation it answers, and its name. */
data class PhoneInfo(val version: String, val revision: Int, val name: String)

/**
 * The OB01 client (see docs/protocol.md): one authenticated request per TLS connection, pinned to the phone's
 * certificate learned by 6-digit pairing. Blocking: call from a background thread.
 * Over Wi‑Fi it talks only to private addresses; over Fast USB only to 127.0.0.1, where adb forwards to the phone.
 */
class BridgeClient internal constructor(
    val host: String, val port: Int, val fingerprint: ByteArray, internal val secret: ByteArray, val transport: Transport = Transport.WIFI,
) : PhoneSource {
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
                try { return open(host, port, pin, 10_000, seen) }
                catch (e: BridgeException) {
                    if (e.message == S.certificateChanged || ++attempt >= 4) throw e
                    Thread.sleep(250L * attempt)
                }
            }
        }

        private fun open(host: String, port: Int, pin: ByteArray?, timeout: Int, seen: (ByteArray) -> Unit): SSLSocket {
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
            // A probe gives up quickly; requests wait as long as the phone may take to answer.
            socket.soTimeout = if (timeout < 10_000) timeout else TIMEOUT
            try {
                socket.connect(InetSocketAddress(host, port), timeout)
                socket.startHandshake()
            } catch (e: Exception) {
                socket.close()
                throw if (e is javax.net.ssl.SSLHandshakeException && e.cause is CertificateException) BridgeException(S.certificateChanged, lost = true, forgotten = true)
                else BridgeException(S.unreachable("$host:$port"), lost = true)
            }
            return socket
        }

        /**
         * The certificate fingerprint of whatever answers TLS at the address, or null. Sends nothing after the
         * handshake: this is how Fast USB tells which phone is at the other end of a cable before using a secret.
         */
        fun fingerprintAt(host: String, port: Int, timeout: Int = 3_000): ByteArray? {
            var seen: ByteArray? = null
            return try { open(host, port, null, timeout) { seen = it }.close(); seen } catch (_: Exception) { null }
        }

        /** Whether anything listens at the address, checked quickly, before trying a saved phone for real. */
        fun reachable(host: String, port: Int, timeout: Int = 1_500): Boolean =
            runCatching { java.net.Socket().use { it.connect(InetSocketAddress(host, port), timeout) }; true }.getOrDefault(false)

        /** Only private Wi‑Fi addresses, as on the phone and the Mac. */
        fun checkAddress(host: String, port: String): Int {
            if (!privateIPv4(host)) throw BridgeException(S.badAddress)
            return port.toIntOrNull()?.takeIf { it in 1..65535 } ?: throw BridgeException(S.badPort)
        }

        /**
         * OBP1 numeric-comparison pairing: `show` displays the 6-digit code; the phone's user taps Pair if it matches.
         * Returns a client pinned to the certificate that the code covered.
         */
        fun pair(host: String, portText: String, opened: (Closeable) -> Unit = {}, show: (String) -> Unit): BridgeClient =
            pairAt(host, checkAddress(host, portText), opened, show = show)

        /**
         * Pairing without the address check: through a Fast USB tunnel (`transport`), and in loopback tests.
         * `opened` gets the connection, so Cancel can close it. Sends this computer's name (OBP2); a phone older than
         * 0.11.0 answers that with "Pairing rejected" at once, and pairing starts over without the name (OBP1).
         */
        internal fun pairAt(host: String, port: Int, opened: (Closeable) -> Unit = {}, named: Boolean = true, transport: Transport = Transport.WIFI,
                            show: (String) -> Unit): BridgeClient {
            try { return exchange(host, port, opened, if (named) Platform.computerName else null, transport, show) }
            catch (e: BridgeException) { if (named && e.message == REJECTED) return exchange(host, port, opened, null, transport, show); throw e }
        }

        private const val REJECTED = "Pairing rejected"

        private fun exchange(host: String, port: Int, opened: (Closeable) -> Unit, name: String?, transport: Transport, show: (String) -> Unit): BridgeClient {
            var fingerprint: ByteArray? = null
            connect(host, port, null) { fingerprint = it }.also(opened).use { socket ->
                val f = fingerprint ?: throw BridgeException("The phone sent no certificate.")
                val input = DataInputStream(socket.inputStream.buffered())
                val output = DataOutputStream(socket.outputStream.buffered())
                if (name != null) { output.write(Pairing.MAGIC2); output.write(Pairing.nameBlock(name)) } else output.write(Pairing.MAGIC)
                output.flush()
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
                return BridgeClient(host, port, f, secret, transport)
            }
        }

        private fun status(input: DataInputStream) {
            when (input.readUnsignedByte()) {
                0 -> return
                1 -> Wire.readString(input, 512).let { message ->
                    // Only the authentication check answers this: the phone no longer knows this computer's secret.
                    throw if (message == REJECTED) BridgeException(message, lost = true, forgotten = true) else BridgeException(message)
                }
                else -> throw BridgeException("The phone sent an invalid reply.")
            }
        }
    }

    /** Where the secret may go: the phone's private Wi‑Fi address, or the Fast USB tunnel on this computer. */
    private val allowed = if (transport == Transport.FAST_USB) host == "127.0.0.1" else privateIPv4(host)

    private fun <T> request(operation: Int, id: String, extra: (DataOutputStream) -> Unit = {}, body: (DataInputStream, DataOutputStream) -> T): T {
        if (!allowed) throw BridgeException(S.badAddress)
        return connect(host, port, fingerprint).use { socket ->
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
    }

    override fun list(id: String): List<Entry> = request(1, id) { input, _ ->
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
    override fun download(entry: Entry, destination: File, cancelled: () -> Boolean, progress: (Long, Long) -> Unit) {
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

    /**
     * Uploads `source`. If an earlier upload of the same file to the same folder was cut off, the phone kept what
     * arrived and this continues from there (phones before 0.12.0 start over).
     */
    override fun upload(source: File, directoryId: String, cancelled: () -> Boolean, progress: (Long, Long) -> Unit) {
        val name = source.name
        Wire.validateName(name)
        if (!source.isFile) throw BridgeException("Choose a regular file.")
        val size = source.length()
        if (size > Wire.MAX_FILE) throw BridgeException("Files up to 64 GiB can be sent.")
        val key = contentKey(source)
        try {
            send(12, source, directoryId, { Wire.writeString(it, name); it.writeLong(size); it.write(key) }, resumable = true, cancelled, progress)
        } catch (e: BridgeException) {
            if (e.message?.contains("Unsupported operation") != true) throw e
            send(3, source, directoryId, { Wire.writeString(it, name); it.writeLong(size) }, resumable = false, cancelled, progress)
        }
    }

    private fun send(operation: Int, source: File, directoryId: String, header: (DataOutputStream) -> Unit, resumable: Boolean,
                     cancelled: () -> Boolean, progress: (Long, Long) -> Unit) {
        val name = source.name
        val size = source.length()
        request(operation, directoryId, header) { input, output ->
            status(input)   // ready: the phone checked the name and space before any data is sent
            val have = if (resumable) input.readLong() else 0L
            if (have < 0 || have > size) throw BridgeException("The phone sent an invalid reply.")
            source.inputStream().buffered(1 shl 16).use { stream ->
                val buffer = ByteArray(1 shl 16)
                Wire.skip(stream, have)
                var sent = have; var reported = have
                progress(have, size)
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

    /**
     * Files and texts sent from the phone (Share › OpenBridge, Send clipboard), waiting up to `wait` milliseconds for
     * one. Phones before 0.13.0 answer "Unsupported operation": use [outbox] then, which lists only files.
     */
    fun outboxAll(wait: Int): List<Waiting> = request(17, "", { it.writeInt(wait.coerceIn(0, 25_000)) }) { input, _ ->
        status(input)
        val count = input.readInt()
        if (count !in 0..1_000) throw BridgeException("The phone sent an invalid reply.")
        List(count) {
            val id = Wire.readString(input, 128)
            val name = Wire.readString(input, 255)
            val kind = input.readUnsignedByte()
            val size = input.readLong()
            if (id.isEmpty() || kind !in 0..1 || size !in 0..Wire.MAX_FILE) throw BridgeException("The phone sent an invalid reply.")
            // A file's name becomes a file here; a text's is only a label.
            if (kind == 0) Wire.validateName(name)
            Waiting(Entry(id, name, false, size), text = kind == 1)
        }
    }

    /** Files sent from the phone (Share › OpenBridge), waiting up to `wait` milliseconds for one. */
    fun outbox(wait: Int): List<Entry> = request(13, "", { it.writeInt(wait.coerceIn(0, 25_000)) }) { input, _ ->
        status(input)
        val count = input.readInt()
        if (count !in 0..1_000) throw BridgeException("The phone sent an invalid reply.")
        List(count) {
            val id = Wire.readString(input, 128)
            val name = Wire.readString(input, 255)
            Wire.validateName(name)
            val size = input.readLong()
            if (id.isEmpty() || size !in 0..Wire.MAX_FILE) throw BridgeException("The phone sent an invalid reply.")
            Entry(id, name, false, size)
        }
    }

    /** Saves a file sent from the phone, continuing an interrupted copy, then tells the phone it can drop it. */
    fun receive(entry: Entry, destination: File, progress: (Long, Long) -> Unit = { _, _ -> }) {
        if (destination.exists()) throw BridgeException("${destination.name} already exists.")
        val partial = Partial.location(destination, entry.size)
        val offset = if (partial.exists() && partial.length() in 1 until entry.size) partial.length() else 0L
        if (offset == 0L) partial.delete()
        request(14, entry.id, { it.writeLong(offset) }) { input, _ ->
            status(input)
            val total = input.readLong()
            if (total != entry.size) throw BridgeException(Partial.CHANGED)
            RandomAccessFile(partial, "rw").use { file ->
                file.seek(offset)
                var received = offset
                val buffer = ByteArray(1 shl 16)
                while (received < total) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), total - received).toInt())
                    if (read < 0) throw BridgeException(S.dropped, lost = true)
                    file.write(buffer, 0, read)
                    received += read
                    progress(received, total)
                }
                file.fd.sync()
            }
        }
        Files.move(partial.toPath(), destination.toPath())
        collected(entry)
    }

    /** A text sent from the phone, for the clipboard. Call [collected] once it's there. */
    fun readText(entry: Entry): String = request(14, entry.id, { it.writeLong(0) }) { input, _ ->
        status(input)
        val size = input.readLong()
        if (size !in 0..Wire.MAX_CLIPBOARD.toLong()) throw BridgeException("The phone sent an invalid reply.")
        val bytes = ByteArray(size.toInt()).also(input::readFully)
        java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }

    /** Tells the phone this computer has a file or text it sent, so it drops its copy. */
    fun collected(entry: Entry) = request(15, entry.id) { input, _ -> status(input) }

    /** The phone app's version and name (from 0.13.0; older phones answer "Unsupported operation"). */
    fun info(): PhoneInfo = request(18, "") { input, _ ->
        status(input)
        val version = Wire.readString(input, 64)
        val revision = input.readInt()
        PhoneInfo(version, revision, Wire.readString(input, 128))
    }

    override fun makeFolder(name: String, directoryId: String): Entry {
        Wire.validateName(name)
        return request(4, directoryId, { Wire.writeString(it, name) }) { input, _ ->
            status(input)
            Entry(Wire.readString(input, 128), name, true, 0)
        }
    }

    override fun thumbnail(entry: Entry, size: Int): ByteArray = request(5, entry.id, { it.writeInt(size.coerceIn(32, 1024)) }) { input, _ ->
        status(input)
        val length = input.readInt()
        if (length !in 1..Wire.MAX_THUMBNAIL) throw BridgeException("Invalid preview.")
        ByteArray(length).also(input::readFully)
    }

    override fun delete(entry: Entry) = request(9, entry.id) { input, _ -> status(input) }

    /** Renames in place and returns the item afterwards (its ID may change). */
    override fun rename(entry: Entry, name: String): Entry {
        Wire.validateName(name)
        return request(10, entry.id, { Wire.writeString(it, name) }) { input, _ ->
            status(input)
            entry.copy(id = Wire.readString(input, 128), name = name)
        }
    }

    override fun measure(folder: Entry): FolderSize = request(11, folder.id) { input, _ ->
        status(input)
        val bytes = input.readLong(); val files = input.readLong()
        val complete = when (input.readUnsignedByte()) { 0 -> false; 1 -> true; else -> throw BridgeException("The phone sent an invalid reply.") }
        if (bytes < 0 || files < 0) throw BridgeException("The phone sent an invalid reply.")
        FolderSize(bytes, files, complete)
    }

    /** Files and folders whose names contain `query`, anywhere below `folderId`, searched on the phone. */
    override fun search(folderId: String, query: String): SearchResult {
        val text = query.trim()
        if (text.isEmpty() || text.toByteArray().size > 255) throw BridgeException(S.typeToSearch)
        return request(16, folderId, { Wire.writeString(it, text) }) { input, _ ->
            status(input)
            val count = input.readInt()
            if (count !in 0..1_000) throw BridgeException("The phone sent an invalid reply.")
            val found = List(count) {
                val id = Wire.readString(input, 128)
                val name = Wire.readString(input, 255)
                Wire.validateName(name)
                val directory = when (input.readUnsignedByte()) { 0 -> false; 1 -> true; else -> throw BridgeException("The phone sent an invalid reply.") }
                val size = input.readLong()
                val location = Wire.readString(input, 1024)
                if (id.isEmpty() || size < 0) throw BridgeException("The phone sent an invalid reply.")
                Found(Entry(id, name, directory, size), location)
            }
            SearchResult(found, input.readUnsignedByte() == 1)
        }
    }

    fun clipboard(): String = request(7, "") { input, _ -> status(input); Wire.readString(input, Wire.MAX_CLIPBOARD) }

    fun setClipboard(text: String) {
        if (text.toByteArray().size > Wire.MAX_CLIPBOARD) throw BridgeException("That text is too long for the phone's clipboard.")
        request(8, "", { Wire.writeString(it, text) }) { input, _ -> status(input) }
    }
}

/**
 * A 32-byte summary of a file for resumable uploads: its size, modification time and first MiB. A file changed since
 * an upload was cut off gets a new one, so the phone starts over instead of mixing old and new bytes.
 */
fun contentKey(file: File): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("${file.length()}:${file.lastModified()}:".toByteArray())
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        var left = 1 shl 20
        while (left > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, left))
            if (read < 0) break
            digest.update(buffer, 0, read); left -= read
        }
    }
    return digest.digest()
}

/** An interrupted download, kept beside its destination: `.<name>.<size>.openbridge-part`, as on the Mac. */
object Partial {
    const val CHANGED = "The file on the phone changed since the download started. Download it again."
    fun location(destination: File, size: Long) = File(destination.parentFile, ".${destination.name}.$size.openbridge-part")
}
