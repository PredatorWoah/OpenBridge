package org.openbridge.android

import java.util.Locale

/** Everything the phone app says, in English and Simplified Chinese (picked from the phone's language). */
object S {
    private val zh get() = Locale.getDefault().language == "zh"
    private fun t(en: String, cn: String) = if (zh) cn else en

    // Main screen
    val byCable get() = t("Connected to your computer by cable", "已通过数据线连接电脑")
    fun connectedTo(computer: String?) = if (computer == null) t("Your computer is connected", "电脑已连接")
        else t("$computer is connected", "$computer 已连接")
    val waiting get() = t("Waiting for your computer", "正在等待电脑")
    val starting get() = t("Starting…", "正在启动…")
    val tapToShare get() = t("Tap to share with your computer", "点一下，与电脑共享")
    val startSharing get() = t("Start sharing", "开始共享")
    val settings get() = t("Settings", "设置")
    fun via(via: Via) = when (via) {
        Via.WIFI -> "Wi‑Fi"
        Via.TETHER -> t("USB tethering", "USB 网络共享")
        Via.HOTSPOT -> t("Hotspot", "热点")
        Via.USB -> fastUsb
    }
    val change get() = t("Change", "更改")
    fun shares(what: String) = t("Shares: $what", "共享：$what")
    val fastUsbReady get() = t("Cable connected with USB debugging: OpenBridge on your computer can connect over it (Fast USB).",
        "数据线已连接并打开了 USB 调试：电脑上的 OpenBridge 可以通过它连接（高速 USB）。")
    val copy get() = t("Copy", "复制")
    val copied get() = t("Copied", "已复制")
    fun copyThing(what: String) = t("Copy $what", "复制$what")
    fun codeForReader(code: String) = t("Code ${code.toList().joinToString(" ")}", "数字 ${code.toList().joinToString(" ")}")

    // Cards on the main screen
    val noTrustedShort get() = t("None yet", "还没有")
    fun connectedNow(via: Via?) = if (via == null) t("Connected now", "现在已连接") else t("Connected now · ${via(via)}", "现在已连接 · ${via(via)}")
    fun forgetComputer(name: String) = t("Forget $name", "移除 $name")
    val sendToComputer get() = t("Send to computer", "发送到电脑")
    val nothingWaiting get() = t("Share files or text to OpenBridge, or type here", "把文件或文字分享到 OpenBridge，或在这里输入")
    fun waitingItems(files: Int, texts: Int) = when {
        texts == 0 -> t(if (files == 1) "1 file waiting" else "$files files waiting", "$files 个文件等待发送")
        files == 0 -> t(if (texts == 1) "1 text waiting" else "$texts texts waiting", "$texts 段文字等待发送")
        else -> t("$files files and $texts texts waiting", "$files 个文件和 $texts 段文字等待发送")
    }
    val aText get() = t("Text", "文字")
    fun andMore(count: Int) = t("and $count more", "还有 $count 项")
    val collectedSoon get() = t("Your computer collects these while it's connected.", "电脑连接时会自动接收。")
    val clearWaiting get() = t("Clear everything waiting", "清除所有等待中的内容")
    val textForComputer get() = t("Text for your computer", "要发送到电脑的文字")
    val sendText get() = t("Send", "发送")
    val texts get() = t("Texts", "文字")
    val noTexts get() = t("Nothing sent or received yet", "还没有发送或收到文字")
    fun textCount(count: Int) = t(if (count == 1) "1 text" else "$count texts", "$count 段文字")
    val textsHint get() = t("Texts you send to your computer, and texts it sends here, are kept here (the last 30).",
        "发送到电脑的文字和电脑发来的文字会保存在这里（最近 30 条）。")
    fun fromComputer(computer: String?) = if (computer == null) t("From computer", "来自电脑") else t("From $computer", "来自 $computer")
    val toComputer get() = t("To computer", "发到电脑")
    val clearTexts get() = t("Clear texts", "清除文字记录")

