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
import androidx.compose.foundation.text.selection.SelectionContainer
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
import com.ax6.luyouqi.VantageProbe
import com.ax6.luyouqi.VantageState
import com.ax6.luyouqi.ui.theme.Ax6Dimens
import com.ax6.luyouqi.ui.theme.Ax6Tokens

/**
 * 「更多功能」页：目前只有 Vantage 一张卡。
 *
 * 卡片只做两件事——说明装了什么插件能解锁哪些功能、显示路由器上到底装没装。
 * 功能开关不在这里：能力由路由器决定，装了就自动生效，没装就自动退回默认口径。
 */
@Composable
fun MoreFeaturesPage(
    vantage: VantageProbe,
    onRecheck: () -> Unit,
    onBack: () -> Unit,
) {
    val cb = LocalClipboardManager.current
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
                Text("更多功能", color = Ax6Tokens.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("路由器插件解锁的增强能力", color = Ax6Tokens.Muted, fontSize = 10.sp)
            }
        }

        Spacer(Modifier.height(Ax6Dimens.CardGap))

        CardBox {
            CardHead("Vantage")
            Spacer(Modifier.height(6.dp))
            Text(
                "路由器安装 Vantage 仪表盘后，启用功能：有线设备显示实时速率，累计流量" +
                    "（获取对应端口数据，一个端口下多设备时，为共享这个数据）、设备所在网口、同端口设备数。" +
                    "没装则以 ARP 邻居表判断在线，不显示端口信息。",
                color = Ax6Tokens.Muted,
                fontSize = 11.sp,
                lineHeight = 17.sp,
            )

            Spacer(Modifier.height(10.dp))

            // 项目地址：可长按选中、也可一键复制；刻意不做超链接，避免误触跳浏览器
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("项目地址", color = Ax6Tokens.Muted, fontSize = 11.sp)
                Text(
                    "复制",
                    color = Ax6Tokens.Accent,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .background(Ax6Tokens.AccentSoft, RoundedCornerShape(50))
                        .border(1.dp, Ax6Tokens.AccentEdgeSoft, RoundedCornerShape(50))
                        .clickable { cb.setText(AnnotatedString(VANTAGE_URL)) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            SelectionContainer {
                Text(
                    VANTAGE_URL,
                    color = Ax6Tokens.Text,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("路由器状态", color = Ax6Tokens.Muted, fontSize = 11.sp)
                StatusPill(vantageLabel(vantage), vantageTone(vantage))
            }
            if (vantage.message.isNotBlank()) {
                Text(
                    vantage.message,
                    color = Ax6Tokens.Dim,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(10.dp))

            Text(
                "重新检测",
                color = Ax6Tokens.Accent,
                fontSize = 12.sp,
                modifier = Modifier
                    .background(Ax6Tokens.AccentSoft, RoundedCornerShape(50))
                    .border(1.dp, Ax6Tokens.AccentEdgeSoft, RoundedCornerShape(50))
                    .clickable(onClick = onRecheck)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "装上插件后无需重连，点「重新检测」即可生效；卸载后同样点一次就会退回默认口径。",
            color = Ax6Tokens.Dim,
            fontSize = 10.sp,
        )
    }
}

private const val VANTAGE_URL = "anothadev.github.io/luci-theme-vantage"

private fun vantageLabel(v: VantageProbe): String = when (v.state) {
    VantageState.INSTALLED -> "已安装"
    VantageState.MISSING -> "未安装"
    VantageState.DENIED -> "已安装 · 未放行"
    VantageState.UNKNOWN -> "检测失败"
}

private fun vantageTone(v: VantageProbe): Tone = when (v.state) {
    VantageState.INSTALLED -> Tone.OK
    VantageState.MISSING -> Tone.SUBTLE
    VantageState.DENIED -> Tone.WARN
    VantageState.UNKNOWN -> Tone.BAD
}
