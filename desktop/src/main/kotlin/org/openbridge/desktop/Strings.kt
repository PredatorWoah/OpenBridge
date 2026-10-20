package org.openbridge.desktop

import java.util.Locale

/** Everything the desktop app says, in English and Simplified Chinese (picked from the system language). */
object S {
    private val zh = Locale.getDefault().language == "zh"
    private fun t(en: String, cn: String) = if (zh) cn else en
    private val windows = Platform.windows

    val tagline = t("Your phone, on your computer. No account, no cloud.", "手机文件，直达电脑。无需账号，不经云端。")
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
    val cableHint = if (windows) t("Plug in your phone and unlock it. Choose File transfer to browse it here, or turn on USB debugging for Fast USB.",
        "连接手机并解锁。选择“文件传输”即可在这里浏览，或打开“USB 调试”使用高速 USB。")
        else t("Plug in your phone and unlock it, with USB debugging on, for Fast USB.", "连接手机并解锁，打开“USB 调试”即可使用高速 USB。")
    fun cableFound(name: String) = t("$name is connected by USB", "$name 已通过 USB 连接")
    val fileTransfer = t("File transfer", "文件传输")
    val openExplorer = t("Open in File Explorer", "在文件资源管理器中打开")
    val mirror = t("Mirror screen", "投屏控制")
    val mirrorHint = t("Mirroring and Fast USB need USB debugging: Settings › About phone › tap Build number 7 times, then Developer options › USB debugging.",
        "投屏和高速 USB 需要 USB 调试：设置 › 关于手机 › 连续点 7 次“版本号”，然后在开发者选项中打开“USB 调试”。")
    val mirrorStarting = t("Starting screen mirroring… If the phone asks to allow USB debugging, tap Allow.",
        "正在启动投屏… 如果手机询问是否允许 USB 调试，请点“允许”。")
    val mirrorMissing = if (windows) t("Screen mirroring isn't included in this copy of OpenBridge.", "此版本的 OpenBridge 未包含投屏组件。")
        else t("Screen mirroring needs scrcpy. Install it (for example: sudo apt install scrcpy), or use the OpenBridge download that includes it.",
            "投屏需要 scrcpy。请先安装（例如：sudo apt install scrcpy），或使用自带投屏组件的 OpenBridge 下载版本。")
    fun mirrorStopped(code: Int) = t("Screen mirroring stopped (code $code).", "投屏已停止（代码 $code）。")
    val disconnect = t("Disconnect", "断开连接")
    val close = t("Close", "关闭")
    val up = t("Up", "上一级")
    val refresh = t("Refresh", "刷新")
    val upload = t("Upload…", "上传…")
    val download = t("Download", "下载")
    fun downloadCount(count: Int) = t("Download $count", "下载 $count 项")
    val newFolder = t("New folder…", "新建文件夹…")
    val folderName = t("Folder name", "文件夹名称")
    val create = t("Create", "创建")
    val cancel = t("Cancel", "取消")
    val sendClipboard = t("Send clipboard to phone", "把剪贴板发到手机")
    val getClipboard = t("Get phone clipboard", "获取手机剪贴板")
    val sentClipboard = t("Sent to the phone's clipboard", "已发送到手机剪贴板")
    val gotClipboard = t("Copied the phone's clipboard", "已复制手机剪贴板")
    val emptyClipboard = t("The clipboard has no text.", "剪贴板中没有文字。")
    val sortName = t("Name", "名称")
    val sortSize = t("Size", "大小")
    val sortKind = t("Kind", "类型")
    val sort = t("Sort", "排序")
    val more = t("More", "更多")
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
    private val receivedPlace = if (windows) t("Downloads\\OpenBridge", "下载\\OpenBridge") else t("Downloads/OpenBridge", "下载/OpenBridge")
    fun received(name: String) = t("Received “$name” from the phone · in $receivedPlace", "已从手机收到“$name” · 保存在 $receivedPlace")
    val openReceived = t("Open received files", "打开收到的文件")
    val importNew = t("Import new photos", "导入新照片")
    val importTo = t("Import new photos to…", "导入新照片到…")
    val chooseImportFolder = t("Choose where to import photos", "选择照片的导入位置")
    val noCamera = t("This phone has no DCIM folder to import from.", "这部手机没有可导入的 DCIM 文件夹。")
    val noNewPhotos = t("No new photos or videos since the last import.", "自上次导入后没有新的照片或视频。")
    fun imported(count: Int) = t(if (count == 1) "Imported 1 photo or video" else "Imported $count photos and videos", "已导入 $count 个照片和视频")
    val mirrorWireless = t("Mirror over Wi‑Fi", "通过 Wi‑Fi 投屏")
    val mirrorWirelessStarting = t("Switching the phone to Wi‑Fi… Once the window opens, you can unplug the cable.",
        "正在把手机切换到 Wi‑Fi… 投屏窗口打开后，就可以拔掉数据线。")
    val forgotten = t("This phone no longer trusts this computer (it was forgotten on the phone, or OpenBridge was reinstalled). Pair again.",
        "这部手机已不再信任这台电脑（可能在手机上移除了它，或重新安装了 OpenBridge）。请重新配对。")
    val yourPhones = t("YOUR PHONES", "你的手机")
    val reconnects = t("Reconnects by itself while the phone is sharing", "手机共享时会自动重新连接")
    val nearbyNow = t("On this Wi‑Fi now", "当前在此 Wi‑Fi 中")
    val onCableNow = t("On the USB cable now", "当前已通过 USB 连接")
    val connect = t("Connect", "连接")
    val forget = t("Forget", "移除")
    fun forgetConfirm(name: String) = t("Forget $name? You'll need to pair with a code again.", "移除 $name？之后需要重新用数字配对。")
    val search = t("Search this folder (Enter: all folders)", "在此文件夹中搜索（回车：所有文件夹）")
    val typeToSearch = t("Type something to search for.", "请输入要搜索的内容。")
    val searching = t("Searching…", "正在搜索…")
    fun searchAll(text: String) = t("Search all folders for “$text”", "在所有文件夹中搜索“$text”")
    val nothingMatches = t("Nothing in this folder matches.", "此文件夹中没有匹配项。")
    fun foundCount(count: Int, more: Boolean, text: String) =
        if (count == 0) t("Nothing found for “$text”", "没有找到“$text”") else t("$count${if (more) "+" else ""} found for “$text”", "找到 $count${if (more) "+" else ""} 项“$text”")
    val searchStopped = t("Showing the first ones. Search a smaller folder or a longer name for the rest.", "只显示前面的结果。可以在更小的文件夹中搜索，或输入更长的名称。")
    val showInFolder2 = t("Show in folder", "在文件夹中显示")
    val open = t("Open", "打开")
    val folderGone = t("That folder is no longer on the phone.", "该文件夹已不在手机上。")
    fun goneFromPhone(name: String) = t("“$name” is no longer on the phone.", "“$name”已不在手机上。")
    fun alreadyHere(name: String) = t("$name already exists. Choose a new name; OpenBridge never replaces files.",
        "$name 已存在。请换个名称；OpenBridge 从不覆盖文件。")
    fun changedDuringUpload(name: String) = t("$name changed during the upload.", "$name 在上传过程中发生了变化。")
    val fileChanged = t("The file on the phone changed since the download started. Download it again.", "手机上的文件在下载开始后发生了变化。请重新下载。")
    val regularFile = t("Choose a regular file.", "请选择普通文件。")
    val tooBig = t("Files up to 64 GiB can be sent.", "最大可发送 64 GiB 的文件。")
    val showAll = t("All files", "所有文件")
    val showMedia = t("Photos and videos", "照片和视频")
    val delete = t("Delete…", "删除…")
    val rename = t("Rename…", "重命名…")
    val newName = t("New name", "新名称")
    fun deleteConfirm(count: Int, first: String) = if (count == 1) t("Delete “$first” from the phone? Folders are deleted with everything in them. This can't be undone.",
        "要从手机上删除“$first”吗？文件夹会连同里面的所有内容一起删除，且无法撤销。")
        else t("Delete $count items from the phone? Folders are deleted with everything in them. This can't be undone.",
        "要从手机上删除 $count 项吗？文件夹会连同里面的所有内容一起删除，且无法撤销。")
    fun deleted(count: Int) = t(if (count == 1) "Deleted" else "Deleted $count items", if (count == 1) "已删除" else "已删除 $count 项")
    fun folderSize(bytes: String, files: Long, complete: Boolean) = (if (complete) "" else "≥ ") + bytes + t(" · $files files", " · $files 个文件")
    val certificateChanged = t("The phone restarted sharing, so it has a new identity. Pair again.", "手机重新开始了共享，身份已更新。请重新配对。")
    fun unreachable(address: String) = t(
        "Couldn't reach the phone at $address. Check that sharing is on and both are on the same Wi‑Fi. If a VPN or proxy is on, allow local network (LAN) traffic or turn it off.",
        "无法连接到手机 $address。请确认手机正在共享，且两者连接同一个 Wi‑Fi。如果开着 VPN 或代理，请允许局域网流量或暂时关闭。")
    val dropped = t("The connection to the phone dropped.", "与手机的连接已断开。")
    val lostHint = t("Tap the sun on the phone and pair again. Unfinished transfers continue by themselves.",
        "请在手机上点一下太阳并重新配对，未完成的传输会自动继续。")
    val usbLostHint = t("Check the cable and that the phone is unlocked. Unfinished transfers continue when it's back.",
        "请检查数据线，并确认手机已解锁。手机重新连接后，未完成的传输会继续。")
    val badAddress = t("Enter the phone's Wi‑Fi address, like 192.168.1.23.", "请输入手机的 Wi‑Fi 地址，例如 192.168.1.23。")
    val badPort = t("Enter the port the phone shows (1–65535).", "请输入手机上显示的端口（1–65535）。")
    val pairingTampered = t("Pairing failed a security check. Someone may be interfering with the connection.", "配对未通过安全检查，可能有人在干扰连接。")
    val notFound = if (windows) t("Not seeing your phone? Type the address shown on the phone below. Windows also hides phones when this Wi‑Fi is set to Public: open Settings › Network & internet › Wi‑Fi, click your network and choose Private.",
        "看不到手机？请在下方输入手机上显示的地址。如果 Windows 把此 Wi‑Fi 设为“公用网络”，也会找不到手机：打开 设置 › 网络和 Internet › WLAN，点当前网络并选择“专用网络”。")
        else t("Not seeing your phone? Type the address shown on the phone below. A firewall on this computer can also hide phones: allow mDNS (UDP port 5353).",
            "看不到手机？请在下方输入手机上显示的地址。这台电脑上的防火墙也可能挡住手机：请允许 mDNS（UDP 端口 5353）。")

