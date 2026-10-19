package org.openbridge.desktop

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Installs a newer release: downloads this computer's package and SHA256SUMS.txt from the GitHub release over HTTPS,
 * refuses anything whose checksum doesn't match, then hands the package to the system. Blocking: call off the UI.
 */
object Updater {
    enum class Package { WINDOWS_SETUP, LINUX_DEB, LINUX_ARCHIVE }

    /** What this copy was installed from; null for development runs, which don't update themselves. */
    val installedFrom: Package? = when {
        AppVersion.installed == null -> null
        Platform.windows -> Package.WINDOWS_SETUP
        // The .deb installs under /opt/openbridge; a copy unpacked from the archive runs from anywhere else.
        System.getProperty("jpackage.app-path").orEmpty().startsWith("/opt/openbridge/") -> Package.LINUX_DEB
        else -> Package.LINUX_ARCHIVE
    }

    fun asset(version: String, kind: Package): String = when (kind) {
        Package.WINDOWS_SETUP -> "OpenBridge-$version-Windows-Setup.exe"
        Package.LINUX_DEB -> "OpenBridge-$version-Linux-amd64.deb"
        Package.LINUX_ARCHIVE -> "OpenBridge-$version-Linux-x86_64.tar.gz"
    }

    private fun release(version: String) = "https://github.com/PredatorWoah/OpenBridge/releases/download/v$version/"

    /** The SHA-256 that SHA256SUMS.txt lists for `name` (lines of "<hex>  <name>"), or null. */
    fun checksum(sums: String, name: String): String? = sums.lines().firstNotNullOfOrNull { line ->
        val match = Regex("""^([0-9a-fA-F]{64}) [ *]?(.+)$""").find(line.trim()) ?: return@firstNotNullOfOrNull null
        match.groupValues[1].lowercase().takeIf { match.groupValues[2].trim() == name }
    }

    /**
     * Downloads the package for `version` into `folder` and checks it; returns the file. `progress` gets 0 to 1.
     * Fails with a message fit to show; a checksum mismatch leaves nothing behind.
     */
    fun download(version: String, kind: Package, folder: File, progress: (Float) -> Unit): File {
        require(version.matches(Regex("[0-9]+(\\.[0-9]+){1,3}")))
        val name = asset(version, kind)
        val sums = open(release(version) + "SHA256SUMS.txt").use { (stream, _) -> String(stream.readNBytes(64 * 1024), Charsets.UTF_8) }
        val expected = checksum(sums, name) ?: throw IOException(S.checksumFailed)
        folder.mkdirs()
        val partial = File(folder, ".$name.part")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            open(release(version) + name).use { (stream, length) ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read); digest.update(buffer, 0, read)
                        done += read
                        if (length > 0) progress((done.toFloat() / length).coerceAtMost(1f))
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != expected) { Log.add("Update: checksum mismatch for $name"); throw IOException(S.checksumFailed) }
            val target = Names.free(File(folder, name))
            java.nio.file.Files.move(partial.toPath(), target.toPath())
            return target
        } finally { partial.delete() }
    }

    private class Download(val stream: InputStream, val length: Long, val connection: HttpURLConnection) : AutoCloseable {
        operator fun component1() = stream
        operator fun component2() = length
        override fun close() { runCatching { stream.close() }; connection.disconnect() }
    }

    /** GET over HTTPS, following GitHub's redirect to its download host (HTTPS only: Java never follows a downgrade). */
    private fun open(url: String): Download {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "OpenBridge")
        val code = connection.responseCode
        if (code != 200 || connection.url.protocol != "https") { connection.disconnect(); throw IOException("HTTP $code") }
        return Download(connection.inputStream, connection.contentLengthLong, connection)
    }
}
