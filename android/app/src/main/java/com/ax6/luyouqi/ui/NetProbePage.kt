package com.ax6.luyouqi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ax6.luyouqi.net.ProbeFamily
import com.ax6.luyouqi.net.ProbeUi
import com.ax6.luyouqi.ui.theme.Ax6Dimens
import com.ax6.luyouqi.ui.theme.Ax6Tokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 公网检测独立页：手机侧直连 Cloudflare cdn-cgi/trace，
 * 并发测 IPv4 / IPv6，展示连通性、出口 IP、节点、TLS、WARP 与 ts。
 */
@Composable
fun NetProbePage(
    probe: ProbeUi,
    onRun: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "←",
                color = Ax6Tokens.Text,
                fontSize = 18.sp,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text("公网检测", color = Ax6Tokens.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Cloudflare trace · 手机侧发起", color = Ax6Tokens.Muted, fontSize = 10.sp)
            }
            Spacer(Modifier.weight(1f))
            val busy = probe is ProbeUi.Running
            Text(
                when {
                    busy -> "检测中…"
                    probe is ProbeUi.Idle -> "开始检测"
                    else -> "重新检测"
                },
                color = if (busy) Ax6Tokens.Muted else Ax6Tokens.Accent,
                fontSize = 11.sp,
                modifier = Modifier
                    .background(Ax6Tokens.AccentSoft, RoundedCornerShape(50))
                    .border(1.dp, Ax6Tokens.AccentEdgeSoft, RoundedCornerShape(50))
                    .clickable(enabled = !busy) { onRun() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        Spacer(Modifier.height(Ax6Dimens.CardGap))

        when (probe) {
            is ProbeUi.Idle -> CardBox {
                CardHead("未检测")
                Spacer(Modifier.height(8.dp))
                Text("点右上角「开始检测」发起一次检测。", color = Ax6Tokens.Text, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Text("IPv4 / IPv6 并发请求 Cloudflare trace，单请求超时 6 秒。", color = Ax6Tokens.Muted, fontSize = 10.sp)
            }

            is ProbeUi.Running -> CardBox {
                CardHead("正在检测")
                Spacer(Modifier.height(8.dp))
                Text("IPv4 / IPv6 并发请求", color = Ax6Tokens.Text, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Text("单请求超时 6 秒，整体最长约 7 秒。", color = Ax6Tokens.Muted, fontSize = 10.sp)
            }

            is ProbeUi.Failed -> CardBox {
                CardHead("检测失败")
                Spacer(Modifier.height(8.dp))
                Text(probe.reason, color = Ax6Tokens.Danger, fontSize = 11.sp)
            }

            is ProbeUi.Done -> {
                val snap = probe.snap
                FamilyCard(snap.v4, listOfNotNull(snap.localV4))
                Spacer(Modifier.height(Ax6Dimens.CardGap))
                FamilyCard(snap.v6, snap.localV6)
                Spacer(Modifier.height(Ax6Dimens.CardGap))
                CardBox {
                    CardHead("链路")
                    Spacer(Modifier.height(6.dp))
                    KvRow("Cloudflare 节点", pick(snap.v4.colo, snap.v6.colo))
                    KvRow("位置", pick(snap.v4.loc, snap.v6.loc))
                    KvRow("TLS", pick(snap.v4.tls, snap.v6.tls))
                    KvRow("WARP", pick(snap.v4.warp, snap.v6.warp))
                    KvRow("检测时间", timeOf(pick(snap.v4.ts, snap.v6.ts), snap.at))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "请求 https://www.cloudflare.com/cdn-cgi/trace",
                        color = Ax6Tokens.Dim,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "检测由手机侧发起，出口 IP 反映当前网络出口。",
                    color = Ax6Tokens.Dim,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun FamilyCard(item: ProbeFamily, locals: List<String>) {
    val cb = LocalClipboardManager.current
    val ip = item.ip
    CardBox {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            CardHead(if (item.v6) "IPv6" else "IPv4")
            Text(
                when {
                    item.ok -> "连接成功"
                    item.error?.startsWith("无 I") == true -> "不可用"
                    else -> "失败"
                },
                color = if (item.ok) Ax6Tokens.Accent else Ax6Tokens.Danger,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(6.dp))
        if (locals.isEmpty()) {
            KvRow("本地 IP", "—", mono = true, wrap = true)
        } else {
            locals.forEachIndexed { index, addr ->
                KvRow(if (index == 0) "本地 IP" else "", addr, mono = true, wrap = true)
            }
        }
        KvRow("出口 IP", ip ?: item.error ?: "—", mono = true, wrap = true)
        KvRow("HTTP", item.http?.toString() ?: "—")
        if (ip != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    "复制出口 IP",
                    color = Ax6Tokens.Accent,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .clickable { cb.setText(AnnotatedString(ip)) }
                        .padding(vertical = 4.dp, horizontal = 2.dp),
                )
            }
        }
    }
}

private fun pick(a: String?, b: String?): String =
    listOfNotNull(a?.takeIf { it.isNotBlank() }, b?.takeIf { it.isNotBlank() })
        .firstOrNull() ?: "—"

/** trace 的 ts 是秒级浮点（如 1795630080.123）；缺失或解析失败时用本次请求时间。 */
private fun timeOf(ts: String?, at: Long): String {
    val value = ts?.toDoubleOrNull()
    val ms = when {
        value == null -> at
        value > 1e11 -> value.toLong()
        else -> (value * 1000).toLong()
    }
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))
}
