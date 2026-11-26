package com.ax6.luyouqi.collect

import com.ax6.luyouqi.domain.Iface
import com.ax6.luyouqi.domain.Mem
import com.ax6.luyouqi.fmt.Fmt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.round

/**
 * 与 server/collector.js 的归一化口径逐条对齐。
 * 这些函数都是纯函数，单测用真实返回样本喂进去即可验证。
 */
object Normalizer {

    // ---- ubus 返回的宽容读取 ------------------------------------------------

    fun asList(element: JsonElement?): List<JsonElement> = when (element) {
        null -> emptyList()
        is JsonArray -> element
        else -> listOf(element)
    }

    fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
    fun JsonObject.arr(key: String): JsonArray? = get(key) as? JsonArray
    fun JsonObject.str(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    fun JsonObject.num(key: String): Long = (get(key) as? JsonPrimitive)?.longOrNull ?: 0L
    fun JsonObject.dbl(key: String): Double? = (get(key) as? JsonPrimitive)?.doubleOrNull
    fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull

    // ---- 接口 ---------------------------------------------------------------

    fun rawInterfaceList(dump: JsonElement?): List<JsonObject> {
        val array = when (dump) {
            is JsonArray -> dump
            is JsonObject -> dump.arr("interface") ?: JsonArray(emptyList())
            else -> return emptyList()
        }
        return array.mapNotNull { it as? JsonObject }
    }

    fun interfaces(dump: JsonElement?, nowSeconds: Long): List<Iface> =
        rawInterfaceList(dump)
            .filter { it.str("interface").isNotEmpty() && it.str("interface") != "loopback" }
            .map { raw ->
                val id = raw.str("interface")

                val ipv4 = asList(raw.get("ipv4-address"))
                    .mapNotNull { it as? JsonObject }
                    .mapNotNull { addr ->
                        val address = addr.str("address")
                        if (address.isEmpty()) null else "$address/${addr.num("mask")}"
                    }

                val ipv6 = asList(raw.get("ipv6-address"))
                    .mapNotNull { it as? JsonObject }
                    .mapNotNull { addr ->
                        val address = addr.str("address")
                        if (address.isEmpty() || address.startsWith("fe80")) null else "$address/${addr.num("mask")}"
                    }
                    .toMutableList()
                asList(raw.get("ipv6-prefix-assignment")).forEach { assignment ->
                    val local = (assignment as? JsonObject)?.obj("local-address") ?: return@forEach
                    val address = local.str("address")
                    if (address.isNotEmpty()) ipv6 += "$address/${local.num("mask")}"
                }

                val route = asList(raw.get("route")).mapNotNull { it as? JsonObject }
                val defaultRoute = route.firstOrNull { r ->
                    val target = r.str("target")
                    val base = target.substringBefore('/')
                    val mask = if (r.get("mask") != null) r.num("mask") else target.substringAfter('/', "x").toLongOrNull() ?: -1L
                    (base == "0.0.0.0" || base == "::") && mask == 0L
                } ?: route.firstOrNull { it.str("nexthop").isNotEmpty() || it.str("gateway").isNotEmpty() }

                val dns = asList(raw.get("dns-server")).mapNotNull { item ->
                    when (item) {
                        is JsonPrimitive -> item.contentOrNull
                        is JsonObject -> item.str("address").ifEmpty { null }
                        else -> null
                    }
                }.filter { it.isNotBlank() }

                val stats = raw.obj("stats") ?: JsonObject(emptyMap())
                // 与 collector.js 一致：uptime 取第一个非 0 字段，不是累加
                val rawUpSince = listOf("uptime", "up_since", "up-since")
                    .map { raw.num(it) }
                    .firstOrNull { it != 0L } ?: 0L
                val uptime = if (rawUpSince > 1_000_000_000L) {
                    max(0L, nowSeconds - rawUpSince)
                } else {
                    rawUpSince
                }

                val pd = asList(raw.get("ipv6-prefix"))
                    .mapNotNull { it as? JsonObject }
                    .mapNotNull { prefix ->
                        val address = prefix.str("address")
                        if (address.isEmpty()) null else "$address/${prefix.num("mask")}"
                    }

                val data = raw.obj("data") ?: JsonObject(emptyMap())
                val leaseTime = data.num("leasetime")
                val leaseRemaining: Long? = if (leaseTime > 0 && uptime >= 0) {
                    max(0L, leaseTime - (uptime % leaseTime))
                } else {
                    (asList(raw.get("ipv6-prefix")) + asList(raw.get("ipv6-address")))
                        .mapNotNull { it as? JsonObject }
                        .mapNotNull { it.dbl("valid") }
                        .firstOrNull { it >= 0 }
                        ?.toLong()
                }

                val gatewayRaw = defaultRoute?.str("nexthop")?.ifEmpty { defaultRoute.str("gateway") }.orEmpty()

                Iface(
                    id = id,
                    up = (raw.get("up") as? JsonPrimitive)?.contentOrNull == "true" ||
                        (raw.get("up") as? JsonPrimitive)?.contentOrNull == "1",
                    proto = raw.str("proto"),
                    device = raw.str("l3_device").ifEmpty { raw.str("device") },
                    ipv4 = ipv4,
                    ipv6 = ipv6.distinct(),
                    pd = pd.distinct(),
                    gateway = if (gatewayRaw == "::") "" else gatewayRaw,
                    dns = dns,
                    uptime = uptime,
                    leaseRemaining = leaseRemaining,
                    rx = stats.num("rx_bytes"),
                    tx = stats.num("tx_bytes"),
                )
            }
            .sortedWith(compareBy({ rankOf(it.id) }, { it.id }))

    private fun rankOf(id: String): Int = when (id) {
        "wan" -> 0
        "wan6" -> 1
        "lan" -> 2
        else -> 3
    }

    // ---- 系统 ---------------------------------------------------------------

    /** rpcd 的 load 是 16 位定点数（2112 = 0.032），需要还原。 */
    fun load(raw: JsonElement?): List<Double> {
        val values = asList(raw).mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
        val max = values.maxOrNull() ?: 0.0
        val scale = if (max > 100 && max == Math.floor(max)) 65536.0 else 1.0
        return values.map { round(it / scale * 100) / 100 }
    }

    /** /proc/stat 第一行 cpu 的累计 jiffies：total 为前 8 项之和，idle 含 idle+iowait。 */
    data class CpuTicks(val total: Long, val idle: Long)

    fun cpuTicks(text: String): CpuTicks? {
        val line = text.lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return null
        val fields = line.trim().split(Regex("\\s+")).drop(1)
            .take(8)
            .mapNotNull { it.toLongOrNull() }
        if (fields.size < 4) return null
        val total = fields.sum()
        if (total <= 0) return null
        val idle = fields[3] + fields.getOrElse(4) { 0L }
        return CpuTicks(total, idle)
    }

    /** 两次采样之间非空闲占比，口径同 top：irq/sirq 算忙，steal 算忙。 */
    fun cpuUsage(prev: CpuTicks?, cur: CpuTicks): Double? {
        if (prev == null) return null
        val dTotal = cur.total - prev.total
        val dIdle = cur.idle - prev.idle
        if (dTotal <= 0 || dIdle < 0) return null
        val busy = (dTotal - dIdle).coerceAtLeast(0)
        return round(busy.toDouble() / dTotal * 1000.0) / 10.0
    }

    fun memory(raw: JsonObject?): Mem {
        val m = raw ?: JsonObject(emptyMap())
        val total = m.num("total")
        val available = m.num("available")
        val free = m.num("free")
        val buffered = m.num("buffered")
        val cached = m.num("cached")
        val used = if (available > 0) max(0L, total - available) else max(0L, total - free - buffered - cached)
        val pct = if (total > 0) Math.round(used.toDouble() / total * 1000.0) / 10.0 else 0.0
        return Mem(total = total, used = used, pct = pct)
    }

    /**
     * luci getTempInfo 返回文本，例如：
     *   CPU: 52.1°C / WiFi: 51.0°C 61.0°C
     * 解析失败时返回 null，由调用方回退到 thermal_zone。
     */
    fun temperature(text: String): Pair<Double?, List<Double>>? {
        val cpuMatch = Regex("CPU:\\s*([0-9.]+)", RegexOption.IGNORE_CASE).find(text)
            ?: Regex("([0-9.]+)\\s*°C").find(text)
        val wifiMatch = Regex("WiFi:\\s*([0-9.]+)\\s*°C\\s*([0-9.]+)", RegexOption.IGNORE_CASE).find(text)

        val wifi = if (wifiMatch != null) {
            listOf(wifiMatch.groupValues[1].toDoubleOrNull(), wifiMatch.groupValues[2].toDoubleOrNull())
        } else {
            Regex("([0-9.]+)\\s*°C").findAll(text).mapNotNull { it.groupValues[1].toDoubleOrNull() }
                .drop(1).take(2).toList()
        }.filterNotNull()

        val cpu = cpuMatch?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        if (cpu == null && wifi.isEmpty()) return null
        return cpu?.let { round(it * 10) / 10.0 } to wifi.map { round(it * 10) / 10.0 }
    }

    /** thermal_zone 返回毫摄氏度。 */
    fun thermalCelsius(raw: String): Double? {
        val value = raw.trim().toDoubleOrNull() ?: return null
        if (value <= 0) return null
        val celsius = if (value > 1000) value / 1000 else value
        return if (celsius in 5.0..150.0) round(celsius * 10) / 10.0 else null
    }

    // ---- 无线 ---------------------------------------------------------------

    fun bandOfChannel(channel: Int?): String {
        val ch = channel ?: 0
        return when {
            ch in 1..14 -> "2.4G"
            ch in 32..196 -> "5G"
            ch > 196 -> "6G"
            else -> ""
        }
    }

    fun bandFromConfig(raw: String?): String {
        val text = raw.orEmpty()
        return when {
            text.contains("5") -> "5G"
            text.contains("2") -> "2.4G"
            else -> ""
        }
    }

    /** assoclist 条目：mac / signal / 收发字节。 */
    fun assocList(raw: JsonElement?): List<AssocClient> {
        val list = when (raw) {
            is JsonObject -> raw.arr("results") ?: raw.arr("clients") ?: JsonArray(emptyList())
            is JsonArray -> raw
            else -> JsonArray(emptyList())
        }
        return list.mapNotNull { it as? JsonObject }.mapNotNull { item ->
            val mac = (item.str("mac").ifEmpty { item.str("addr") })
                .lowercase().replace('-', ':')
            if (mac.isEmpty()) null else AssocClient(
                mac = mac,
                signal = item.int("signal"),
                rxBytes = item.obj("rx")?.num("bytes") ?: 0L,
                txBytes = item.obj("tx")?.num("bytes") ?: 0L,
                bitrate = item.int("bitrate")
                    ?: item.obj("rx")?.int("rate")
                    ?: item.obj("tx")?.int("rate"),
                connectedTime = item.num("connected_time").takeIf { it > 0 },
            )
        }
    }

    fun encryptionLabel(raw: String?, info: JsonObject?): String {
        val text = raw.orEmpty()
        if (text.isEmpty()) {
            val enc = info?.get("encryption")
            return when (enc) {
                is JsonPrimitive -> enc.contentOrNull.orEmpty()
                is JsonObject -> {
                    if ((enc["enabled"] as? JsonPrimitive)?.contentOrNull == "false") "开放" else "加密"
                }
                else -> ""
            }
        }
        if (text == "none") return "开放"
        val bits = mutableListOf<String>()
        if (Regex("wpa3").containsMatchIn(text)) bits += "WPA3"
        else if (Regex("wpa2|psk2").containsMatchIn(text)) bits += "WPA2"
        else if (Regex("wpa|psk").containsMatchIn(text)) bits += "WPA"
        if (Regex("wep").containsMatchIn(text)) bits += "WEP"
        if (Regex("ccmp|aes").containsMatchIn(text)) bits += "AES"
        else if (Regex("tkip").containsMatchIn(text)) bits += "TKIP"
        return bits.joinToString("/").ifEmpty { text.uppercase() }
    }

    /** 与 Fmt.bandLabel 相同的展示口径，但输入是 "5G"/"2.4G"。 */
    fun bandLabel(band: String?): String = Fmt.bandLabel(band)
}

data class AssocClient(
    val mac: String,
    val signal: Int?,
    val rxBytes: Long,
    val txBytes: Long,
    val bitrate: Int? = null,
    val connectedTime: Long? = null,
)
