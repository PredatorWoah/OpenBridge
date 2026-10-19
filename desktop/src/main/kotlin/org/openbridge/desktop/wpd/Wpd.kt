package org.openbridge.desktop.wpd

import org.openbridge.desktop.BridgeException
import org.openbridge.desktop.PhoneSource

/** A phone plugged in by USB in File transfer mode, as Windows Portable Devices lists it. */
data class UsbPhone(val id: String, val name: String, val manufacturer: String)

/**
 * Phones over USB through Windows Portable Devices (the MTP driver Windows already has): browse, copy both ways,
 * thumbnails, rename, delete, folder sizes and search, like over Wi‑Fi. Windows only; elsewhere there are none.
 */
object Wpd {
    /** Phones plugged in and unlocked in File transfer mode. Empty when there are none, or off Windows. */
    fun phones(): List<UsbPhone> = emptyList()

    /** Opens a phone for browsing. Its top level lists the phone's storages ("Internal shared storage", an SD card). */
    fun open(phone: UsbPhone): PhoneSource = throw BridgeException("USB browsing isn't available on this computer.")
}
