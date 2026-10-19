package org.openbridge.desktop.wpd

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.lang.ref.Reference

// The WPD interfaces, as thin wrappers over their vtables. Slot numbers follow the method order in mingw-w64's
// portabledeviceapi.idl / portabledevicetypes.idl (and the C vtables in the generated .h files), after IUnknown's
// slots 0–2. Strings go in as LPCWSTR (WString); strings the API hands back are CoTaskMemFree'd.

private const val S_FALSE = 1

private fun out(call: String, invoke: (PointerByReference) -> Int): Pointer {
    val result = PointerByReference()
    check(invoke(result), call)
    return result.value ?: throw ComException(Errors.E_POINTER, call)
}

/** IPortableDeviceManager: the portable devices Windows knows about. */
internal class DeviceManager(pointer: Pointer) : Unknown(pointer) {
    /** Slot 3, GetDevices: once for the count, then for the IDs. */
    fun devices(): List<String> {
        val count = IntByReference(0)
        check(invoke(3, null, count), "GetDevices")
        if (count.value <= 0) return emptyList()
        val capacity = count.value
        val ids = Memory(capacity.toLong() * Native.POINTER_SIZE).apply { clear() }
        check(invoke(3, ids, count), "GetDevices")
        val got = count.value.coerceIn(0, capacity)
        return (0 until capacity).mapNotNull { i ->
            val p = ids.getPointer(i.toLong() * Native.POINTER_SIZE)
            if (i < got) Com.take(p) else { Com.free(p); null }
        }
    }

    /** Slot 4, RefreshDeviceList: the list is a snapshot from when the manager was made. */
    fun refresh() = check(invoke(4), "RefreshDeviceList")

    fun friendlyName(id: String) = text(5, id, "GetDeviceFriendlyName")
    fun description(id: String) = text(6, id, "GetDeviceDescription")
    fun manufacturer(id: String) = text(7, id, "GetDeviceManufacturer")

    /** Slots 5–7 fill a caller's WCHAR buffer; asked first with none, they give the size in characters. */
    private fun text(slot: Int, id: String, call: String): String {
        val device = WString(id)
        val size = IntByReference(0)
        check(invoke(slot, device, null, size), call)
        if (size.value <= 0) return ""
        val buffer = Memory((size.value + 1L) * Native.WCHAR_SIZE).apply { clear() }
        check(invoke(slot, device, buffer, size), call)
        return buffer.getWideString(0).trim()
    }
}

/** IPortableDevice: one open connection to a device. */
internal class Device(pointer: Pointer) : Unknown(pointer) {
    /** Slot 3, Open. Returns the HRESULT, so a refusal can be retried read-only. */
    fun open(id: String, client: Values): Int = invoke(3, WString(id), client.pointer)
    /** Slot 5, Content. */
    fun content() = Content(out("Content") { invoke(5, it) })
    /** Slot 8, Close. Releasing the object afterwards ([close]) is still needed. */
    fun disconnect() { invoke(8) }
}

/** IPortableDeviceContent: objects on the device. */
internal class Content(pointer: Pointer) : Unknown(pointer) {
    /** Slot 3, EnumObjects: the children of `parent`, no filter. */
    fun children(parent: String) = ObjectIds(out("EnumObjects") { invoke(3, 0, WString(parent), null, it) })
    /** Slot 4, Properties. */
    fun properties() = Properties(out("Properties") { invoke(4, it) })
    /** Slot 5, Transfer. */
    fun transfer() = Resources(out("Transfer") { invoke(5, it) })

    /** Slot 6, CreateObjectWithPropertiesOnly: a folder. Returns the new object's ID. */
    fun createWithPropertiesOnly(values: Values): String {
        val id = PointerByReference()
        check(invoke(6, values.pointer, id), "CreateObjectWithPropertiesOnly")
        return Com.take(id.value) ?: throw ComException(Errors.E_POINTER, "CreateObjectWithPropertiesOnly")
    }

