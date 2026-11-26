package com.ax6.luyouqi.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 错误分类，UI 只按 kind 判断，不匹配中文文案。 */
enum class ErrKind {
    UNREACHABLE,
    TIMEOUT,
    LOGIN_FAILED,
    AUTH_REQUIRED,
    PERMISSION_DENIED,
    ROUTER_ERROR,
    HTTP_ERROR,
    BAD_RESPONSE,
    BAD_REQUEST,
}

class RouterException(
    message: String,
    val kind: ErrKind,
    val ubusCode: Int? = null,
    val httpCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/** ubus 状态码（与 server/ubus.js 的 STATUS_TEXT 一致）。 */
val UBUS_STATUS_TEXT: Map<Int, String> = mapOf(
    0 to "OK",
    1 to "INVALID_COMMAND",
    2 to "INVALID_ARGUMENT",
    3 to "METHOD_NOT_FOUND",
    4 to "NOT_FOUND",
    5 to "PERMISSION_DENIED",
    6 to "TIMEOUT",
    7 to "NOT_SUPPORTED",
    8 to "UNKNOWN_ERROR",
    9 to "CONNECTION_FAILED",
)

object RpcJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    val emptyParams: JsonObject = JsonObject(emptyMap())
}

/** 登录用的全 0 匿名会话。 */
const val ANON_SESSION: String = "00000000000000000000000000000000"

internal fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject
