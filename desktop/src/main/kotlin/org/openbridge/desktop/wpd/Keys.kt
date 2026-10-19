package org.openbridge.desktop.wpd

import com.sun.jna.Memory

/**
 * A GUID as COM lays it out in memory: Data1 (32 bits), Data2 and Data3 (16 bits each), all little-endian, then
 * Data4's eight bytes as they are.
 */
internal class Guid(bytes: ByteArray) {
    val bytes: ByteArray = bytes.copyOf()

    init { require(bytes.size == 16) }

    /** The copy that native calls point at (REFGUID). Allocated on first use, so tests never touch native memory. */
    val native: Memory by lazy { Memory(16).apply { write(0, this@Guid.bytes, 0, 16) } }

    override fun equals(other: Any?) = other is Guid && bytes.contentEquals(other.bytes)
    override fun hashCode() = bytes.contentHashCode()

    /** The registry form, "a1567595-4c2f-4574-a6fa-ecef917b9a40". */
    override fun toString(): String {
        val b = bytes.map { it.toInt() and 0xff }
        fun hex(vararg indices: Int) = indices.joinToString("") { "%02x".format(b[it]) }
        return "${hex(3, 2, 1, 0)}-${hex(5, 4)}-${hex(7, 6)}-${hex(8, 9)}-${hex(10, 11, 12, 13, 14, 15)}"
    }

    companion object {
        /** DEFINE_GUID's numbers, in the order the headers list them. */
        fun of(data1: Long, data2: Int, data3: Int, vararg data4: Int): Guid {
            require(data4.size == 8)
            val b = ByteArray(16)
            for (i in 0 until 4) b[i] = (data1 shr (8 * i)).toByte()
            for (i in 0 until 2) { b[4 + i] = (data2 shr (8 * i)).toByte(); b[6 + i] = (data3 shr (8 * i)).toByte() }
            for (i in 0 until 8) b[8 + i] = data4[i].toByte()
            return Guid(b)
        }

        /** The registry form, as in the IDL's uuid() attributes. */
        fun parse(text: String): Guid {
            val parts = text.trim('{', '}').split('-')
            require(parts.size == 5 && parts.map { it.length } == listOf(8, 4, 4, 4, 12)) { "Not a GUID: $text" }
            val tail = (parts[3] + parts[4]).chunked(2).map { it.toInt(16) }
            return of(parts[0].toLong(16), parts[1].toInt(16), parts[2].toInt(16), *tail.toIntArray())
        }
    }
}

/** PROPERTYKEY (wtypes.idl): a GUID and a DWORD property ID, 20 bytes. Passed by reference (REFPROPERTYKEY). */
internal class PropertyKey(val fmtid: Guid, val pid: Int) {
    val bytes: ByteArray get() = fmtid.bytes + ByteArray(4) { (pid shr (8 * it)).toByte() }
    val native: Memory by lazy { Memory(SIZE.toLong()).apply { write(0, bytes, 0, SIZE) } }

    override fun equals(other: Any?) = other is PropertyKey && fmtid == other.fmtid && pid == other.pid
    override fun hashCode() = fmtid.hashCode() * 31 + pid
    override fun toString() = "{$fmtid} $pid"

    companion object { const val SIZE = 20 }
}

/** Class and interface IDs, from mingw-w64's portabledeviceapi.h and portabledevicetypes.h (DEFINE_GUID). */
internal object Ids {
    val CLSID_PortableDeviceManager = Guid.of(0x0af10cec, 0x2ecd, 0x4b92, 0x95, 0x81, 0x34, 0xf6, 0xae, 0x06, 0x37, 0xf3)
    val CLSID_PortableDeviceFTM = Guid.of(0xf7c0039a, 0x4762, 0x488a, 0xb4, 0xb3, 0x76, 0x0e, 0xf9, 0xa1, 0xba, 0x9b)
    val CLSID_PortableDeviceValues = Guid.of(0x0c15d503, 0xd017, 0x47ce, 0x90, 0x16, 0x7b, 0x3f, 0x97, 0x87, 0x21, 0xcc)
    val CLSID_PortableDeviceKeyCollection = Guid.of(0xde2d022d, 0x2480, 0x43be, 0x97, 0xf0, 0xd1, 0xfa, 0x2c, 0xf9, 0x8f, 0x4f)
    val CLSID_PortableDevicePropVariantCollection = Guid.of(0x08a99e2f, 0x6d6d, 0x4b80, 0xaf, 0x5a, 0xba, 0xf2, 0xbc, 0xbe, 0x4c, 0xb9)

    val IID_IPortableDeviceManager = Guid.of(0xa1567595, 0x4c2f, 0x4574, 0xa6, 0xfa, 0xec, 0xef, 0x91, 0x7b, 0x9a, 0x40)
    val IID_IPortableDevice = Guid.of(0x625e2df8, 0x6392, 0x4cf0, 0x9a, 0xd1, 0x3c, 0xfa, 0x5f, 0x17, 0x77, 0x5c)
    val IID_IPortableDeviceValues = Guid.of(0x6848f6f2, 0x3155, 0x4f86, 0xb6, 0xf5, 0x26, 0x3e, 0xee, 0xab, 0x31, 0x43)
    val IID_IPortableDeviceKeyCollection = Guid.of(0xdada2357, 0xe0ad, 0x492e, 0x98, 0xdb, 0xdd, 0x61, 0xc5, 0x3b, 0xa3, 0x53)
    val IID_IPortableDevicePropVariantCollection = Guid.of(0x89b2e422, 0x4f1b, 0x4316, 0xbc, 0xef, 0xa4, 0x4a, 0xfe, 0xa8, 0x3e, 0xb3)
}

