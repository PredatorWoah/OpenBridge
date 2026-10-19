package org.openbridge.desktop

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The official scrcpy (Apache-2.0) and the adb it brings: bundled with the Windows app and the Linux packages, else
 * one installed on Linux (on PATH, for example from the distribution's scrcpy and adb packages).
 */
object Tools {
    private val exe = if (Platform.windows) ".exe" else ""
    private val bundled: File? = System.getProperty("compose.application.resources.dir")?.let { File(it, "scrcpy") }

    val scrcpy: File? get() = bundled?.resolve("scrcpy$exe")?.takeIf { it.isFile } ?: onPath("scrcpy$exe")
    val adb: File? get() = bundled?.resolve("adb$exe")?.takeIf { it.isFile } ?: onPath("adb$exe")
    /** Whether scrcpy came with the app, for diagnostics. */
    val scrcpyBundled: Boolean get() = bundled?.resolve("scrcpy$exe")?.isFile == true

    private fun onPath(name: String): File? = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .filter { it.isNotBlank() }.map { File(it, name) }.firstOrNull { it.isFile && it.canExecute() }

    /**
     * The environment for scrcpy: the bundled build finds its server and adb beside it, and the Linux one is told
     * explicitly, so it never mixes with another adb version on PATH (two versions restart each other's server).
     */
    fun environment(builder: ProcessBuilder): ProcessBuilder {
        val folder = bundled?.takeIf { scrcpyBundled } ?: return builder
        if (!Platform.windows) {
            builder.environment()["ADB"] = File(folder, "adb").path
            builder.environment()["SCRCPY_SERVER_PATH"] = File(folder, "scrcpy-server").path
        }
        return builder
    }
}

/** Screen mirroring with scrcpy, in its own window. */
object Mirror {
    val available: Boolean get() = Tools.scrcpy != null
    @Volatile private var running: Process? = null

    /**
     * Starts scrcpy; `finished` gets an error message, or null when the window closed normally. `serial` picks the
     * phone when several are plugged in. `wireless`: with the phone on the cable, scrcpy switches it to Wi‑Fi
     * (`--tcpip`), and the cable can come out. When the window closes, the phone goes back to USB-only debugging,
     * so it doesn't stay open on the network.
     */
    fun start(wireless: Boolean = false, serial: String? = null, finished: (String?) -> Unit) {
        val exe = Tools.scrcpy ?: return finished(S.mirrorMissing)
        Thread {
            val result = runCatching {
                val arguments = mutableListOf(exe.path, "--window-title=OpenBridge", "--stay-awake")
                if (serial != null) arguments += "--serial=$serial"
                if (wireless) arguments += listOf("--tcpip", "--video-bit-rate=8M", "--max-size=1920")
                val process = Tools.environment(ProcessBuilder(arguments)).directory(exe.parentFile).redirectErrorStream(true).start()
                running = process
                val log = process.inputStream.bufferedReader().readText()
                val code = process.waitFor()
                running = null
                if (wireless) closeWireless()
                Log.add("Mirroring ended (code $code)")
                if (code == 0) null
                else log.lines().lastOrNull { it.contains("ERROR") }?.substringAfter("ERROR:")?.trim()
                    ?: "${S.mirrorStopped(code)} ${S.mirrorHint}"
            }.getOrElse { it.message }
            finished(result)
        }.apply { isDaemon = true }.start()
    }

    /** Closes the mirroring window, before an update replaces the files it runs from. */
    fun stop() { running?.destroy() }

    /** Turns wireless debugging back off on phones connected over Wi‑Fi (`adb usb` restarts it on the cable only). */
    private fun closeWireless() {
        val adb = Tools.adb?.path ?: return
        runCatching {
            val listing = ProcessBuilder(adb, "devices").redirectErrorStream(true).start()
            val lines = listing.inputStream.bufferedReader().readLines()
            listing.waitFor(10, TimeUnit.SECONDS)
            lines.map { it.split('\t') }.filter { it.size == 2 && it[1] == "device" && it[0].contains(':') }.forEach { (serial, _) ->
                ProcessBuilder(adb, "-s", serial, "usb").start().waitFor(10, TimeUnit.SECONDS)
            }
        }
    }
}
