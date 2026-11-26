package com.ax6.luyouqi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.ax6.luyouqi.ui.theme.Ax6Dimens
import com.ax6.luyouqi.ui.theme.Ax6Tokens

/**
 * 运行日志页：展示 crash.log（最近一次崩溃）与 trace.log（最近 40 行事件）。
 * 日志里只有生命周期与退出计时，不含密码、设备信息或业务数据。
 */
@Composable
fun LogPage(
    text: String,
    onBack: () -> Unit,
) {
    val cb = LocalClipboardManager.current
    val body = text.trim()
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
                Text("运行日志", color = Ax6Tokens.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("崩溃栈 + 最近 40 行事件", color = Ax6Tokens.Muted, fontSize = 10.sp)
            }
            Spacer(Modifier.weight(1f))
            if (body.isNotEmpty()) {
                Text(
                    "复制全部",
                    color = Ax6Tokens.Accent,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .background(Ax6Tokens.AccentSoft, RoundedCornerShape(50))
                        .border(1.dp, Ax6Tokens.AccentEdgeSoft, RoundedCornerShape(50))
                        .clickable { cb.setText(AnnotatedString(body)) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        Spacer(Modifier.height(Ax6Dimens.CardGap))

        CardBox {
            if (body.isEmpty()) {
                Text("暂无日志记录。", color = Ax6Tokens.Muted, fontSize = 11.sp)
            } else {
                SelectionContainer {
                    Text(
                        body,
                        color = Ax6Tokens.Muted,
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 13.sp,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "内容只含生命周期与退出计时事件，不含密码、设备信息或业务数据；长按可选中部分文本。",
            color = Ax6Tokens.Dim,
            fontSize = 10.sp,
        )
    }
}
