package org.openbridge.desktop

import java.io.File
import java.util.Base64

/** A phone this PC paired with, which it reconnects to without a code. */
data class SavedPhone(val name: String, val fingerprint: ByteArray, val host: String, val port: Int, val secret: ByteArray) {
    /** The short ID the phone announces on the network: the first 8 bytes of its certificate fingerprint. */
    val id: String get() = fingerprint.take(8).joinToString("") { "%02x".format(it) }
    fun client() = BridgeClient(host, port, fingerprint, secret)
    override fun equals(other: Any?) = other is SavedPhone && other.fingerprint.contentEquals(fingerprint)
    override fun hashCode() = fingerprint.contentHashCode()
}

/** Where photos are imported to, and which ones were imported from each phone (%APPDATA%\OpenBridge\imported-<id>.txt). */
object Imports {
    private val preferences = java.util.prefs.Preferences.userRoot().node("org/openbridge")
    private val base = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "OpenBridge")

    /** The import folder; asks the first time, or when `choose` is set. */
    fun folder(choose: Boolean): File? {
        val saved = preferences.get("importFolder", null)?.let(::File)
        if (!choose && saved != null && saved.isDirectory) return saved
        val picked = Dialogs.chooseFolder(S.chooseImportFolder) ?: return null
        preferences.put("importFolder", picked.absolutePath)
        return picked
    }

    fun line(entry: Entry) = "${entry.name}\t${entry.size}"
    fun load(phone: String): Set<String> = runCatching { File(base, "imported-$phone.txt").readLines().toSet() }.getOrDefault(emptySet())
    fun save(phone: String, lines: Set<String>) {
        runCatching { base.mkdirs(); File(base, "imported-$phone.txt").writeText(lines.joinToString("\n")) }
    }
}

/** Files sent from the phone land here. */
val receivedFolder = File(System.getProperty("user.home"), "Downloads/OpenBridge")

/**
 * Saved phones, in %APPDATA%\OpenBridge\phones.dat. On Windows the whole file is encrypted with DPAPI, so only this
 * Windows account can read the secrets; elsewhere (development builds) it's a private file.
 */
object Phones {
    private val windows = System.getProperty("os.name").startsWith("Windows")
    private val file = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "OpenBridge/phones.dat")

    fun load(): List<SavedPhone> = runCatching {
        if (!file.isFile) return emptyList()
        val raw = file.readBytes()
        val text = String(if (windows) com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(raw) else raw, Charsets.UTF_8)
        text.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val f = line.split('\t').map { Base64.getDecoder().decode(it) }
            if (f.size != 5) return@mapNotNull null
            SavedPhone(String(f[0]), f[1], String(f[2]), String(f[3]).toInt(), f[4]).takeIf { it.fingerprint.size == 32 && it.secret.size == 32 }
        }
    }.getOrDefault(emptyList())

    fun save(phones: List<SavedPhone>) {
        val encode = Base64.getEncoder()
        val text = phones.joinToString("\n") { phone ->
            listOf(phone.name.toByteArray(), phone.fingerprint, phone.host.toByteArray(), phone.port.toString().toByteArray(), phone.secret)
                .joinToString("\t") { encode.encodeToString(it) }
        }.toByteArray()
        file.parentFile.mkdirs()
        val temporary = File(file.parentFile, "phones.tmp")
        temporary.writeBytes(if (windows) com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(text) else text)
        if (!windows) runCatching { temporary.setReadable(false, false); temporary.setReadable(true, true) }
        java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}
