package org.openbridge.desktop

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * One OpenBridge per user: the first one holds a lock in the settings folder and listens on 127.0.0.1 for files to
 * send (from "Send to", "Open With" or the command line). A second launch hands its files over and quits.
 *
 * Only this user can hand files over: the listener answers only a connection that first sends the random token
 * kept in `instance` in the settings folder, a file only this user can read (0600 on Linux; %APPDATA% on Windows).
 * What it takes is a list of paths, which become files to send to the phone; nothing else.
 */
class Handoff(private val folder: File = Platform.configDir) {
    private var lock: FileLock? = null
    private var server: ServerSocket? = null
    private val tokenFile get() = File(folder, "instance")

    /**
     * Becomes the running OpenBridge and returns true, or hands `paths` (and a request to show the window) to the one
     * already running and returns false. When the hand-off fails, this one runs instead.
     */
    fun claim(paths: List<String>, received: (List<File>) -> Unit): Boolean {
        folder.mkdirs()
        val channel = runCatching { RandomAccessFile(File(folder, "instance.lock"), "rw").channel }.getOrNull()
        lock = channel?.let { runCatching { it.tryLock() }.getOrNull() }
        if (channel != null && lock == null) {
            // Another OpenBridge holds the lock: it may still be starting, so try a few times.
            for (attempt in 0 until 25) {
                if (handOver(paths)) return false
                Thread.sleep(200)
                lock = runCatching { channel.tryLock() }.getOrNull()
                if (lock != null) break
            }
            if (lock == null) Log.add("Another OpenBridge seems to run but didn't answer; starting anyway")
        }
        listen(received)
        return true
    }

    /** Sends the paths to the running OpenBridge; true when it took them. */
    fun handOver(paths: List<String>): Boolean = runCatching {
        val (port, token) = tokenFile.readText().trim().split(' ').let { it[0].toInt() to it[1] }
        Socket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2_000)
            socket.soTimeout = 5_000
            val output = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
            output.write(token); output.write("\n")
            for (path in paths) { output.write(File(path).absolutePath.replace("\n", "")); output.write("\n") }
            output.write("\n"); output.flush()
            socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine() == "OK"
        }
    }.getOrDefault(false)

    private fun listen(received: (List<File>) -> Unit) {
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val socket = runCatching { ServerSocket(0, 8, InetAddress.getLoopbackAddress()) }.getOrElse { Log.add("Hand-off not available: ${it.message}"); return }
        server = socket
        runCatching { Platform.writePrivate(tokenFile, "${socket.localPort} $token".toByteArray()) }
            .onFailure { Log.add("Hand-off not available: ${it.message}"); socket.close(); return }
        Thread({
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: continue
                Thread({ client.use { serve(it, token, received) } }, "openbridge-handoff-client").apply { isDaemon = true }.start()
            }
        }, "openbridge-handoff").apply { isDaemon = true }.start()
    }

    private fun serve(client: Socket, token: String, received: (List<File>) -> Unit) {
        runCatching {
            client.soTimeout = 5_000
            val input = client.getInputStream().bufferedReader(Charsets.UTF_8)
            val offered = input.readLine() ?: return
            if (!MessageDigest.isEqual(offered.toByteArray(), token.toByteArray())) return
            val paths = ArrayList<String>()
            while (paths.size < 10_000) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                paths += line
            }
            client.getOutputStream().apply { write("OK\n".toByteArray()); flush() }
            received(paths.map(::File).filter { it.isAbsolute && it.exists() })
        }
    }

    fun close() { runCatching { server?.close() }; runCatching { lock?.release() } }
}
