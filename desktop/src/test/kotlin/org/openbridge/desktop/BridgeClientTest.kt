package org.openbridge.desktop

import org.openbridge.protocol.Clipboard
import org.openbridge.protocol.FileStore
import org.openbridge.protocol.Names
import org.openbridge.protocol.Outbox
import org.openbridge.protocol.Session
import org.openbridge.protocol.Store
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

/** The desktop client against the real Java server (the phone's Session) over real TLS on loopback. */
class BridgeClientTest {
    private lateinit var base: File
    private lateinit var phone: File
    private lateinit var server: SSLServerSocket
    private lateinit var fingerprint: ByteArray
    private lateinit var tls: SSLContext
    private val secret = ByteArray(32) { 0x5a }
    private var clip = "phone clipboard"
    private val sent = linkedMapOf("1" to ByteArray(70_000) { (it % 13).toByte() })
    private val texts = linkedMapOf<String, ByteArray>()

    @BeforeTest
    fun start() {
        base = Files.createTempDirectory("openbridge-desktop").toFile()
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
        tls = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        val store = FileStore(listOf(FileStore.Root("Internal storage", phone))) { file, size -> "jpeg:${file.name}:$size".toByteArray() }
        val clipboard = object : Clipboard {
            override fun read() = clip
            override fun write(text: String) { clip = text }
        }
        val outbox = object : Outbox {
            override fun waitFor(millis: Long, withTexts: Boolean) = sent.map { (id, data) -> Outbox.Item(id, "IMG_$id.jpg", data.size.toLong(), Outbox.FILE) } +
                (if (withTexts) texts.map { (id, data) -> Outbox.Item(id, "Text", data.size.toLong(), Outbox.TEXT) } else emptyList())
            override fun open(id: String): Store.Read {
                val data = sent[id] ?: texts[id] ?: throw java.io.IOException("Already collected")
                val stream = data.inputStream()
                return object : Store.Read { override fun size() = data.size.toLong(); override fun stream() = stream; override fun close() {} }
            }
            override fun done(id: String) { sent.remove(id); texts.remove(id) }
        }
        val session = Session(store, Trust.single(secret), fingerprint, { true }, clipboard, outbox).describe("0.13.0", "vivo X300 Pro")
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

    private fun client() = BridgeClient("127.0.0.1", server.localPort, fingerprint, secret, Transport.FAST_USB)

    @Test
    fun pairsWithTheSameCodeAndPin() {
        var shown = ""
        val paired = BridgeClient.pairAt("127.0.0.1", server.localPort, transport = Transport.FAST_USB) { shown = it }
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
        client.setClipboard("from the computer ✓")
        assertEquals("from the computer ✓", clip)
    }

    @Test
    fun refusesAnotherCertificateOrSecret() {
        // Either means the saved pairing is no longer any good: the PC forgets the phone and asks to pair again.
        val wrongPin = BridgeClient("127.0.0.1", server.localPort, ByteArray(32), secret, Transport.FAST_USB)
        assertTrue(assertFailsWith<BridgeException> { wrongPin.list() }.forgotten)
        val wrongSecret = BridgeClient("127.0.0.1", server.localPort, fingerprint, ByteArray(32), Transport.FAST_USB)
        assertTrue(assertFailsWith<BridgeException> { wrongSecret.list() }.forgotten)
    }

    @Test
    fun renamesMeasuresAndDeletes() {
        val client = client()
        File(phone, "DCIM/Camera").mkdirs()
        File(phone, "DCIM/Camera/a.jpg").writeBytes(ByteArray(1500))
        File(phone, "DCIM/b.jpg").writeBytes(ByteArray(500))
        val dcim = client.list().first { it.name == "DCIM" }
        assertEquals(FolderSize(2000, 2, true), client.measure(dcim))
        val hits = client.search("", "A.JPG")
        assertEquals(listOf("a.jpg"), hits.found.map { it.entry.name })
        assertEquals("DCIM/Camera", hits.found[0].location)
        assertTrue(hits.complete)

        val notes = client.list().first { it.name == "notes.txt" }
        val renamed = client.rename(notes, "renamed.txt")
        assertEquals("renamed.txt", renamed.name)
        assertTrue(File(phone, "renamed.txt").isFile)
        assertFalse(File(phone, "notes.txt").exists())
        assertFailsWith<BridgeException> { client.rename(renamed, "DCIM") }   // never takes an existing name

        client.delete(client.list().first { it.name == "DCIM" })
        assertFalse(File(phone, "DCIM").exists())
        assertEquals(listOf("renamed.txt"), client.list().map { it.name })
    }

    @Test
    fun receivesFilesSentFromThePhone() {
        val client = client()
        val waiting = client.outbox(0)
        assertEquals(listOf("IMG_1.jpg"), waiting.map { it.name })
        val target = File(base, "IMG_1.jpg")
        Partial.location(target, 70_000).writeBytes(ByteArray(20_000) { (it % 13).toByte() })   // an earlier, cut-off copy
        var first = -1L
        client.receive(waiting[0], target) { done, _ -> if (first < 0) first = done }
        assertTrue(first > 20_000 - 1 && first <= 70_000)
        assertContentEquals(ByteArray(70_000) { (it % 13).toByte() }, target.readBytes())
        assertTrue(sent.isEmpty())   // the phone dropped its copy
        assertEquals(emptyList(), client.outbox(0))
    }

    @Test
    fun interruptedUploadsContinue() {
        val source = File(base, "movie.mp4").apply { writeBytes(ByteArray(300_000) { (it % 7).toByte() }) }
        // What the phone kept from an earlier attempt of this same file.
        File(phone, Names.resumable("movie.mp4", source.length(), contentKey(source))).writeBytes(ByteArray(120_000) { (it % 7).toByte() })
        var first = -1L
        client().upload(source, "") { done, _ -> if (first < 0) first = done }
        assertEquals(120_000L, first)
        assertContentEquals(source.readBytes(), File(phone, "movie.mp4").readBytes())
    }

    @Test
    fun savedPhonesKeepTheirPairing() {
        val saved = SavedPhone("vivo X300 Pro", fingerprint, "127.0.0.1", server.localPort, secret)
        assertEquals(fingerprint.take(8).joinToString("") { "%02x".format(it) }, saved.id)
        assertEquals(listOf("DCIM", "notes.txt"), saved.overUsb(server.localPort).list().map { it.name })
        // Over Wi‑Fi the secret only goes to private addresses, never to this computer itself.
        assertFailsWith<BridgeException> { saved.client().list() }
        assertTrue(BridgeClient.reachable("127.0.0.1", server.localPort))
    }

    @Test
    fun aPhoneThatStoppedSharingIsReportedAsLost() {
        val client = client()
        assertFalse(assertFailsWith<BridgeException> { client.list("no-such-id") }.lost)   // the phone answered: not lost
        // Nothing listens on that port any more, as when the phone stopped sharing.
        val stopped = java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        assertTrue(assertFailsWith<BridgeException> { BridgeClient("127.0.0.1", stopped, fingerprint, secret, Transport.FAST_USB).list() }.lost)
    }

    @Test
    fun receivesTextsAndDescribesThePhone() {
        val client = client()
        texts["t1"] = "Hello from the phone ✓".toByteArray()
        val waiting = client.outboxAll(0)
        assertEquals(listOf(false, true), waiting.map { it.text })
        assertEquals(listOf("IMG_1.jpg"), client.outbox(0).map { it.name })   // the old operation never lists texts
        val text = waiting.first { it.text }
        assertEquals("Hello from the phone ✓", client.readText(text.entry))
        client.collected(text.entry)
        assertTrue(texts.isEmpty())
        assertEquals(PhoneInfo("0.13.0", 18, "vivo X300 Pro"), client.info())
    }

    @Test
    fun aPingCountsAnyAnswer() {
        client().ping()
        // Nothing listens there any more: lost.
        val stopped = java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        assertTrue(assertFailsWith<BridgeException> { BridgeClient("127.0.0.1", stopped, fingerprint, secret, Transport.FAST_USB).ping(1_000) }.lost)
        // The phone no longer knows this computer: forgotten, not just gone.
        assertTrue(assertFailsWith<BridgeException> { BridgeClient("127.0.0.1", server.localPort, fingerprint, ByteArray(32), Transport.FAST_USB).ping() }.forgotten)
    }

    @Test
    fun closingCutsARequestThatHangs() {
        // A phone that finished the handshake and then went quiet, as when it left the Wi‑Fi mid-request.
        val quiet = tls.serverSocketFactory.createServerSocket(0, 4, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        val held = java.util.concurrent.CopyOnWriteArrayList<java.net.Socket>()
        thread(isDaemon = true) {
            while (!quiet.isClosed) runCatching { (quiet.accept() as SSLSocket).also { held += it }.startHandshake() }
        }
        try {
            val client = BridgeClient("127.0.0.1", quiet.localPort, fingerprint, secret, Transport.FAST_USB)
            thread(isDaemon = true) { Thread.sleep(400); client.close() }
            val started = System.nanoTime()
            assertTrue(assertFailsWith<BridgeException> { client.list() }.lost)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000, "closing ends the request at once")
            // Done with: nothing more goes out.
            assertTrue(assertFailsWith<BridgeException> { client.list() }.lost)
            assertTrue(assertFailsWith<BridgeException> { client.ping() }.lost)
        } finally { quiet.close(); held.forEach { runCatching { it.close() } } }
    }

    @Test
    fun readsTheCertificateWithoutSendingAnything() {
        assertContentEquals(fingerprint, BridgeClient.fingerprintAt("127.0.0.1", server.localPort))
        val nothing = java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        assertEquals(null, BridgeClient.fingerprintAt("127.0.0.1", nothing, 1_000))
    }
}
