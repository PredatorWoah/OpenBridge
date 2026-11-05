package org.openbridge.desktop

import org.openbridge.protocol.Wire
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * A phone speaking OB01 operations 2, 6 and 18 to 24, and pairing by QR code (OBP3), exactly as docs/protocol.md
 * describes them, over real TLS on loopback, for testing the client before the phone's own server has them. Anything
 * else is answered "Unsupported operation". A `revision` below 24 behaves like a phone before 1.3.0.
 */
class FakePhone(private val revision: Int = 24) : AutoCloseable {
    val base: File = Files.createTempDirectory("openbridge-fake").toFile()
    val secret = ByteArray(32) { 0x33 }
    val fingerprint: ByteArray
    private val server: SSLServerSocket

    /** Raw EVENTS replies to give, in order (after the status byte); the last one repeats. */
    val eventReplies = CopyOnWriteArrayList<ByteArray>()
    /** The (cursor, wait) of each EVENTS request. */
    val eventRequests = CopyOnWriteArrayList<Pair<Long, Int>>()
    /** What NOTIFICATION ACTION, RING and OPEN URL received, as readable lines. */
    val received = CopyOnWriteArrayList<String>()
    /** Files SEND received, by the name they were saved under. */
    val sent = File(base, "Download/OpenBridge").apply { mkdirs() }
    /** Bytes SEND says it already holds from an interrupted send, by name. */
    val partial = HashMap<String, ByteArray>()

    /** The key of a QR code the phone scanned (OBP3), or null; forgotten once used, or after 3 failed attempts. */
    @Volatile var qrKey: ByteArray? = null
    /** Answer OBP3 with a proof made with another key, as a phone that didn't scan this code (or a relay) would. */
    @Volatile var qrWrongProof = false
    /** The computers' names OBP3 brought, and the proofs (Pc) that arrived, checked or not. */
    val qrNames = CopyOnWriteArrayList<String>()
    val qrProofs = CopyOnWriteArrayList<ByteArray>()
    /** The phone's nonce for OBP3; random unless set. */
    @Volatile var qrPhoneNonce: ByteArray? = null
    private var qrFailures = 0

    /** Files GET and RESUME serve, by ID. */
    val files = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    /** The offset of each GET (0) or RESUME request, in order. */
    val reads = CopyOnWriteArrayList<Long>()
    /** Reads cut off because the computer closed the connection before the end. */
    val readsCut = java.util.concurrent.atomic.AtomicInteger()
    /** MEDIA CONTROL answers "Nothing is playing." */
    @Volatile var nothingPlaying = false

