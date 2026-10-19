package org.openbridge.desktop

import org.openbridge.protocol.FileStore
import org.openbridge.protocol.Session
import org.openbridge.protocol.Trust
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A phone's loopback listener: the real Java server with its own certificate, on a port of this computer. */
private class TestPhone(base: File, name: String, val secret: ByteArray = ByteArray(32) { 7 }) : Closeable {
    val fingerprint: ByteArray
    private val server: SSLServerSocket
    val port: Int get() = server.localPort

    init {
        val folder = File(base, name).apply { mkdirs() }
        File(folder, "$name.txt").writeText("hello from $name")
        val keystore = File(base, "$name.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        ProcessBuilder(keytool, "-genkeypair", "-alias", "fixture", "-keyalg", "EC", "-groupname", "secp256r1", "-storetype", "PKCS12",
            "-keystore", keystore.path, "-storepass", "test-only", "-keypass", "test-only", "-dname", "CN=$name", "-validity", "1")
            .redirectErrorStream(true).start().waitFor()
        val keys = KeyStore.getInstance("PKCS12").apply { keystore.inputStream().use { load(it, "test-only".toCharArray()) } }
        fingerprint = MessageDigest.getInstance("SHA-256").digest(keys.getCertificate("fixture").encoded)
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "test-only".toCharArray()) }
        val tls = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        val session = Session(FileStore(listOf(FileStore.Root("Internal storage", folder))) { _, _ -> null }, Trust.single(secret), fingerprint, { true }, null)
        server = tls.serverSocketFactory.createServerSocket(0, 8, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        thread(isDaemon = true) {
            while (!server.isClosed) {
                try { (server.accept() as SSLSocket).use { session.serve(it.inputStream, it.outputStream) } } catch (_: Exception) { }
            }
        }
    }

    override fun close() = server.close()
}

/**
 * adb as Fast USB sees it. Like `adb forward`, each forward is a port on this computer that, for every connection,
 * reaches the phone's port at that moment: a test server when [listening] says so, else the connection closes.
 */
private class FakeAdb : Adb, Closeable {
    val devices = mutableListOf<AdbDevice>()
    /** serial → phone port → the test server's port. */
    val listening = HashMap<String, MutableMap<Int, Int>>()
    val forwards = HashMap<String, MutableSet<Int>>()
    val started = mutableListOf<String>()
    var installed = true
    private val relays = mutableListOf<ServerSocket>()

    override fun devices() = devices.toList()
    override fun forward(serial: String, remote: Int): Int? {
        if (devices.none { it.serial == serial && it.state == "device" }) return null
        val relay = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).also { relays += it }
        thread(isDaemon = true) {
            while (!relay.isClosed) {
                val socket = try { relay.accept() } catch (_: Exception) { break }
                val target = listening[serial]?.get(remote)
                if (target == null) { socket.close(); continue }
                val phone = Socket("127.0.0.1", target)
                thread(isDaemon = true) { runCatching { socket.getInputStream().copyTo(phone.getOutputStream()) }; runCatching { phone.shutdownOutput() } }
                thread(isDaemon = true) { runCatching { phone.getInputStream().copyTo(socket.getOutputStream()) }; runCatching { socket.close(); phone.close() } }
            }
        }
        forwards.getOrPut(serial) { mutableSetOf() } += relay.localPort
        return relay.localPort
    }
    override fun removeForward(serial: String, local: Int) { forwards[serial]?.remove(local) }
    override fun startSharing(serial: String): Boolean { started += serial; return installed }
    override fun close() = relays.forEach { it.close() }
}

class FastUsbTest {
    private val base = Files.createTempDirectory("openbridge-fastusb").toFile()
    private val phones = mutableListOf<TestPhone>()
    private val adb = FakeAdb()

    @AfterTest fun stop() { adb.close(); phones.forEach { it.close() }; base.deleteRecursively() }

    private fun phone(name: String) = TestPhone(base, name).also { phones += it }
    private fun saved(phone: TestPhone, name: String) = SavedPhone(name, phone.fingerprint, "192.168.1.23", 41987, phone.secret)

    @Test
    fun parsesAdbDevices() {
        val output = """
            * daemon not running; starting now at tcp:5037
            * daemon started successfully
            List of devices attached
            10ABC123               device usb:1-1 product:PD2405 model:V2405A device:PD2405 transport_id:1
            R58M00XYZ              unauthorized usb:1-2 transport_id:2
            0123456789AB           no permissions (missing udev rules? user is in the plugdev group); see [http://developer.android.com/tools/device.html] usb:1-3 transport_id:3
            192.168.1.23:5555      device product:x model:Pixel_8 device:y transport_id:4

        """.trimIndent()
        assertEquals(listOf(AdbDevice("10ABC123", "device", "V2405A"), AdbDevice("R58M00XYZ", "unauthorized"), AdbDevice("0123456789AB", "no permissions")),
            AdbTool.parseDevices(output))
    }

