package com.ax6.luyouqi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ax6.luyouqi.domain.Device
import com.ax6.luyouqi.domain.DeviceKind
import com.ax6.luyouqi.domain.DeviceSnapshot
import com.ax6.luyouqi.fmt.Fmt
import com.ax6.luyouqi.ui.theme.Ax6Dimens
import com.ax6.luyouqi.ui.theme.Ax6Tokens

private enum class Filter(val label: String) {
    ALL("全部"), WIFI("WiFi"), WIRED("有线"), TOTAL("累计")
}

/** 累计口径：WiFi 用 assoclist 的会话计数，有线用端口计数（终端视角 down=tx up=rx）。 */
private val Device.downBytes: Long? get() = txBytes ?: downTotal
private val Device.upBytes: Long? get() = rxBytes ?: upTotal

/** 累计列表的分组顺序：有线 → 5 GHz → 2.4 GHz（其余含离线排最后）。 */
private fun cumRank(d: Device): Int = when {
    d.online && d.kind == DeviceKind.WIRED -> 0
    d.online && d.kind == DeviceKind.WIFI && Fmt.bandLabel(d.band).startsWith("5") -> 1
    d.online && d.kind == DeviceKind.WIFI && Fmt.bandLabel(d.band).startsWith("2.4") -> 2
    else -> 3
}

private val AVATAR_COLORS = listOf(
    Color(0xFF3DDC97) to Color(0xFF1A9F6E),
    Color(0xFF62A8FF) to Color(0xFF3B6FE0),
    Color(0xFFC084FC) to Color(0xFF7C3AED),
    Color(0xFFF5B64A) to Color(0xFFE07B39),
    Color(0xFFFB7185) to Color(0xFFE11D48),
    Color(0xFF22D3EE) to Color(0xFF0891B2),
)

