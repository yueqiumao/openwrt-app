package com.ax6.luyouqi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ax6.luyouqi.AppUiState
import com.ax6.luyouqi.fmt.Fmt
import com.ax6.luyouqi.ui.theme.Ax6Dimens
import com.ax6.luyouqi.ui.theme.Ax6Tokens

/**
 * 扫描周边 WiFi 独立页：顶部返回、选接口、扫描、结果列表。
 * 数据来自 iwinfo scan，只读，不改路由器配置。
 */
@Composable
fun ScanPage(
    state: AppUiState.Connected,
    onScan: (String) -> Unit,
    onBack: () -> Unit,
) {
    val ssids = state.home?.wifi?.ssids.orEmpty()
    var target by remember(state.scanIfname) {
        mutableStateOf(state.scanIfname.ifBlank { ssids.firstOrNull()?.ifname ?: "" })
    }

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
                Text("扫描WiFi", color = Ax6Tokens.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("只读扫描，不改配置", color = Ax6Tokens.Muted, fontSize = 10.sp)
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (state.scanBusy) "扫描中…" else "开始扫描",
                color = if (state.scanBusy) Ax6Tokens.Muted else Ax6Tokens.Accent,
                fontSize = 11.sp,
                modifier = Modifier
                    .background(Ax6Tokens.AccentSoft, RoundedCornerShape(50))
                    .border(1.dp, Ax6Tokens.AccentEdgeSoft, RoundedCornerShape(50))
                    .clickable(enabled = !state.scanBusy && target.isNotBlank()) { onScan(target) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        Spacer(Modifier.height(Ax6Dimens.CardGap))

        if (ssids.isEmpty()) {
            CardBox {
                Text("暂无可用无线接口。", color = Ax6Tokens.Muted, fontSize = 11.sp)
            }
            return@Column
        }

        CardBox {
            CardHead("选择接口")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ssids.forEach { ssid ->
                    val active = target == ssid.ifname
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (active) Ax6Tokens.AccentSoft else Ax6Tokens.Field,
                                RoundedCornerShape(Ax6Dimens.RadiusSm),
                            )
                            .border(
                                1.dp,
                                if (active) Ax6Tokens.AccentEdgeSoft else Ax6Tokens.Line,
                                RoundedCornerShape(Ax6Dimens.RadiusSm),
                            )
                            .clickable { target = ssid.ifname }
                            .padding(10.dp),
                    ) {
                        Text(
                            Fmt.bandLabel(ssid.band).ifBlank { ssid.ifname },
                            color = if (active) Ax6Tokens.Accent else Ax6Tokens.Text,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "${ssid.ssid.ifBlank { "(未命名)" }} · ${ssid.ifname}",
                            color = Ax6Tokens.Muted,
                            fontSize = 9.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(Ax6Dimens.CardGap))

        CardBox {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                CardHead("扫描结果")
                Text(
                    when {
                        state.scanBusy -> "请稍候"
                        state.scanDone && state.scanResults.isNotEmpty() -> "${state.scanResults.size} 个"
                        state.scanDone -> "无结果"
                        else -> "尚未扫描"
                    },
                    color = Ax6Tokens.Muted,
                    fontSize = 10.sp,
                )
            }
            Spacer(Modifier.height(6.dp))
            when {
                state.scanBusy -> Text("正在扫描，通常 3–8 秒…", color = Ax6Tokens.Muted, fontSize = 11.sp)
                state.scanDone && state.scanResults.isEmpty() ->
                    Text(state.scanError ?: "未扫描到周边 WiFi。", color = Ax6Tokens.Warn, fontSize = 11.sp)
                state.scanResults.isNotEmpty() -> state.scanResults.forEachIndexed { index, ap ->
                    if (index > 0) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Ax6Tokens.Line))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                ap.ssid.ifBlank { "(隐藏 SSID)" },
                                color = Ax6Tokens.Text,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                            )
                            Text(
                                "${Fmt.bandLabel(bandOfValue(ap.band))} · 信道 ${ap.channel} · ${ap.encryption}",
                                color = Ax6Tokens.Muted,
                                fontSize = 9.sp,
                            )
                            Text(
                                ap.bssid,
                                color = Ax6Tokens.Dim,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${ap.signal} dBm", color = signalColor(ap.signal), style = numStyle(12, FontWeight.SemiBold))
                            Text(
                                signalWord(ap.signal),
                                color = Ax6Tokens.Muted,
                                fontSize = 9.sp,
                            )
                        }
                    }
                }
                else -> Text("选择接口后点「开始扫描」。", color = Ax6Tokens.Muted, fontSize = 11.sp)
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

private fun bandOfValue(band: Int): String = when (band) {
    5 -> "5g"
    6 -> "6g"
    else -> "24g"
}

private fun signalColor(signal: Int): androidx.compose.ui.graphics.Color = when {
    signal >= -55 -> Ax6Tokens.Accent
    signal >= -70 -> Ax6Tokens.Warn
    else -> Ax6Tokens.Danger
}

private fun signalWord(signal: Int): String = when {
    signal >= -55 -> "强"
    signal >= -70 -> "中"
    else -> "弱"
}