    // Window layouts
    val listView = t("Show as a list", "以列表显示")
    val gridView = t("Show as a grid", "以网格显示")
    val phones = t("Phones", "手机")
    val transfers = t("Transfers", "传输")
    fun doneCount(count: Int) = t(if (count == 1) "1 done" else "$count done", "已完成 $count 项")
    fun andMore(count: Int) = t("and $count more", "还有 $count 项")
    val paused = t("Paused", "已暂停")

    // Transports
    val wifi = t("Wi‑Fi", "Wi‑Fi")
    val fastUsb = t("Fast USB", "高速 USB")
    val usb = t("USB", "USB")
    fun connectedOver(name: String, transport: String) = t("Connected to $name over $transport", "已通过${transport}连接到 $name")
    fun switchedTo(transport: String) = t("Switched to $transport", "已切换到${transport}")
    val cableOut = t("The USB cable was unplugged.", "USB 数据线已拔出。")

    // Fast USB
    val pairOverUsb = t("Pair over USB", "通过 USB 配对")
    val fastUsbReady = t("Fast USB ready", "高速 USB 已就绪")
    val fastUsbNew = t("Not paired with this computer yet", "尚未与这台电脑配对")
    val allowDebugging = t("Allow USB debugging on the phone (tap Allow when it asks).", "请在手机上允许 USB 调试（询问时点“允许”）。")
    val noPermission = t("Linux needs permission to use this phone: install the android-sdk-platform-tools-common (or android-udev-rules) package, then plug it in again.",
        "Linux 需要权限才能使用这部手机：请安装 android-sdk-platform-tools-common（或 android-udev-rules）软件包，然后重新插入手机。")
    val startingOnPhone = t("Starting OpenBridge on the phone…", "正在手机上启动 OpenBridge…")
    val notSharing = t("Open OpenBridge on the phone and tap the sun.", "请在手机上打开 OpenBridge 并点一下太阳。")
    val noApp = t("Install OpenBridge on the phone for Fast USB.", "请在手机上安装 OpenBridge 以使用高速 USB。")

