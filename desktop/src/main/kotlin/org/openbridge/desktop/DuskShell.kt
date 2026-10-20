package org.openbridge.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openbridge.ui.Look
import org.openbridge.ui.Palette

/** Dusk, the original layout: a toolbar with the path, a filter bar, the folder, and the transfer bar at the bottom. */
@Composable
fun DuskShell(state: AppState, dropping: Boolean, searchFocus: FocusRequester) {
    if (state.source != null) DuskBrowser(state, dropping, searchFocus) else ConnectScreen(state)
}

@Composable
private fun DuskBrowser(state: AppState, dropping: Boolean, searchFocus: FocusRequester) {
    Column(Modifier.fillMaxSize()) {
        Toolbar(state)
        FilterBar(state, searchFocus)
        if (state.update != null || state.updating != null) Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp), horizontalArrangement = Arrangement.End) { UpdatePill(state) }
        state.phoneOutdated?.let { Hint(it, Icons.Filled.SystemUpdate, Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) { state.dismissOutdated() } }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            FileArea(state, Look.DUSK, Modifier.fillMaxSize().padding(horizontal = 10.dp), PaddingValues(vertical = 8.dp))
            if (dropping) {
                Box(Modifier.fillMaxSize().padding(10.dp).clip(RoundedCornerShape(16.dp)).background(Palette.violet.copy(alpha = 0.18f))
                    .border(2.dp, Palette.sunset, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                    Text("${S.dropHere} · ${state.path.last().second}", color = Palette.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        ErrorLine(state, Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
        state.transfer?.let { TransferBar(state, it) }
    }
}

@Composable
private fun Toolbar(state: AppState) {
    Row(Modifier.fillMaxWidth().background(Palette.fg.copy(alpha = 0.04f)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(Icons.Filled.ArrowUpward, S.up, enabled = state.path.size > 1 && !state.busy) { state.up() }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            state.path.forEachIndexed { index, crumb ->
                if (index > 0) Icon(Icons.Filled.ChevronRight, null, tint = Palette.muted, modifier = Modifier.size(16.dp))
                val last = index == state.path.size - 1
                Text(if (index == 0) state.deviceName else crumb.second, color = if (last) Palette.fg else Palette.muted, fontSize = 14.sp,
                    fontWeight = if (last) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    // The open folder keeps its name; the ones above it give way first.
                    modifier = (if (last) Modifier.widthIn(max = 280.dp) else Modifier.weight(1f, fill = false)).pressable(RoundedCornerShape(6.dp), enabled = !last && !state.busy) { state.go(index) }.padding(4.dp))
            }
        }
        TransportChip(state)
        if (state.bridge != null) IconButton(Icons.Filled.ChatBubbleOutline, S.texts) { state.panel = Panel.TEXTS }
        SortButton(state)
        MoreButton(state)
        DownloadButton(state)
        IconButton(Icons.Filled.Settings, S.settings) { state.panel = Panel.SETTINGS }
    }
}

/** Search within the folder, show only photos and videos, and list or grid. */
@Composable
private fun FilterBar(state: AppState, searchFocus: FocusRequester) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchField(state, searchFocus, Modifier.weight(1f))
        SearchAllButton(state)
        listOf(Show.ALL to S.showAll, Show.MEDIA to S.showMedia).forEach { (option, label) ->
            val on = state.show == option
            Text(label, color = if (on) Color.White else Palette.muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).background(if (on) Palette.violet.copy(alpha = 0.6f) else Palette.fg.copy(alpha = 0.06f))
                    .pressable(CircleShape, role = Role.Tab) { state.show = option }.semantics { this.selected = on }.padding(horizontal = 12.dp, vertical = 7.dp))
        }
        ViewToggle(state)
    }
}

@Composable
private fun TransferBar(state: AppState, transfer: Transfer) {
    Column(Modifier.fillMaxWidth().padding(16.dp).glass(RoundedCornerShape(14.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (transfer.upload) Icons.Filled.Upload else Icons.Filled.Download, null, tint = Palette.amber, modifier = Modifier.size(18.dp))
            Text(transfer.name, color = Palette.fg, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f, fill = false))
            if (transfer.count > 1) Text(S.ofCount(transfer.index, transfer.count), color = Palette.muted, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            TransferActions(state, transfer)
        }
        // The bar in the look's accent colours, with a little light at its tip.
        Canvas(Modifier.fillMaxWidth().height(16.dp).semantics { progressBarRangeInfo = ProgressBarRangeInfo(transfer.fraction, 0f..1f) }) {
            val h = 10.dp.toPx(); val top = (size.height - h) / 2
            drawRoundRect(Palette.fg.copy(alpha = 0.1f), Offset(0f, top), Size(size.width, h), CornerRadius(h / 2))
            val width = maxOf(h, size.width * transfer.fraction)
            drawRoundRect(accentBrush(width), Offset(0f, top), Size(width, h), CornerRadius(h / 2))
            if (!transfer.done) {
                val center = Offset(width - h / 2, size.height / 2)
                drawCircle(Brush.radialGradient(listOf(Palette.sun.copy(alpha = 0.6f), Color.Transparent), center, 14.dp.toPx()), 14.dp.toPx(), center)
                drawCircle(Brush.radialGradient(listOf(Palette.cream, Palette.sun, Palette.amber), center, 8.dp.toPx()), 8.dp.toPx(), center)
            }
        }
    }
}
