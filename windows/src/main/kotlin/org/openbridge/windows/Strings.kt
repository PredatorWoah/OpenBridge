package org.openbridge.windows

import java.util.Locale

/** Everything the Windows app says, in English and Simplified Chinese (picked from Windows' language). */
object S {
    private val zh = Locale.getDefault().language == "zh"
    private fun t(en: String, cn: String) = if (zh) cn else en

    val tagline = t("Your phone, on your PC. No account, no cloud.", "手机文件，直达电脑。无需账号，不经云端。")
    val waiting = t("Waiting for your phone", "正在等待手机")
    val waitingHint = t("Tap the sun in OpenBridge on your phone, or plug it in with a cable.", "在手机上的 OpenBridge 中点一下太阳，或用数据线连接。")
    val connected = t("Connected over Wi‑Fi", "已通过 Wi‑Fi 连接")
    val nearby = t("PHONES NEARBY", "附近的手机")
    val looking = t("Looking for phones sharing on this Wi‑Fi…", "正在查找此 Wi‑Fi 中正在共享的手机…")
    val orType = t("OR TYPE THE ADDRESS", "或输入地址")
    val address = t("Phone address, like 192.168.1.23", "手机地址，例如 192.168.1.23")
    val port = t("Port", "端口")
    val pair = t("Pair", "配对")
    val pairing = t("Connecting to the phone…", "正在连接手机…")
    val compare = t("Codes match? Tap Pair on the phone.", "两边的数字一样吗？请在手机上点“配对”。")
    val cable = t("USB CABLE", "USB 数据线")
    val cableHint = t("Plug in your phone, unlock it and choose File transfer. Windows opens it in File Explorer, like a USB drive.",
        "连接手机并解锁，在 USB 通知中选择“文件传输”。Windows 会在文件资源管理器中打开它，就像 U 盘一样。")
    fun cableFound(name: String) = t("$name is connected by USB", "$name 已通过 USB 连接")
    val openExplorer = t("Open in File Explorer", "在文件资源管理器中打开")
    val mirror = t("Mirror screen", "投屏控制")
    val mirrorHint = t("Needs USB debugging: Settings › About phone › tap Build number 7 times, then Developer options › USB debugging.",
        "需要 USB 调试：设置 › 关于手机 › 连续点 7 次“版本号”，然后在开发者选项中打开“USB 调试”。")
    val mirrorStarting = t("Starting screen mirroring… If the phone asks to allow USB debugging, tap Allow.",
        "正在启动投屏… 如果手机询问是否允许 USB 调试，请点“允许”。")
    val mirrorMissing = t("Screen mirroring isn't included in this copy of OpenBridge.", "此版本的 OpenBridge 未包含投屏组件。")
    val disconnect = t("Disconnect", "断开连接")
    val up = t("Up", "上一级")
    val refresh = t("Refresh", "刷新")
    val upload = t("Upload…", "上传…")
    val download = t("Download", "下载")
    fun downloadCount(count: Int) = t("Download $count", "下载 $count 项")
    val newFolder = t("New folder…", "新建文件夹…")
    val folderName = t("Folder name", "文件夹名称")
    val create = t("Create", "创建")
    val cancel = t("Cancel", "取消")
    val sendClipboard = t("Send PC clipboard to phone", "把电脑剪贴板发到手机")
    val getClipboard = t("Copy phone clipboard to PC", "把手机剪贴板复制到电脑")
    val sentClipboard = t("Sent to the phone's clipboard", "已发送到手机剪贴板")
    val gotClipboard = t("Copied the phone's clipboard", "已复制手机剪贴板")
    val emptyClipboard = t("The clipboard has no text.", "剪贴板中没有文字。")
    val sortName = t("Name", "名称")
    val sortSize = t("Size", "大小")
    val sortKind = t("Kind", "类型")
    val emptyFolder = t("This folder is empty. Drop files here to upload them.", "此文件夹为空。把文件拖到这里即可上传。")
    val dropHere = t("Drop to upload", "松开即可上传")
    val resume = t("Resume", "继续")
    val done = t("Done", "完成")
    val saved = t("Saved", "已保存")
    val showInFolder = t("Show in folder", "在文件夹中显示")
    val uploaded = t("Uploaded to the phone", "已上传到手机")
    fun ofCount(index: Int, count: Int) = t("$index of $count", "第 $index / $count 项")
    val chooseFolder = t("Choose a folder for the download", "选择下载位置")
    val chooseFolderButton = t("Save here", "保存到这里")
    val phone = t("Phone", "手机")
    fun continuing(name: String) = t("Continuing “$name”", "继续传输“$name”")
    val cancelled = t("Cancelled.", "已取消。")
}
