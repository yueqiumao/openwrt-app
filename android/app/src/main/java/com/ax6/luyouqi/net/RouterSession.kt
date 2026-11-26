package com.ax6.luyouqi.net

import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * 路由器 RPC 会话。
 *
 * 两条通道（与 server/ubus.js 完全一致）：
 * - `/ubus`：params = [sid, object, method, params]，共 4 项，第 4 项必须是对象；
 * - `/cgi-bin/luci/admin/ubus`：params = [object, method, params]，会话靠 Cookie: sysauth=<sid>。
 *
 * 同一个路由器只允许一个在途请求（Mutex 包住全部调用），避免 uhttpd 并发卡死。
 */
class RouterSession(
    private val client: OkHttpClient = defaultClient(),
) {
    private val json = RpcJson.instance
    private val gate = Mutex()

    private var idSeq = 0L

    @Volatile var host: String = ""
        private set
    @Volatile var port: Int = 80
        private set
    @Volatile var secure: Boolean = false
        private set
    @Volatile var username: String = "root"
        private set

    private var password: String = ""

    @Volatile private var sid: String? = null
    @Volatile private var sidExpiresAt: Long = 0L

    val baseUrl: String
        get() = "${if (secure) "https" else "http"}://${host.trim()}:$port"

    val isLoggedIn: Boolean
        get() = sid != null && System.currentTimeMillis() < sidExpiresAt

    fun configure(host: String, port: Int = 80, secure: Boolean = false, username: String = "root", password: String = "") {
        this.host = host.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        this.port = port
        this.secure = secure
        this.username = username.ifBlank { "root" }
        this.password = password
        reset()
    }

    fun reset() {
        sid = null
        sidExpiresAt = 0L
    }

    /** 登录并缓存 sid。会占用请求闸门，不要在已持锁的代码里调用。 */
    suspend fun login(force: Boolean = false): String = gate.withLock { loginLocked(force) }

    /** 通用 ubus 调用（`/ubus`，4 项参数）。 */
    suspend fun call(objectName: String, method: String, params: JsonObject = RpcJson.emptyParams): JsonElement =
        gate.withLock {
            doCall(loginLocked(false), objectName, method, params, retried = false)
        }

    /** LuCI RPC（`/cgi-bin/luci/admin/ubus`，3 项参数，sid/cookie 走 Cookie）。 */
    suspend fun luciCall(objectName: String, method: String, params: JsonObject = RpcJson.emptyParams): JsonElement =
        gate.withLock {
            doLuciCall(loginLocked(false), objectName, method, params, retried = false)
        }

    suspend fun logout() {
        gate.withLock {
            val session = sid ?: return@withLock
            runCatching {
                val body = buildCall(nextId(), JsonArray(listOf(
                    JsonPrimitive(session), JsonPrimitive("session"), JsonPrimitive("logout"), RpcJson.emptyParams,
                )))
                post("/ubus", body, null)
            }
            reset()
        }
    }

    // ---- 内部实现 -----------------------------------------------------------

    private suspend fun loginLocked(force: Boolean): String {
        val cached = sid
        if (!force && cached != null && System.currentTimeMillis() < sidExpiresAt) return cached

        val params = JsonObject(mapOf(
            "username" to JsonPrimitive(username),
            "password" to JsonPrimitive(password),
            "timeout" to JsonPrimitive(3600),
        ))
        val body = buildCall(nextId(), JsonArray(listOf(
            JsonPrimitive(ANON_SESSION), JsonPrimitive("session"), JsonPrimitive("login"), params,
        )))
        val text = post("/ubus", body, null)
        val status = statusOf(parse(text, "/ubus"), "session", "login")
        if (status.first != 0) {
            throw RouterException(
                "登录失败：用户名或密码错误，或 rpcd 拒绝登录（ubus code ${status.first}）",
                ErrKind.LOGIN_FAILED,
                ubusCode = status.first,
            )
        }
        val data = status.second?.jsonObject
            ?: throw RouterException("登录响应缺少数据", ErrKind.LOGIN_FAILED)
        val newSid = data["ubus_rpc_session"]?.jsonPrimitive?.contentOrNull
            ?: throw RouterException("登录成功但响应里没有 ubus_rpc_session", ErrKind.LOGIN_FAILED)
        val ttl = data["timeout"]?.jsonPrimitive?.longOrNull ?: 3600L
        sid = newSid
        sidExpiresAt = System.currentTimeMillis() + max(60L, ttl - 90L) * 1000L
        return newSid
    }

    private suspend fun doCall(
        session: String,
        objectName: String,
        method: String,
        params: JsonObject,
        retried: Boolean,
    ): JsonElement {
        val body = buildCall(nextId(), JsonArray(listOf(
            JsonPrimitive(session), JsonPrimitive(objectName), JsonPrimitive(method), params,
        )))
        val text = post("/ubus", body, null)
        val response = parse(text, "/ubus")
        val (status, payload) = statusOf(response, objectName, method)
        if (status != 0) {
            // 会话过期：rpcd 会返回 PERMISSION_DENIED，重登后再试一次
            if (status == 5 && !retried) {
                reset()
                return doCall(loginLocked(true), objectName, method, params, retried = true)
            }
            throw ubusError(objectName, method, status, payload)
        }
        return payload ?: JsonPrimitive("")
    }

    private suspend fun doLuciCall(
        session: String,
        objectName: String,
        method: String,
        params: JsonObject,
        retried: Boolean,
    ): JsonElement {
        val body = buildCall(nextId(), JsonArray(listOf(
            JsonPrimitive(objectName), JsonPrimitive(method), params,
        )))
        val text = post("/cgi-bin/luci/admin/ubus", body, session)
        val response = parse(text, "/cgi-bin/luci/admin/ubus")
        val (status, payload) = statusOf(response, objectName, method)
        if (status != 0) {
            if (status == 5 && !retried) {
                // 只重置 ubus 会话，保留表单登录换来的 LuCI cookie
                sid = null
                sidExpiresAt = 0L
                return doLuciCall(loginLocked(true), objectName, method, params, retried = true)
            }
            throw ubusError(objectName, method, status, payload)
        }
        return payload ?: JsonPrimitive("")
    }

    private fun ubusError(objectName: String, method: String, status: Int, payload: JsonElement?): RouterException {
        val text = UBUS_STATUS_TEXT[status] ?: "code $status"
        val kind = if (status == 5) ErrKind.PERMISSION_DENIED else ErrKind.ROUTER_ERROR
        return RouterException("$objectName.$method 调用失败：$text", kind, ubusCode = status)
    }

    private fun nextId(): Long {
        idSeq += 1
        return idSeq
    }

    private fun buildCall(id: Long, params: JsonArray): JsonObject = JsonObject(mapOf(
        "jsonrpc" to JsonPrimitive("2.0"),
        "id" to JsonPrimitive(id),
        "method" to JsonPrimitive("call"),
        "params" to params,
    ))

    private fun parse(text: String, path: String): JsonObject {
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw RouterException("$path 返回了非 JSON 内容：请确认地址指向 LuCI（rpcd）", ErrKind.BAD_RESPONSE, cause = e)
        }
        val obj = element as? JsonObject
            ?: throw RouterException("$path 响应不是 JSON 对象", ErrKind.BAD_RESPONSE)
        val rpcError = obj["error"] as? JsonObject
        if (rpcError != null) {
            val message = rpcError["message"]?.jsonPrimitive?.contentOrNull ?: "Access denied"
            val kind = if (Regex("access|denied|permission", RegexOption.IGNORE_CASE).containsMatchIn(message)) {
                ErrKind.PERMISSION_DENIED
            } else {
                ErrKind.ROUTER_ERROR
            }
            throw RouterException("$path 调用被拒绝：$message", kind)
        }
        return obj
    }

    private fun statusOf(response: JsonObject, objectName: String, method: String): Pair<Int, JsonElement?> {
        val result = response["result"] as? JsonArray
            ?: throw RouterException("ubus 响应格式异常（$objectName.$method）", ErrKind.BAD_RESPONSE)
        val status = result.getOrNull(0)?.jsonPrimitive?.intOrNull
            ?: throw RouterException("ubus 响应缺少状态码（$objectName.$method）", ErrKind.BAD_RESPONSE)
        return status to result.getOrNull(1)
    }

    private suspend fun post(path: String, body: JsonObject, cookie: String?): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(baseUrl + path)
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", "application/json")
            .header("User-Agent", "AX6-Assistant/1.3 (Android; +luci-ubus)")

        if (cookie != null) builder.header("Cookie", "sysauth=$cookie")

        val request = builder.post(body.toString().toRequestBody(JSON_MEDIA)).build()
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                when {
                    response.code == 401 || response.code == 403 ->
                        throw RouterException("会话无效（HTTP ${response.code}）", ErrKind.AUTH_REQUIRED, httpCode = response.code)
                    !response.isSuccessful ->
                        throw RouterException("$path 返回 HTTP ${response.code}", ErrKind.HTTP_ERROR, httpCode = response.code)
                }
                text
            }
        } catch (e: SocketTimeoutException) {
            throw RouterException("请求超时（$path）", ErrKind.TIMEOUT, cause = e)
        } catch (e: IOException) {
            throw RouterException("无法连接路由器：${e.message ?: "网络错误"}", ErrKind.UNREACHABLE, cause = e)
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