    // Settings
    val look get() = t("Look", "外观")
    fun lookName(look: org.openbridge.ui.Look) = t(look.label, look.labelZh)
    val preview get() = t("Preview", "预览")
    val whatToShare get() = t("What to share", "共享内容")
    val stopToChange get() = t("Stop sharing to change what's shared.", "停止共享后才能更改共享内容。")
    val phoneHotspot get() = t("Phone hotspot", "手机热点")
    val hotspotExplain get() = t("For Wi‑Fi that keeps devices apart (guest, hotel and campus networks): the phone makes its own network for your computer to join.",
        "适用于不让设备互相连接的 Wi‑Fi（访客、酒店和校园网络）：手机会建立自己的网络，让电脑加入。")
    val turnOnHotspot get() = t("Turn on hotspot", "开启热点")
    val turnOffHotspot get() = t("Turn off hotspot", "关闭热点")
    val hotspotJoin get() = t("Join this network from your computer, then it finds the phone.", "在电脑上加入这个网络，电脑就会找到手机。")
    val networkName get() = t("Network name", "网络名称")
    val password get() = t("Password", "密码")
    fun hotspotServing(host: String, port: Int) = t("Sharing on the hotspot at $host · port $port", "正在通过热点共享：$host · 端口 $port")
    val hotspotStartSharing get() = t("Tap to share, and the phone shares on this network too.", "点一下开始共享，手机也会在这个网络上共享。")
    val hotspotSystemHint get() = t("Turn on the hotspot in Android settings and join it from your computer. While sharing, OpenBridge shares on it too.",
        "请在 Android 设置中开启热点，并在电脑上加入。共享时，OpenBridge 也会在热点上共享。")
    val openHotspotSettings get() = t("Open hotspot settings", "打开热点设置")
    val updates get() = t("Updates", "更新")
    val help get() = t("Help", "帮助")
    val copyDiagnostics get() = t("Copy diagnostics", "复制诊断信息")
    val diagnosticsDetail get() = t("Copies the app and Android versions, the phone model, how it's sharing and recent events, to paste into a bug report. No files, texts or codes.",
        "复制应用和 Android 版本、手机型号、共享方式和最近的事件，方便粘贴到问题报告中。不含文件、文字或配对数字。")
    val about get() = t("About", "关于")
    val aboutDetail get() = t("Moves files between your phone and computer. No accounts, no cloud.", "在手机和电脑之间传文件。无需账号，不经云端。")
    val githubPage get() = t("GitHub page", "GitHub 页面")
    val openGithub get() = t("Open the OpenBridge page on GitHub", "打开 GitHub 上的 OpenBridge 页面")
    val typeOnComputer get() = t("On your computer, type", "在电脑上输入")
    fun port(port: Int) = t("port $port", "端口 $port")
    val openOnComputer get() = t("Open OpenBridge on your Mac, or File Explorer on a PC, to browse this phone.",
        "在 Mac 上打开 OpenBridge，或在 Windows 电脑上打开文件资源管理器，即可浏览这部手机。")
    val chargingOnly get() = t("Cable connected, but only charging. Choose File transfer in the USB notification to browse from your computer.",
        "数据线已连接，但只在充电。请在 USB 通知中选择“文件传输”，即可在电脑上浏览。")
    val stopSharing get() = t("Stop sharing", "停止共享")
    val wholePhone get() = t("Whole phone", "整部手机")
    val wholePhoneDetail get() = t("Every folder, like a USB drive", "所有文件夹，就像 U 盘")
    val oneFolder get() = t("One folder", "一个文件夹")
    val oneFolderDetail get() = t("Only the folder you pick", "只共享你选的文件夹")
    val storage get() = t("Storage", "存储")
    val connectedComputer get() = t("Connected to your computer", "已连接电脑")
    val cableFileTransfer get() = t("Connected by cable · File transfer", "数据线连接 · 文件传输")
    val notSharing get() = t("Not sharing", "未共享")
    val keepInBackground get() = t("Keep sharing in the background", "在后台继续共享")
    val backgroundOn get() = t("A notification lets you stop it anytime.", "可随时通过通知停止。")
    val backgroundOff get() = t("Leaving the app stops sharing.", "离开应用就会停止共享。")
    val usingCable get() = t("Using a cable?", "用数据线？")
    val cableHint get() = t("Plug into your computer. With USB debugging on, OpenBridge on the computer connects over the cable by itself (Fast USB). Otherwise choose File transfer in the USB notification: OpenBridge on the Mac, or File Explorer on Windows, opens your phone.",
        "连接电脑。打开 USB 调试后，电脑上的 OpenBridge 会自动通过数据线连接（高速 USB）。否则请在 USB 通知中选择“文件传输”：Mac 上的 OpenBridge 或 Windows 的文件资源管理器会打开手机。")

    // Pairing
    fun pairWith(computer: String?) = if (computer == null || computer == "Computer") t("Pair with this computer?", "与这台电脑配对？")
        else t("Pair with $computer?", "与 $computer 配对？")
    val sameCode get() = t("Check that your computer shows the same code", "请确认电脑上显示的数字相同")
    val codesDiffer get() = t("Codes differ? Tap Cancel: another device may be trying to connect.", "数字不同？请点“取消”：可能有其他设备在尝试连接。")
    val cancel get() = t("Cancel", "取消")
    val pair get() = t("Pair", "配对")
    fun codeNotice(code: String) = t("Code ${code.chunked(3).joinToString(" ")}. Tap Pair only if your computer shows the same code.",
        "数字 ${code.chunked(3).joinToString(" ")}。只有电脑上显示相同数字时才点“配对”。")

