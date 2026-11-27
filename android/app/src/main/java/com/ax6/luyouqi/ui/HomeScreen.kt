package com.ax6.luyouqi.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ax6.luyouqi.AppUiState
import com.ax6.luyouqi.domain.Device
import com.ax6.luyouqi.domain.HistoryPoint
import com.ax6.luyouqi.domain.HomeData
import com.ax6.luyouqi.domain.Iface
import com.ax6.luyouqi.domain.Port
import com.ax6.luyouqi.fmt.Fmt
import com.ax6.luyouqi.net.ProbeUi
import com.ax6.luyouqi.ui.theme.Ax6Dimens
import com.ax6.luyouqi.ui.theme.Ax6Tokens
import kotlin.math.max

private val TABS = listOf("首页", "终端", "网络", "设置")

@Composable
fun HomeScreen(
    state: AppUiState.Connected,
    onRefresh: () -> Unit,
    onRange: (Int) -> Unit,
    onTab: (Int) -> Unit,
    onDisconnect: () -> Unit,
    dark: Boolean,
    onToggleTheme: () -> Unit,
    onScan: (String) -> Unit,
    onReboot: () -> Unit,
    manualExit: Boolean,
    onManualExitChange: (Boolean) -> Unit,
    onBackToDesktop: () -> Unit,
    onQuit: () -> Unit,
    probe: ProbeUi = ProbeUi.Idle,
    onProbe: () -> Unit = {},
    readLog: () -> String = { "" },
    onRecheckVantage: () -> Unit = {},
) {
    var selected by remember { mutableStateOf<Device?>(null) }
    var subPage by remember { mutableStateOf(0) }
    var confirmExit by remember { mutableStateOf(false) }

    // 返回键：扫描子页 → 回设置页；手动退出 → 直接回桌面；非首页 tab → 回首页；首页 → 确认退出
    BackHandler(enabled = true) {
        when {
            subPage != 0 -> subPage = 0
            manualExit -> onBackToDesktop()
            state.tab != 0 -> onTab(0)
            else -> confirmExit = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ax6Tokens.Bg)
            .statusBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Ax6Dimens.PagePadding, vertical = 8.dp),
        ) {
            if (subPage == 1) {
                ScanPage(state, onScan = onScan) { subPage = 0 }
            } else if (subPage == 2) {
                NetProbePage(probe, onRun = onProbe) { subPage = 0 }
            } else if (subPage == 3) {
                LogPage(remember { readLog() }) { subPage = 0 }
            } else if (subPage == 4) {
                MoreFeaturesPage(state.caps.vantage, onRecheck = onRecheckVantage) { subPage = 0 }
            } else {
                when (state.tab) {
                    0 -> OverviewTab(state, onRefresh, onRange)
                    1 -> DevicesPage(state.devices, onOpen = { selected = it })
                    2 -> NetworkPage(state)
                    3 -> SettingsTab(
                        state = state,
                        onRefresh = onRefresh,
                        onToggleTheme = onToggleTheme,
                        onDisconnect = onDisconnect,
                        onOpenScan = { subPage = 1 },
                        onReboot = onReboot,
                        dark = dark,
                        manualExit = manualExit,
                        onManualExitChange = onManualExitChange,
                        onOpenProbe = { subPage = 2 },
                        onOpenLog = { subPage = 3 },
                        onOpenMore = { subPage = 4 },
                    )
                    else -> Unit
                }
            }
        }
        if (subPage == 0) BottomNav(state.tab, onTab)
    }

    selected?.let { DeviceDetailDialog(it) { selected = null } }

    if (confirmExit) {
        Dialog(
            onDismissRequest = { confirmExit = false },
            properties = DialogProperties(usePlatformDefaultWidth = true),
        ) {
            PopIn {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Ax6Tokens.CardSolid, RoundedCornerShape(Ax6Dimens.Radius))
                        .border(1.dp, Ax6Tokens.Line, RoundedCornerShape(Ax6Dimens.Radius))
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                ) {
                    Text("退出应用", color = Ax6Tokens.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "退出后将停止后台刷新，重新打开需重新登录路由器。",
                        color = Ax6Tokens.Muted,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { confirmExit = false }) {
                            Text("取消", color = Ax6Tokens.Muted, fontSize = 13.sp)
                        }
                        TextButton(onClick = {
                            confirmExit = false
                            onQuit()
                        }) { Text("确认退出", color = Ax6Tokens.Danger, fontSize = 13.sp) }
                    }
                }
            }
        }
    }
}

// ---- 首页 ----------------------------------------------------------------

