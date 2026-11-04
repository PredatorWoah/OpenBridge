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
 * A phone speaking OB01 operations 18 to 23 exactly as docs/protocol.md describes them, over real TLS on loopback,
 * for testing the client before the phone's own server has them. Anything else is answered "Unsupported operation".
 */
class FakePhone(private val revision: Int = 23) : AutoCloseable {
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
        val key = ByteArray(32).also(input::readFully)
        if (!magic.contentEquals(Wire.MAGIC) || !key.contentEquals(secret)) return fail(output, "Pairing rejected")
        val operation = input.readUnsignedByte()
        val id = Wire.readString(input, 256)
        if (operation in 19..23 && revision < 23) return fail(output, "Unsupported operation; update OpenBridge on the phone")
        when (operation) {
            18 -> { output.writeByte(0); Wire.writeString(output, "1.2.0"); output.writeInt(revision); Wire.writeString(output, "Fake phone") }
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
        val reset = 0 to ByteArray(0)
    }
}