    // Trusted computers
    val trusted get() = t("Trusted computers", "已信任的电脑")
    fun trustedCount(count: Int) = t(if (count == 1) "1 computer reconnects without a code" else "$count computers reconnect without a code",
        "$count 台电脑无需验证码即可重新连接")
    val noTrusted get() = t("Computers you pair with are remembered here, so they reconnect by themselves.", "配对过的电脑会记在这里，之后会自动重新连接。")
    val forget get() = t("Forget", "移除")
    val forgetHint get() = t("A forgotten computer has to pair with a code again.", "移除后，这台电脑需要重新用数字配对。")
    fun lastSeen(days: Long) = when {
        days <= 0 -> t("Used today", "今天使用过")
        days == 1L -> t("Used yesterday", "昨天使用过")
        else -> t("Used $days days ago", "$days 天前使用过")
    }

    // Sending from the phone
    val shareFilesOnly get() = t("Share photos or files to send them to your computer.", "分享照片或文件，即可发送到电脑。")
    val sendingToComputer get() = t("Sending to your computer", "正在发送到电脑")
    fun ofCount(index: Int, count: Int) = t("$index of $count", "第 $index / $count 个")
    val couldNotRead get() = t("Couldn't read those files.", "无法读取这些文件。")
    fun queued(count: Int, computer: String?) = when {
        computer != null -> t(if (count == 1) "Sending to $computer" else "Sending $count files to $computer", if (count == 1) "正在发送到 $computer" else "正在把 $count 个文件发送到 $computer")
        else -> t(if (count == 1) "Ready for your computer" else "$count files ready for your computer", if (count == 1) "已准备好发送到电脑" else "$count 个文件已准备好发送到电脑")
    }
    fun tapToSend(count: Int) = t(if (count == 1) "Start sharing to send the file to your computer." else "Start sharing to send $count files to your computer.",
        if (count == 1) "开始共享，即可把文件发送到电脑。" else "开始共享，即可把 $count 个文件发送到电脑。")
    val clear get() = t("Clear", "清除")
    fun sentTo(computer: String?) = if (computer == null) t("Sent to your computer", "已发送到电脑") else t("Sent to $computer", "已发送到 $computer")

    // Texts
    val sendClipboard get() = t("Send clipboard", "发送剪贴板")
    val clipboardEmpty get() = t("The clipboard has no text to send.", "剪贴板里没有可发送的文字。")
    val textTooLong get() = t("That text is too long to send (256 KB at most).", "文字太长，无法发送（最多 256 KB）。")
    val waitingForComputer get() = t("Waiting for a computer", "正在等待电脑")
    val openToSend get() = t("Waiting for a computer: open OpenBridge and start sharing.", "正在等待电脑：请打开 OpenBridge 并开始共享。")
    val startToSend get() = t("Start sharing to send what's waiting to your computer.", "开始共享，即可把等待中的内容发送到电脑。")
    fun textCopied(computer: String?) = if (computer == null) t("Text from your computer copied", "已复制来自电脑的文字")
        else t("Text from $computer copied", "已复制来自 $computer 的文字")
    val channelTexts get() = t("Texts from computers", "来自电脑的文字")

    // Shared controls
    val expand get() = t("Expand", "展开")
    val collapse get() = t("Collapse", "收起")
    val expanded get() = t("Expanded", "已展开")
    val collapsed get() = t("Collapsed", "已收起")
    val dismiss get() = t("Dismiss", "关闭提示")
    val close get() = t("Close", "关闭")

    // Updates
    fun update(version: String) = t("Update available: $version", "有新版本：$version")
    val updateNow get() = t("Update", "更新")
    val whatsNew get() = t("What's new", "更新内容")
    fun downloading(percent: Int) = t("Downloading… $percent%", "正在下载… $percent%")
    val verifying get() = t("Checking the download…", "正在校验下载…")
    val installing get() = t("Installing…", "正在安装…")
    val allowInstall get() = t("Allow installing apps from OpenBridge, then come back.", "请允许 OpenBridge 安装应用，然后返回。")
    val updateNeedsPermission get() = t("OpenBridge isn't allowed to install updates. Get it from the release page instead.",
        "OpenBridge 没有安装更新的权限。请从发布页面下载。")
    val updateMismatch get() = t("The download didn't match its checksum, so it wasn't installed. Get it from the release page.",
        "下载的文件与校验值不符，因此没有安装。请从发布页面下载。")
    val updateFailed get() = t("The update didn't go through. Get it from the release page.", "更新没有完成。请从发布页面下载。")
    val openReleasePage get() = t("Open the release page", "打开发布页面")
    val checkForUpdates get() = t("Check for updates", "检查更新")
    val checking get() = t("Checking…", "正在检查…")
    val upToDate get() = t("You have the latest version.", "已是最新版本。")

