package org.openbridge.desktop.wpd

import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real Windows Portable Devices calls. CI's Windows runner has no phone, so this shows that COM starts, the device
 * manager is created and GetDevices answers; elsewhere it shows that nothing native is touched.
 */
class WpdWindowsTest {
    @Test
    fun listsDevicesOnWindows() {
        assumeTrue(Com.windows, "Windows only")
        val devices = Wpd.devices()
        assertEquals(devices, Wpd.phones())
        assertTrue(devices.all { it.id.isNotEmpty() && it.name.isNotEmpty() })
    }

    @Test
    fun noPhonesElsewhere() {
        assumeTrue(!Com.windows, "not Windows")
        assertEquals(emptyList(), Wpd.phones())
    }
}
