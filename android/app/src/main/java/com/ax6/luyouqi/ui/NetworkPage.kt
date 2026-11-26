package com.ax6.luyouqi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ax6.luyouqi.AppUiState
import com.ax6.luyouqi.domain.Port
import com.ax6.luyouqi.domain.WifiState
import com.ax6.luyouqi.fmt.Fmt
import com.ax6.luyouqi.ui.theme.Ax6Dimens
import com.ax6.luyouqi.ui.theme.Ax6Tokens

/** 网络页：上行 WAN v4 / v6、物理网口、无线网络。口径对齐网页版 renderNetwork。 */
@Composable
fun NetworkPage(state: AppUiState.Connected) {
    val home = state.home
    val wan = home?.wan
    val wan6 = home?.wan6

    if (home == null) {
        CardBox {
            CardHead("网络接口", "读取中")
            Spacer(Modifier.height(6.dp))
            Text("正在读取网络接口…", color = Ax6Tokens.Muted, fontSize = 11.sp)
        }
        return
    }

    // 上行 WAN · IPv4
    CardBox {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardHead("上行 WAN · IPv4")
            if (wan != null) StatusPill(if (wan.up) "已连接" else "未连接", if (wan.up) Tone.OK else Tone.BAD)
        }
        Spacer(Modifier.height(6.dp))
        if (wan == null) {
            Text("没有可用的 wan 接口。", color = Ax6Tokens.Muted, fontSize = 11.sp)
        } else {
            KvRow("IPv4 地址", wan.ipv4First.ifBlank { "未获取 IPv4" }, mono = true, wrap = true)
            KvRow("协议", protoLabel(wan.proto))
            KvRow("物理接口", dash(wan.device), mono = true)
            KvRow("网关", dash(wan.gateway), mono = true)
            KvRow("DNS", wan.dns.joinToString(" · ").ifBlank { "—" }, mono = true, wrap = true)
            KvRow("剩余有效期", wan.leaseRemaining?.let { Fmt.duration(it) } ?: "—")
            KvRow("已连接", if (wan.uptime > 0) Fmt.duration(wan.uptime) else "—")
        }
    }

    Spacer(Modifier.height(Ax6Dimens.CardGap))

    // 上行 WAN · IPv6
    val wan6Up = wan6 != null && wan6.up && wan6.ipv6.isNotEmpty()
    CardBox {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardHead("上行 WAN · IPv6")
            if (wan6 != null) StatusPill(if (wan6Up) "已连接" else "未连接", if (wan6Up) Tone.OK else Tone.BAD)
        }
        Spacer(Modifier.height(6.dp))
        if (wan6 == null) {
            Text("没有可用的 wan6 接口。", color = Ax6Tokens.Muted, fontSize = 11.sp)
        } else {
            Text(
                "全部 IPv6 地址（${wan6.ipv6.size}）",
                color = Ax6Tokens.Muted,
                fontSize = 10.sp,
            )
            Spacer(Modifier.height(2.dp))
            if (wan6.ipv6.isEmpty()) {
                Text("未获取到 IPv6 地址", color = Ax6Tokens.Muted, fontSize = 11.sp)
            } else {
                wan6.ipv6.forEach { MonoFitText(it) }
            }
            Spacer(Modifier.height(6.dp))
            KvRow("协议", protoLabel(wan6.proto))
            if (wan6.pd.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text("分发前缀", color = Ax6Tokens.Muted, fontSize = 11.sp)
                wan6.pd.forEach { MonoFitText(it) }
            } else {
                KvRow("分发前缀", "—")
            }
            KvRow("网关", dash(wan6.gateway), mono = true)
            KvRow("DNS", wan6.dns.joinToString(" · ").ifBlank { "—" }, mono = true, wrap = true)
            KvRow("剩余有效期", wan6.leaseRemaining?.let { Fmt.duration(it) } ?: "—")
            KvRow("已连接", if (wan6.uptime > 0) Fmt.duration(wan6.uptime) else "—")
        }
    }

    Spacer(Modifier.height(Ax6Dimens.CardGap))

    // 物理网口 + LAN/WiFi 流量（合并成一张卡，逐条平铺）
    CardBox {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            CardHead("网络接口")
            Text(
                "${home.ports.count { it.carrier }} / ${home.ports.size} 已插线 · ${home.traffic.size} 个流量接口",
                color = Ax6Tokens.Muted,
                fontSize = 10.sp,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text("物理网口", color = Ax6Tokens.Dim, fontSize = 9.sp)
        if (home.ports.isEmpty()) {
            Text("固件未返回物理网口信息。", color = Ax6Tokens.Muted, fontSize = 11.sp)
        } else {
            home.ports.forEachIndexed { index, port ->
                if (index > 0) ItemDivider()
                NetRow(
                    title = port.label,
                    sub = if (port.carrier) {
                        "${Fmt.portSpeedText(port.speed).ifBlank { "—" }} ${if (port.duplex == "full") "全双工" else "半双工"}"
                    } else {
                        "未插线"
                    },
                    rate = if (port.carrier) "↓ ${Fmt.rate(port.viewDownRate)} · ↑ ${Fmt.rate(port.viewUpRate)}" else "—",
                    total = "累计 ↓ ${Fmt.bytes(port.viewDownTotal)} · ↑ ${Fmt.bytes(port.viewUpTotal)}",
                    online = port.carrier,
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ax6Tokens.Line))
        Spacer(Modifier.height(6.dp))
        Text("LAN / WiFi 收发流量", color = Ax6Tokens.Dim, fontSize = 9.sp)
        if (home.traffic.isEmpty()) {
            Text("固件没有返回 LAN / WiFi 的流量计数。", color = Ax6Tokens.Muted, fontSize = 11.sp)
        } else {
            home.traffic.forEachIndexed { index, row ->
                if (index > 0) ItemDivider()
                // 接口都是路由器视角：tx 是发向终端（下行），rx 是终端发来（上行）
                val downTotal = row.tx
                val upTotal = row.rx
                NetRow(
                    title = row.label,
                    sub = row.sub,
                    rate = "↓ ${Fmt.rate(row.rate.down)} · ↑ ${Fmt.rate(row.rate.up)}",
                    total = "累计 ↓ ${Fmt.bytes(downTotal)} · ↑ ${Fmt.bytes(upTotal)}",
                    online = true,
                )
            }
        }
    }

    Spacer(Modifier.height(Ax6Dimens.CardGap))

    // 无线网络
    WifiCard(home.wifi)

    Spacer(Modifier.height(Ax6Dimens.CardGap))


}

@Composable
private fun ItemDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ax6Tokens.Line))
}

