package org.openbridge.windows

import org.openbridge.protocol.Clipboard
import org.openbridge.protocol.FileStore
import org.openbridge.protocol.Session
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

/** The Windows client against the real Java server (the phone's Session) over real TLS on loopback. */
class BridgeClientTest {
    private lateinit var base: File
    private lateinit var phone: File
    private lateinit var server: SSLServerSocket
    private lateinit var fingerprint: ByteArray
    private val secret = ByteArray(32) { 0x5a }
    private var clip = "phone clipboard"

    @BeforeTest
    fun start() {
        base = Files.createTempDirectory("openbridge-windows").toFile()
        phone = File(base, "phone").apply { mkdirs() }
        File(phone, "DCIM").mkdirs()
        File(phone, "notes.txt").writeText("hello from the phone")
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
        val store = FileStore(listOf(FileStore.Root("Internal storage", phone))) { file, size -> "jpeg:${file.name}:$size".toByteArray() }
        val clipboard = object : Clipboard {
            override fun read() = clip
            override fun write(text: String) { clip = text }
        }
        val session = Session(store, secret, fingerprint, { true }, clipboard)
        server = tls.serverSocketFactory.createServerSocket(0, 8, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        thread(isDaemon = true) {
            while (!server.isClosed) {
                try {
                    (server.accept() as SSLSocket).use { session.serve(it.inputStream, it.outputStream) }
                } catch (_: Exception) { }
            }
        }
    }

    @AfterTest
    fun stop() { server.close(); base.deleteRecursively() }

    private fun client() = BridgeClient("127.0.0.1", server.localPort, fingerprint, secret)

    @Test
    fun pairsWithTheSameCodeAndPin() {
        var shown = ""
        val paired = BridgeClient.pairAt("127.0.0.1", server.localPort) { shown = it }
        assertTrue(shown.matches(Regex("[0-9]{6}")), "6-digit code")
        assertEquals(listOf("DCIM", "notes.txt"), paired.list().map { it.name })   // pinned and holding the secret
        assertFailsWith<BridgeException> { BridgeClient.pair("127.0.0.1", server.localPort.toString()) { } }   // public path: LAN only
        assertFailsWith<BridgeException> { BridgeClient.checkAddress("8.8.8.8", "80") }
        assertFailsWith<BridgeException> { BridgeClient.checkAddress("192.168.1.023", "1") }
        assertEquals(41987, BridgeClient.checkAddress("192.168.1.23", "41987"))
    }

    @Test
    fun browsesTransfersAndResumes() {
        val client = client()
        val top = client.list()
        assertEquals(listOf("DCIM", "notes.txt"), top.map { it.name })
        val notes = top.first { it.name == "notes.txt" }

        val downloads = File(base, "downloads").apply { mkdirs() }
        val target = File(downloads, "notes.txt")
        client.download(notes, target) { _, _ -> }
        assertEquals("hello from the phone", target.readText())
        assertFailsWith<BridgeException> { client.download(notes, target) { _, _ -> } }   // never replaces

        // An interrupted download continues from its partial file.
        val resumed = File(downloads, "again.txt")
        Partial.location(resumed, notes.size).writeText("hello ")
        var first = -1L
        client.download(notes, resumed) { done, _ -> if (first < 0) first = done }
        assertEquals(6L, first)
        assertEquals("hello from the phone", resumed.readText())
        assertFalse(Partial.location(resumed, notes.size).exists())

        val source = File(base, "upload.bin").apply { writeBytes(ByteArray(200_003) { (it % 251).toByte() }) }
        client.upload(source, "") { _, _ -> }
        assertContentEquals(source.readBytes(), File(phone, "upload.bin").readBytes())
        assertFailsWith<BridgeException> { client.upload(source, "") { _, _ -> } }    // collision

        val folder = client.makeFolder("Trips", "")
        assertTrue(File(phone, "Trips").isDirectory)
        assertEquals(emptyList(), client.list(folder.id))
        assertEquals("jpeg:notes.txt:256", String(client.thumbnail(notes)))

        assertEquals("phone clipboard", client.clipboard())
        client.setClipboard("from Windows ✓")
        assertEquals("from Windows ✓", clip)
    }

    @Test
    fun refusesAnotherCertificateOrSecret() {
        val wrongPin = BridgeClient("127.0.0.1", server.localPort, ByteArray(32), secret)
        assertFailsWith<BridgeException> { wrongPin.list() }
        val wrongSecret = BridgeClient("127.0.0.1", server.localPort, fingerprint, ByteArray(32))
        assertFailsWith<BridgeException> { wrongSecret.list() }
    }
}