    // Notification
    val channelSharing get() = t("Sharing", "共享")
    val channelPairing get() = t("Pairing requests", "配对请求")
    val sharingWholePhoneTitle get() = t("Sharing your whole phone", "正在共享整部手机")
    val sharingOneFolderTitle get() = t("Sharing one folder", "正在共享一个文件夹")
    fun onComputer(host: String, port: Int) = t("On your computer: $host · port $port", "在电脑上输入：$host · 端口 $port")
    val overCable get() = t("Open OpenBridge on your computer; it connects over the USB cable.", "在电脑上打开 OpenBridge，它会通过数据线连接。")
    val sharingOverCableTitle get() = t("Sharing over the USB cable", "正在通过数据线共享")
    val pausedInBackground get() = t("Android paused sharing in the background after a few hours. Tap to share again.",
        "Android 在后台运行几小时后暂停了共享。点一下即可重新共享。")

    // Problems
    val pickLocalFolder get() = t("Pick a folder on this phone's storage or SD card.", "请选择这部手机存储或 SD 卡上的文件夹。")
    val needAllFiles get() = t("OpenBridge needs All files access to share the whole phone. Or choose One folder.",
        "共享整部手机需要“所有文件访问权限”。也可以选择“一个文件夹”。")
    val allFilesOff get() = t("All files access was turned off. Allow it again to share the whole phone.", "“所有文件访问权限”已关闭。请重新允许，才能共享整部手机。")
    val chooseFolderAgain get() = t("Choose the folder again", "请重新选择文件夹")
    val folderDenied get() = t("Android denied access to the folder. Choose it again.", "Android 拒绝访问该文件夹，请重新选择。")
    val couldNotStart get() = t("Couldn't start sharing.", "无法开始共享。")
    val wifiDisconnected get() = t("Wi‑Fi disconnected", "Wi‑Fi 已断开")
    val serverStopped get() = t("Sharing stopped unexpectedly", "共享意外停止")
    val cableRemoved get() = t("USB cable disconnected or tethering turned off", "USB 数据线已断开，或网络共享已关闭")
    val noWifi get() = t("No Wi‑Fi: connect a computer by USB cable with USB debugging, or use Phone hotspot in Settings.",
        "没有 Wi‑Fi：请用数据线连接电脑并打开 USB 调试，或在“设置”中使用“手机热点”。")
    fun stillSharing(problem: String) = t("$problem. Still sharing over the phone hotspot or Fast USB, while they're in use.",
        "$problem。手机热点或高速 USB 仍在使用时，会继续通过它们共享。")

    // Fast USB (over USB debugging)
    val fastUsb get() = t("Fast USB", "高速 USB")
    val fastUsbBusy get() = t("Fast USB ports 41987–41990 are all in use.", "高速 USB 端口 41987–41990 都被占用了。")
    val cableUnplugged get() = t("USB cable unplugged", "USB 数据线已拔出")
    val cableNeedsDebugging get() = t("To share over the cable, plug it in with USB debugging on, or tap to share over Wi‑Fi.",
        "要通过数据线共享，请在打开 USB 调试后连接数据线，或点一下通过 Wi‑Fi 共享。")
    val cableNeedsSetup get() = t("Your computer wants to connect over the cable. Tap to share, and allow access first.",
        "电脑想通过数据线连接。请点一下开始共享，并先允许访问。")

    // Phone hotspot
    val hotspotBusy get() = t("Android can't start this hotspot now. Turn off the regular hotspot, or use it instead.",
        "Android 现在无法开启这个热点。请关闭普通热点，或改用普通热点。")
    val hotspotDisallowed get() = t("This phone doesn't allow apps to start a hotspot.", "这部手机不允许应用开启热点。")
    val hotspotFailed get() = t("Couldn't start the hotspot. Try again in a moment.", "无法开启热点，请稍后再试。")
    val hotspotPermission get() = t("Allow Nearby devices for OpenBridge to start the hotspot (it doesn't use your location).",
        "请允许 OpenBridge 使用“附近的设备”权限来开启热点（不会使用你的位置）。")
}
