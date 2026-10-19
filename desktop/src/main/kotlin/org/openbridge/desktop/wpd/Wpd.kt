package org.openbridge.desktop.wpd

import org.openbridge.desktop.BridgeException
import org.openbridge.desktop.PhoneSource
import java.util.Locale

/** A phone plugged in by USB in File transfer mode, as Windows Portable Devices lists it. */
data class UsbPhone(val id: String, val name: String, val manufacturer: String)

/**
 * Phones over USB through Windows Portable Devices (the MTP driver Windows already has): browse, copy both ways,
 * thumbnails, rename, delete, folder sizes and search, like over Wi‑Fi. Windows only; elsewhere there are none.
 */
object Wpd {
    private val sources = HashMap<String, WpdSource>()

    /** Phones plugged in and unlocked in File transfer mode. Empty when there are none, or off Windows. */
    fun phones(): List<UsbPhone> = if (!Com.windows) emptyList() else runCatching { devices() }.getOrDefault(emptyList())

    /** Like [phones], but failing loudly; for tests. */
    internal fun devices(): List<UsbPhone> = ComThread.run { Devices.list() }

    /** Opens a phone for browsing. Its top level lists the phone's storages ("Internal shared storage", an SD card). */
    fun open(phone: UsbPhone): PhoneSource {
        if (!Com.windows) throw BridgeException(T.unavailable)
        // One connection per phone, kept open; it reopens by itself after the cable was out.
        val source = synchronized(sources) { sources.getOrPut(phone.id) { WpdSource(phone) } }
        source.connect()
        return source
    }
}

/** The device manager and how devices are opened. COM thread only. */
internal object Devices {
    private var manager: DeviceManager? = null
    /** What each device is, learned once per device ID: (rank for sorting, false to leave it out). */
    private val kinds = HashMap<String, Pair<Int, Boolean>>()

    fun list(): List<UsbPhone> {
        val manager = manager ?: Com.create(Ids.CLSID_PortableDeviceManager, Ids.IID_IPortableDeviceManager, ::DeviceManager).also { manager = it }
        try {
            manager.refresh()
            val phones = manager.devices().mapNotNull { id ->
                val (rank, shown) = kinds.getOrPut(id) { kind(id) }
                if (!shown) return@mapNotNull null
                val name = manager.friendlyName(id).ifBlank { manager.description(id) }.ifBlank { T.unnamed }
                rank to UsbPhone(id, name, manager.manufacturer(id))
            }
            return phones.sortedWith(compareBy({ it.first }, { it.second.name.lowercase(Locale.ROOT) })).map { it.second }
        } catch (e: Throwable) {
            this.manager = null
            manager.close()
            throw e
        }
    }

    /**
     * Phones first, then media players, then the rest (cameras and the like are fine to show). USB drives also
     * appear as portable devices, speaking mass storage ("MSC") rather than MTP; Windows shows those as drives, so
     * they are left out.
     */
    private fun kind(id: String): Pair<Int, Boolean> = runCatching {
        val device = open(id)
        try {
            device.content().use { content ->
                content.properties().use { properties ->
                    KeyCollection.of(Keys.DEVICE_TYPE, Keys.DEVICE_PROTOCOL).use { keys ->
                        properties.values(Keys.DEVICE_OBJECT_ID, keys).use { v ->
                            val rank = when (v.number(Keys.DEVICE_TYPE)?.toInt()) {
                                Keys.DEVICE_TYPE_PHONE -> 0
                                Keys.DEVICE_TYPE_MEDIA_PLAYER -> 1
                                else -> 2
                            }
                            rank to !(v.string(Keys.DEVICE_PROTOCOL)?.trim()?.startsWith("MSC", ignoreCase = true) ?: false)
                        }
                    }
                }
            }
        } finally { runCatching { device.disconnect() }; device.close() }
    }.getOrDefault(2 to true)

    /**
     * IPortableDevice::Open with this app as the client. A device that refuses (E_ACCESSDENIED) is opened read-only
     * instead, as Microsoft's WPD sample does; changes then fail with a message.
     */
    fun open(id: String): Device {
        val device = Com.create(Ids.CLSID_PortableDeviceFTM, Ids.IID_IPortableDevice, ::Device)
        try {
            Values.create().use { client ->
                val version = (System.getProperty("jpackage.app-version") ?: "").split('.').map { it.toIntOrNull() ?: 0 }
                client.setString(Keys.CLIENT_NAME, "OpenBridge")
                client.setUnsigned(Keys.CLIENT_MAJOR_VERSION, version.getOrElse(0) { 0 })
                client.setUnsigned(Keys.CLIENT_MINOR_VERSION, version.getOrElse(1) { 0 })
                client.setUnsigned(Keys.CLIENT_REVISION, version.getOrElse(2) { 0 })
                client.setUnsigned(Keys.CLIENT_SECURITY_QUALITY_OF_SERVICE, SECURITY_IMPERSONATION)
                var hr = device.open(id, client)
                if (hr == Errors.E_ACCESSDENIED) {
                    client.setUnsigned(Keys.CLIENT_DESIRED_ACCESS, GENERIC_READ)
                    hr = device.open(id, client)
                }
                check(hr, "IPortableDevice::Open")
            }
            return device
        } catch (e: Throwable) { device.close(); throw e }
    }

    /** SecurityImpersonation << 16 (winbase.h). */
    private const val SECURITY_IMPERSONATION = 2 shl 16
    private const val GENERIC_READ = 0x80000000.toInt()   // winnt.h
}
