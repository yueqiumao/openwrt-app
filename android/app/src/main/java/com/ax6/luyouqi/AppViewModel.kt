package com.ax6.luyouqi

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ax6.luyouqi.collect.Normalizer
import com.ax6.luyouqi.collect.RouterRepository
import com.ax6.luyouqi.data.Ax6Db
import com.ax6.luyouqi.data.CredentialStore
import com.ax6.luyouqi.data.HistoryStore
import com.ax6.luyouqi.domain.DeviceSnapshot
import com.ax6.luyouqi.domain.HistoryPoint
import com.ax6.luyouqi.domain.HomeData
import com.ax6.luyouqi.domain.ScanAp
import com.ax6.luyouqi.net.ErrKind
import com.ax6.luyouqi.net.NetProbe
import com.ax6.luyouqi.net.ProbeUi
import com.ax6.luyouqi.net.RouterException
import com.ax6.luyouqi.net.RouterSession
import com.ax6.luyouqi.ui.theme.Ax6Tokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface AppUiState {

    data class Login(
        val busy: Boolean = false,
        val error: String? = null,
        val host: String = "",
        val port: String = "80",
        val username: String = "",
        val password: String = "",
        val secure: Boolean = false,
    ) : AppUiState

    data class Connected(
        val host: String,
        val hostname: String,
        val username: String = "",
        val model: String,
        val firmware: String,
        val target: String,
        val caps: Capabilities,
        val home: HomeData? = null,
        val devices: DeviceSnapshot? = null,
        val history: List<HistoryPoint> = emptyList(),
        val range: Int = 300,
        val tab: Int = 0,
        val error: String? = null,
        val scanBusy: Boolean = false,
        val scanDone: Boolean = false,
        val scanIfname: String = "",
        val scanResults: List<ScanAp> = emptyList(),
        val scanError: String? = null,
        val notice: String? = null,
    ) : AppUiState
}

/** 数据源探测结果，映射成设置页的诊断行。 */
data class ProbeItem(val label: String, val value: String, val ok: Boolean)

