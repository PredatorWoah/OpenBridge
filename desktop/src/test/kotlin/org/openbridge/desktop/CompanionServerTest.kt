package org.openbridge.desktop

import org.openbridge.protocol.FileStore
import org.openbridge.protocol.MemoryCompanion
import org.openbridge.protocol.Session
import org.openbridge.protocol.Trust
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Operations 19 to 23 against the phone's own server (Session) with an in-memory companion, over real TLS. */
class CompanionServerTest {
    private lateinit var base: File
    private lateinit var phone: File
    private lateinit var server: SSLServerSocket
    private lateinit var fingerprint: ByteArray
    private val secret = ByteArray(32) { 0x21 }
    private val companion = MemoryCompanion().apply { maxWaitMillis = 300 }

    @BeforeTest
    fun start() {
        base = Files.createTempDirectory("openbridge-companion").toFile()
        phone = File(base, "phone").apply { mkdirs() }
        val keystore = File(base, "fixture.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val made = ProcessBuilder(keytool, "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12",
            "-keystore", keystore.path, "-storepass", "test-only", "-keypass", "test-only", "-dname", "CN=OpenBridge-Test", "-validity", "1")
            .redirectErrorStream(true).start().waitFor()
        assertEquals(0, made, "keytool")
        val keys = KeyStore.getInstance("PKCS12").apply { keystore.inputStream().use { load(it, "test-only".toCharArray()) } }
        fingerprint = MessageDigest.getInstance("SHA-256").digest(keys.getCertificate("fixture").encoded)
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "test-only".toCharArray()) }
        val tls = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        val store = FileStore(listOf(FileStore.Root("Internal storage", phone))) { _, _ -> ByteArray(0) }.inbox("Download", "OpenBridge")
        val session = Session(store, Trust.single(secret), fingerprint, { true }, null, null, companion).describe("1.2.0", "Pixel 8")
        server = tls.serverSocketFactory.createServerSocket(0, 8, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() as SSLSocket }.getOrNull() ?: continue
                thread(isDaemon = true) { socket.use { runCatching { session.serve(it.inputStream, it.outputStream) } } }
            }
        }
    }

    @AfterTest
    fun stop() { server.close(); base.deleteRecursively() }

    private fun client() = BridgeClient("127.0.0.1", server.localPort, fingerprint, secret, Transport.FAST_USB)

    @Test
    fun revisionSaysTheOperationsAreThere() {
        assertTrue(client().info().revision >= PhoneEvents.REVISION)
    }

    @Test
    fun followsNotificationsAndBattery() {
        val client = client()
        companion.log.battery(76, true)
        companion.post("k1", "Messages", "Anna", "See you at 7", true, "")
        val first = client.events(0, 0)
        assertEquals(PhoneEvent.Reset, first.events.first())
        val list = NotificationList().apply(first.events)
        assertEquals(Battery(76, true), list.list.battery)
        assertEquals(listOf("k1"), list.list.items.map { it.key })
        assertTrue(list.fresh.isEmpty())
        assertTrue(list.list.items.single().canReply)

        companion.post("k2", "Bank", "Code", "Use 482913", false, "482913")
        val next = client.events(first.cursor, 5_000)
        val applied = list.list.apply(next.events)
        assertEquals(listOf("k2"), applied.fresh.map { it.key })
        assertEquals("482913", applied.list.items.first { it.key == "k2" }.code)

        // Nothing new: it waits, then answers with no events.
        assertTrue(client.events(next.cursor, 100).events.isEmpty())
        // A cursor the phone doesn't know starts over.
        assertEquals(PhoneEvent.Reset, client.events(next.cursor + 12_345, 0).events.first())
    }

    @Test
    fun answersDismissesRingsAndOpensLinks() {
        val client = client()
        val key = "0|com.example.messages|42|" + "x".repeat(200)   // keys are longer than ordinary IDs
        companion.post(key, "Messages", "Anna", "Hi", true, "")
        client.notificationAction(key, "On my way ✓")
        assertEquals(listOf(key, "On my way ✓"), companion.replies.single().toList())
        client.notificationAction(key)
        assertEquals(listOf(key), companion.dismissed)
        assertFailsWith<BridgeException> { client.notificationAction(key) }.also { assertFalse(it.lost) }
        client.ring(true); client.ring(false)
        assertEquals(listOf(true, false), companion.rings)
        client.openUrl("https://example.com/a?b=c")
        assertEquals("https://example.com/a?b=c", companion.links.single()[0])
        companion.notificationsOn = false
        companion.post("k3", "Messages", "Bo", "Yo", true, "")
        assertFailsWith<BridgeException> { client.notificationAction("k1", "x") }
    }

    @Test
    fun sendsIntoTheInboxUnderAFreeName() {
        val client = client()
        val file = File(base, "holiday.jpg").apply { writeBytes(ByteArray(300_000) { (it % 241).toByte() }) }
        assertEquals("holiday.jpg", client.sendFile(file))
        val again = client.sendFile(file)
        assertTrue(again != "holiday.jpg" && again.endsWith(".jpg"), again)
        val inbox = File(phone, "Download/OpenBridge")
        assertContentEquals(file.readBytes(), File(inbox, "holiday.jpg").readBytes())
        assertContentEquals(file.readBytes(), File(inbox, again).readBytes())
        assertEquals(listOf("holiday.jpg", again), companion.received.map { it[0] })
    }
}
