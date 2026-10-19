package org.openbridge.desktop

import java.io.File

/**
 * What the browser shows and changes: a phone over Wi‑Fi or Fast USB (OB01, [BridgeClient]), or plugged in by USB in
 * File transfer mode (MTP through Windows Portable Devices, [org.openbridge.desktop.wpd.Wpd]). Blocking: call from a
 * background thread. Failures are [BridgeException]s with messages fit to show.
 */
interface PhoneSource {
    fun list(id: String = ""): List<Entry>
    /** Downloads `entry` to `destination`, which must not exist; continues an interrupted download when it can. */
    fun download(entry: Entry, destination: File, cancelled: () -> Boolean = { false }, progress: (Long, Long) -> Unit)
    /** Uploads `source` into a folder under its own name, never replacing anything there. */
    fun upload(source: File, directoryId: String, cancelled: () -> Boolean = { false }, progress: (Long, Long) -> Unit)
    fun makeFolder(name: String, directoryId: String): Entry
    /** A small JPEG of a photo or video. */
    fun thumbnail(entry: Entry, size: Int = 256): ByteArray
    /** A file, or a folder with everything in it. */
    fun delete(entry: Entry)
    /** Renames in place and returns the item afterwards (its ID may change). */
    fun rename(entry: Entry, name: String): Entry
    fun measure(folder: Entry): FolderSize
    /** Files and folders whose names contain `query`, anywhere below `folderId`. */
    fun search(folderId: String, query: String): SearchResult
}

/** How the open phone is connected; decides what the browser can offer. */
enum class Transport(val english: String) {
    /** OB01 over the network: everything, including texts, the clipboard and files sent from the phone. */
    WIFI("Wi-Fi"),
    /** OB01 through USB debugging (adb forward to the phone's loopback listener): everything, and faster. */
    FAST_USB("Fast USB"),
    /** File transfer mode (MTP through Windows Portable Devices): files only. */
    USB("USB");

    val label: String get() = when (this) { WIFI -> S.wifi; FAST_USB -> S.fastUsb; USB -> S.usb }
}