    init {
        val keystore = File(base, "fake.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val made = ProcessBuilder(keytool, "-genkeypair", "-alias", "fake", "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12",
            "-keystore", keystore.path, "-storepass", "test-only", "-keypass", "test-only", "-dname", "CN=OpenBridge-Fake", "-validity", "1")
            .redirectErrorStream(true).start().waitFor()
        check(made == 0) { "keytool" }
        val keys = KeyStore.getInstance("PKCS12").apply { keystore.inputStream().use { load(it, "test-only".toCharArray()) } }
        fingerprint = MessageDigest.getInstance("SHA-256").digest(keys.getCertificate("fake").encoded)
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "test-only".toCharArray()) }
        val tls = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        server = tls.serverSocketFactory.createServerSocket(0, 8, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() as SSLSocket }.getOrNull() ?: continue
                thread(isDaemon = true) { socket.use { runCatching { serve(DataInputStream(it.inputStream.buffered()), DataOutputStream(it.outputStream.buffered())) } } }
            }
        }
    }

    val port get() = server.localPort

    fun client() = BridgeClient("127.0.0.1", port, fingerprint, secret, Transport.FAST_USB)

    override fun close() { server.close(); base.deleteRecursively() }

    private fun fail(output: DataOutputStream, message: String) { output.writeByte(1); Wire.writeString(output, message); output.flush() }

    private fun serve(input: DataInputStream, output: DataOutputStream) {
        val magic = ByteArray(4).also(input::readFully)
        if (magic.contentEquals(QrPairing.MAGIC) && revision >= 24) return pairByQr(input, output)
        val key = ByteArray(32).also(input::readFully)
        if (!magic.contentEquals(Wire.MAGIC) || !key.contentEquals(secret)) return fail(output, "Pairing rejected")
        val operation = input.readUnsignedByte()
        val id = Wire.readString(input, 256)
        if (operation in 19..23 && revision < 23) return fail(output, "Unsupported operation; update OpenBridge on the phone")
        if (operation == 24 && revision < 24) return fail(output, "Unsupported operation; update OpenBridge on the phone")
        when (operation) {
            2, 6 -> {
                val bytes = files[id] ?: return fail(output, "No such file")
                val offset = if (operation == 6) input.readLong() else 0L
                if (offset !in 0..bytes.size.toLong()) return fail(output, "Invalid offset")
                reads += offset
                output.writeByte(0); output.writeLong(bytes.size.toLong())
                try {
                    var at = offset.toInt()
                    while (at < bytes.size) { val n = minOf(16_384, bytes.size - at); output.write(bytes, at, n); at += n }
                    output.flush()
                } catch (e: java.io.IOException) { readsCut.incrementAndGet(); throw e }
            }
            24 -> {
                val action = input.readUnsignedByte()
                if (action !in 1..6) return fail(output, "Invalid action")
                received += if (action == 6) "media 6 ${input.readLong()}" else "media $action"
                if (nothingPlaying) return fail(output, "Nothing is playing.")
                output.writeByte(0)
            }
            18 -> { output.writeByte(0); Wire.writeString(output, if (revision >= 24) "1.3.0" else "1.2.0"); output.writeInt(revision); Wire.writeString(output, "Fake phone") }
            19 -> {
                val cursor = input.readLong(); val wait = input.readInt()
                eventRequests += cursor to wait
                if (id.isNotEmpty() || wait !in 0..25_000) return fail(output, "Invalid request")
                val reply = eventReplies.getOrNull(eventRequests.size - 1) ?: eventReplies.lastOrNull() ?: events(cursor)
                if (eventRequests.size > eventReplies.size && eventReplies.isNotEmpty()) Thread.sleep(minOf(wait.toLong(), 200))
                output.writeByte(0); output.write(reply)
            }
            20 -> {
                val action = input.readUnsignedByte()
                when (action) {
                    1 -> received += "dismiss $id"
                    2 -> received += "reply $id ${Wire.readString(input, 4_096)}"
                    else -> return fail(output, "Invalid action")
                }
                if (id == "gone") return fail(output, "That notification is gone.")
                output.writeByte(0)
            }
            21 -> { received += "ring ${input.readUnsignedByte()}"; output.writeByte(0) }
            22 -> {
                val url = Wire.readString(input, 2_048)
                if (!url.startsWith("http://") && !url.startsWith("https://")) return fail(output, "Only web links")
                received += "open $url"; output.writeByte(0)
            }
            23 -> {
                val name = Wire.readString(input, 255)
                val size = input.readLong()
                val contentKey = ByteArray(32).also(input::readFully)
                Wire.validateName(name)
                val have = partial[name] ?: ByteArray(0)
                output.writeByte(0); output.writeLong(have.size.toLong()); output.flush()
                val rest = ByteArray((size - have.size).toInt()).also(input::readFully)
                var saved = name
                var n = 2
                while (File(sent, saved).exists()) { saved = name.substringBeforeLast('.') + " ($n)." + name.substringAfterLast('.'); n++ }
                File(sent, saved).writeBytes(have + rest)
                received += "send $name ${contentKey.size}"
                output.writeByte(0); Wire.writeString(output, saved)
            }
            else -> return fail(output, "Unsupported operation; update OpenBridge on the phone")
        }
        output.flush()
    }

    /** OBP3, after the magic: name, the computer's nonce; then the phone's nonce and proof, the computer's proof, the secret. */
    private fun pairByQr(input: DataInputStream, output: DataOutputStream) {
        val block = ByteArray(128).also(input::readFully)
        qrNames += String(block, Charsets.UTF_8).trimEnd('\u0000')
        val computerNonce = ByteArray(32).also(input::readFully)
        val key = qrKey ?: return fail(output, "Scan the code on the computer first")
        val phoneNonce = qrPhoneNonce ?: ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val proofKey = if (qrWrongProof) ByteArray(16) { 9 } else key
        output.writeByte(0); output.write(phoneNonce); output.write(QrPairing.phoneProof(proofKey, fingerprint, computerNonce, phoneNonce)); output.flush()
        val proof = ByteArray(32)
        try { input.readFully(proof) } catch (e: java.io.IOException) { return }   // the computer hung up: it didn't trust the proof
        qrProofs += proof
        if (!MessageDigest.isEqual(proof, QrPairing.computerProof(key, fingerprint, computerNonce, phoneNonce))) {
            if (++qrFailures >= 3) { qrKey = null; qrFailures = 0 }
            return fail(output, "That code didn't match. Scan it again.")
        }
        qrKey = null; qrFailures = 0
        output.writeByte(0); output.write(secret); output.flush()
    }

    /** A default EVENTS reply: nothing new, the same cursor back. */
    private fun events(cursor: Long): ByteArray = reply(cursor)

    companion object {
        /** An EVENTS reply body after the status: cursor, count, then (kind, body) pairs. */
        fun reply(cursor: Long, vararg events: Pair<Int, ByteArray>): ByteArray = bytes {
            writeLong(cursor); writeInt(events.size)
            for ((kind, body) in events) { writeByte(kind); writeInt(body.size); write(body) }
        }

        fun bytes(write: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { DataOutputStream(it).apply { write(); flush() } }.toByteArray()

        fun notification(key: String, title: String, text: String, time: Long = 1_000, flags: Int = 3, code: String = "",
                         app: String = "Messages", pkg: String = "com.example.messages") = 1 to bytes {
            Wire.writeString(this, key); Wire.writeString(this, app); Wire.writeString(this, pkg); Wire.writeString(this, title)
            Wire.writeString(this, text); writeLong(time); writeByte(flags); Wire.writeString(this, code)
        }

        fun removed(key: String) = 2 to bytes { Wire.writeString(this, key) }
        fun battery(level: Int, charging: Boolean) = 3 to bytes { writeByte(level); writeByte(if (charging) 1 else 0) }
        fun media(app: String, title: String, artist: String, duration: Long, position: Long, state: Int, actions: Int) = 4 to bytes {
            Wire.writeString(this, app); Wire.writeString(this, title); Wire.writeString(this, artist)
            writeLong(duration); writeLong(position); writeByte(state); writeByte(actions)
        }
        val reset = 0 to ByteArray(0)
    }
}