/** 平铺的一行：左名称/副标题，右实时与累计。 */
@Composable
private fun NetRow(title: String, sub: String, rate: String, total: String, online: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(
                            if (online) Ax6Tokens.Accent else Ax6Tokens.Danger,
                            RoundedCornerShape(50),
                        ),
                )
                Spacer(Modifier.width(6.dp))
                Text(title, color = Ax6Tokens.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            if (sub.isNotBlank()) Text(sub, color = Ax6Tokens.Muted, fontSize = 9.sp, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                rate,
                color = if (online) Ax6Tokens.Accent else Ax6Tokens.Muted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
            Text(
                total,
                color = Ax6Tokens.Dim,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PortRow(port: Port, modifier: Modifier = Modifier) {
    val on = port.carrier
    Column(
        modifier = modifier
            .background(Ax6Tokens.Field, RoundedCornerShape(Ax6Dimens.RadiusSm))
            .border(
                1.dp,
                if (on) Ax6Tokens.Accent.copy(alpha = 0.45f) else Ax6Tokens.Line,
                RoundedCornerShape(Ax6Dimens.RadiusSm),
            )
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                port.label,
                color = Ax6Tokens.Text,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            StatusPill(if (on) "已连接" else "未插线", if (on) Tone.OK else Tone.BAD)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (on) "${Fmt.portSpeedText(port.speed)} ${if (port.duplex == "full") "全双工" else "半双工"}" else "—",
            color = Ax6Tokens.Muted,
            fontSize = 9.sp,
        )
        Spacer(Modifier.height(3.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "↓ ${Fmt.rate(port.rate.down)}",
                color = Ax6Tokens.Dim,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "↑ ${Fmt.rate(port.rate.up)}",
                color = Ax6Tokens.Dim,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Text(
            "累计 ↓ ${Fmt.bytes(port.rx)} · ↑ ${Fmt.bytes(port.tx)}",
            color = Ax6Tokens.Dim,
            fontSize = 9.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun WifiCard(wifi: WifiState) {
    CardBox {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            CardHead("无线网络")
            if (wifi.available) {
                Text("${wifi.clients} 台已连接", color = Ax6Tokens.Muted, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            !wifi.available -> Text(
                "无线状态不可用：固件未开放 network.wireless / iwinfo。",
                color = Ax6Tokens.Muted,
                fontSize = 11.sp,
            )
            wifi.ssids.isEmpty() -> Text("没有启用中的 SSID。", color = Ax6Tokens.Muted, fontSize = 11.sp)
            else -> wifi.ssids.forEachIndexed { index, ssid ->
                if (index > 0) Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Ax6Tokens.Field, RoundedCornerShape(Ax6Dimens.RadiusSm))
                        .border(1.dp, Ax6Tokens.Line, RoundedCornerShape(Ax6Dimens.RadiusSm))
                        .padding(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            ssid.ssid.ifBlank { "(未命名)" },
                            color = Ax6Tokens.Text,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        StatusPill(
                            "${ssid.clients} 台",
                            if (ssid.clients > 0) Tone.OK else Tone.SUBTLE,
                        )
                    }
                    Text(
                        "${ssid.ifname} · ${Fmt.bandLabel(ssid.band)} · 信道 ${if (ssid.channel > 0) ssid.channel else "—"}",
                        color = Ax6Tokens.Muted,
                        fontSize = 10.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "加密 ${ssid.encryption.ifBlank { "—" }}",
                        color = Ax6Tokens.Muted,
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}

private fun protoLabel(proto: String): String = when (proto) {
    "pppoe", "pppoe-wan" -> "PPPoE"
    "dhcp" -> "DHCP"
    "dhcpv6" -> "DHCPv6 客户端"
    "static" -> "静态"
    "none" -> "未配置"
    else -> proto.ifBlank { "—" }
}