@Composable
fun DevicesPage(snapshot: DeviceSnapshot?, onOpen: (Device) -> Unit) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(Filter.ALL) }

    val all = snapshot?.devices.orEmpty()
    val visible = all.filter { d ->
        when (filter) {
            Filter.ALL, Filter.TOTAL -> true
            Filter.WIFI -> d.online && d.kind == DeviceKind.WIFI
            Filter.WIRED -> d.online && d.kind == DeviceKind.WIRED
        }
    }.filter { d ->
        val q = query.trim().lowercase()
        q.isEmpty() || listOf(d.displayName, d.hostname, d.ip, d.mac).any { it.lowercase().contains(q) }
    }.let { list ->
        if (filter == Filter.TOTAL) {
            list.sortedWith(
                compareBy<Device> { cumRank(it) }
                    .thenByDescending { (it.downBytes ?: 0L) + (it.upBytes ?: 0L) }
            )
        } else {
            list
        }
    }

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Ax6Tokens.Field, RoundedCornerShape(Ax6Dimens.RadiusSm))
                .border(1.dp, Ax6Tokens.Line, RoundedCornerShape(Ax6Dimens.RadiusSm))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            if (query.isEmpty()) {
                Text("搜索名称 / IP / MAC", color = Ax6Tokens.Dim, fontSize = 12.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Ax6Tokens.Text, fontSize = 12.sp),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Filter.entries.forEach { item ->
                val active = filter == item
                Text(
                    item.label,
                    color = if (active) Ax6Tokens.Accent else Ax6Tokens.Muted,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .background(
                            if (active) Ax6Tokens.Accent.copy(alpha = 0.12f) else Ax6Tokens.CardSolid,
                            RoundedCornerShape(50),
                        )
                        .border(
                            1.dp,
                            if (active) Ax6Tokens.Accent.copy(alpha = 0.55f) else Ax6Tokens.Line,
                            RoundedCornerShape(50),
                        )
                        .clickable { filter = item }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Ax6Tokens.CardSolid, RoundedCornerShape(Ax6Dimens.Radius))
                .border(1.dp, Ax6Tokens.Line, RoundedCornerShape(Ax6Dimens.Radius)),
        ) {
            if (visible.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("⌁", color = Ax6Tokens.Dim, fontSize = 22.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("没有匹配的终端", color = Ax6Tokens.Muted, fontSize = 13.sp)
                    Text(
                        if (all.isEmpty()) "正在等待路由器上报终端…" else "试试切换筛选条件",
                        color = Ax6Tokens.Dim,
                        fontSize = 11.sp,
                    )
                }
            }

            visible.forEachIndexed { index, device ->
                DeviceRow(
                    device,
                    onClick = { onOpen(device) },
                    last = index == visible.lastIndex && filter != Filter.TOTAL,
                    showTotal = filter == Filter.TOTAL,
                )
            }

            if (filter == Filter.TOTAL) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ax6Tokens.Line))
                Text(
                    "仅在线设备有计数，WiFi 为本次连接累计",
                    color = Ax6Tokens.Dim,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: Device,
    onClick: () -> Unit,
    last: Boolean = false,
    showTotal: Boolean = false,
) {
    val online = device.online
    val colors = AVATAR_COLORS[Fmt.avatarIndex(device.mac)]

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(
                    Brush.linearGradient(listOf(colors.first, colors.second)),
                    RoundedCornerShape(11.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                Fmt.initials(device.displayName),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.24.sp,
            )
        }

        Spacer(Modifier.width(11.dp))

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    device.displayName,
                    color = Ax6Tokens.Text,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                DeviceTag(device)
            }
            Text(
                "${device.ip.ifBlank { "—" }} · ${linkText(device)}",
                style = TextStyle(fontSize = 10.sp, fontFeatureSettings = "tnum", color = Ax6Tokens.Dim),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(10.dp))

        Column(
            modifier = Modifier.widthIn(min = 60.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (showTotal) {
                val down = device.downBytes
                val up = device.upBytes
                Text(
                    "↓ ${down?.let { Fmt.bytes(it) } ?: "—"}",
                    color = if (down != null) Ax6Tokens.Down else Ax6Tokens.Dim,
                    style = numStyle(10),
                )
                Text(
                    "↑ ${up?.let { Fmt.bytes(it) } ?: "—"}",
                    color = if (up != null) Ax6Tokens.Up else Ax6Tokens.Dim,
                    style = numStyle(10),
                )
            } else if (online) {
                Text("↓ ${Fmt.rate(device.rate.down)}", color = Ax6Tokens.Down, style = numStyle(10))
                Text("↑ ${Fmt.rate(device.rate.up)}", color = Ax6Tokens.Up, style = numStyle(10))
            } else {
                Text("—", color = Ax6Tokens.Dim, fontSize = 11.sp)
            }
            Text(
                if (online) "在线" else "离线",
                color = if (online) Ax6Tokens.Accent else Ax6Tokens.Dim,
                fontSize = 9.5.sp,
            )
        }
    }
    if (!last) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ax6Tokens.Line))
    }
}