@Composable
private fun OverviewTab(
    state: AppUiState.Connected,
    onRefresh: () -> Unit,
    onRange: (Int) -> Unit,
) {
    val data = state.home
    val linkOk = data != null && state.error == null
    var openPort by remember { mutableStateOf<Port?>(null) }

    Card {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(8.dp).background(
                        if (linkOk) Ax6Tokens.Accent else Ax6Tokens.Danger,
                        CircleShape,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (linkOk) "已连接" else "未连接",
                    color = if (linkOk) Ax6Tokens.Accent else Ax6Tokens.Danger,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "↻ 刷新",
                    color = Ax6Tokens.Muted,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable(onClick = onRefresh)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Spacer(Modifier.width(6.dp))
                RangeSeg(state.range, onRange)
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            SpeedBlock("↓ 下载", Fmt.rate(data?.rates?.down ?: 0), Ax6Tokens.Down, Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            SpeedBlock("↑ 上传", Fmt.rate(data?.rates?.up ?: 0), Ax6Tokens.Up, Modifier.weight(1f))
        }

        Spacer(Modifier.height(12.dp))

        // 累计收发作为曲线的手势页脚：手指落在这行也能拖动定位
        RateChart(
            points = state.history,
            windowSec = state.range,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("累计接收 ${data?.let { Fmt.bytes(it.rxTotal) } ?: "—"}", color = Ax6Tokens.Muted, fontSize = 11.sp)
                Text("累计发送 ${data?.let { Fmt.bytes(it.txTotal) } ?: "—"}", color = Ax6Tokens.Muted, fontSize = 11.sp)
            }
        }

        if (state.error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "读取失败：${state.error}（已显示上次数据，恢复后自动刷新）",
                color = Ax6Tokens.Warn,
                fontSize = 11.sp,
            )
        }
    }

    Spacer(Modifier.height(Ax6Dimens.CardGap))

    StatGrid(data)

    Spacer(Modifier.height(Ax6Dimens.CardGap))

    PortsCard(data?.ports ?: emptyList(), onOpen = { openPort = it })

    Spacer(Modifier.height(Ax6Dimens.CardGap))

    WanCard(data)

    Spacer(Modifier.height(Ax6Dimens.CardGap))

    WifiCard(data)

    openPort?.let { PortDetailDialog(it) { openPort = null } }
}

@Composable
private fun SpeedBlock(label: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            color = Ax6Tokens.Text,
            style = numStyle(22),
        )
    }
}