/** Property keys, content types and other values from mingw-w64's portabledevice.h. */
internal object Keys {
    /** The device itself, parent of its storages (WPD_DEVICE_OBJECT_ID). */
    const val DEVICE_OBJECT_ID = "DEVICE"

    private val OBJECT = Guid.of(0xef6b490d, 0x5cd8, 0x437a, 0xaf, 0xfc, 0xda, 0x8b, 0x60, 0xee, 0x4a, 0x3c)   // WPD_OBJECT_PROPERTIES_V1
    val OBJECT_ID = PropertyKey(OBJECT, 2)
    val OBJECT_PARENT_ID = PropertyKey(OBJECT, 3)
    val OBJECT_NAME = PropertyKey(OBJECT, 4)
    val OBJECT_FORMAT = PropertyKey(OBJECT, 6)
    val OBJECT_CONTENT_TYPE = PropertyKey(OBJECT, 7)
    val OBJECT_ISHIDDEN = PropertyKey(OBJECT, 9)
    val OBJECT_ISSYSTEM = PropertyKey(OBJECT, 10)
    val OBJECT_SIZE = PropertyKey(OBJECT, 11)
    val OBJECT_ORIGINAL_FILE_NAME = PropertyKey(OBJECT, 12)

    val FUNCTIONAL_OBJECT_CATEGORY = PropertyKey(Guid.of(0x8f052d93, 0xabca, 0x4fc5, 0xa5, 0xac, 0xb0, 0x1d, 0xf4, 0xdb, 0xe5, 0x98), 2)

    private val STORAGE = Guid.of(0x01a3057a, 0x74d6, 0x4e80, 0xbe, 0xa7, 0xdc, 0x4c, 0x21, 0x2c, 0xe5, 0x0a)   // WPD_STORAGE_OBJECT_PROPERTIES_V1
    val STORAGE_CAPACITY = PropertyKey(STORAGE, 4)
    val STORAGE_FREE_SPACE_IN_BYTES = PropertyKey(STORAGE, 5)
    val STORAGE_DESCRIPTION = PropertyKey(STORAGE, 7)

    private val CLIENT = Guid.of(0x204d9f0c, 0x2292, 0x4080, 0x9f, 0x42, 0x40, 0x66, 0x4e, 0x70, 0xf8, 0x59)   // WPD_CLIENT_INFORMATION_PROPERTIES_V1
    val CLIENT_NAME = PropertyKey(CLIENT, 2)
    val CLIENT_MAJOR_VERSION = PropertyKey(CLIENT, 3)
    val CLIENT_MINOR_VERSION = PropertyKey(CLIENT, 4)
    val CLIENT_REVISION = PropertyKey(CLIENT, 5)
    val CLIENT_SECURITY_QUALITY_OF_SERVICE = PropertyKey(CLIENT, 8)
    val CLIENT_DESIRED_ACCESS = PropertyKey(CLIENT, 9)

    private val DEVICE = Guid.of(0x26d4979a, 0xe643, 0x4626, 0x9e, 0x2b, 0x73, 0x6d, 0xc0, 0xc9, 0x2f, 0xdc)   // WPD_DEVICE_PROPERTIES_V1
    val DEVICE_PROTOCOL = PropertyKey(DEVICE, 6)
    val DEVICE_TYPE = PropertyKey(DEVICE, 15)

    val RESOURCE_DEFAULT = PropertyKey(Guid.of(0xe81e79be, 0x34f0, 0x41bf, 0xb5, 0x3f, 0xf1, 0xa0, 0x6a, 0xe8, 0x78, 0x42), 0)
    val RESOURCE_THUMBNAIL = PropertyKey(Guid.of(0xc7c407ba, 0x98fa, 0x46b5, 0x99, 0x60, 0x23, 0xfe, 0xc1, 0x24, 0xcf, 0xde), 0)

    val CONTENT_TYPE_FUNCTIONAL_OBJECT = Guid.of(0x99ed0160, 0x17ff, 0x4c44, 0x9d, 0x98, 0x1d, 0x7a, 0x6f, 0x94, 0x19, 0x21)
    val CONTENT_TYPE_FOLDER = Guid.of(0x27e2e392, 0xa111, 0x48e0, 0xab, 0x0c, 0xe1, 0x77, 0x05, 0xa0, 0x5f, 0x85)
    val CONTENT_TYPE_GENERIC_FILE = Guid.of(0x0085e0a6, 0x8d34, 0x45d7, 0xbc, 0x5c, 0x44, 0x7e, 0x59, 0xc7, 0x3d, 0x48)
    val FUNCTIONAL_CATEGORY_STORAGE = Guid.of(0x23f05bbc, 0x15de, 0x4c2a, 0xa5, 0x5b, 0xa9, 0xaf, 0x5c, 0xe4, 0x12, 0xef)
    /** MTP's "Undefined" object format (0x3000), what the Mac sends for every file too. */
    val OBJECT_FORMAT_UNSPECIFIED = Guid.of(0x30000000, 0xae6c, 0x4804, 0x98, 0xba, 0xc5, 0x7b, 0x46, 0x96, 0x5f, 0xe7)

    /** WPD_DEVICE_TYPES. */
    const val DEVICE_TYPE_MEDIA_PLAYER = 2
    const val DEVICE_TYPE_PHONE = 3

    /** DELETE_OBJECT_OPTIONS. */
    const val DELETE_NO_RECURSION = 0
    const val DELETE_WITH_RECURSION = 1
}
