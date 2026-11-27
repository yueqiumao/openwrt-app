package com.ax6.luyouqi.domain

enum class DeviceKind { WIFI, WIRED, UNKNOWN }

data class Device(
    val mac: String,
    val hostname: String = "",
    val ip: String = "",
    val ipv6: String = "",
    val ipv6List: List<String> = emptyList(),
    val online: Boolean = false,
    val kind: DeviceKind = DeviceKind.UNKNOWN,
    val ifname: String = "",
    val ssid: String = "",
    val band: String = "",
    val channel: Int? = null,
    val signal: Int? = null,
    val bitrate: Int? = null,
    val connectedTime: Long? = null,
    val rxBytes: Long? = null,
    val txBytes: Long? = null,
    /** 有线设备：所在端口的累计计数（终端视角 down=tx、up=rx，同端口多设备共享）。 */
    val downTotal: Long? = null,
    val upTotal: Long? = null,
    /** 同端口上的在线有线终端数量（vantage FDB 口径）。 */
    val portPeers: Int = 0,
    val rate: Rates = Rates(),
    val rateShared: Boolean = false,
    val port: String = "",
    val bridge: String = "",
    val leaseExpires: Long? = null,
    val source: String = "",
    /** Vantage 仪表盘里自定义的设备备注（/etc/config/vantage 的 client.name）。 */
    val remark: String = "",
) {
    val displayName: String get() = remark.ifBlank { hostname.ifBlank { ip.ifBlank { mac } } }
}

data class DeviceTotals(
    val total: Int = 0,
    val wifi: Int = 0,
    val wired: Int = 0,
    val online: Int = 0,
)

data class DeviceSnapshot(
    val devices: List<Device> = emptyList(),
    val totals: DeviceTotals = DeviceTotals(),
)

data class Lease(val mac: String, val ip: String, val hostname: String, val expires: Long)

data class HostHint(val name: String, val ip: String, val ip6: String, val ip6List: List<String>)

/**
 * 有线终端候选。默认固件没有 FDB，[port]/[bridge]/[vlan] 通常为空（不可得），
 * [online] 由 ARP 邻居表推断。
 */
data class WiredHost(
    val mac: String,
    val port: String = "",
    val bridge: String = "",
    val vlan: String = "",
    val online: Boolean = true,
)
