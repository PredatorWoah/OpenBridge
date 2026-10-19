package org.openbridge.desktop.wpd

import org.openbridge.desktop.BridgeException
import java.util.Locale

/** What USB browsing says, in English and Simplified Chinese, like the app's `S`. */
internal object T {
    private val zh = Locale.getDefault().language == "zh"
    private fun t(en: String, cn: String) = if (zh) cn else en

    val unavailable = t("USB browsing isn't available on this computer.", "此电脑无法通过 USB 浏览手机。")
    val noWpd = t("USB browsing needs Windows Portable Devices, which this edition of Windows lacks. Install the Media Feature Pack from Windows Settings.",
        "通过 USB 浏览需要“Windows 便携设备”组件，此版本的 Windows 没有。请在 Windows 设置中安装“媒体功能包”。")
    val locked = t("Unlock the phone and choose File transfer in its USB notification.", "请解锁手机，并在 USB 通知中选择“文件传输”。")
    val lost = t("The phone was unplugged or stopped File transfer. Plug it in, unlock it and choose File transfer.",
        "手机已断开或已停止文件传输。请重新连接并解锁手机，然后选择“文件传输”。")
    val gone = t("That item is no longer on the phone. Refresh the folder.", "该项目已不在手机上。请刷新文件夹。")
    val full = t("Not enough space on the phone.", "手机存储空间不足。")
    val readOnly = t("This storage on the phone is read-only.", "手机上的这个存储空间是只读的。")
    val unsupported = t("The phone doesn't allow this over USB.", "手机不支持通过 USB 执行此操作。")
    val taken = t("A file or folder with that name already exists on the phone.", "手机上已有同名的文件或文件夹。")
    fun failed(code: Int) = t("The phone reported an error (0x%08X). Unplug it and try again.".format(code),
        "手机报告了错误（0x%08X）。请拔下后重试。".format(code))

    val storage = t("Storage", "存储空间")
    val unnamed = t("Phone", "手机")
    fun exists(name: String) = t("A file or folder named “$name” already exists on the phone.", "手机上已有名为“$name”的文件或文件夹。")
    fun existsHere(name: String) = t("$name already exists. Choose a new name; OpenBridge never replaces files.",
        "$name 已存在。请换个名称；OpenBridge 从不覆盖文件。")
    val invalidName = t("That name can't be used on the phone.", "手机上不能使用这个名称。")
    val tooLong = t("That name is too long for the phone.", "这个名称对手机来说太长了。")
    val chooseFile = t("Choose a file to download.", "请选择要下载的文件。")
    val chooseFolder = t("Choose a folder.", "请选择一个文件夹。")
    val regularFile = t("Choose a regular file.", "请选择普通文件。")
    val openStorage = t("Open a storage on the phone first.", "请先打开手机上的存储空间。")
    val storageFixed = t("The storage itself can't be deleted or renamed.", "存储空间本身无法删除或重命名。")
    val noPreview = t("No preview.", "无预览。")
    fun short(got: Long, total: Long) = t("The phone sent $got of $total bytes. Try again.", "手机只发送了 $got / $total 字节。请重试。")
    fun changed(name: String) = t("$name changed during the upload.", "上传期间 $name 发生了变化。")
    fun partialLeft(name: String) = t("A partial copy of “$name” may remain on the phone; delete it there before trying again.",
        "手机上可能留有“$name”的不完整副本；请先在手机上删除，再重试。")
}

/** HRESULTs, from mingw-w64's winerror.h and portabledevice.h, and what they mean for people. */
internal object Errors {
    const val E_POINTER = 0x80004003.toInt()
    const val E_FAIL = 0x80004005.toInt()
    const val E_NOTIMPL = 0x80004001.toInt()
    const val E_INVALIDARG = 0x80070057.toInt()
    const val E_ACCESSDENIED = 0x80070005.toInt()
    const val REGDB_E_CLASSNOTREG = 0x80040154.toInt()
    const val RPC_E_DISCONNECTED = 0x80010108.toInt()
    const val E_WPD_DEVICE_NOT_OPEN = 0x802A0002.toInt()
    const val E_WPD_DEVICE_IS_HUNG = 0x802A0006.toInt()