data class Capabilities(val items: List<ProbeItem> = emptyList())

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = CredentialStore(app)
    private val historyStore by lazy { HistoryStore(Ax6Db.get(app).history()) }
    val session = RouterSession()

    private var pollJob: Job? = null
    private var repository: RouterRepository? = null

    private val prefs = app.getSharedPreferences("ax6_ui", Application.MODE_PRIVATE)

    private val _dark = MutableStateFlow(prefs.getBoolean("theme_dark", true))
    val dark: StateFlow<Boolean> = _dark

    /** 退出方式：false = 默认退出（返回键确认退出、后台 3 分钟关闭），true = 手动退出（后台 10 分钟关闭）。 */
    private val _foreground = MutableStateFlow(true)
    private val _manualExit = MutableStateFlow(prefs.getBoolean("exit_manual", false))
    val manualExit: StateFlow<Boolean> = _manualExit

    /** 公网检测结果，只在内存里，重开 App 回到 Idle。 */
    private val _probe = MutableStateFlow<ProbeUi>(ProbeUi.Idle)
    val probe: StateFlow<ProbeUi> = _probe
    private var probeJob: Job? = null

    /** 诊断日志（最近一次崩溃栈 + 最近事件），只用于排查「打开即消失」。 */
    private val _diag = MutableStateFlow("")
    val diag: StateFlow<String> = _diag

    init {
        Ax6Tokens.dark = _dark.value
        viewModelScope.launch(Dispatchers.IO) {
            _diag.value = runCatching { DiagLog.report(app) }.getOrDefault("")
        }
    }

    fun toggleTheme() {
        val next = !_dark.value
        _dark.value = next
        Ax6Tokens.dark = next
        prefs.edit().putBoolean("theme_dark", next).apply()
    }

    /** 打开日志页时同步读一次：崩溃栈 + 最近 40 行事件。 */
    fun logReport(): String = runCatching { DiagLog.report(getApplication()) }.getOrDefault("")

    /** 清除诊断日志（崩溃栈 + 事件时间线），并清空设置页展示。 */
    /** 前后台状态：手动退出模式下退到后台时把轮询放慢到 10 秒。 */
    fun setForeground(foreground: Boolean) {
        _foreground.value = foreground
    }

    fun setManualExit(manual: Boolean) {
        _manualExit.value = manual
        prefs.edit().putBoolean("exit_manual", manual).apply()
    }

    /** 完全关闭前停掉轮询，避免退出瞬间再打一次路由器。 */
    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun hasCredentials(): Boolean =
        store.loadProfile() != null && !store.loadPassword().isNullOrBlank()

    /** 冷启动占位首页：字段留空显示 —，登录成功后被真实数据替换。 */
    private fun placeholderHome(): AppUiState.Connected {
        val profile = store.loadProfile()
        return AppUiState.Connected(
            host = profile?.host ?: "192.168.1.1",
            username = profile?.username.orEmpty(),
            hostname = "",
            model = "",
            firmware = "",
            target = "",
            caps = Capabilities(),
        )
    }

    private val _state = MutableStateFlow<AppUiState>(
        if (hasCredentials()) placeholderHome() else initialLogin(),
    )
    val state: StateFlow<AppUiState> = _state

    init {
        // 已保存过账号密码：直接进首页，后台静默登录
        if (_state.value is AppUiState.Connected) {
            viewModelScope.launch { connect() }
        }
    }

    private fun initialLogin(): AppUiState.Login {
        val profile = store.loadProfile()
        return AppUiState.Login(
            host = profile?.host ?: "192.168.1.1",
            port = (profile?.port ?: 80).toString(),
            username = profile?.username ?: "",
            password = store.loadPassword().orEmpty(),
            secure = profile?.secure ?: false,
        )
    }

    fun onHostChange(value: String) = editLogin { it.copy(host = value, error = null) }
    fun onPortChange(value: String) = editLogin { it.copy(port = value.filter(Char::isDigit), error = null) }
    fun onUsernameChange(value: String) = editLogin { it.copy(username = value, error = null) }
    fun onPasswordChange(value: String) = editLogin { it.copy(password = value, error = null) }
    fun onSecureChange(value: Boolean) = editLogin { it.copy(secure = value, error = null) }

    private fun editLogin(block: (AppUiState.Login) -> AppUiState.Login) {
        val current = _state.value as? AppUiState.Login ?: return
        _state.value = block(current)
    }

    fun connect() {
        val current = _state.value
        val coldStart = current is AppUiState.Connected
        val form = (current as? AppUiState.Login) ?: initialLogin()
        if (current is AppUiState.Login && form.busy) return
        if (current is AppUiState.Login) _state.value = form.copy(busy = true, error = null)

        viewModelScope.launch {
            val deadline = System.currentTimeMillis() + if (coldStart) 10_000L else 0L
            var lastError = "登录失败"
            while (true) {
                try {
                    doConnect(form)
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    session.reset()
                    lastError = if (e is RouterException) friendly(e) else "未知错误：${e.message ?: e.javaClass.simpleName}"
                    val authFail = e is RouterException && e.kind == ErrKind.AUTH_REQUIRED
                    if (coldStart && !authFail && System.currentTimeMillis() < deadline) {
                        delay(1_200L)
                        continue
                    }
                    _state.value = form.copy(busy = false, error = lastError)
                    return@launch
                }
            }
        }
    }

    private suspend fun doConnect(form: AppUiState.Login) {
        val port = form.port.toIntOrNull() ?: 80
        session.configure(form.host, port, form.secure, form.username, form.password)
        session.login()

        val board = runCatching { session.call("system", "board").jsonObject }.getOrNull()
        val caps = probeCapabilities()

        store.save(
            CredentialStore.Profile(session.host, port, form.secure, form.username),
            form.password,
        )

        val stored = withContext(Dispatchers.IO) {
            runCatching { historyStore.since(300) }.getOrDefault(emptyList())
        }
        val connected = AppUiState.Connected(
            host = session.host,
            username = session.username,
            hostname = board?.get("hostname")?.jsonPrimitive?.contentOrNull ?: session.host,
            model = modelOf(board),
            firmware = firmwareOf(board),
            target = targetOf(board),
            caps = caps,
            history = stored.filter { it.t >= System.currentTimeMillis() - 310_000L },
        )
        _state.value = connected
        startPolling()
    }

    // ---- 采集循环 ------------------------------------------------------------

    private fun startPolling() {
        pollJob?.cancel()
        val repo = RouterRepository(session).also { repository = it }
        val initial = _state.value as? AppUiState.Connected ?: return
        pollJob = viewModelScope.launch {
            var failures = 0
            // 首轮不等待，立即出数
            while (isActive) {
                val started = System.currentTimeMillis()
                try {
                    val data = withContext(Dispatchers.IO) { repo.sample() }
                    val snapshot = withContext(Dispatchers.IO) { runCatching { repo.devices() }.getOrNull() }
                    failures = 0
                    val current = _state.value as? AppUiState.Connected ?: break
                    _state.value = current.copy(
                        home = data,
                        devices = snapshot ?: current.devices,
                        error = null,
                        history = trim(
                            current.history + HistoryPoint(data.ts, data.rates.down, data.rates.up),
                            current.range,
                        ),
                    )
                    withContext(Dispatchers.IO) {
                        runCatching { historyStore.append(HistoryPoint(data.ts, data.rates.down, data.rates.up)) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures += 1
                    val message = if (e is RouterException) friendly(e) else e.message ?: "读取失败"
                    if (failures >= 3) {
                        session.reset()
                        _state.value = initialLogin().copy(error = "与路由器断开：$message")
                        break
                    }
                    val current = _state.value as? AppUiState.Connected ?: break
                    val now = System.currentTimeMillis()
                    // 失败这一拍按 0 记，曲线立刻归零，不保留上一次的虚假速率
                    _state.value = current.copy(
                        error = message,
                        home = current.home?.copy(rates = com.ax6.luyouqi.domain.Rates()),
                        history = trim(
                            current.history + HistoryPoint(now, 0, 0),
                            current.range,
                        ),
                    )
                }
                val cost = System.currentTimeMillis() - started
                // 手动退出模式退到后台后只慢速保活：10 秒一拍，避免长期 3 秒轮询的唤醒
                val interval = if (!_foreground.value && _manualExit.value) 10_000L else 3_000L
                delay((interval - cost).coerceAtLeast(500L))
            }
        }
    }

    private fun trim(points: List<HistoryPoint>, range: Int): List<HistoryPoint> {
        val since = System.currentTimeMillis() - (range.toLong() + 10) * 1000
        return points.filter { it.t >= since }
    }

    /** iwinfo scan：拉一次周边 WiFi，不动路由器配置。 */
    /** 点「公网检测」进页面时自动跑一次；重复点按会取消上一轮。 */
    fun runProbe() {
        if (_probe.value is ProbeUi.Running) return
        probeJob?.cancel()
        _probe.value = ProbeUi.Running
        probeJob = viewModelScope.launch {
            val result = try {
                NetProbe.run(getApplication())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _probe.value = ProbeUi.Failed(e.message ?: "检测失败")
                return@launch
            }
            _probe.value = ProbeUi.Done(result)
        }
    }

    fun scanWifi(ifname: String) {
        val current = _state.value as? AppUiState.Connected ?: return
        if (current.scanBusy || ifname.isBlank()) return
        val repo = repository ?: return
        _state.value = current.copy(
            scanBusy = true,
            scanDone = false,
            scanIfname = ifname,
            scanResults = emptyList(),
            scanError = null,
        )
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { repo.scan(ifname) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RouterException) {
                _state.value = (_state.value as? AppUiState.Connected)?.copy(
                    scanBusy = false,
                    scanDone = true,
                    scanError = friendly(e),
                ) ?: _state.value
                return@launch
            } catch (e: Exception) {
                _state.value = (_state.value as? AppUiState.Connected)?.copy(
                    scanBusy = false,
                    scanDone = true,
                    scanError = "扫描失败：${e.message ?: "未知错误"}",
                ) ?: _state.value
                return@launch
            }
            _state.value = (_state.value as? AppUiState.Connected)?.copy(
                scanBusy = false,
                scanDone = true,
                scanResults = result,
                scanError = if (result.isEmpty()) "未扫描到周边 WiFi（固件可能限制扫描）" else null,
            ) ?: _state.value
        }
    }

    /** 重启路由器：失败不改变连接状态，只回一条提示。 */
    fun rebootRouter() {
        val repo = repository ?: return
        viewModelScope.launch {
            val message = try {
                withContext(Dispatchers.IO) { repo.reboot() }
                "重启指令已发送，路由器约 1–2 分钟后恢复"
            } catch (e: RouterException) {
                "重启失败：${friendly(e)}"
            } catch (e: Exception) {
                "重启失败：${e.message ?: "未知错误"}"
            }
            val current = _state.value as? AppUiState.Connected ?: return@launch
            _state.value = current.copy(notice = message)
            delay(6_000L)
            val now = _state.value as? AppUiState.Connected ?: return@launch
            if (now.notice == message) _state.value = now.copy(notice = null)
        }
    }

    fun selectRange(seconds: Int) {
        val current = _state.value as? AppUiState.Connected ?: return
        _state.value = current.copy(range = seconds)
        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) {
                runCatching { historyStore.since(seconds) }.getOrDefault(emptyList())
            }
            val latest = _state.value as? AppUiState.Connected ?: return@launch
            val live = trim(latest.history.filter { it.t >= System.currentTimeMillis() - seconds * 1000L }, seconds)
            val merged = (stored + live)
                .distinctBy { it.t }
                .sortedBy { it.t }
            _state.value = latest.copy(history = merged)
        }
    }

    fun selectTab(index: Int) {
        val current = _state.value as? AppUiState.Connected ?: return
        _state.value = current.copy(tab = index)
        // 离开首页就把速率窗口收回 5 分钟：1 分钟视图每拍多打一次路由器，别的页面没必要付这个成本
        if (index != 0 && current.range == 60) selectRange(300)
    }

    fun refresh() {
        val current = _state.value as? AppUiState.Connected ?: return
        _state.value = current.copy(error = null)
        startPolling()
    }

    fun disconnect() {
        pollJob?.cancel()
        pollJob = null
        repository = null
        viewModelScope.launch {
            runCatching { session.logout() }
            _state.value = initialLogin()
        }
    }

    // ---- 数据源探测 ----------------------------------------------------------

    /**
     * 直连优先：真机已证明 `file.read` 与 `luci.vantage wired` 在 `/ubus` 上可用，
     * LuCI 通道（带 sid 的 `/cgi-bin/luci/admin/ubus`）返回 code 5、表单登录 403。
     * 这里不再主动尝试表单登录，避免消耗 rpcd 的登录限速额度。
     */
    private suspend fun probeCapabilities(): Capabilities {
        val items = mutableListOf<ProbeItem>()
        items += ProbeItem("ubus 通道", "已通", true)

        val directConn = runCatching { readConntrackDirect() }
        val conn = directConn.getOrNull()
        items += if (conn != null) {
            ProbeItem("连接数读取", "可读 · $conn（ubus 直连）", true)
        } else {
            val viaLuci = runCatching {
                val payload = session.luciCall("file", "read", conntrackPath())
                payload.jsonObject["data"]?.jsonPrimitive?.contentOrNull?.trim()?.toIntOrNull()
            }.getOrNull()
            if (viaLuci != null) {
                ProbeItem("连接数读取", "可读 · $viaLuci（LuCI）", true)
            } else {
                ProbeItem("连接数读取", "不可用", false)
            }
        }

        val luciCpuText = runCatching {
            session.call("luci", "getCPUUsage").jsonObject["cpuusage"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        val luciCpu = Normalizer.cpuPercent(luciCpuText.orEmpty())
        val statPayload = runCatching {
            session.call("file", "read", JsonObject(mapOf("path" to JsonPrimitive("/proc/stat"))))
        }.getOrNull()
        val statText = statPayload?.jsonObject?.get("data")?.jsonPrimitive?.contentOrNull
        items += when {
            luciCpu != null -> ProbeItem("CPU 用量通道", "已通 · luci（$luciCpu%）", true)
            Normalizer.cpuTicks(statText.orEmpty()) != null -> ProbeItem("CPU 用量通道", "已通 · /proc/stat", true)
            else -> ProbeItem("CPU 用量通道", "未通 · 用负载折算", false)
        }

        val directWired = runCatching { session.call("luci.vantage", "wired") }
        val hosts = (directWired.getOrNull()?.jsonObject?.get("hosts") as? JsonArray)?.size
        items += if (hosts != null) {
            ProbeItem("有线端口映射", "可用 · $hosts 台（ubus 直连）", true)
        } else {
            ProbeItem("有线端口映射", "不可用", false)
        }

        val remarks = runCatching {
            val payload = session.call("uci", "get", JsonObject(mapOf("config" to JsonPrimitive("vantage"))))
            ((payload.jsonObject["values"] as? JsonObject)?.entries ?: emptySet<Map.Entry<String, kotlinx.serialization.json.JsonElement>>())
                .count { (_, v) -> (v as? JsonObject)?.get(".type")?.jsonPrimitive?.contentOrNull == "client" }
        }
        items += if (remarks.isSuccess) {
            ProbeItem("设备备注（vantage）", "可读 · ${remarks.getOrNull()} 条（uci）", true)
        } else {
            ProbeItem("设备备注（vantage）", "不可用", false)
        }

        items += ProbeItem("LuCI 通道", "不使用（ubus 直连已覆盖）", true)
        return Capabilities(items)
    }

    private suspend fun readConntrackDirect(): Int? {
        val payload = session.call("file", "read", conntrackPath())
        return payload.jsonObject["data"]?.jsonPrimitive?.contentOrNull?.trim()?.toIntOrNull()
    }

    private fun conntrackPath(): JsonObject =
        JsonObject(mapOf("path" to JsonPrimitive("/proc/sys/net/netfilter/nf_conntrack_count")))

    private fun describe(error: Throwable?): String {
        if (error == null) return "未知错误"
        if (error !is RouterException) return error.message ?: error.javaClass.simpleName
        val label = when (error.kind) {
            ErrKind.AUTH_REQUIRED -> "被拒绝"
            ErrKind.PERMISSION_DENIED -> "权限不足"
            ErrKind.HTTP_ERROR -> "HTTP 错误"
            ErrKind.TIMEOUT -> "超时"
            ErrKind.UNREACHABLE -> "连不上"
            ErrKind.BAD_RESPONSE -> "响应异常"
            ErrKind.ROUTER_ERROR -> "ubus 报错"
            ErrKind.LOGIN_FAILED -> "登录失败"
            ErrKind.BAD_REQUEST -> "参数错误"
        }
        val code = error.httpCode?.let { " HTTP $it" } ?: error.ubusCode?.let { " ubus $it" } ?: ""
        return "$label$code"
    }

    /** system board 的 model 可能是字符串，也可能是 { id, name } 对象（与 collector.js 一致）。 */
    private fun modelOf(board: JsonObject?): String {
        val model = board?.get("model")
        (model as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotBlank()) return it }
        (model as? JsonObject)?.let { obj ->
            obj["name"]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotBlank()) return it }
            obj["id"]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotBlank()) return it }
        }
        return board?.get("board_name")?.jsonPrimitive?.contentOrNull ?: "Redmi AX6"
    }

    /** 固件版本取 board.release，不能用 luci getVersion（那是 LuCI 自己的版本号）。 */
    private fun firmwareOf(board: JsonObject?): String {
        val release = board?.get("release") as? JsonObject ?: return ""
        release["description"]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotBlank()) return it }
        val parts = listOf("distribution", "version")
            .mapNotNull { release[it]?.jsonPrimitive?.contentOrNull }
            .filter { it.isNotBlank() }
        return parts.joinToString(" ")
    }

    private fun targetOf(board: JsonObject?): String {
        val release = board?.get("release") as? JsonObject
        return release?.get("target")?.jsonPrimitive?.contentOrNull
            ?: board?.get("target")?.jsonPrimitive?.contentOrNull
            ?: ""
    }

    private fun friendly(e: RouterException): String = when (e.kind) {
        ErrKind.LOGIN_FAILED ->
            if (e.ubusCode == 6) "登录失败：用户名或密码错误" else "登录失败：rpcd 拒绝登录（ubus code ${e.ubusCode}）"
        ErrKind.AUTH_REQUIRED -> "会话已失效，请重新登录"
        ErrKind.UNREACHABLE -> "连不上路由器：检查 IP、端口，手机是否与路由器同一网络"
        ErrKind.TIMEOUT -> "请求超时：路由器忙或网络不稳"
        ErrKind.PERMISSION_DENIED -> "权限不足：当前账号不允许该操作"
        ErrKind.HTTP_ERROR -> "路由器返回 HTTP ${e.httpCode}，确认地址指向 LuCI"
        ErrKind.BAD_RESPONSE -> "响应不是合法 JSON-RPC：地址可能指向了别的服务"
        ErrKind.ROUTER_ERROR -> "路由器报错：${e.message}"
        ErrKind.BAD_REQUEST -> e.message ?: "请求参数不合法"
    }
}