    // Wi‑Fi that keeps devices apart
    val isolated = t("This Wi‑Fi keeps devices apart (common on guest, hotel and campus networks). On the phone, open OpenBridge › Settings › Phone hotspot and join that network from this computer, or use a USB cable.",
        "此 Wi‑Fi 会把设备相互隔开（访客、酒店和校园网络常见）。请在手机上打开 OpenBridge › 设置 › 手机热点，让这台电脑连接该热点，或使用 USB 数据线。")

    // Texts
    val texts = t("Texts", "文字")
    val textsHint = t("Text you send arrives on the phone's clipboard. Text sent from the phone lands on this computer's clipboard.",
        "发送的文字会放到手机的剪贴板上；手机发来的文字会放到这台电脑的剪贴板上。")
    val typeText = t("Type or paste text for the phone", "输入或粘贴要发到手机的文字")
    val send = t("Send", "发送")
    val copy = t("Copy", "复制")
    val copied = t("Copied", "已复制")
    val clear = t("Clear", "清除")
    val fromPhone = t("From phone", "来自手机")
    val toPhone = t("To phone", "发往手机")
    val noTexts = t("No texts yet.", "还没有文字。")
    val autoSend = t("Send copied text to the phone automatically", "自动把复制的文字发到手机")
    val autoSendHint = t("While connected, text you copy on this computer goes to the phone's clipboard.", "连接期间，在这台电脑上复制的文字会发送到手机的剪贴板。")
    fun textFrom(name: String) = t("Text from $name copied", "已复制来自 $name 的文字")
    val sentText = t("Sent to the phone", "已发送到手机")
    val tooLong = t("That text is too long for the phone's clipboard.", "文字太长，无法放到手机的剪贴板。")
    val textsOld = t("Texts from the phone need OpenBridge 0.13 or later on the phone.", "接收手机发来的文字需要手机上的 OpenBridge 0.13 或更高版本。")

