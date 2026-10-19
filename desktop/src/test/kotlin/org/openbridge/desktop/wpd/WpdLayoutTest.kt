package org.openbridge.desktop.wpd

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The bytes handed to Windows, checked against the values in mingw-w64's WPD headers. */
class WpdLayoutTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun guidsAreLaidOutLikeCom() {
        // DEFINE_GUID(IID_IPortableDeviceManager, 0xa1567595, 0x4c2f, 0x4574, 0xa6,0xfa, 0xec,0xef,0x91,0x7b,0x9a,0x40)
        assertContentEquals(bytes(0x95, 0x75, 0x56, 0xa1, 0x2f, 0x4c, 0x74, 0x45, 0xa6, 0xfa, 0xec, 0xef, 0x91, 0x7b, 0x9a, 0x40),
            Ids.IID_IPortableDeviceManager.bytes)
        assertEquals("a1567595-4c2f-4574-a6fa-ecef917b9a40", Ids.IID_IPortableDeviceManager.toString())
    }

    @Test
    fun idsMatchTheIdlUuids() {
        // uuid() attributes in portabledeviceapi.idl and portabledevicetypes.idl
        val expected = mapOf(
            Ids.CLSID_PortableDeviceManager to "0af10cec-2ecd-4b92-9581-34f6ae0637f3",
            Ids.CLSID_PortableDeviceFTM to "f7c0039a-4762-488a-b4b3-760ef9a1ba9b",
            Ids.CLSID_PortableDeviceValues to "0c15d503-d017-47ce-9016-7b3f978721cc",
            Ids.CLSID_PortableDeviceKeyCollection to "de2d022d-2480-43be-97f0-d1fa2cf98f4f",
            Ids.CLSID_PortableDevicePropVariantCollection to "08a99e2f-6d6d-4b80-af5a-baf2bcbe4cb9",
            Ids.IID_IPortableDeviceManager to "a1567595-4c2f-4574-a6fa-ecef917b9a40",
            Ids.IID_IPortableDevice to "625e2df8-6392-4cf0-9ad1-3cfa5f17775c",
            Ids.IID_IPortableDeviceValues to "6848f6f2-3155-4f86-b6f5-263eeeab3143",
            Ids.IID_IPortableDeviceKeyCollection to "dada2357-e0ad-492e-98db-dd61c53ba353",
            Ids.IID_IPortableDevicePropVariantCollection to "89b2e422-4f1b-4316-bcef-a44afea83eb3",
        )
        for ((guid, text) in expected) {
            assertEquals(text, guid.toString())
            assertEquals(guid, Guid.parse("{$text}"))
        }
    }

    @Test
    fun propertyKeysAreGuidThenPid() {
        // DEFINE_PROPERTYKEY(WPD_OBJECT_ORIGINAL_FILE_NAME, 0xEF6B490D, 0x5CD8, 0x437A, 0xAF, 0xFC, 0xDA, 0x8B, 0x60, 0xEE, 0x4A, 0x3C, 12)
        assertContentEquals(bytes(0x0d, 0x49, 0x6b, 0xef, 0xd8, 0x5c, 0x7a, 0x43, 0xaf, 0xfc, 0xda, 0x8b, 0x60, 0xee, 0x4a, 0x3c, 12, 0, 0, 0),
            Keys.OBJECT_ORIGINAL_FILE_NAME.bytes)
        assertEquals(PropertyKey.SIZE, Keys.OBJECT_ORIGINAL_FILE_NAME.bytes.size)
        // DEFINE_PROPERTYKEY(WPD_STORAGE_FREE_SPACE_IN_BYTES, 0x01a3057a, 0x74d6, 0x4e80, 0xbe, 0xa7, 0xdc, 0x4c, 0x21, 0x2c, 0xe5, 0x0a, 5)
        assertContentEquals(bytes(0x7a, 0x05, 0xa3, 0x01, 0xd6, 0x74, 0x80, 0x4e, 0xbe, 0xa7, 0xdc, 0x4c, 0x21, 0x2c, 0xe5, 0x0a, 5, 0, 0, 0),
            Keys.STORAGE_FREE_SPACE_IN_BYTES.bytes)
    }

    @Test
    fun keysMatchTheHeader() {
        val objects = "ef6b490d-5cd8-437a-affc-da8b60ee4a3c"
        val expected = mapOf(
            Keys.OBJECT_ID to (objects to 2), Keys.OBJECT_PARENT_ID to (objects to 3), Keys.OBJECT_NAME to (objects to 4),
            Keys.OBJECT_FORMAT to (objects to 6), Keys.OBJECT_CONTENT_TYPE to (objects to 7), Keys.OBJECT_ISHIDDEN to (objects to 9),
            Keys.OBJECT_ISSYSTEM to (objects to 10), Keys.OBJECT_SIZE to (objects to 11), Keys.OBJECT_ORIGINAL_FILE_NAME to (objects to 12),
            Keys.FUNCTIONAL_OBJECT_CATEGORY to ("8f052d93-abca-4fc5-a5ac-b01df4dbe598" to 2),
            Keys.STORAGE_CAPACITY to ("01a3057a-74d6-4e80-bea7-dc4c212ce50a" to 4),
            Keys.STORAGE_DESCRIPTION to ("01a3057a-74d6-4e80-bea7-dc4c212ce50a" to 7),
            Keys.CLIENT_NAME to ("204d9f0c-2292-4080-9f42-40664e70f859" to 2),
            Keys.CLIENT_MAJOR_VERSION to ("204d9f0c-2292-4080-9f42-40664e70f859" to 3),
            Keys.CLIENT_MINOR_VERSION to ("204d9f0c-2292-4080-9f42-40664e70f859" to 4),
            Keys.CLIENT_REVISION to ("204d9f0c-2292-4080-9f42-40664e70f859" to 5),
            Keys.CLIENT_SECURITY_QUALITY_OF_SERVICE to ("204d9f0c-2292-4080-9f42-40664e70f859" to 8),
            Keys.CLIENT_DESIRED_ACCESS to ("204d9f0c-2292-4080-9f42-40664e70f859" to 9),
            Keys.DEVICE_PROTOCOL to ("26d4979a-e643-4626-9e2b-736dc0c92fdc" to 6),
            Keys.DEVICE_TYPE to ("26d4979a-e643-4626-9e2b-736dc0c92fdc" to 15),
            Keys.RESOURCE_DEFAULT to ("e81e79be-34f0-41bf-b53f-f1a06ae87842" to 0),
            Keys.RESOURCE_THUMBNAIL to ("c7c407ba-98fa-46b5-9960-23fec124cfde" to 0),
        )
        for ((key, value) in expected) assertEquals(PropertyKey(Guid.parse(value.first), value.second), key)
        assertEquals("27e2e392-a111-48e0-ab0c-e17705a05f85", Keys.CONTENT_TYPE_FOLDER.toString())
        assertEquals("99ed0160-17ff-4c44-9d98-1d7a6f941921", Keys.CONTENT_TYPE_FUNCTIONAL_OBJECT.toString())
        assertEquals("0085e0a6-8d34-45d7-bc5c-447e59c73d48", Keys.CONTENT_TYPE_GENERIC_FILE.toString())
        assertEquals("23f05bbc-15de-4c2a-a55b-a9af5ce412ef", Keys.FUNCTIONAL_CATEGORY_STORAGE.toString())
        assertEquals("30000000-ae6c-4804-98ba-c57b46965fe7", Keys.OBJECT_FORMAT_UNSPECIFIED.toString())
    }

    @Test
    fun propVariantLayout() {
        assertEquals(24, PropVariant.size(8))
        assertEquals(16, PropVariant.size(4))
        assertEquals(8, PropVariant.VALUE_OFFSET)
        assertEquals(31, PropVariant.VT_LPWSTR.toInt())
        // A VT_UI4 leaves the upper half of the union undefined: only the low 32 bits count.
        assertEquals(0xffff_fffeL, PropVariant.number(PropVariant.VT_UI4, 0x1234_5678_ffff_fffeL))
        assertEquals(5_000_000_000L, PropVariant.number(PropVariant.VT_UI8, 5_000_000_000L))
        assertEquals(1L, PropVariant.number(PropVariant.VT_BOOL, 0xffffL))   // VARIANT_TRUE
        assertEquals(0L, PropVariant.number(PropVariant.VT_BOOL, 0x7_0000L))
        assertNull(PropVariant.number(PropVariant.VT_LPWSTR, 0))
    }

    @Test
    fun hresultsBecomeMessages() {
        assertEquals(0x80070005.toInt(), Errors.win32(5))
        assertEquals(0x8007048F.toInt(), Errors.win32(1167))
        assertEquals(T.locked, Errors.message(Errors.E_ACCESSDENIED))
        assertEquals(T.locked, Errors.message(0x800700AA.toInt()))   // ERROR_BUSY
        assertTrue(Errors.lost(0x8007048F.toInt()))                  // ERROR_DEVICE_NOT_CONNECTED
        assertTrue(Errors.lost(Errors.E_WPD_DEVICE_NOT_OPEN))
        assertTrue(Errors.bridge(0x8007001F.toInt()).lost)            // ERROR_GEN_FAILURE
        assertFalse(Errors.lost(Errors.E_ACCESSDENIED))
        assertEquals(T.full, Errors.message(0x80070070.toInt()))     // ERROR_DISK_FULL
        assertEquals(T.gone, Errors.message(0x80070002.toInt()))     // ERROR_FILE_NOT_FOUND
        assertEquals(T.noWpd, Errors.message(Errors.REGDB_E_CLASSNOTREG))
        assertTrue(Errors.message(0x8000FFFF.toInt()).contains("0x8000FFFF"))
        assertTrue(Errors.noRecursion(Errors.E_NOTIMPL))
    }

    @Test
    fun namesAreFilteredLikeOverWifi() {
        assertTrue(WpdSource.valid("IMG_0001.jpg"))
        assertFalse(WpdSource.valid(""))
        assertFalse(WpdSource.valid(".."))
        assertFalse(WpdSource.valid("a/b"))
        assertFalse(WpdSource.valid("bad\u0007name"))
        assertEquals(256 shl 10, WpdSource.chunk(0))
        assertEquals(64 shl 10, WpdSource.chunk(512))
        assertEquals(4 shl 20, WpdSource.chunk(64 shl 20))
        assertEquals(262_144, WpdSource.chunk(262_144))
    }
}
