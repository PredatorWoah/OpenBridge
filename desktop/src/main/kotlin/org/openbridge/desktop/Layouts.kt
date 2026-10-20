package org.openbridge.desktop

import org.openbridge.desktop.wpd.UsbPhone

/*
 * The small decisions the looks' window layouts share: which phones the switchers show, how a long folder path
 * folds, and how far the whole queue of transfers has got. Kept apart from drawing so they can be tested.
 */

/** A phone as the switchers show it (Aurora's stars and orbs, Tide's portholes and bubbles). */
class PhoneStar(
    val key: String, val name: String,
    /** What clicking it does. */
    val reach: Reach,
    /** Open in the browser right now. */
    val open: Boolean = false,
    /** Paired before: it reconnects without a code. */
    val saved: Boolean = false,
    /** On the USB cable (Fast USB or File transfer). */
    val cable: Boolean = false,
    /** Can be reached now (on this Wi‑Fi or the cable). */
    val here: Boolean = false,
    /** A short line for its tooltip and screen readers. */
    val status: String = "",
) {
    sealed class Reach {
        class Saved(val phone: SavedPhone) : Reach()
        class Nearby(val phone: NearbyPhone) : Reach()
        class Link(val link: UsbLink) : Reach()
        class Wpd(val phone: UsbPhone) : Reach()
        /** The phone in the browser when nothing else describes it. */
        object Current : Reach()
    }
}

/**
 * Every phone this computer knows about, once each: saved phones (with how they can be reached), phones sharing
 * nearby that aren't saved, phones on the cable that aren't a saved phone, and phones in File transfer mode.
 * The open phone comes first.
 */
fun phoneStars(state: AppState): List<PhoneStar> {
    val stars = ArrayList<PhoneStar>()
    for (phone in state.saved) {
        val cable = state.onCable(phone) != null
        val here = cable || state.isNearby(phone)
        stars += PhoneStar("saved-" + phone.id, phone.name, PhoneStar.Reach.Saved(phone), open = state.isOpen(phone), saved = true,
            cable = cable, here = here, status = when { cable -> S.onCableNow; here -> S.nearbyNow; else -> S.reconnects })
    }
    for (phone in state.nearby) if (state.saved.none { it.id == phone.id })
        stars += PhoneStar("nearby-" + (phone.id ?: phone.host), phone.name, PhoneStar.Reach.Nearby(phone), here = true, status = "${S.nearbyNow} · ${S.pair}")
    for (link in state.usbLinks) if (link.phone == null || state.saved.none { it == link.phone })
        stars += PhoneStar("link-" + link.serial, link.name, PhoneStar.Reach.Link(link), cable = true, here = link.ready, status = linkStatus(link))
    for (phone in state.wpdPhones)
        stars += PhoneStar("wpd-" + phone.id, phone.name, PhoneStar.Reach.Wpd(phone), open = state.isOpen(phone), cable = true, here = true, status = S.fileTransfer)
    if (state.source != null && stars.none { it.open })
        stars.add(0, PhoneStar("current", state.deviceName, PhoneStar.Reach.Current, open = true, saved = true,
            cable = state.transport != Transport.WIFI, here = true, status = state.transport.label))
    return stars.sortedByDescending { it.open }
}

fun linkStatus(link: UsbLink): String = when (link.state) {
    UsbLink.State.READY -> if (link.phone != null) S.fastUsbReady else S.fastUsbNew
    UsbLink.State.UNAUTHORIZED -> S.allowDebugging
    UsbLink.State.NO_PERMISSION -> S.noPermission
    UsbLink.State.STARTING -> S.startingOnPhone
    UsbLink.State.NOT_SHARING -> S.notSharing
    UsbLink.State.NO_APP -> S.noApp
}

/** Opens, connects or pairs with a star's phone; a phone on the cable that isn't ready says what it needs instead. */
fun reach(state: AppState, star: PhoneStar) {
    if (star.open || state.busy) return
    when (val reach = star.reach) {
        is PhoneStar.Reach.Saved -> state.connect(reach.phone)
        is PhoneStar.Reach.Nearby -> state.pairWith(reach.phone)
        is PhoneStar.Reach.Link -> when {
            reach.link.ready && reach.link.phone != null -> state.connectUsb(reach.link)
            reach.link.ready -> state.pairOverUsb(reach.link)
            else -> state.show(star.status)
        }
        is PhoneStar.Reach.Wpd -> state.openWpd(reach.phone)
        PhoneStar.Reach.Current -> {}
    }
}

/**
 * Which crumbs of a path of `size` folders to show in `room` places: all of them when they fit, else the top, a
 * fold (null) standing for the middle, and as many of the deepest as fit. The open folder always shows.
 */
fun foldPath(size: Int, room: Int): List<Int?> {
    if (size <= room || size <= 2) return (0 until size).toList()
    val tail = (room - 2).coerceAtLeast(1)
    return listOf<Int?>(0, null) + (size - tail until size).toList()
}

/** The hidden middle of a folded path, deepest last. */
fun foldedOut(size: Int, room: Int): List<Int> {
    val shown = foldPath(size, room).filterNotNull().toSet()
    return (0 until size).filter { it !in shown }
}

/** How far the whole queue has got, 0 to 1: finished files count whole, the current one by its share. */
fun overallProgress(transfer: Transfer): Float {
    if (transfer.done) return 1f
    val count = transfer.count.coerceAtLeast(1)
    val finished = (transfer.index - 1).coerceIn(0, count - 1)
    return ((finished + transfer.fraction.coerceIn(0f, 1f)) / count).coerceIn(0f, 1f)
}

/** The queue as the comet's popover and Tide's dock show it. */
class Queue(
    /** Files already moved. */
    val finished: Int,
    val current: String,
    val fraction: Float,
    /** The next few files, in order. */
    val upcoming: List<String>,
    /** Files after those. */
    val more: Int,
)

fun queueOf(transfer: Transfer, show: Int = 3): Queue {
    val count = transfer.count.coerceAtLeast(1)
    val finished = if (transfer.done) count else (transfer.index - 1).coerceIn(0, count)
    // While running, `remaining` starts with the current file.
    val next = if (transfer.done) emptyList() else transfer.remaining.drop(1).map { it.name }
    return Queue(finished, transfer.name, if (transfer.done) 1f else transfer.fraction.coerceIn(0f, 1f), next.take(show), (next.size - show).coerceAtLeast(0))
}

/** A transfer that stopped part way (the phone went away, or it failed) and can be resumed. */
fun stalled(transfer: Transfer, busy: Boolean) = !transfer.done && !busy && transfer.remaining.isNotEmpty()