    /** HRESULT_FROM_WIN32. */
    fun win32(code: Int) = if (code <= 0) code else (code and 0xffff) or (7 shl 16) or 0x80000000.toInt()

    // Win32 error codes (winerror.h)
    private const val FILE_NOT_FOUND = 2
    private const val PATH_NOT_FOUND = 3
    private const val INVALID_HANDLE = 6
    private const val WRITE_PROTECT = 19
    private const val NOT_READY = 21
    private const val GEN_FAILURE = 31
    private const val SHARING_VIOLATION = 32
    private const val LOCK_VIOLATION = 33
    private const val HANDLE_DISK_FULL = 39
    private const val NOT_SUPPORTED = 50
    private const val DEV_NOT_EXIST = 55
    private const val FILE_EXISTS = 80
    private const val DISK_FULL = 112
    private const val SEM_TIMEOUT = 121
    private const val INVALID_NAME = 123
    private const val BUSY = 170
    private const val ALREADY_EXISTS = 183
    private const val NO_SUCH_DEVICE = 433
    private const val OPERATION_ABORTED = 995
    private const val IO_DEVICE = 1117
    private const val DEVICE_NOT_CONNECTED = 1167
    private const val NOT_FOUND = 1168
    private const val TIMEOUT = 1460
    private const val DEVICE_REMOVED = 1617
    private const val NOT_CONNECTED = 2250
    private const val DEVICE_NOT_AVAILABLE = 4319

    private val lostCodes = setOf(E_WPD_DEVICE_NOT_OPEN, E_WPD_DEVICE_IS_HUNG, RPC_E_DISCONNECTED) +
        listOf(INVALID_HANDLE, NOT_READY, GEN_FAILURE, DEV_NOT_EXIST, SEM_TIMEOUT, NO_SUCH_DEVICE, OPERATION_ABORTED,
            IO_DEVICE, DEVICE_NOT_CONNECTED, TIMEOUT, DEVICE_REMOVED, NOT_CONNECTED, DEVICE_NOT_AVAILABLE).map(::win32)
    private val lockedCodes = setOf(E_ACCESSDENIED) + listOf(BUSY, SHARING_VIOLATION, LOCK_VIOLATION).map(::win32)
    private val goneCodes = listOf(FILE_NOT_FOUND, PATH_NOT_FOUND, NOT_FOUND).map(::win32).toSet()
    private val fullCodes = listOf(DISK_FULL, HANDLE_DISK_FULL).map(::win32).toSet()
    private val takenCodes = listOf(FILE_EXISTS, ALREADY_EXISTS).map(::win32).toSet()
    private val unsupportedCodes = setOf(E_NOTIMPL, win32(NOT_SUPPORTED))

    /** The open device is no good any more: the phone was unplugged, locked or stopped answering. */
    fun lost(hr: Int) = hr in lostCodes

    /** A device that can't delete recursively says one of these; Android deletes a folder's contents anyway. */
    fun noRecursion(hr: Int) = hr in unsupportedCodes || hr == E_INVALIDARG

    fun message(hr: Int): String = when (hr) {
        in lostCodes -> T.lost
        in lockedCodes -> T.locked
        in goneCodes -> T.gone
        in fullCodes -> T.full
        in takenCodes -> T.taken
        in unsupportedCodes -> T.unsupported
        win32(WRITE_PROTECT) -> T.readOnly
        win32(INVALID_NAME) -> T.invalidName
        REGDB_E_CLASSNOTREG -> T.noWpd
        else -> T.failed(hr)
    }

    fun bridge(hr: Int) = BridgeException(message(hr), lost = lost(hr))
}