@Composable
private fun DeviceTag(device: Device) {
    val text: String
    val color: Color
    when {
        device.online && device.kind == DeviceKind.WIFI -> {
            text = device.band.ifBlank { "WiFi" }
            color = Ax6Tokens.Up
        }
        device.online && device.kind == DeviceKind.WIRED -> {
            text = "有线"
            color = Ax6Tokens.Muted
        }
        else -> {
            text = "离线"
            color = Ax6Tokens.Dim
        }
    }
    Text(
        text,
        color = color,
        style = TextStyle(
            fontSize = 9.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.SemiBold,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        ),
        modifier = Modifier
            .background(color.copy(alpha = if (color == Ax6Tokens.Muted) 0.07f else 0.13f), RoundedCornerShape(5.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
    if (device.rateShared) {
        Text(
            "共享",
            color = Ax6Tokens.Dim,
            fontSize = 9.sp,
            modifier = Modifier
                .padding(start = 4.dp)
                .border(1.dp, Ax6Tokens.Line, RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

private fun linkText(device: Device): String = when {
    device.kind == DeviceKind.WIFI && device.online ->
        device.signal?.let { "$it dBm" } ?: "WiFi"
    device.kind == DeviceKind.WIRED && device.online ->
        device.port.takeIf { it.isNotBlank() }?.uppercase() ?: "有线"
    else -> "离线"
}

/** 设备详情：字段与网页版详情面板一致，空值显示 —，不补 0。 */
@Composable
fun DeviceDetailDialog(device: Device, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val scrim = remember { MutableInteractionSource() }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .clickable(indication = null, interactionSource = scrim) { onClose() }
                .padding(horizontal = 14.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            val maxContentHeight = maxHeight * 0.86f
            PopIn {
            Column(
                modifier = Modifier
                    .widthIn(max = 368.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxContentHeight)
                    .background(Ax6Tokens.CardSolid, RoundedCornerShape(Ax6Dimens.Radius))
                    .border(1.dp, Ax6Tokens.Line, RoundedCornerShape(Ax6Dimens.Radius))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { }
                    .padding(Ax6Dimens.CardPadding)
                    .verticalScroll(rememberScrollState()),
            ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    device.displayName,
                    color = Ax6Tokens.Text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (device.online) "在线" else "离线",
                    color = if (device.online) Ax6Tokens.Accent else Ax6Tokens.Dim,
                    fontSize = 11.sp,
                )
            }
            Text(
                when {
                    device.kind == DeviceKind.WIFI -> "${Fmt.bandLabel(device.band)} WiFi"
                    device.kind == DeviceKind.WIRED -> "有线连接"
                    else -> "离线设备"
                },
                color = Ax6Tokens.Muted,
                fontSize = 11.sp,
            )

            Spacer(Modifier.height(10.dp))

            DetailRow("名称", device.hostname.ifBlank { "—" })
            DetailRow("MAC", device.mac, mono = true)
            DetailRow("IPv4", device.ip.ifBlank { "—" }, mono = true)
            val v6List = device.ipv6List.ifEmpty { listOfNotNull(device.ipv6.takeIf { it.isNotBlank() }) }
            if (v6List.isEmpty()) {
                DetailRow("IPv6", "—", mono = true)
            } else {
                Spacer(Modifier.height(2.dp))
                Text("IPv6", color = Ax6Tokens.Muted, fontSize = 11.sp)
                v6List.forEach { address ->
                    Text(
                        address,
                        color = Ax6Tokens.Text,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        textAlign = TextAlign.Start,
                    )
                }
            }
            if (device.online && device.kind == DeviceKind.WIFI) {
                DetailRow("SSID", device.ssid.ifBlank { "—" })
                DetailRow("信道", device.channel?.toString() ?: "—")
                DetailRow(
                    "信号",
                    device.signal?.let { "$it dBm · ${Fmt.signalPercent(it)}%" } ?: "—",
                )
                DetailRow("协商速率", Fmt.linkSpeedText(device.bitrate?.toLong()))
                DetailRow("连接时长", device.connectedTime?.let { Fmt.duration(it) } ?: "—")
            }
            if (device.online && device.kind == DeviceKind.WIRED) {
                DetailRow("端口", device.port.uppercase().ifBlank { "—" })
                if (device.portPeers > 1) DetailRow("累计流量同端口多设备共享", "${device.portPeers} 台")
            }
            val downTotal = device.downBytes
            val upTotal = device.upBytes
            if (downTotal != null || upTotal != null) {
                DetailRow(
                    "累计收发",
                    "↓ ${downTotal?.let { Fmt.bytes(it) } ?: "—"} · ↑ ${upTotal?.let { Fmt.bytes(it) } ?: "—"}",
                )
            }
            if (device.online) {
                DetailRow("实时速率", "↓ ${Fmt.rate(device.rate.down)} · ↑ ${Fmt.rate(device.rate.up)}")
            }
            if ((device.leaseExpires ?: 0) > 0) {
                DetailRow("租约剩余", Fmt.duration(device.leaseExpires!!))
            }
            DetailRow(
                "来源",
                when (device.source) {
                    "wifi" -> "无线关联表"
                    "fdb" -> "有线 FDB"
                    "lease" -> "DHCP 租约"
                    "hint" -> "ARP 主机表"
                    "remark" -> "备注（已离线）"
                    else -> "—"
                },
            )

            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) {
                Text("关闭", color = Ax6Tokens.Muted, fontSize = 13.sp)
            }
            }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Ax6Tokens.Muted, fontSize = 11.sp)
        Text(
            value,
            color = Ax6Tokens.Text,
            fontSize = 11.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        )
    }
}
