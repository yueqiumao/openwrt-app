package com.ax6.luyouqi.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ax6.luyouqi.ui.theme.Ax6Dimens
import com.ax6.luyouqi.ui.theme.Ax6Tokens

/** 通用卡片容器（网络页/设置页共用）。 */
@Composable
fun CardBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Ax6Tokens.CardSolid, RoundedCornerShape(Ax6Dimens.Radius))
            .border(1.dp, Ax6Tokens.Line, RoundedCornerShape(Ax6Dimens.Radius))
            .padding(Ax6Dimens.CardPadding),
        content = content,
    )
}

/** 卡片标题行：左侧标题 + 右侧可选说明。 */
@Composable
fun CardHead(title: String, hint: String = "") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Ax6Tokens.Text, fontSize = 13.sp)
        if (hint.isNotBlank()) {
            Text(
                hint,
                color = Ax6Tokens.Muted,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** 键值行：右侧值过长时可换行（长 IPv6/域名用）。 */
@Composable
fun KvRow(
    label: String,
    value: String,
    mono: Boolean = false,
    wrap: Boolean = false,
    accent: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            color = Ax6Tokens.Muted,
            fontSize = 11.sp,
            modifier = Modifier.padding(end = 10.dp),
        )
        Text(
            value,
            color = if (accent) Ax6Tokens.Accent else Ax6Tokens.Text,
            fontSize = 11.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            textAlign = TextAlign.End,
            maxLines = if (wrap) 2 else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 状态胶囊：已连接 / 未连接 / 获取中。 */
@Composable
fun StatusPill(text: String, tone: Tone) {
    val color = when (tone) {
        Tone.OK -> Ax6Tokens.Accent
        Tone.BAD -> Ax6Tokens.Danger
        Tone.WARN -> Ax6Tokens.Warn
        Tone.SUBTLE -> Ax6Tokens.Muted
    }
    Text(
        text,
        color = color,
        fontSize = 9.sp,
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

enum class Tone { OK, BAD, WARN, SUBTLE }

/** 等宽文本：在单行完整显示的前提下取最大字号（上限 11sp，下限 7sp）。 */
@Composable
fun MonoFitText(
    text: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = Ax6Tokens.Text,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        val maxWidthPx = constraints.maxWidth
        var size = 11f
        while (size > 7f) {
            val layout = measurer.measure(
                text = AnnotatedString(text),
                style = TextStyle(fontSize = size.sp, fontFamily = FontFamily.Monospace),
                maxLines = 1,
                softWrap = false,
            )
            if (layout.size.width <= maxWidthPx) break
            size -= 0.5f
        }
        Text(
            text,
            color = color,
            fontSize = size.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 弹窗入场：缩放 + 淡入（180ms）。 */
@Composable
fun PopIn(content: @Composable () -> Unit) {
    val scale = remember { Animatable(0.94f) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        scale.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
        alpha.animateTo(1f, tween(150))
    }
    Box(Modifier.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
        this.alpha = alpha.value
    }) { content() }
}

/** 数值文本样式：等宽数字（tnum），避免速率/百分比跳动。 */
fun numStyle(size: Int, weight: FontWeight = FontWeight.SemiBold, mono: Boolean = false): TextStyle =
    TextStyle(
        fontSize = size.sp,
        fontWeight = weight,
        fontFeatureSettings = "tnum",
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
    )

/** 空值占位，统一 `—`，不补 0。 */
fun dash(value: String?): String = value?.takeIf { it.isNotBlank() } ?: "—"

fun dash(value: Int?): String = value?.toString() ?: "—"

fun dash(value: Long?): String = value?.toString() ?: "—"
