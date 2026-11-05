package org.openbridge.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/*
 * Playing the phone's videos and music on this computer without saving them first: a small web server on
 * 127.0.0.1 only, at a random port, with an unguessable token in each address. The browser (or VLC) asks for the
 * parts it needs with Range requests; each one becomes its own RESUME request to the phone, from that offset, and is
 * cut off as soon as the player stops reading. The server stops by itself after a while with nothing playing.
 */

/** A Range header, against a file of a known size (RFC 9110 §14). */
sealed class ByteRange {
    /** No usable Range header: the whole file, 200. */
    object Whole : ByteRange()
    /** Bytes `start` to `end` inclusive, 206. */
    data class Part(val start: Long, val end: Long) : ByteRange() { val length get() = end - start + 1 }
    /** A range that starts past the end: 416. */
    object Unsatisfiable : ByteRange()

    companion object {
        /**
         * The first range of `header`. A header that doesn't parse is ignored (the whole file), as the RFC says; one
         * that parses but starts past the end can't be satisfied.
         */
        fun parse(header: String?, size: Long): ByteRange {
            val value = header?.trim() ?: return Whole
            if (!value.startsWith("bytes=", ignoreCase = true)) return Whole
            val first = value.substring(6).substringBefore(',').trim()
            val dash = first.indexOf('-')
            if (dash < 0) return Whole
            val from = first.substring(0, dash).trim(); val to = first.substring(dash + 1).trim()
            fun number(text: String) = text.takeIf { it.isNotEmpty() && it.length <= 18 && it.all(Char::isDigit) }?.toLong()
            if (from.isEmpty()) {
                // The last n bytes.
                val suffix = number(to) ?: return Whole
                if (suffix == 0L || size == 0L) return Unsatisfiable
                return Part(maxOf(0, size - suffix), size - 1)
            }
            val start = number(from) ?: return Whole
            val end = if (to.isEmpty()) null else number(to) ?: return Whole
            if (end != null && end < start) return Whole
            if (start >= size) return Unsatisfiable
            return Part(start, minOf(end ?: (size - 1), size - 1))
        }
    }
}

/** What a player understands the file as, from its name. */
fun mediaType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "mp4", "m4v" -> "video/mp4"
    "webm" -> "video/webm"
    "mkv" -> "video/x-matroska"
    "mov" -> "video/quicktime"
    "3gp" -> "video/3gpp"
    "avi" -> "video/x-msvideo"
    "mp3" -> "audio/mpeg"
    "m4a", "aac" -> "audio/mp4"
    "ogg", "opus" -> "audio/ogg"
    "wav" -> "audio/wav"
    "flac" -> "audio/flac"
    else -> "application/octet-stream"
}

/** Whether a file can be played from the phone here (in the browser or VLC). */
fun playable(entry: Entry) = !entry.directory && (Kind.of(entry) == Kind.VIDEO || Kind.of(entry) == Kind.AUDIO)

class MediaServer(private val idleMillis: Long = 15 * 60_000L) {
    /** Reads a file from the phone from an offset: `body` gets its full size and the bytes from there. */
    fun interface Reader { fun read(entry: Entry, offset: Long, body: (Long, InputStream) -> Unit) }

    private class Stream(val reader: Reader, val entry: Entry)

    /** The player went away mid-answer: not the phone's fault. */
    private class PlayerGone : RuntimeException()

    private var server: HttpServer? = null
    private val streams = ConcurrentHashMap<String, Stream>()
    private val active = AtomicInteger()
    @Volatile private var lastUsed = System.currentTimeMillis()
    private val random = SecureRandom()

    val port: Int get() = server?.address?.port ?: 0