@Composable
private fun RangeSeg(current: Int, onRange: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .background(Ax6Tokens.Field, RoundedCornerShape(50))
            .padding(2.dp),
    ) {
        listOf(60 to "1 分", 300 to "5 分", 3600 to "1 时").forEach { (seconds, label) ->
            val active = current == seconds
            Text(
                label,
                color = if (active) Ax6Tokens.Text else Ax6Tokens.Muted,
                fontSize = 11.sp,
                modifier = Modifier
                    .background(
                        if (active) Ax6Tokens.Card else Ax6Tokens.Field,
                        RoundedCornerShape(50),
                    )
                    .clickable { onRange(seconds) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

private data class ChartSample(val t: Long, val down: Long, val up: Long, val missing: Boolean = false)

/** 相邻两点间隔超过 15 秒视为断档：两侧各补一个 0 点，曲线直接截断，不画长斜线。 */
private fun chartSamples(points: List<HistoryPoint>): List<ChartSample> {
    if (points.size < 2) return points.map { ChartSample(it.t, it.down, it.up) }
    val out = ArrayList<ChartSample>(points.size * 2)
    points.forEachIndexed { i, p ->
        out += ChartSample(p.t, p.down, p.up)
        val next = points.getOrNull(i + 1)
        if (next != null && next.t - p.t > 15_000L) {
            out += ChartSample(p.t + 1_000L, 0, 0, missing = true)
            out += ChartSample(next.t - 1_000L, 0, 0, missing = true)
        }
    }
    return out
}

@Composable
private fun RateChart(
    points: List<HistoryPoint>,
    windowSec: Int,
    modifier: Modifier = Modifier,
    footer: @Composable () -> Unit = {},
) {
    val downColor = Ax6Tokens.Down
    val upColor = Ax6Tokens.Up
    val gridColor = Ax6Tokens.Line
    val dashColor = Ax6Tokens.Muted

    val samples = remember(points) { chartSamples(points) }
    val spanMs = windowSec.coerceAtLeast(60) * 1000f

    // 点选：显示该时刻的入站/出站与虚线，4 秒后自动消失
    var selected by remember { mutableStateOf<Pair<Float, ChartSample>?>(null) }
    LaunchedEffect(selected) {
        if (selected != null) {
            kotlinx.coroutines.delay(4_000L)
            selected = null
        }
    }

    // 手势区覆盖「曲线 + 页脚（累计收发）」整块：整块独占触摸，不参与页面滚动
    Box(
        modifier = modifier.pointerInput(samples, windowSec) {

            fun pick(x: Float, w: Float) {
                if (samples.size < 2 || w <= 0f) return
                val endT = samples.last().t
                val startT = endT - spanMs.toLong()
                val t = startT + ((x / w) * spanMs).toLong()
                val nearest = samples.minByOrNull { kotlin.math.abs(it.t - t) } ?: return
                val frac = ((nearest.t - startT).toFloat() / spanMs).coerceIn(0f, 1f)
                selected = frac to nearest
            }
            // 曲线区域的触摸全部由图表接管（不参与页面滚动）：
            // 按下即选中，按住左右滑动连续定位，松手后 4 秒消失。
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                pick(down.position.x, size.width.toFloat())
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    pick(change.position.x, size.width.toFloat())
                    change.consume()
                    if (!change.pressed) break
                }
            }
        },
    ) {
        Column {
            BoxWithConstraints(Modifier.fillMaxWidth().height(118.dp)) {
        val chartWidth = maxWidth

        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            for (i in 0..4) {
                val y = h * i / 4f
                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }
            if (samples.size < 2) return@Canvas

            val endT = samples.last().t
            val startT = endT - spanMs.toLong()
            val maxValue = max(1L, samples.maxOf { max(it.down, it.up) })
            val top = 8f

            fun x(t: Long): Float = (((t - startT).toFloat() / spanMs) * w).coerceIn(0f, w)
            fun y(v: Long): Float = (h - (v.toFloat() / maxValue) * (h - top)).coerceIn(0f, h)

            val xs = samples.map { x(it.t) }

            // 两次邻域平均做预平滑，去掉 3 秒采样造成的锯齿
            fun smooth(raw: List<Float>): List<Float> {
                var cur = raw
                repeat(2) {
                    cur = cur.mapIndexed { i, v ->
                        when {
                            i == 0 || i == cur.lastIndex -> v
                            else -> (cur[i - 1] + 2f * v + cur[i + 1]) / 4f
                        }
                    }
                }
                return cur
            }

            // 断档（无数据）颜色：深色下用略透明白，浅色底上白线不可见，退回浅灰
            val gapColor = if (Ax6Tokens.dark) Color.White.copy(alpha = 0.40f) else Ax6Tokens.Muted.copy(alpha = 0.30f)

            // 被断档点切开的连续数据区间：彩色曲线只画在有数据的区间
            val runs = ArrayList<IntRange>()
            var runStart = -1
            for (i in samples.indices) {
                if (samples[i].missing) {
                    if (runStart >= 0) {
                        runs += runStart until i
                        runStart = -1
                    }
                } else if (runStart < 0) {
                    runStart = i
                }
            }
            if (runStart >= 0) runs += runStart..samples.lastIndex

            runs.forEach { range ->
                val idx = range.toList()
                if (idx.size < 2) {
                    val p = idx.firstOrNull() ?: return@forEach
                    drawCircle(downColor, 3f, Offset(xs[p], y(samples[p].down)))
                    return@forEach
                }
                val xsRun = idx.map { xs[it] }
                fun splineRun(vals: List<Float>): Path {
                    val path = Path()
                    path.moveTo(xsRun.first(), vals.first())
                    for (i in 0 until vals.size - 1) {
                        val p0 = vals[(i - 1).coerceAtLeast(0)]
                        val p1 = vals[i]
                        val p2 = vals[i + 1]
                        val p3 = vals[(i + 2).coerceAtMost(vals.lastIndex)]
                        val dx = (xsRun[i + 1] - xsRun[i]) / 3f
                        val c1y = (p1 + (p2 - p0) / 6f).coerceIn(0f, h)
                        val c2y = (p2 - (p3 - p1) / 6f).coerceIn(0f, h)
                        path.cubicTo(xsRun[i] + dx, c1y, xsRun[i + 1] - dx, c2y, xsRun[i + 1], p2)
                    }
                    return path
                }
                val downLine = splineRun(smooth(idx.map { y(samples[it].down) }))
                val area = Path().apply {
                    addPath(downLine)
                    lineTo(xsRun.last(), h)
                    lineTo(xsRun.first(), h)
                    close()
                }
                drawPath(
                    area,
                    Brush.verticalGradient(
                        colors = listOf(downColor.copy(alpha = 0.24f), downColor.copy(alpha = 0.02f)),
                        startY = 0f,
                        endY = h,
                    ),
                )
                drawPath(downLine, downColor, style = Stroke(width = 5f, cap = StrokeCap.Round))
                drawPath(
                    splineRun(smooth(idx.map { y(samples[it].up) })),
                    upColor,
                    style = Stroke(width = 4f, cap = StrokeCap.Round),
                )
            }

            // 无数据段：整段铺一层很淡的白，基线处再画一条半透明白线，与低速率（贴底的彩色线）区分
            var gapFrom: Int? = null
            for (i in samples.indices) {
                if (samples[i].missing) {
                    if (gapFrom == null) gapFrom = i
                    else {
                        val a = xs[gapFrom]
                        val b = xs[i]
                        if (b > a) {
                            drawRect(
                                gapColor.copy(alpha = gapColor.alpha * 0.18f),
                                topLeft = Offset(a, 0f),
                                size = Size(b - a, h),
                            )
                            drawLine(
                                gapColor,
                                Offset(a, h - 1.5f),
                                Offset(b, h - 1.5f),
                                strokeWidth = 3f,
                            )
                        }
                        gapFrom = null
                    }
                }
            }

            // 点选虚线
            selected?.let { (frac, _) ->
                val sx = (frac * w).coerceIn(0f, w)
                drawLine(
                    dashColor.copy(alpha = 0.8f),
                    Offset(sx, 0f),
                    Offset(sx, h),
                    strokeWidth = 2f,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                )
            }
        }

        selected?.let { (frac, p) ->
            val xDp = (chartWidth * frac) - 62.dp
            Column(
                modifier = Modifier
                    .offset(x = if (xDp < 0.dp) 0.dp else xDp, y = 2.dp)
                    .width(120.dp)
                    .background(Ax6Tokens.Bg2.copy(alpha = 0.94f), RoundedCornerShape(8.dp))
                    .border(1.dp, Ax6Tokens.Line, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(
                    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date(p.t)),
                    color = Ax6Tokens.Dim,
                    fontSize = 9.sp,
                )
                if (p.missing) {
                    Text("该时段无数据", color = Ax6Tokens.Muted, fontSize = 10.sp)
                }
                Text(if (p.missing) "↓ —" else "↓ ${Fmt.rate(p.down)}", color = Ax6Tokens.Down, style = numStyle(11))
                Text(if (p.missing) "↑ —" else "↑ ${Fmt.rate(p.up)}", color = Ax6Tokens.Up, style = numStyle(11))
            }
        }
            }
            footer()
        }
    }
}

@Composable
private fun StatGrid(data: HomeData?) {
    val system = data?.system
    // 卡片高度随系统字体缩放一起放大，保证固定等高时内容不被裁掉
    val cardHeight = 84.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.6f)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(cardHeight),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatCard(
                title = "CPU 占用",
                value = system?.let { "${Fmt.num(it.cpuPct, 0)}%" } ?: "—",
                sub = system?.let { s ->
                    "${Fmt.num(s.load.getOrElse(0) { 0.0 }, 2)} · " +
                        "${Fmt.num(s.load.getOrElse(1) { 0.0 }, 2)} · " +
                        Fmt.num(s.load.getOrElse(2) { 0.0 }, 2)
                } ?: "—",
                meter = ((system?.cpuPct ?: 0.0) / 100).toFloat().coerceIn(0f, 1f),
                bottomPad = 4.dp,
                valueSubGap = 8.dp,
                tightSub = true,
                modifier = Modifier.weight(1f),
            )
            StatCard(
                title = "内存",
                value = system?.let { "${Fmt.num(it.mem.pct, 1)}%" } ?: "—",
                sub = system?.let { "${Fmt.bytes(it.mem.used)} / ${Fmt.bytes(it.mem.total)}" } ?: "—",
                meter = ((system?.mem?.pct ?: 0.0) / 100).toFloat().coerceIn(0f, 1f),
                bottomPad = 4.dp,
                valueSubGap = 8.dp,
                tightSub = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(cardHeight),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val temps = system?.wifiTemps.orEmpty()
            StatCard(
                title = "温度",
                value = system?.tempC?.let { "${Fmt.num(it, 1)}°" } ?: "—",
                sub = if (temps.isNotEmpty()) {
                    "CPU · WiFi ${temps.joinToString(" / ") { "${Fmt.num(it, 0)}°" }}"
                } else {
                    "CPU · WiFi 传感器不可用"
                },
                modifier = Modifier.weight(1f),
            )
            ClientsCard(data, modifier = Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(cardHeight),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatCard(
                title = "系统运行时间",
                value = system?.let { if (it.uptime > 0) Fmt.durationCN(it.uptime) else "—" } ?: "—",
                sub = system?.let { if (it.uptime > 0) Fmt.durationHMS(it.uptime) else "系统时间不可用" } ?: "—",
                modifier = Modifier.weight(1f),
                valueSize = 15,
            )
            StatCard(
                title = "连接数量",
                value = data?.connections?.toString() ?: "—",
                sub = when {
                    data?.connections == null -> "未读取到 conntrack"
                    data.connectionsMax != null -> "上限 ${data.connectionsMax}"
                    else -> "当前活动连接"
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    sub: String,
    modifier: Modifier = Modifier,
    meter: Float? = null,
    valueSize: Int = 18,
    bottomPad: androidx.compose.ui.unit.Dp = 8.dp,
    valueSubGap: androidx.compose.ui.unit.Dp = 2.dp,
    tightSub: Boolean = false,
) {
    Column(modifier = modifier.fillMaxHeight().statCard(bottomPad)) {
        Text(title, color = Ax6Tokens.Muted, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            color = Ax6Tokens.Text,
            style = numStyle(valueSize),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(valueSubGap))
        if (tightSub) {
            Text(
                sub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontSize = 10.sp,
                    lineHeight = 11.sp,
                    color = Ax6Tokens.Dim,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
            )
        } else {
            Text(
                sub,
                color = Ax6Tokens.Dim,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (meter != null) {
            Spacer(Modifier.height(3.dp))
            Meter(meter)
        }
    }
}

@Composable
private fun ClientsCard(data: HomeData?, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxHeight().statCard()) {
        Text("在线终端", color = Ax6Tokens.Muted, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column {
                Text(
                    data?.wiredClients?.toString() ?: "—",
                    color = Ax6Tokens.Text,
                    style = numStyle(18),
                )
                Spacer(Modifier.height(2.dp))
                Text("有线", color = Ax6Tokens.Dim, fontSize = 10.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    data?.wifi?.clients?.toString() ?: "—",
                    color = Ax6Tokens.Text,
                    style = numStyle(18),
                )
                Spacer(Modifier.height(2.dp))
                Text("WiFi", color = Ax6Tokens.Dim, fontSize = 10.sp)
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    data?.onlineClients?.toString() ?: "—",
                    color = Ax6Tokens.Text,
                    style = numStyle(18),
                )
                Spacer(Modifier.height(2.dp))
                Text("全部", color = Ax6Tokens.Dim, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun Meter(fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(Ax6Tokens.Field, RoundedCornerShape(50)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(4.dp)
                .background(Ax6Tokens.Accent, RoundedCornerShape(50)),
        )
    }
}

@Composable
private fun PortsCard(ports: List<Port>, onOpen: (Port) -> Unit) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("物理网口", color = Ax6Tokens.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                "${ports.count { it.carrier }} / ${ports.size} 已插线",
                color = Ax6Tokens.Muted,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(10.dp))
        if (ports.isEmpty()) {
            Text("固件未返回物理网口信息。", color = Ax6Tokens.Muted, fontSize = 12.sp)
            return@Card
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ports.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { port ->
                        PortTile(port, Modifier.weight(1f), onClick = { onOpen(port) })
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PortTile(port: Port, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    val on = port.carrier
    Column(
        modifier = modifier
            .background(Ax6Tokens.Field, RoundedCornerShape(Ax6Dimens.RadiusSm))
            .border(
                1.dp,
                if (on) Ax6Tokens.Accent.copy(alpha = 0.45f) else Ax6Tokens.Line,
                RoundedCornerShape(Ax6Dimens.RadiusSm),
            )
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(6.dp).background(
                    if (on) Ax6Tokens.Accent else Ax6Tokens.Danger,
                    CircleShape,
                ),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (on) "已连接" else "未插线",
                color = if (on) Ax6Tokens.Accent else Ax6Tokens.Danger,
                fontSize = 10.sp,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(port.label, color = Ax6Tokens.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(
            buildString {
                append(if (on) Fmt.portSpeed(port.speed) else "—")
                if (on && port.duplex == "full") append(" 全双工")
            },
            color = Ax6Tokens.Muted,
            fontSize = 9.sp,
        )
        Spacer(Modifier.height(3.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            RateCell("↓", Fmt.rate(port.viewDownRate))
            RateCell("↑", Fmt.rate(port.viewUpRate))
        }
    }
}

/** 箭头与数值分开排版，间隙 2dp（原来是一个空格，约 5dp，偏松）。 */
@Composable
private fun RateCell(arrow: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            arrow,
            color = Ax6Tokens.Dim,
            fontSize = 9.sp,
            maxLines = 1,
            softWrap = false,
        )
        Spacer(Modifier.width(2.dp))
        Text(
            value,
            color = Ax6Tokens.Dim,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun PortDetailDialog(port: Port, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        PopIn {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Ax6Tokens.CardSolid, RoundedCornerShape(Ax6Dimens.Radius))
                .border(1.dp, Ax6Tokens.Line, RoundedCornerShape(Ax6Dimens.Radius))
                .padding(Ax6Dimens.CardPadding),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(port.label, color = Ax6Tokens.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                Text(port.name, color = Ax6Tokens.Muted, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    if (port.carrier) "连接" else "未连接",
                    color = if (port.carrier) Ax6Tokens.Accent else Ax6Tokens.Danger,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .border(
                            1.dp,
                            (if (port.carrier) Ax6Tokens.Accent else Ax6Tokens.Danger).copy(alpha = 0.5f),
                            RoundedCornerShape(50),
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            Text(
                if (port.carrier) "1 · 已插线" else "0 · 未插线",
                color = Ax6Tokens.Muted,
                fontSize = 11.sp,
            )

            Spacer(Modifier.height(10.dp))

            PortRow("接口名称", port.name)
            PortRow("MAC 地址", port.mac.ifBlank { "—" })
            PortRow("当前速率", if (port.carrier) Fmt.portSpeedText(port.speed) else "—")
            PortRow(
                "双工模式",
                when (port.duplex) {
                    "full" -> "全双工"
                    "half" -> "半双工"
                    else -> "—"
                },
            )
            PortRow("链路状态", if (port.carrier) "载波正常" else "载波断开")
            PortRow("实时收发", "↓ ${Fmt.rate(port.viewDownRate)} · ↑ ${Fmt.rate(port.viewUpRate)}")
            PortRow("累计下行", Fmt.bytes(port.viewDownTotal))
            PortRow("累计上行", Fmt.bytes(port.viewUpTotal))

            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) {
                Text("关闭", color = Ax6Tokens.Muted, fontSize = 13.sp)
            }
        }
        }
    }
}

@Composable
private fun PortRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Ax6Tokens.Muted, fontSize = 11.sp)
        Text(value, color = Ax6Tokens.Text, fontSize = 11.sp)
    }
}

@Composable
private fun WanCard(data: HomeData?) {
    val wan = data?.wan
    val wan6 = data?.wan6
    Card {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("上行网络", color = Ax6Tokens.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("WAN / WAN6", color = Ax6Tokens.Muted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(10.dp))
        WanBlock(
            label = "WAN",
            proto = "IPv4",
            up = wan?.up == true,
            detail = when {
                wan == null -> "无 WAN 接口"
                wan.ipv4First.isNotBlank() -> wan.ipv4First
                else -> "未获取 IPv4"
            },
            meta = if (wan != null) {
                "${Fmt.protoLabel(wan.proto)} · ${wan.device.ifBlank { "—" }} · 网关 ${wan.gateway.ifBlank { "—" }}"
            } else {
                "未找到 wan 接口"
            },
        )
        Spacer(Modifier.height(8.dp))
        val wan6Addrs = wan6?.ipv6.orEmpty()
        WanBlock(
            label = "WAN6",
            proto = "IPv6",
            up = wan6?.up == true && wan6Addrs.isNotEmpty(),
            detail = wan6Addrs.firstOrNull() ?: "未获取 IPv6",
            meta = if (wan6 != null) {
                buildString {
                    append("${wan6Addrs.size} 个 IPv6")
                    append(if (wan6.pd.isNotEmpty()) " · PD ${wan6.pd.first()}" else " · 无 PD")
                }
            } else {
                "未找到 wan6 接口"
            },
        )
    }
}

@Composable
private fun WanBlock(label: String, proto: String, up: Boolean, detail: String, meta: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Ax6Tokens.Field, RoundedCornerShape(Ax6Dimens.RadiusSm))
            .border(
                1.dp,
                if (up) Ax6Tokens.Accent.copy(alpha = 0.4f) else Ax6Tokens.Line,
                RoundedCornerShape(Ax6Dimens.RadiusSm),
            )
            .padding(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(6.dp)
                        .background(if (up) Ax6Tokens.Accent else Ax6Tokens.Danger, CircleShape),
                )
                Spacer(Modifier.width(6.dp))
                Text(label, color = Ax6Tokens.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                Text(proto, color = Ax6Tokens.Muted, fontSize = 10.sp)
            }
            Text(
                if (up) "在线" else "未连接",
                color = if (up) Ax6Tokens.Accent else Ax6Tokens.Danger,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            detail,
            color = Ax6Tokens.Text,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            meta,
            color = Ax6Tokens.Dim,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WifiCard(data: HomeData?) {
    val wifi = data?.wifi
    Card {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("无线网络", color = Ax6Tokens.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("${wifi?.clients ?: 0} 台已连接", color = Ax6Tokens.Muted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(10.dp))
        val ssids = wifi?.ssids.orEmpty()
        if (ssids.isEmpty()) {
            Text(
                if (wifi != null && !wifi.available) "固件未提供无线状态（缺少 iwinfo / network.wireless）" else "暂无无线接口",
                color = Ax6Tokens.Muted,
                fontSize = 12.sp,
            )
            return@Card
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ssids.forEach { ssid ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Ax6Tokens.Field, RoundedCornerShape(Ax6Dimens.RadiusSm))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        Fmt.bandLabel(ssid.band).ifBlank { "WiFi" },
                        color = Ax6Tokens.Accent,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .background(Ax6Tokens.Card, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            ssid.ssid.ifBlank { "(未命名)" },
                            color = Ax6Tokens.Text,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "信道 ${if (ssid.channel > 0) ssid.channel.toString() else "—"} · ${ssid.encryption.ifBlank { "—" }}",
                            color = Ax6Tokens.Dim,
                            fontSize = 10.sp,
                        )
                    }
                    Text(
                        "${ssid.clients} 台",
                        color = Ax6Tokens.Muted,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

// ---- 设置页 ----------------------------------------------------------------

@Composable
private fun SettingsTab(
    state: AppUiState.Connected,
    onRefresh: () -> Unit,
    onToggleTheme: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenScan: () -> Unit,
    onReboot: () -> Unit,
    dark: Boolean,
    manualExit: Boolean,
    onManualExitChange: (Boolean) -> Unit,
    onOpenProbe: () -> Unit = {},
    onOpenLog: () -> Unit = {},
    onOpenMore: () -> Unit = {},
) {
    var confirmReboot by remember { mutableStateOf(false) }
    var pickExitMode by remember { mutableStateOf(false) }

    // 1 设备
    CardBox {
        CardHead("设备")
        Spacer(Modifier.height(6.dp))
        KvRow("主机名", state.hostname.ifBlank { "—" })
        KvRow("设备型号", state.model.ifBlank { "—" })
        KvRow("目标平台", state.target.ifBlank { "—" }, mono = true)
        KvRow("固件版本", state.firmware.ifBlank { "—" }, wrap = true)
        KvRow(
            "运行时长",
            state.home?.system?.uptime?.takeIf { it > 0 }?.let { Fmt.duration(it) } ?: "—",
        )
        KvRow("终端", "${state.home?.onlineClients ?: 0} 台在线")
    }

    Spacer(Modifier.height(Ax6Dimens.CardGap))

    // 2 连接
    CardBox {
        CardHead("连接")
        Spacer(Modifier.height(6.dp))
        KvRow("路由器地址", "http://${state.host}", mono = true)
        KvRow("轮询周期", "3 秒")
    }

    Spacer(Modifier.height(Ax6Dimens.CardGap))

    // 3 工具
    CardBox {
        CardHead("工具")
        Spacer(Modifier.height(2.dp))
        ActionRow("扫描WiFi", "→", onClick = onOpenScan)
        ActionDivider()
        ActionRow("公网检测", "→", onClick = onOpenProbe)
        ActionDivider()
        ActionRow("更多功能", "→", onClick = onOpenMore)
    }

    Spacer(Modifier.height(Ax6Dimens.CardGap))

    // 4 选项
    CardBox {
        CardHead("选项")
        Spacer(Modifier.height(2.dp))
        ActionRow(
            if (dark) "切换到浅色模式" else "切换到深色模式",
            if (dark) "☀️" else "🌙",
            onClick = onToggleTheme,
        )
        ActionDivider()
        ActionRow(
            "退出方式",
            if (manualExit) "手动退出" else "默认退出",
            onClick = { pickExitMode = true },
        )
    }

    Spacer(Modifier.height(Ax6Dimens.CardGap))

    // 5 操作
    CardBox {
        CardHead("操作")
        Spacer(Modifier.height(2.dp))
        ActionRow("立即刷新全部数据", "↻", onClick = onRefresh)
        ActionDivider()
        ActionRow("重启路由器", "需要确认", danger = true, onClick = { confirmReboot = true })
        ActionDivider()
        ActionRow("断开连接", "→", danger = true, onClick = onDisconnect)
        state.notice?.let { message ->
            Spacer(Modifier.height(6.dp))
            Text(message, color = Ax6Tokens.Warn, fontSize = 10.sp)
        }
    }

    Spacer(Modifier.height(Ax6Dimens.CardGap))

    // 6 关于
    CardBox {
        CardHead("关于")
        Spacer(Modifier.height(6.dp))
        KvRow("版本", "v${com.ax6.luyouqi.BuildConfig.VERSION_NAME}")
        Spacer(Modifier.height(2.dp))
        ActionDivider()
        ActionRow("日志", "→", onClick = onOpenLog)
    }

    Spacer(Modifier.height(12.dp))

    if (confirmReboot) {
        Dialog(
            onDismissRequest = { confirmReboot = false },
            properties = DialogProperties(usePlatformDefaultWidth = true),
        ) {
            PopIn {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Ax6Tokens.CardSolid, RoundedCornerShape(Ax6Dimens.Radius))
                        .border(1.dp, Ax6Tokens.Line, RoundedCornerShape(Ax6Dimens.Radius))
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                ) {
                    Text("重启路由器？", color = Ax6Tokens.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "所有设备会短暂断网，路由器约 1–2 分钟后恢复；本 App 会在此期间显示未连接。",
                        color = Ax6Tokens.Muted,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = {
                            confirmReboot = false
                            onReboot()
                        }) { Text("确认重启", color = Ax6Tokens.Danger, fontSize = 13.sp) }
                        TextButton(onClick = { confirmReboot = false }) {
                            Text("取消", color = Ax6Tokens.Muted, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }

    if (pickExitMode) {
        Dialog(
            onDismissRequest = { pickExitMode = false },
            properties = DialogProperties(usePlatformDefaultWidth = true),
        ) {
            PopIn {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Ax6Tokens.CardSolid, RoundedCornerShape(Ax6Dimens.Radius))
                        .border(1.dp, Ax6Tokens.Line, RoundedCornerShape(Ax6Dimens.Radius))
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                ) {
                    Text("退出方式", color = Ax6Tokens.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    ExitModeOption(
                        title = "默认退出",
                        desc = "返回键弹确认框；切到后台 30 秒后自动关闭",
                        selected = !manualExit,
                    ) {
                        onManualExitChange(false)
                        pickExitMode = false
                    }
                    Spacer(Modifier.height(10.dp))
                    ExitModeOption(
                        title = "手动退出",
                        desc = "返回键直接回桌面；切到后台 4 分钟后自动关闭",
                        selected = manualExit,
                    ) {
                        onManualExitChange(true)
                        pickExitMode = false
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { pickExitMode = false }) {
                            Text("取消", color = Ax6Tokens.Muted, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExitModeOption(
    title: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (selected) Ax6Tokens.Accent else Ax6Tokens.Text,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            Spacer(Modifier.height(2.dp))
            Text(desc, color = Ax6Tokens.Muted, fontSize = 11.sp)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            if (selected) "✓" else "",
            color = Ax6Tokens.Accent,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ActionDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ax6Tokens.Line))
}


@Composable
private fun ActionRow(label: String, hint: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (danger) Ax6Tokens.Danger else Ax6Tokens.Text,
            fontSize = 12.sp,
        )
        Text(
            hint,
            color = if (danger) Ax6Tokens.Danger else Ax6Tokens.Muted,
            fontSize = 11.sp,
        )
    }
}


// ---- 通用容器 -------------------------------------------------------------

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    CardBox(content = content)
}

@Composable
private fun Modifier.statCard(bottomPad: androidx.compose.ui.unit.Dp = 8.dp): Modifier = this
    .background(Ax6Tokens.CardSolid, RoundedCornerShape(Ax6Dimens.RadiusSm))
    .border(1.dp, Ax6Tokens.Line, RoundedCornerShape(Ax6Dimens.RadiusSm))
    .padding(start = 12.dp, top = 6.dp, end = 12.dp, bottom = bottomPad)

@Composable
private fun BottomNav(current: Int, onTab: (Int) -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ax6Tokens.Line))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Ax6Tokens.Bg2)
                .navigationBarsPadding()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TABS.forEachIndexed { index, label ->
                val active = current == index
                Text(
                    label,
                    color = if (active) Ax6Tokens.Accent else Ax6Tokens.Muted,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .clickable { onTab(index) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}
