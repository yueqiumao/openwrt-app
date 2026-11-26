package com.ax6.luyouqi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 设计 token 直接取自网页版 public/css/app.css 的 `:root` 与 `html[data-theme="light"]`，
 * 不是看截图估的颜色。
 */
object Ax6Tokens {
    /** 当前是否深色。切换后所有 token getter 立即返回新色值，读它们的 Composable 会重组。 */
    var dark by mutableStateOf(true)

    // 深色（网页版默认，取自 :root）
    private val DBg = Color(0xFF06090D)
    private val DBg2 = Color(0xFF0A1016)
    private val DCard = Color(0xFF111A23)
    private val DCardSolid = Color(0xFF101821)
    private val DField = Color(0xFF0B131B)
    private val DLine = Color(0x24A1B3C7)
    private val DText = Color(0xFFE8EFF7)
    private val DMuted = Color(0xFF93A2B4)
    private val DDim = Color(0xFF63727F)
    private val DAccent = Color(0xFF3DDC97)
    private val DDown = Color(0xFF3DDC97)
    private val DUp = Color(0xFF62A8FF)
    private val DWarn = Color(0xFFF5B64A)
    private val DDanger = Color(0xFFFF6B6B)

    // 浅色（取自 html[data-theme="light"]）
    private val LBg = Color(0xFFECEFF5)
    private val LBg2 = Color(0xFFF3F5F9)
    private val LCard = Color(0xFFFFFFFF)
    private val LCardSolid = Color(0xFFFFFFFF)
    private val LField = Color(0xFFF4F6FA)
    private val LLine = Color(0x1F0F172A)
    private val LText = Color(0xFF0F172A)
    private val LMuted = Color(0xFF5D6B7E)
    private val LDim = Color(0xFF8B98A9)
    private val LAccent = Color(0xFF0F9D6B)
    private val LDown = Color(0xFF0F9D6B)
    private val LUp = Color(0xFF2563EB)
    private val LWarn = Color(0xFFB45309)
    private val LDanger = Color(0xFFDC2626)

    val Bg: Color get() = if (dark) DBg else LBg
    val Bg2: Color get() = if (dark) DBg2 else LBg2
    val Card: Color get() = if (dark) DCard else LCard
    val CardSolid: Color get() = if (dark) DCardSolid else LCardSolid
    val Field: Color get() = if (dark) DField else LField
    val Line: Color get() = if (dark) DLine else LLine
    val Text: Color get() = if (dark) DText else LText
    val Muted: Color get() = if (dark) DMuted else LMuted
    val Dim: Color get() = if (dark) DDim else LDim
    val Accent: Color get() = if (dark) DAccent else LAccent
    val Down: Color get() = if (dark) DDown else LDown
    val Up: Color get() = if (dark) DUp else LUp
    val Warn: Color get() = if (dark) DWarn else LWarn
    val Danger: Color get() = if (dark) DDanger else LDanger

    // 供 Material3 配色方案使用（不随开关变化，仅初始化时读一次）
    val LightBackground = LBg
    val LightSurface = LCardSolid
    val LightTextColor = LText
    val LightMutedColor = LMuted
    val LightAccentValue = LAccent
    val LightUpValue = LUp
    val LightFieldValue = LField
    val LightDangerValue = LDanger

    // 视觉层（阶段 4）：卡片渐变、描边、阴影、强调色底
    private val DCardTop = Color(0xFF17222E)
    private val DCardBottom = Color(0xFF0D151D)
    private val DCardEdgeTop = Color(0x33A9C4DE)
    private val DCardEdgeBottom = Color(0x0AA9C4DE)
    private val DCardShadow = Color(0x66000000)
    private val LCartTop = Color(0xFFFFFFFF)
    private val LCartBottom = Color(0xFFFAFCFF)
    private val LCardEdgeTop = Color(0x1F0F172A)
    private val LCardEdgeBottom = Color(0x080F172A)

    val CardTop: Color get() = if (dark) DCardTop else LCartTop
    val CardBottom: Color get() = if (dark) DCardBottom else LCartBottom
    val CardEdgeTop: Color get() = if (dark) DCardEdgeTop else LCardEdgeTop
    val CardEdgeBottom: Color get() = if (dark) DCardEdgeBottom else LCardEdgeBottom
    val CardShadow: Color get() = if (dark) DCardShadow else Color(0x220F172A)
    val AccentSoft: Color get() = (if (dark) DAccent else LAccent).copy(alpha = 0.12f)
    val AccentEdgeSoft: Color get() = (if (dark) DAccent else LAccent).copy(alpha = 0.45f)
}

object Ax6Dimens {
    val Radius = 18.dp
    val RadiusSm = 12.dp
    val PagePadding = 14.dp
    val CardGap = 12.dp
    val CardPadding = 14.dp
}

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF3DDC97),
    onPrimary = Color(0xFF04140D),
    secondary = Color(0xFF62A8FF),
    onSecondary = Color(0xFF04101F),
    background = Color(0xFF06090D),
    onBackground = Color(0xFFE8EFF7),
    surface = Color(0xFF101821),
    onSurface = Color(0xFFE8EFF7),
    surfaceVariant = Color(0xFF0B131B),
    onSurfaceVariant = Color(0xFF93A2B4),
    outline = Color(0x24A1B3C7),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF1A0505),
)

private val LightScheme = lightColorScheme(
    primary = Ax6Tokens.LightAccentValue,
    onPrimary = Color.White,
    secondary = Ax6Tokens.LightUpValue,
    onSecondary = Color.White,
    background = Ax6Tokens.LightBackground,
    onBackground = Ax6Tokens.LightTextColor,
    surface = Ax6Tokens.LightSurface,
    onSurface = Ax6Tokens.LightTextColor,
    surfaceVariant = Ax6Tokens.LightFieldValue,
    onSurfaceVariant = Ax6Tokens.LightMutedColor,
    outline = Color(0x1F0F172A),
    error = Ax6Tokens.LightDangerValue,
    onError = Color.White,
)

@Composable
fun Ax6Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