    /**
     * Slot 7, CreateObjectWithPropertiesAndData: a stream to write the file into. The object only exists once the
     * stream is committed; Revert abandons it. Returns the stream and the driver's preferred write size.
     */
    fun createWithData(values: Values): Pair<Stream, Int> {
        val optimal = IntByReference(0)
        val stream = out("CreateObjectWithPropertiesAndData") { invoke(7, values.pointer, it, optimal, null) }
        return Stream(stream) to optimal.value
    }

    /** Slot 8, Delete. Returns the HRESULT, so a device that can't recurse can be asked again without. */
    fun delete(options: Int, ids: PropVariantCollection): Int = invoke(8, options, ids.pointer, null)
}

/** IEnumPortableDeviceObjectIDs. */
internal class ObjectIds(pointer: Pointer) : Unknown(pointer) {
    /** Slot 3, Next: up to `count` IDs, and whether the enumeration is over (S_FALSE, or none left). */
    fun next(count: Int): Pair<List<String>, Boolean> {
        val ids = Memory(count.toLong() * Native.POINTER_SIZE).apply { clear() }
        val fetched = IntByReference(0)
        val hr = invoke(3, count, ids, fetched)
        check(hr, "IEnumPortableDeviceObjectIDs::Next")
        val got = fetched.value.coerceIn(0, count)
        val list = (0 until got).mapNotNull { Com.take(ids.getPointer(it.toLong() * Native.POINTER_SIZE)) }
        return list to (hr == S_FALSE || got == 0)
    }
}

/** IPortableDeviceProperties. */
internal class Properties(pointer: Pointer) : Unknown(pointer) {
    /** Slot 5, GetValues: the requested properties of one object. */
    fun values(id: String, keys: KeyCollection) = Values(out("GetValues") { invoke(5, WString(id), keys.pointer, it) })

    /**
     * Slot 6, SetValues. Returns the call's HRESULT (S_FALSE when some property failed) and the per-property
     * results, each a VT_ERROR, when the driver gave them.
     */
    fun setValues(id: String, values: Values): Pair<Int, Values?> {
        val results = PointerByReference()
        val hr = invoke(6, WString(id), values.pointer, results)
        val wrapped = results.value?.let(::Values)
        if (hr < 0) { wrapped?.close(); throw ComException(hr, "SetValues") }
        return hr to wrapped
    }
}

/** IPortableDeviceResources: an object's data streams. */
internal class Resources(pointer: Pointer) : Unknown(pointer) {
    /** Slot 5, GetStream. Returns the stream and the driver's preferred read size. */
    fun stream(id: String, key: PropertyKey, mode: Int): Pair<Stream, Int> {
        val optimal = IntByReference(0)
        val stream = out("GetStream") { invoke(5, WString(id), key.native, mode, optimal, it) }
        return Stream(stream) to optimal.value
    }

    companion object { const val STGM_READ = 0 }   // objbase.h
}

/** IStream (objidlbase.idl): ISequentialStream's Read and Write at 3–4, then Seek … Clone at 5–13. */
internal class Stream(pointer: Pointer) : Unknown(pointer) {
    /** Slot 3, Read: bytes read into `buffer`; 0 at the end. */
    fun read(buffer: Memory, count: Int): Int {
        val read = IntByReference(0)
        check(invoke(3, buffer, count, read), "IStream::Read")
        return read.value.coerceIn(0, count)
    }

    /** Slot 4, Write, repeated until the driver took all `count` bytes. */
    fun write(buffer: Memory, count: Int) {
        var done = 0
        val written = IntByReference(0)
        while (done < count) {
            written.value = 0
            check(invoke(4, buffer.share(done.toLong()), count - done, written), "IStream::Write")
            if (written.value <= 0) throw ComException(Errors.E_FAIL, "IStream::Write")
            done += written.value
        }
    }

    /** Slot 8, Commit (STGC_DEFAULT). */
    fun commit() = check(invoke(8, 0), "IStream::Commit")
    /** Slot 9, Revert. */
    fun revert() = check(invoke(9), "IStream::Revert")
}

