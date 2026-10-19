package org.openbridge.desktop

import java.io.File
import java.util.Base64

/**
 * A phone this computer paired with, which it reconnects to without a code. `host` is its last Wi‑Fi address (empty
 * when it was paired over USB and never seen on the network); over Fast USB it's recognized by its fingerprint.
 */
data class SavedPhone(val name: String, val fingerprint: ByteArray, val host: String, val port: Int, val secret: ByteArray) {
    /** The short ID the phone announces on the network: the first 8 bytes of its certificate fingerprint. */
    val id: String get() = fingerprint.take(8).joinToString("") { "%02x".format(it) }
    fun client() = BridgeClient(host, port, fingerprint, secret)
    /** The phone through a Fast USB tunnel: adb forwards `localPort` on this computer to the phone. */
    fun overUsb(localPort: Int) = BridgeClient("127.0.0.1", localPort, fingerprint, secret, Transport.FAST_USB)
    override fun equals(other: Any?) = other is SavedPhone && other.fingerprint.contentEquals(fingerprint)
    override fun hashCode() = fingerprint.contentHashCode()
}

/** Where photos are imported to, and which ones were imported from each phone (imported-<id>.txt in the data folder). */
object Imports {
    private val preferences = java.util.prefs.Preferences.userRoot().node("org/openbridge")

    /** The import folder; asks the first time, or when `choose` is set. */
    fun folder(choose: Boolean): File? {
        val saved = preferences.get("importFolder", null)?.let(::File)
        if (!choose && saved != null && saved.isDirectory) return saved
        val picked = Dialogs.chooseFolder(S.chooseImportFolder) ?: return null
        preferences.put("importFolder", picked.absolutePath)
        return picked
    }

    fun line(entry: Entry) = "${entry.name}\t${entry.size}"
    fun load(phone: String): Set<String> = runCatching { File(Platform.dataDir, "imported-$phone.txt").readLines().toSet() }.getOrDefault(emptySet())
    fun save(phone: String, lines: Set<String>) {
        runCatching { Platform.writePrivate(File(Platform.dataDir, "imported-$phone.txt"), lines.joinToString("\n").toByteArray()) }
    }
}

/** Files sent from the phone land here. */
val receivedFolder: File get() = File(Platform.downloads, "OpenBridge")

/**
 * Saved phones, in phones.dat in the settings folder (%APPDATA%\OpenBridge, ~/.config/OpenBridge). On Windows the
 * whole file is encrypted with DPAPI, so only this Windows account can read the secrets; on Linux the file is
 * readable by this user only (0600). The same lines either way: name, fingerprint, host, port and secret in Base64.
 */
object Phones {
    private val file = File(Platform.configDir, "phones.dat")

    fun load(): List<SavedPhone> = runCatching {
        if (!file.isFile) return emptyList()
        val text = String(Platform.unprotect(file.readBytes()), Charsets.UTF_8)
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
        Platform.writePrivate(file, Platform.protect(text))
    }
}
