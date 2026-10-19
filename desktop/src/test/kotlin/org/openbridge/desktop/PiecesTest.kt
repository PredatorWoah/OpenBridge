package org.openbridge.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The small decisions behind texts, updates and networks, without a phone. */
class PiecesTest {
    @Test
    fun clipboardWatchSendsOnlyNewTextFromThisComputer() {
        val watch = ClipboardWatch(limit = 10)
        watch.reset("already there")
        assertNull(watch.changed("already there"))
        assertEquals("hello", watch.changed("hello"))
        assertNull(watch.changed("hello"))                // unchanged
        watch.arrived("from phone")
        assertNull(watch.changed("from phone"))           // never echoed back
        assertNull(watch.changed("far too long for it"))  // over the limit
        assertNull(watch.changed("   "))
        assertEquals("again", watch.changed("again"))
    }

    @Test
    fun textHistoryKeepsTheLatestPrivately() {
        val file = Files.createTempDirectory("openbridge-texts").resolve("texts.dat").toFile()
        val items = (1..40).map { TextItem("text $it\twith a tab\nand a line ✓", it % 2 == 0, it.toLong()) }
        TextHistory.save(items, file)
        val loaded = TextHistory.load(file)
        assertEquals(items.take(TextHistory.KEEP), loaded)
        if (!Platform.windows) assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
        file.parentFile.deleteRecursively()
    }

    @Test
    fun updatesNeedTheirPublishedChecksum() {
        val sums = """
            4e41b5c80d00e40b264abfe65c986a8b6d1f4fdff7e6d17eeb66105ae4bb1685  OpenBridge-0.13.0.apk
            9F969D30CC574816077EDECDA65719C1C70B0AEE1DF1BA5FA00779AC07B8FD6E  OpenBridge-0.13.0-Linux-amd64.deb
            b12a2c4ee8be317422451fc7dcf8ee20a71b5ea7ef9ad73ddd825a25316227a5 *OpenBridge-0.13.0-Windows-Setup.exe
        """.trimIndent()
        assertEquals("9f969d30cc574816077edecda65719c1c70b0aee1df1ba5fa00779ac07b8fd6e", Updater.checksum(sums, "OpenBridge-0.13.0-Linux-amd64.deb"))
        assertEquals("b12a2c4ee8be317422451fc7dcf8ee20a71b5ea7ef9ad73ddd825a25316227a5", Updater.checksum(sums, Updater.asset("0.13.0", Updater.Package.WINDOWS_SETUP)))
        assertNull(Updater.checksum(sums, "OpenBridge-0.13.0-Linux-x86_64.tar.gz"))
    }

    @Test
    fun findsTheDefaultGateway() {
        val linux = """
            Iface	Destination	Gateway 	Flags	RefCnt	Use	Metric	Mask		MTU	Window	IRTT
            wlan0	00000000	2B2BA8C0	0003	0	0	600	00000000	0	0	0
            eth0	00000000	0101A8C0	0003	0	0	100	00000000	0	0	0
            wlan0	002BA8C0	00000000	0001	0	0	600	00FFFFFF	0	0	0
        """.trimIndent()
        assertEquals("192.168.1.1", Network.linuxGateway(linux))
        val windows = """
            IPv4 Route Table
            ===========================================================================
            Active Routes:
            Network Destination        Netmask          Gateway       Interface  Metric
                      0.0.0.0          0.0.0.0     192.168.43.1   192.168.43.120     55
                      0.0.0.0          0.0.0.0        10.0.0.1        10.0.0.23     35
            ===========================================================================
        """.trimIndent()
        assertEquals("10.0.0.1", Network.windowsGateway(windows))
        assertFalse(privateIPv4("8.8.8.8"))
        assertTrue(privateIPv4("192.168.43.1"))
    }

    @Test
    fun noticesAWiFiThatKeepsDevicesApart() {
        val isolation = Isolation()
        assertFalse(isolation.attempt("vivo", reached = false))
        assertFalse(isolation.attempt("vivo", reached = false))
        assertTrue(isolation.attempt("vivo", reached = false))
        assertFalse(isolation.attempt("vivo", reached = true))
    }
}