/** IPortableDeviceValues: a property bag, used for requests and answers alike. */
internal class Values(pointer: Pointer) : Unknown(pointer) {
    /** Slot 6, GetValue, read as a whole number of any integer type (or VT_BOOL); null when absent or not one. */
    fun number(key: PropertyKey): Long? {
        val variant = Memory(PropVariant.size()).apply { clear() }
        if (invoke(6, key.native, variant) < 0) return null
        try { return PropVariant.number(variant.getShort(0), variant.getLong(PropVariant.VALUE_OFFSET)) }
        finally { Com.clear(variant) }
    }

    /** Slot 7, SetStringValue. */
    fun setString(key: PropertyKey, value: String) = check(invoke(7, key.native, WString(value)), "SetStringValue")

    /** Slot 8, GetStringValue; null when absent. */
    fun string(key: PropertyKey): String? {
        val value = PointerByReference()
        return if (invoke(8, key.native, value) >= 0) Com.take(value.value) else null
    }

    /** Slot 9, SetUnsignedIntegerValue. */
    fun setUnsigned(key: PropertyKey, value: Int) = check(invoke(9, key.native, value), "SetUnsignedIntegerValue")
    /** Slot 13, SetUnsignedLargeIntegerValue. */
    fun setUnsignedLarge(key: PropertyKey, value: Long) = check(invoke(13, key.native, value), "SetUnsignedLargeIntegerValue")

    /** Slot 20, GetErrorValue: a per-property result after SetValues; null when absent. */
    fun error(key: PropertyKey): Int? {
        val value = IntByReference(0)
        return if (invoke(20, key.native, value) >= 0) value.value else null
    }

    /** Slot 27, SetGuidValue. */
    fun setGuid(key: PropertyKey, value: Guid) = check(invoke(27, key.native, value.native), "SetGuidValue")

    /** Slot 28, GetGuidValue; null when absent. */
    fun guid(key: PropertyKey): Guid? {
        val value = Memory(16).apply { clear() }
        return if (invoke(28, key.native, value) >= 0) Guid(value.getByteArray(0, 16)) else null
    }

    companion object {
        fun create() = Com.create(Ids.CLSID_PortableDeviceValues, Ids.IID_IPortableDeviceValues, ::Values)
    }
}

/** IPortableDeviceKeyCollection: which properties to ask for. */
internal class KeyCollection(pointer: Pointer) : Unknown(pointer) {
    /** Slot 5, Add. */
    fun add(key: PropertyKey) = check(invoke(5, key.native), "IPortableDeviceKeyCollection::Add")

    companion object {
        fun of(vararg keys: PropertyKey): KeyCollection {
            val collection = Com.create(Ids.CLSID_PortableDeviceKeyCollection, Ids.IID_IPortableDeviceKeyCollection, ::KeyCollection)
            try { keys.forEach(collection::add) } catch (e: Throwable) { collection.close(); throw e }
            return collection
        }
    }
}

/** IPortableDevicePropVariantCollection: object IDs for Delete. */
internal class PropVariantCollection(pointer: Pointer) : Unknown(pointer) {
    /** Slot 5, Add, with a VT_LPWSTR PROPVARIANT that the collection copies. */
    fun add(text: String) {
        val chars = Com.wide(text)
        val variant = Memory(PropVariant.size()).apply {
            clear(); setShort(0, PropVariant.VT_LPWSTR); setPointer(PropVariant.VALUE_OFFSET, chars)
        }
        try { check(invoke(5, variant), "IPortableDevicePropVariantCollection::Add") }
        finally { Reference.reachabilityFence(chars) }   // the variant only points at it
    }

    companion object {
        fun of(vararg texts: String): PropVariantCollection {
            val collection = Com.create(Ids.CLSID_PortableDevicePropVariantCollection, Ids.IID_IPortableDevicePropVariantCollection, ::PropVariantCollection)
            try { texts.forEach(collection::add) } catch (e: Throwable) { collection.close(); throw e }
            return collection
        }
    }
}