    @Test
    fun findsASavedPhoneOnWhicheverPortItAnswers() {
        val vivo = phone("vivo")
        val other = phone("other")   // another app's TLS listener on the phone, on the first port
        adb.devices += AdbDevice("10ABC123", "device", "V2405A")
        adb.listening["10ABC123"] = mutableMapOf(41987 to other.port, 41989 to vivo.port)
        val known = listOf(saved(vivo, "vivo X300 Pro"))
        val usb = FastUsb(adb)

        val link = usb.scan(known).single()
        assertEquals(UsbLink.State.READY, link.state)
        assertTrue(link.port in adb.forwards["10ABC123"]!!)
        assertEquals("vivo X300 Pro", link.name)
        assertEquals(known[0], link.phone)
        assertTrue(adb.started.isEmpty())   // it answered: nothing to start

        // The tunnel works with the saved secret, and only over Fast USB to this computer.
        val client = link.phone!!.overUsb(link.port)
        assertEquals(Transport.FAST_USB, client.transport)
        assertEquals(listOf("vivo.txt"), client.list().map { it.name })

        // Unplugged: its forwards are removed.
        assertEquals(4, adb.forwards["10ABC123"]!!.size)
        adb.devices.clear()
        assertEquals(emptyList(), usb.scan(known))
        assertTrue(adb.forwards["10ABC123"]!!.isEmpty())
    }

    @Test
    fun anUnknownPhonePairsThroughTheTunnel() {
        val vivo = phone("vivo")
        adb.devices += AdbDevice("10ABC123", "device", "V2405A")
        adb.listening["10ABC123"] = mutableMapOf(41987 to vivo.port)
        val link = FastUsb(adb).scan(emptyList()).single()
        assertEquals(UsbLink.State.READY, link.state)
        assertNull(link.phone)
        assertContentEquals(vivo.fingerprint, link.fingerprint)

        var code = ""
        val paired = BridgeClient.pairAt("127.0.0.1", link.port, transport = Transport.FAST_USB) { code = it }
        assertTrue(code.matches(Regex("[0-9]{6}")))
        assertContentEquals(vivo.fingerprint, paired.fingerprint)
        // Saved without a Wi‑Fi address; next time the cable finds it by its certificate.
        val phone = SavedPhone("vivo X300 Pro", paired.fingerprint, "", 41987, paired.secret)
        assertEquals(phone, FastUsb(adb).scan(listOf(phone)).single().phone)
    }

    @Test
    fun startsSharingOnceWhenNothingAnswers() {
        val vivo = phone("vivo")
        var now = 0L
        adb.devices += AdbDevice("10ABC123", "device", "V2405A")
        val usb = FastUsb(adb, clock = { now })
        assertEquals(UsbLink.State.STARTING, usb.scan(emptyList()).single().state)
        assertEquals(listOf("10ABC123"), adb.started)
        assertTrue(usb.starting)
        now += 3_000
        assertEquals(UsbLink.State.STARTING, usb.scan(emptyList()).single().state)
        // The phone app comes up and listens on its second port: the same forwards reach it.
        adb.listening["10ABC123"] = mutableMapOf(41988 to vivo.port)
        val link = usb.scan(emptyList()).single()
        assertEquals(UsbLink.State.READY, link.state)
        assertContentEquals(vivo.fingerprint, link.fingerprint)

        // Another phone where nothing comes up: after the wait it's not sharing, and isn't started again.
        adb.devices += AdbDevice("R58M00XYZ", "device", "SM-S918B")
        assertEquals(UsbLink.State.STARTING, usb.scan(emptyList())[1].state)
        now += FastUsb.START_WAIT + 1
        assertEquals(UsbLink.State.NOT_SHARING, usb.scan(emptyList())[1].state)
        assertEquals(listOf("10ABC123", "R58M00XYZ"), adb.started)
    }

    @Test
    fun explainsWhatIsMissing() {
        adb.installed = false
        adb.devices += listOf(AdbDevice("A", "device"), AdbDevice("B", "unauthorized"), AdbDevice("C", "no permissions"), AdbDevice("D", "offline"))
        val links = FastUsb(adb).scan(emptyList())
        assertEquals(listOf(UsbLink.State.NO_APP, UsbLink.State.UNAUTHORIZED, UsbLink.State.NO_PERMISSION), links.map { it.state })
    }

    @Test
    fun leavesTheTunnelInUseAlone() {
        val vivo = phone("vivo")
        adb.devices += AdbDevice("10ABC123", "device", "V2405A")
        adb.listening["10ABC123"] = mutableMapOf(41987 to vivo.port)
        var probes = 0
        val usb = FastUsb(adb, probe = { probes++; BridgeClient.fingerprintAt("127.0.0.1", it, 1_000) })
        val known = listOf(saved(vivo, "vivo"))
        usb.scan(known)
        val before = probes
        assertTrue(usb.scan(known, inUse = "10ABC123").single().ready)
        assertEquals(before, probes)
        usb.close()
        assertTrue(adb.forwards["10ABC123"]!!.isEmpty())
    }
}