    // Phone app version
    fun phoneOlder(phone: String, computer: String) = t("The phone has OpenBridge $phone; this computer has $computer. Update it on the phone for texts and Fast USB.",
        "手机上的 OpenBridge 是 $phone，这台电脑上的是 $computer。请在手机上更新，以使用文字和高速 USB。")
    fun phoneOld(computer: String) = t("The phone has an older OpenBridge than this computer ($computer). Update it on the phone for texts and Fast USB.",
        "手机上的 OpenBridge 比这台电脑上的（$computer）旧。请在手机上更新，以使用文字和高速 USB。")

    // Updates
    fun update(version: String) = t("Update available: $version", "有新版本：$version")
    val updateNow = t("Update", "更新")
    val whatsNew = t("What's new", "更新内容")
    fun downloading(percent: Int) = t("Downloading… $percent%", "正在下载… $percent%")
    val verifying = t("Checking the download…", "正在校验下载…")
    val openingInstaller = t("Opening the installer…", "正在打开安装程序…")
    val debOpened = t("The software installer opens the update. Restart OpenBridge when it's done.", "软件安装器会打开更新，安装完成后请重新启动 OpenBridge。")
    fun archiveSaved(name: String) = t("Downloaded and checked $name. Unpack it in place of this copy, then start it again.",
        "已下载并校验 $name。请解压后替换当前版本，然后重新启动。")
    val updateFailed = t("The update couldn't be downloaded. Opening the release page instead.", "无法下载更新，已改为打开发布页面。")
    val checksumFailed = t("The download didn't match its published checksum, so it wasn't installed.", "下载的文件与公布的校验值不符，因此未安装。")
    val checkUpdates = t("Check for updates", "检查更新")
    val checking = t("Checking…", "正在检查…")
    val upToDate = t("OpenBridge is up to date.", "OpenBridge 已是最新版本。")
    val checkFailed = t("Couldn't check for updates. Try again later.", "无法检查更新，请稍后再试。")

    // Settings
    val settings = t("Settings", "设置")
    val look = t("LOOK", "外观")
    val preview = t("Preview", "预览")
    val about = t("ABOUT", "关于")
    val textsSection = t("TEXTS", "文字")
    val updatesSection = t("UPDATES", "更新")
    val help = t("HELP", "帮助")
    fun version(version: String) = t("Version $version", "版本 $version")
    val aboutLine = t("Free and open source. No account, no cloud, no tracking.", "免费开源。无需账号，不经云端，没有跟踪。")
    val github = t("OpenBridge on GitHub", "GitHub 上的 OpenBridge")
    val copyDiagnostics = t("Copy diagnostics", "复制诊断信息")
    val diagnosticsHint = t("Copies versions, the connection and recent events (never file contents or secrets), to paste into a bug report.",
        "复制版本、连接情况和最近的事件（不含文件内容或密钥），可粘贴到问题报告中。")
    val diagnosticsCopied = t("Diagnostics copied", "诊断信息已复制")
    val on = t("On", "开")
    val off = t("Off", "关")
}
