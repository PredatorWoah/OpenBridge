package org.openbridge.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.ptr.IntByReference
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/** What differs between Windows and Linux: where things are kept, how secrets are protected, how folders open. */
object Platform {
    val windows = System.getProperty("os.name").startsWith("Windows")
    private val home = File(System.getProperty("user.home"))

    /** Saved phones and settings: %APPDATA%\OpenBridge, or $XDG_CONFIG_HOME/OpenBridge (~/.config/OpenBridge). */
    val configDir: File = if (windows) File(System.getenv("APPDATA") ?: home.path, "OpenBridge") else File(xdg("XDG_CONFIG_HOME", ".config"), "OpenBridge")

    /** Import records and texts: %APPDATA%\OpenBridge, or $XDG_DATA_HOME/OpenBridge (~/.local/share/OpenBridge). */
    val dataDir: File = if (windows) configDir else File(xdg("XDG_DATA_HOME", ".local/share"), "OpenBridge")

    // The XDG spec says to ignore relative paths in these variables.
    private fun xdg(variable: String, fallback: String) = System.getenv(variable)?.takeIf { it.startsWith("/") }?.let(::File) ?: File(home, fallback)

    /** Downloads. On Linux, the folder named in user-dirs.dirs: many desktops translate it ("下载"). */
    val downloads: File by lazy {
        val named = if (windows) null else runCatching {
            File(xdg("XDG_CONFIG_HOME", ".config"), "user-dirs.dirs").readLines()
                .firstNotNullOfOrNull { Regex("""^XDG_DOWNLOAD_DIR="(.+)"$""").find(it.trim())?.groupValues?.get(1) }
                ?.replace("\$HOME", home.path)?.let(::File)?.takeIf { it.isAbsolute && it.absoluteFile != home.absoluteFile }
        }.getOrNull()
        named ?: File(home, "Downloads")
    }

    /** Writes a file only this user can read (mode 0600 on Linux), replacing the old one in one step. */
    fun writePrivate(file: File, bytes: ByteArray) {
        val folder = file.absoluteFile.parentFile
        if (!folder.isDirectory) {
            folder.parentFile?.mkdirs()
            if (windows) folder.mkdirs()
            else Files.createDirectories(folder.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        }
        val temporary = File(folder, "${file.name}.tmp")
        temporary.delete()
        if (!windows) Files.createFile(temporary.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        temporary.writeBytes(bytes)
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /**
     * Saved secrets: on Windows encrypted with DPAPI, so only this Windows account can read them; on Linux the file
     * itself is private to this user (0600), like ssh keys.
     */
    fun protect(data: ByteArray): ByteArray = if (windows) com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(data) else data
    fun unprotect(data: ByteArray): ByteArray = if (windows) com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(data) else data

    /** Opens a folder in File Explorer, or in the desktop's file manager. */
    fun openFolder(folder: File) { start(if (windows) listOf("explorer.exe", folder.absolutePath) else listOf("xdg-open", folder.absolutePath)) }

    /** "This PC" in File Explorer, where Windows lists a phone in File transfer mode next to the drives. */
    fun openThisPc() { if (windows) start(listOf("explorer.exe", "shell:MyComputerFolder")) }

    /** Shows a file selected in its folder. Linux file managers do that through FileManager1; others open the folder. */
    fun reveal(file: File) {
        if (windows) { start(listOf("explorer.exe", "/select,", file.absolutePath)); return }
        Thread {
            val uri = file.absoluteFile.toPath().toUri().toString()
            val shown = !uri.contains(',') && exitCode(listOf("dbus-send", "--session", "--print-reply", "--dest=org.freedesktop.FileManager1",
                "--type=method_call", "/org/freedesktop/FileManager1", "org.freedesktop.FileManager1.ShowItems", "array:string:$uri", "string:")) == 0
            if (!shown) openFolder(file.absoluteFile.parentFile)
        }.apply { isDaemon = true }.start()
    }

    /** Opens a file with the program the system chose for it (on Linux, a .deb opens in the software installer). */
    fun open(file: File) {
        if (windows) runCatching { java.awt.Desktop.getDesktop().open(file) } else start(listOf("xdg-open", file.absolutePath))
    }

    private fun start(command: List<String>) { runCatching { ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start() } }

    /** Runs a short command and returns its exit code, or null when it couldn't run or took too long. */
    fun exitCode(command: List<String>, seconds: Long = 5): Int? = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        if (!process.waitFor(seconds, TimeUnit.SECONDS)) { process.destroyForcibly(); null } else process.exitValue()
    }.getOrNull()

    /**
     * A short command's output, or null. The output is read on its own thread: reading it first would wait for as
     * long as the command hangs (adb with a stuck phone), and the time limit would never apply.
     */
    fun output(command: List<String>, seconds: Long = 5): String? = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val text = StringBuffer()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { input ->
                    val buffer = CharArray(4096)
                    while (true) { val read = input.read(buffer); if (read < 0) break; text.append(buffer, 0, read) }
                }
            }
        }.apply { isDaemon = true; start() }
        if (!process.waitFor(seconds, TimeUnit.SECONDS)) { process.destroyForcibly(); null }
        else { reader.join(1_000); text.toString() }
    }.getOrNull()

    /** This computer's name, shown on the phone when pairing and in its list of trusted computers. */
    val computerName: String by lazy {
        // Linux: the kernel's hostname, not a DNS lookup, which can hang for seconds on some networks.
        val name = if (windows) System.getenv("COMPUTERNAME") else runCatching { File("/proc/sys/kernel/hostname").readText().trim() }.getOrNull()
        name?.takeIf { it.isNotBlank() && it != "localhost" } ?: if (windows) "Windows PC" else "Linux computer"
    }

    private interface SystemParameters : Library {
        fun SystemParametersInfoW(action: Int, param: Int, value: IntByReference, update: Int): Boolean
    }

    /** The system asks for less motion: backgrounds stay still and the hero doesn't spin. */
    val reduceMotion: Boolean by lazy {
        runCatching {
            if (windows) {
                // SPI_GETCLIENTAREAANIMATION: Settings › Accessibility › Visual effects › Animation effects.
                val on = IntByReference(1)
                Native.load("user32", SystemParameters::class.java).SystemParametersInfoW(0x1042, 0, on, 0) && on.value == 0
            } else output(listOf("gsettings", "get", "org.gnome.desktop.interface", "enable-animations"), 2)?.trim() == "false"
        }.getOrDefault(false)
    }

    /** "Windows 11 10.0 (amd64)" or "Ubuntu 24.04.1 LTS, Linux 6.8 (amd64)", for diagnostics. */
    val description: String by lazy {
        val system = "${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})"
        val distribution = if (windows) null else runCatching {
            File("/etc/os-release").readLines().firstOrNull { it.startsWith("PRETTY_NAME=") }?.substringAfter('=')?.trim('"')
        }.getOrNull()
        listOfNotNull(distribution, system).joinToString(", ") + ", Java ${System.getProperty("java.version")}"
    }
}

/** This app's version: the installed one, or the build's for development runs. */
object AppVersion {
    /** Set by the installed app's launcher; development runs don't check for updates. */
    val installed: String? = System.getProperty("jpackage.app-version")
    val current: String = installed
        ?: runCatching { AppVersion::class.java.getResource("/openbridge-version.txt")?.readText()?.trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
        ?: "dev"
}
