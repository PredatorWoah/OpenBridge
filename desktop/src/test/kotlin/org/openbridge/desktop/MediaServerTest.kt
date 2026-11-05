package org.openbridge.desktop

import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Playing from the phone: Range requests on the local server, each mapped onto RESUME against the fake phone. */
class MediaServerTest {
    private val phone = FakePhone()
    private val server = MediaServer()
    private val video = Random(7).nextBytes(3_000_000)
    private val entry = Entry("v1", "VID 2026 (1).mp4", false, video.size.toLong())

    init { phone.files["v1"] = video }

    @AfterTest fun stop() { server.stop(); phone.close() }

    private fun address(): String { val client = phone.client(); return server.address({ e, offset, body -> client.read(e, offset, body) }, entry) }

    private class Answer(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    private fun get(url: String, range: String? = null, method: String = "GET"): Answer {
        val connection = URI(url).toURL().openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
        connection.requestMethod = method
        if (range != null) connection.setRequestProperty("Range", range)
        val status = connection.responseCode
        val body = (if (status < 400) connection.inputStream else connection.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        val headers = java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER).apply { connection.headerFields.forEach { (k, v) -> if (k != null) put(k, v.first()) } }
        connection.disconnect()
        return Answer(status, headers, body)
    }

    @Test
    fun parsesRanges() {
        assertEquals(ByteRange.Whole, ByteRange.parse(null, 100))
        assertEquals(ByteRange.Part(0, 9), ByteRange.parse("bytes=0-9", 100))
        assertEquals(ByteRange.Part(50, 99), ByteRange.parse("bytes=50-", 100))
        assertEquals(ByteRange.Part(90, 99), ByteRange.parse("bytes=-10", 100))
        assertEquals(ByteRange.Part(0, 99), ByteRange.parse("bytes=-500", 100))
        assertEquals(ByteRange.Part(10, 99), ByteRange.parse("bytes=10-5000", 100))
        assertEquals(ByteRange.Part(1, 2), ByteRange.parse("bytes=1-2, 5-6", 100))   // only the first
        // Not understood: ignored, the whole file.
        for (bad in listOf("bytes=abc", "items=0-9", "bytes=9-1", "bytes=-", "bytes=1-x", "bytes=99999999999999999999-"))
            assertEquals(ByteRange.Whole, ByteRange.parse(bad, 100), bad)
        // Understood but past the end.
        assertEquals(ByteRange.Unsatisfiable, ByteRange.parse("bytes=100-", 100))
        assertEquals(ByteRange.Unsatisfiable, ByteRange.parse("bytes=-0", 100))
        assertEquals(ByteRange.Unsatisfiable, ByteRange.parse("bytes=0-", 0))
    }

    @Test
    fun servesTheWholeFileAndRanges() {
        val url = address()
        assertTrue(url.startsWith("http://127.0.0.1:${server.port}/"))
        assertTrue(url.endsWith("/VID%202026%20%281%29.mp4"), url)

        val whole = get(url)
        assertEquals(200, whole.status)
        assertEquals("video/mp4", whole.headers["Content-Type"]); assertEquals("bytes", whole.headers["Accept-Ranges"])
        assertContentEquals(video, whole.body)

        val start = get(url, "bytes=0-1023")
        assertEquals(206, start.status); assertEquals("bytes 0-1023/3000000", start.headers["Content-Range"])
        assertContentEquals(video.copyOfRange(0, 1024), start.body)

        val middle = get(url, "bytes=1500000-1600000")
        assertEquals(206, middle.status); assertEquals("bytes 1500000-1600000/3000000", middle.headers["Content-Range"])
        assertContentEquals(video.copyOfRange(1_500_000, 1_600_001), middle.body)

        val open = get(url, "bytes=2999000-")
        assertContentEquals(video.copyOfRange(2_999_000, 3_000_000), open.body)

        val suffix = get(url, "bytes=-500")
        assertEquals(206, suffix.status); assertEquals("bytes 2999500-2999999/3000000", suffix.headers["Content-Range"])
        assertContentEquals(video.copyOfRange(2_999_500, 3_000_000), suffix.body)

        val invalid = get(url, "bytes=nonsense")
        assertEquals(200, invalid.status); assertEquals(video.size, invalid.body.size)

        val past = get(url, "bytes=3000000-")
        assertEquals(416, past.status); assertEquals("bytes */3000000", past.headers["Content-Range"])

        val head = get(url, "bytes=10-19", method = "HEAD")
        assertEquals(206, head.status); assertEquals(0, head.body.size)

        // Each request read the phone from its own offset, on a connection of its own; HEAD and 416 didn't ask it.
        assertEquals(listOf(0L, 0L, 1_500_000L, 2_999_000L, 2_999_500L, 0L), phone.reads)
    }

    @Test
    fun refusesWhatIsNotAPlayerHere() {
        val url = address()
        assertEquals(404, get(url.replaceAfterLast(":", "${server.port}/not-the-token/x.mp4")).status)
        assertEquals(405, get(url, method = "DELETE").status)
        // Another name for this computer (as a page using DNS rebinding would send) is refused.
        Socket("127.0.0.1", server.port).use { socket ->
            socket.getOutputStream().write("GET ${URI(url).rawPath} HTTP/1.1\r\nHost: evil.example:${server.port}\r\nConnection: close\r\n\r\n".toByteArray())
            assertTrue(socket.getInputStream().bufferedReader().readLine().startsWith("HTTP/1.1 403"))
        }
        assertTrue(phone.reads.isEmpty())
    }

    @Test
    fun aPlayerThatGoesAwayStopsThePhone() {
        phone.files["v1"] = Random(1).nextBytes(40_000_000)
        val big = entry.copy(size = 40_000_000)
        val client = phone.client()
        val url = server.address({ e, offset, body -> client.read(e, offset, body) }, big)
        Socket("127.0.0.1", server.port).use { socket ->
            socket.getOutputStream().write("GET ${URI(url).rawPath} HTTP/1.1\r\nHost: 127.0.0.1:${server.port}\r\n\r\n".toByteArray())
            val input = socket.getInputStream()
            input.readNBytes(100_000)   // the start of the video, then the tab closes
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (phone.readsCut.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(1, phone.readsCut.get())
    }

    @Test
    fun stopsWhenIdle() {
        val idle = MediaServer(idleMillis = 300)
        val client = phone.client()
        idle.address({ e, offset, body -> client.read(e, offset, body) }, entry)
        assertTrue(idle.port > 0)
        val deadline = System.currentTimeMillis() + 5_000
        while (idle.port != 0 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(0, idle.port)
    }

    @Test
    fun knowsWhatPlays() {
        assertTrue(playable(Entry("1", "a.MKV", false, 1))); assertTrue(playable(Entry("1", "a.flac", false, 1)))
        assertTrue(!playable(Entry("1", "a.jpg", false, 1))); assertTrue(!playable(Entry("1", "Movies.mp4", true, 0)))
        assertEquals("audio/mpeg", mediaType("x.mp3")); assertEquals("video/webm", mediaType("x.WEBM"))
    }
}
