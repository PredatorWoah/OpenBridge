package org.openbridge.desktop.wpd

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/** A failed COM call, by its HRESULT. Turned into a message for people by [Errors]. */
internal class ComException(val hr: Int, call: String) : IOException("$call failed (0x%08X)".format(hr)) {
    /** The phone went away (unplugged, locked, switched off File transfer): the open device is no good any more. */
    val lost: Boolean get() = Errors.lost(hr)
}

internal fun check(hr: Int, call: String) { if (hr < 0) throw ComException(hr, call) }

/** The few COM runtime functions needed, from ole32.dll. Windows only. */
internal object Com {
    val windows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

    // COM methods and these APIs are __stdcall on 32-bit Windows; JNA ignores the request on 64-bit, where there is
    // one convention.
    const val CONVENTION = Function.ALT_CONVENTION
    private const val COINIT_MULTITHREADED = 0       // objbase.h
    private const val CLSCTX_INPROC_SERVER = 1       // wtypesbase.idl

    private val coInitializeEx by lazy { Function.getFunction("ole32", "CoInitializeEx", CONVENTION) }
    private val coCreateInstance by lazy { Function.getFunction("ole32", "CoCreateInstance", CONVENTION) }
    private val coTaskMemFree by lazy { Function.getFunction("ole32", "CoTaskMemFree", CONVENTION) }
    private val propVariantClear by lazy { Function.getFunction("ole32", "PropVariantClear", CONVENTION) }

    fun initialize() = check(coInitializeEx.invokeInt(arrayOf(null, COINIT_MULTITHREADED)), "CoInitializeEx")

    fun <T : Unknown> create(clsid: Guid, iid: Guid, wrap: (Pointer) -> T): T {
        val result = PointerByReference()
        check(coCreateInstance.invokeInt(arrayOf(clsid.native, null, CLSCTX_INPROC_SERVER, iid.native, result)), "CoCreateInstance")
        return wrap(result.value ?: throw ComException(Errors.E_POINTER, "CoCreateInstance"))
    }

    /** Frees memory the API allocated for us, like the strings it returns. */
    fun free(pointer: Pointer?) { if (pointer != null) coTaskMemFree.invokeVoid(arrayOf(pointer)) }

    /** Takes a string the API allocated and frees it. */
    fun take(pointer: Pointer?): String? = pointer?.let { try { it.getWideString(0) } finally { free(it) } }

    fun clear(variant: Pointer) { propVariantClear.invokeInt(arrayOf(variant)) }

    /** A NUL-terminated UTF-16 copy of `text`, for a PROPVARIANT we fill ourselves (never PropVariantClear'd). */
    fun wide(text: String): Memory = Memory((text.length + 1L) * Native.WCHAR_SIZE).apply { setWideString(0, text) }
}

/**
 * PROPVARIANT (propidl.idl): VARTYPE then three reserved WORDs, then the value union at offset 8; 24 bytes on 64-bit
 * Windows (the union is two pointers wide there), 16 on 32-bit.
 */
internal object PropVariant {
    const val VALUE_OFFSET = 8L
    fun size(pointerSize: Int = Native.POINTER_SIZE): Long = if (pointerSize == 8) 24 else 16

    // VARENUM, wtypes.idl
    const val VT_EMPTY: Short = 0
    const val VT_I2: Short = 2
    const val VT_I4: Short = 3
    const val VT_BOOL: Short = 11
    const val VT_I1: Short = 16
    const val VT_UI1: Short = 17
    const val VT_UI2: Short = 18
    const val VT_UI4: Short = 19
    const val VT_I8: Short = 20
    const val VT_UI8: Short = 21
    const val VT_INT: Short = 22
    const val VT_UINT: Short = 23
    const val VT_LPWSTR: Short = 31

    /** A whole number in a PROPVARIANT read back as bytes (offset 0 = vt), or null for other types. */
    fun number(vt: Short, value: Long): Long? = when (vt) {
        VT_I1 -> value.toByte().toLong()
        VT_UI1 -> value and 0xff
        VT_I2 -> value.toShort().toLong()
        VT_UI2 -> value and 0xffff
        VT_I4, VT_INT -> value.toInt().toLong()
        VT_UI4, VT_UINT -> value and 0xffff_ffffL
        VT_I8, VT_UI8 -> value
        VT_BOOL -> if (value.toShort().toInt() != 0) 1 else 0
        else -> null
    }
}

/**
 * Every WPD call runs on this one thread, which joined COM's multithreaded apartment once. Interface pointers made
 * here are only ever used here, and callers on any thread simply wait for their turn. Long transfers go chunk by
 * chunk, so browsing and previews can slip in between.
 */
internal object ComThread {
    @Volatile private var worker: Thread? = null
    private var failure: Throwable? = null
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread({
            failure = runCatching { Com.initialize() }.exceptionOrNull()
            task.run()
        }, "OpenBridge USB").apply { isDaemon = true; worker = this }
    }

    fun <T> run(work: () -> T): T {
        if (Thread.currentThread() === worker) {
            failure?.let { throw it }
            return work()
        }
        val future = executor.submit(Callable { failure?.let { throw it }; work() })
        try { return future.get() }
        catch (e: ExecutionException) { throw e.cause ?: e }
    }
}

/**
 * An interface pointer and its vtable. The first three slots are IUnknown's (QueryInterface, AddRef, Release); each
 * wrapper names the slots after them in the header's order. [close] releases the reference once.
 */
internal open class Unknown(pointer: Pointer) : AutoCloseable {
    private var self: Pointer? = pointer
    val pointer: Pointer get() = self ?: throw IllegalStateException("Interface already released")

    protected fun invoke(slot: Int, vararg arguments: Any?): Int = call(pointer, slot, arguments)

    override fun close() {
        val p = self ?: return
        self = null
        call(p, 2, emptyArray())   // IUnknown::Release
    }

    private fun call(p: Pointer, slot: Int, arguments: Array<out Any?>): Int {
        val function = p.getPointer(0).getPointer(slot.toLong() * Native.POINTER_SIZE)
        return Function.getFunction(function, Com.CONVENTION).invokeInt(arrayOf(p, *arguments))
    }
}