    /** The address a player opens to play `entry` read through `reader`. Starts the server if it isn't running. */
    @Synchronized fun address(reader: Reader, entry: Entry): String {
        val running = server ?: start()
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also(random::nextBytes))
        streams[token] = Stream(reader, entry)
        lastUsed = System.currentTimeMillis()
        return "http://127.0.0.1:${running.address.port}/$token/${QrPairing.percent(entry.name)}"
    }

    private fun start(): HttpServer {
        val http = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16)
        http.executor = Executors.newCachedThreadPool { Thread(it, "openbridge-media").apply { isDaemon = true } }
        http.createContext("/") { exchange -> exchange.use { serve(it) } }
        http.start()
        server = http
        Log.add("Playing from the phone: serving on 127.0.0.1:${http.address.port}")
        Thread({
            while (true) {
                Thread.sleep(minOf(30_000L, idleMillis))
                synchronized(this) {
                    if (server !== http) return@Thread
                    if (active.get() == 0 && System.currentTimeMillis() - lastUsed >= idleMillis) { stopNow(); return@Thread }
                }
            }
        }, "openbridge-media-idle").apply { isDaemon = true }.start()
        return http
    }

    /** Stops serving: every address handed out stops working. */
    @Synchronized fun stop() = stopNow()

    private fun stopNow() {
        val http = server ?: return
        server = null
        streams.clear()
        http.stop(0)
        (http.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        Log.add("Playing from the phone: stopped serving")
    }

    private fun serve(exchange: HttpExchange) {
        active.incrementAndGet()
        try { answer(exchange) } finally { active.decrementAndGet(); lastUsed = System.currentTimeMillis() }
    }

    private fun answer(exchange: HttpExchange) {
        val headers = exchange.responseHeaders
        // Only this computer's own pages and players: another name for the address (DNS rebinding) is refused.
        val host = exchange.requestHeaders.getFirst("Host")
        if (host != "127.0.0.1:$port" && host != "localhost:$port") return exchange.sendResponseHeaders(403, -1)
        val head = exchange.requestMethod == "HEAD"
        if (!head && exchange.requestMethod != "GET") { headers["Allow"] = listOf("GET, HEAD"); return exchange.sendResponseHeaders(405, -1) }
        val token = exchange.requestURI.rawPath.removePrefix("/").substringBefore('/')
        val stream = streams[token] ?: return exchange.sendResponseHeaders(404, -1)
        val size = stream.entry.size
        headers["Content-Type"] = listOf(mediaType(stream.entry.name))
        headers["Accept-Ranges"] = listOf("bytes")
        headers["Cache-Control"] = listOf("no-store")
        headers["X-Content-Type-Options"] = listOf("nosniff")
        val range = ByteRange.parse(exchange.requestHeaders.getFirst("Range"), size)
        val (status, start, length) = when (range) {
            ByteRange.Unsatisfiable -> { headers["Content-Range"] = listOf("bytes */$size"); return exchange.sendResponseHeaders(416, -1) }
            ByteRange.Whole -> Triple(200, 0L, size)
            is ByteRange.Part -> { headers["Content-Range"] = listOf("bytes ${range.start}-${range.end}/$size"); Triple(206, range.start, range.length) }
        }
        if (head || length == 0L) { headers["Content-Length"] = listOf(length.toString()); return exchange.sendResponseHeaders(status, -1) }
        var started = false
        try {
            stream.reader.read(stream.entry, start) { total, input ->
                if (total != size) throw IOException("The file changed on the phone.")
                exchange.sendResponseHeaders(status, length)
                started = true
                val output = exchange.responseBody
                val buffer = ByteArray(1 shl 16)
                var left = length
                while (left > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (read < 0) throw IOException("The phone stopped sending.")
                    // The player closing the connection ends the phone's request too: this throws out of it.
                    try { output.write(buffer, 0, read) } catch (e: IOException) { throw PlayerGone() }
                    left -= read
                }
                try { output.flush() } catch (e: IOException) { throw PlayerGone() }
            }
        } catch (e: PlayerGone) {
            // Seeking or closing the tab: nothing went wrong.
        } catch (e: Exception) {
            Log.add("Playing ${stream.entry.name}: ${e.message ?: e.javaClass.simpleName}")
            if (!started) runCatching { exchange.sendResponseHeaders(502, -1) }
        }
    }
}

/** VLC, when it's installed: from the Windows registry or Program Files, or `vlc` on the PATH on Linux. */
object Vlc {
    val program: File? by lazy {
        if (Platform.windows) {
            val registered = runCatching {
                com.sun.jna.platform.win32.Advapi32Util.registryGetStringValue(com.sun.jna.platform.win32.WinReg.HKEY_LOCAL_MACHINE, "SOFTWARE\\VideoLAN\\VLC", "InstallDir")
            }.getOrNull()?.let { File(it, "vlc.exe") }
            (listOfNotNull(registered) + listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"), System.getenv("ProgramW6432"))
                .map { File(it, "VideoLAN\\VLC\\vlc.exe") }).firstOrNull { it.isFile }
        } else (System.getenv("PATH") ?: "").split(File.pathSeparator).filter { it.isNotBlank() }.map { File(it, "vlc") }.firstOrNull { it.isFile && it.canExecute() }
    }

    fun open(address: String) = program?.let { runCatching { ProcessBuilder(it.path, address).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start() }.isSuccess } ?: false
}
