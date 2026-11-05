package org.openbridge.desktop

import java.io.File

/**
 * Sending to the phone from the file manager: on Windows a shortcut in the user's Send to folder, on Linux a
 * desktop entry in ~/.local/share/applications that takes files (%F), so it's offered under Open With. Both start
 * OpenBridge with the files, which hands them to the OpenBridge already running (see [Handoff]).
 */
object Integration {
    /** The installed app's launcher; development runs have none, and can't be added. */
    val launcher: File? get() = System.getProperty("jpackage.app-path")?.let(::File)?.takeIf { it.isFile }

    private val sendTo: File? get() = System.getenv("APPDATA")?.let { File(it, "Microsoft\\Windows\\SendTo\\OpenBridge.lnk") }
    private val desktopEntry: File get() = File(File(System.getenv("XDG_DATA_HOME")?.takeIf { it.startsWith("/") } ?: (System.getProperty("user.home") + "/.local/share"),
        "applications"), "openbridge-send.desktop")

    val installed: Boolean get() = if (Platform.windows) sendTo?.isFile == true else desktopEntry.isFile

    /** Adds the entry; false when it couldn't. */
    fun add(): Boolean {
        val program = launcher ?: return false
        return runCatching {
            if (Platform.windows) {
                val link = sendTo ?: return false
                // WScript.Shell makes .lnk files; the paths go in as single-quoted PowerShell strings.
                fun quote(text: String) = "'" + text.replace("'", "''") + "'"
                val script = "\$s = (New-Object -ComObject WScript.Shell).CreateShortcut(${quote(link.path)}); \$s.TargetPath = ${quote(program.path)}; " +
                    "\$s.IconLocation = ${quote(program.path)}; \$s.Description = 'OpenBridge'; \$s.Save()"
                Platform.exitCode(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script), 20) == 0 && link.isFile
            } else {
                desktopEntry.parentFile.mkdirs()
                desktopEntry.writeText(desktopFile(program.path))
                Platform.exitCode(listOf("update-desktop-database", desktopEntry.parentFile.path), 10)
                true
            }
        }.onFailure { Log.add("Send to not added: ${it.message}") }.getOrDefault(false)
    }

    fun remove(): Boolean = runCatching {
        val file = if (Platform.windows) sendTo ?: return false else desktopEntry
        val removed = !file.exists() || file.delete()
        if (!Platform.windows) Platform.exitCode(listOf("update-desktop-database", desktopEntry.parentFile.path), 10)
        removed
    }.getOrDefault(false)

    /** The Linux desktop entry: hidden from menus, offered for files of any common kind. */
    fun desktopFile(program: String): String {
        // The Exec key's own quoting: inside double quotes, ", `, $ and \ are escaped with a backslash.
        val exec = "\"" + program.replace("\\", "\\\\").replace("\"", "\\\"").replace("`", "\\`").replace("$", "\\$") + "\" %F"
        return """
            [Desktop Entry]
            Type=Application
            Name=OpenBridge
            GenericName=Send to phone
            Comment=Send files to your phone with OpenBridge
            Exec=$exec
            Icon=${launcher?.parentFile?.parentFile?.let { File(it, "lib/OpenBridge.png") }?.takeIf { it.isFile }?.path ?: "openbridge"}
            Terminal=false
            NoDisplay=true
            MimeType=application/octet-stream;application/pdf;application/zip;application/x-7z-compressed;application/vnd.android.package-archive;image/jpeg;image/png;image/gif;image/webp;image/heic;image/heif;image/bmp;image/svg+xml;video/mp4;video/quicktime;video/x-matroska;video/webm;audio/mpeg;audio/mp4;audio/x-wav;audio/flac;audio/ogg;text/plain;text/csv;text/markdown;application/msword;application/vnd.openxmlformats-officedocument.wordprocessingml.document;application/vnd.openxmlformats-officedocument.spreadsheetml.sheet;application/vnd.openxmlformats-officedocument.presentationml.presentation;application/vnd.oasis.opendocument.text;
            Categories=Utility;
        """.trimIndent() + "\n"
    }
}
