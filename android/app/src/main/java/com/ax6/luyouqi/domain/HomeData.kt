package com.ax6.luyouqi.domain

data class Rates(val down: Long = 0, val up: Long = 0)

data class Mem(val total: Long = 0, val used: Long = 0, val pct: Double = 0.0)

data class SystemState(
    val load: List<Double> = listOf(0.0, 0.0, 0.0),
    /** 真实占用率（/proc/stat 差分，0..100）。拿不到时为 null，退回负载折算。 */
    val cpuUse: Double? = null,
    val cores: Int = 4,
    val mem: Mem = Mem(),
    val tempC: Double? = null,
    val wifiTemps: List<Double> = emptyList(),
    val uptime: Long = 0,
) {
    val load1: Double get() = load.getOrElse(0) { 0.0 }

    /** 优先真实占用率；/proc/stat 读不到时退回 load1/核心数 折算，仅作兜底。 */
    val cpuPct: Double
        get() = cpuUse ?: (load1 / cores.coerceAtLeast(1) * 100).coerceIn(0.0, 999.0)
}

data class Port(
    val name: String,
    val label: String,
    val mac: String,
    val carrier: Boolean,
    val speed: Int,
    val duplex: String,
    val rx: Long,
    val tx: Long,
    val rate: Rates = Rates(),
) {
    /**
     * 端口计数是路由器视角：LAN 口 rx = 终端上传、tx = 终端下载，展示时要互换；
     * WAN 口 rx 本来就是下载、tx 是上传，保持原样。
     */
    val lanSide: Boolean get() = name != "wan"

    val viewDownRate: Long get() = if (lanSide) rate.up else rate.down
    val viewUpRate: Long get() = if (lanSide) rate.down else rate.up
    val viewDownTotal: Long get() = if (lanSide) tx else rx
    val viewUpTotal: Long get() = if (lanSide) rx else tx
}

data class Iface(
    val id: String,
    val up: Boolean,
    val proto: String,
    val device: String,
    val ipv4: List<String> = emptyList(),
    val ipv6: List<String> = emptyList(),
    val pd: List<String> = emptyList(),
    val gateway: String = "",
    val dns: List<String> = emptyList(),
    val uptime: Long = 0,
    val leaseRemaining: Long? = null,
    val rx: Long = 0,
    val tx: Long = 0,
    val rate: Rates = Rates(),
) {
    val ipv4First: String get() = ipv4.firstOrNull()?.substringBefore('/') ?: ""
}

data class WifiSsid(
    val ssid: String,
    val ifname: String,
    val band: String,
    val channel: Int,
    val clients: Int,
    val encryption: String,
)

data class WifiState(
    val available: Boolean = false,
    val ssids: List<WifiSsid> = emptyList(),
) {
    val clients: Int get() = ssids.sumOf { it.clients }
    val ifnames: List<String> get() = ssids.map { it.ifname }.filter { it.isNotBlank() }
}

/** 首页一次 tick 的全部数据。字段对齐 /api/overview，缺失一律 null/空，不补 0。 */
data class HomeData(
    val ts: Long,
    val connected: Boolean,
    val rates: Rates = Rates(),
    val rxTotal: Long = 0,
    val txTotal: Long = 0,
    val system: SystemState = SystemState(),
    val ports: List<Port> = emptyList(),
    val wan: Iface? = null,
    val wan6: Iface? = null,
    val wifi: WifiState = WifiState(),
    val traffic: List<IfaceTraffic> = emptyList(),
    val wiredClients: Int = 0,
    val connections: Int? = null,
    val connectionsMax: Int? = null,
) {
    val onlineClients: Int get() = wiredClients + wifi.clients
}

/** LAN / WiFi 分接口收发（来自 luci-rpc getNetworkDevices 的 stats）。 */
data class IfaceTraffic(
    val device: String,
    val label: String,
    val sub: String,
    val rx: Long,
    val tx: Long,
    val rate: Rates,
)

/** iwinfo scan 的单条结果。 */
data class ScanAp(
    val ssid: String,
    val bssid: String,
    val band: Int,
    val channel: Int,
    val signal: Int,
    val encryption: String,
)

data class HistoryPoint(val t: Long, val down: Long, val up: Long)
