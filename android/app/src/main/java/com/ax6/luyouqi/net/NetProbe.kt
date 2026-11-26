package com.ax6.luyouqi.net

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.ConnectException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** 单个地址族的检测结果。 */
data class ProbeFamily(
    val v6: Boolean,
    val ok: Boolean,
    val http: Int? = null,
    val ip: String? = null,
    val colo: String? = null,
    val loc: String? = null,
    val tls: String? = null,
    val warp: String? = null,
    val ts: String? = null,
    val error: String? = null,
)

data class ProbeSnapshot(
    val v4: ProbeFamily,
    val v6: ProbeFamily,
    val localV4: String?,
    /** 本机全部公网 IPv6（排除回环、链路本地 fe80::/10、ULA fc00::/7）。 */
    val localV6: List<String>,
    val at: Long,
)

sealed interface ProbeUi {
    data object Idle : ProbeUi
    data object Running : ProbeUi
    data class Done(val snap: ProbeSnapshot) : ProbeUi
    data class Failed(val reason: String) : ProbeUi
}

/**
 * 公网连通性检测：手机侧直连 Cloudflare 的 cdn-cgi/trace，
 * IPv4 / IPv6 各发一个请求，用自定义 Dns 强制地址族，SNI 仍是域名、证书校验不受影响。
 */
object NetProbe {
    private const val HOST = "www.cloudflare.com"
    private const val URL = "https://$HOST/cdn-cgi/trace"
    private const val TIMEOUT_S = 6L

    suspend fun run(context: Context): ProbeSnapshot = coroutineScope {
        val local = withContext(Dispatchers.IO) { localAddresses(context) }
        val v4 = async(Dispatchers.IO) { probe(v6 = false) }
        val v6 = async(Dispatchers.IO) { probe(v6 = true) }
        ProbeSnapshot(
            v4 = v4.await(),
            v6 = v6.await(),
            localV4 = local.first,
            localV6 = local.second,
            at = System.currentTimeMillis(),
        )
    }

    private fun probe(v6: Boolean): ProbeFamily {
        val family = if (v6) "IPv6" else "IPv4"
        return try {
            val addrs = InetAddress.getAllByName(HOST)
                .filter { if (v6) it is Inet6Address else it is Inet4Address }
            if (addrs.isEmpty()) return ProbeFamily(v6 = v6, ok = false, error = "无 $family 地址")

            val client = OkHttpClient.Builder()
                .callTimeout(TIMEOUT_S, TimeUnit.SECONDS)
                .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> = addrs
                })
                .build()

            client.newCall(
                Request.Builder()
                    .url(URL)
                    .header("User-Agent", "AX6Assistant")
                    .build(),
            ).execute().use { resp ->
                val map = parseTrace(resp.body?.string().orEmpty())
                ProbeFamily(
                    v6 = v6,
                    ok = resp.isSuccessful,
                    http = resp.code,
                    ip = map["ip"],
                    colo = map["colo"],
                    loc = map["loc"],
                    tls = map["tls"],
                    warp = map["warp"],
                    ts = map["ts"],
                    error = if (resp.isSuccessful) null else "HTTP ${resp.code}",
                )
            }
        } catch (e: Exception) {
            ProbeFamily(v6 = v6, ok = false, error = describe(e))
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is SocketTimeoutException -> "超时"
        is UnknownHostException -> "域名解析失败"
        is ConnectException -> "连接被拒绝"
        is NoRouteToHostException -> "无路由"
        is SocketException -> "网络不可用"
        else -> e.javaClass.simpleName
    }

    /** trace 是纯文本 key=value，一行一对。 */
    private fun parseTrace(text: String): Map<String, String> =
        text.lineSequence().mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }.toMap()

    private fun localAddresses(context: Context): Pair<String?, List<String>> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null to emptyList()
        val lp = cm.activeNetwork?.let { cm.getLinkProperties(it) } ?: return null to emptyList()
        val addresses = lp.linkAddresses.map { it.address }

        val v4 = addresses.firstOrNull {
            it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress
        }?.hostAddress?.substringBefore('%')

        // 公网 IPv6：非回环、非链路本地（fe80::/10）、非 ULA（fc00::/7）
        val v6 = addresses.filterIsInstance<Inet6Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isSiteLocalAddress }
            .filter { addr ->
                val bytes = addr.address
                val first = bytes[0].toInt() and 0xFF
                // fc00::/7 → ULA，直接排除
                !(first == 0xFC || first == 0xFD)
            }
            .map { it.hostAddress.substringBefore('%') }
            .distinct()

        return v4 to v6
    }
}
