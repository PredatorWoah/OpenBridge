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
    val tapSun get() = t("Tap the sun to share with your computer", "点一下太阳，与电脑共享")
    val sharing get() = t("Sharing", "正在共享")
    val startSharing get() = t("Start sharing", "开始共享")
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
    val sharingWholePhone get() = t("Sharing whole phone", "正在共享整部手机")
    val sharingOneFolder get() = t("Sharing one folder", "正在共享一个文件夹")
    val notSharing get() = t("Not sharing", "未共享")
    val keepInBackground get() = t("Keep sharing in the background", "在后台继续共享")
    val backgroundOn get() = t("A notification lets you stop it anytime.", "可随时通过通知停止。")
    val backgroundOff get() = t("Leaving the app stops sharing.", "离开应用就会停止共享。")
    val usingCable get() = t("Using a cable?", "用数据线？")
    val cableHint get() = t("Plug into your Mac or PC and choose File transfer in the USB notification. OpenBridge on the Mac, or File Explorer on Windows, opens your phone. No need to tap the sun for a cable.",
        "连接 Mac 或 Windows 电脑，在 USB 通知中选择“文件传输”。Mac 上的 OpenBridge 或 Windows 的文件资源管理器会打开手机。用数据线时不需要点太阳。")

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
    val done get() = t("Done", "完成")

    // Updates
    fun update(version: String) = t("Update available: $version", "有新版本：$version")

    // Notification
    val channelSharing get() = t("Sharing", "共享")
    val channelPairing get() = t("Pairing requests", "配对请求")
    val sharingWholePhoneTitle get() = t("Sharing your whole phone", "正在共享整部手机")
    val sharingOneFolderTitle get() = t("Sharing one folder", "正在共享一个文件夹")
    fun onComputer(host: String, port: Int) = t("On your computer: $host · port $port", "在电脑上输入：$host · 端口 $port")
    val pausedInBackground get() = t("Android paused sharing in the background after a few hours. Tap the sun to share again.",
        "Android 在后台运行几小时后暂停了共享。点一下太阳即可重新共享。")

    // Problems
    val pickLocalFolder get() = t("Pick a folder on this phone's storage or SD card.", "请选择这部手机存储或 SD 卡上的文件夹。")
    val needAllFiles get() = t("OpenBridge needs All files access to share the whole phone. Or choose One folder.",
        "共享整部手机需要“所有文件访问权限”。也可以选择“一个文件夹”。")
    val allFilesOff get() = t("All files access was turned off. Allow it again to share the whole phone.", "“所有文件访问权限”已关闭。请重新允许，才能共享整部手机。")
    val chooseFolderAgain get() = t("Choose the folder again", "请重新选择文件夹")
    val folderDenied get() = t("Android denied access to the folder. Choose it again.", "Android 拒绝访问该文件夹，请重新选择。")
    val couldNotStart get() = t("Couldn't start sharing.", "无法开始共享。")
    val checkWifi get() = t("Couldn't start sharing. Check that Wi‑Fi is on.", "无法开始共享，请确认 Wi‑Fi 已打开。")
    val needNetwork get() = t("Connect to Wi‑Fi or turn on USB tethering first", "请先连接 Wi‑Fi 或打开 USB 网络共享")
    val wifiDisconnected get() = t("Wi‑Fi disconnected", "Wi‑Fi 已断开")
    val wifiChanged get() = t("Wi‑Fi address changed", "Wi‑Fi 地址已变化")
    val serverStopped get() = t("Sharing stopped unexpectedly", "共享意外停止")
    val cableRemoved get() = t("USB cable disconnected or tethering turned off", "USB 数据线已断开，或网络共享已关闭")
}
