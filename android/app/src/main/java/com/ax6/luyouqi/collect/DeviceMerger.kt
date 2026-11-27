package com.ax6.luyouqi.collect

import com.ax6.luyouqi.domain.Device
import com.ax6.luyouqi.domain.DeviceKind
import com.ax6.luyouqi.domain.DeviceSnapshot
import com.ax6.luyouqi.domain.DeviceTotals
import com.ax6.luyouqi.domain.HostHint
import com.ax6.luyouqi.domain.Iface
import com.ax6.luyouqi.domain.Lease
import com.ax6.luyouqi.domain.Rates
import com.ax6.luyouqi.domain.WifiState
import com.ax6.luyouqi.domain.WiredHost

/**
 * 终端合并，口径完全照 server/collector.js 的 devices()：
 * 1. 只有 iwinfo 关联表里的才算"无线在线"；
 * 2. 只有非无线端口上出现的才算"有线在线"（同一 MAC 以 WiFi 为准）；
 * 3. DHCP 租约与 hostHints 只补名字/IP，不改变在线状态；
 * 4. 离线设备沿用最近 30 分钟的类型，避免刚断开就从"有线"变"未知"；
 * 5. 有线设备的速率是端口速率，同端口多设备时标记共享。
 *
 * 纯函数，方便用真机 fixtures 直接测。
 */
object DeviceMerger {

    fun mac(value: String?): String =
        (value ?: "").trim().lowercase().replace('-', ':')

    fun merge(
        interfaces: List<Iface>,
        wifi: WifiState,
        assoc: Map<String, List<AssocClient>>,
        leases: List<Lease>,
        hints: Map<String, HostHint>,
        wiredHosts: List<WiredHost>,
        remarks: Map<String, String> = emptyMap(),
        deviceMacs: Set<String>,
        clientRates: Map<String, Rates>,
        portRates: Map<String, Rates>,
        portTotals: Map<String, Rates> = emptyMap(),
        lastKinds: Map<String, Pair<DeviceKind, Long>>,
        now: Long,
    ): Pair<DeviceSnapshot, Map<String, Pair<DeviceKind, Long>>> {
        val selfIps = interfaces.map { it.ipv4First }.filter { it.isNotBlank() }.toSet()
        val lanCidrs = interfaces.firstOrNull { it.id == "lan" }?.ipv4.orEmpty().filter { it.contains('/') }
        val wifiIfnames = wifi.ifnames.toSet()
        fun inLan(ip: String): Boolean = lanCidrs.isEmpty() || lanCidrs.any { ipv4InCidr(ip, it) }

        val devices = LinkedHashMap<String, Device>()

        fun ensure(raw: String?): String? {
            val key = mac(raw)
            if (key.isEmpty()) return null
            if (!devices.containsKey(key)) devices[key] = Device(mac = key)
            return key
        }

        // 1) WiFi 关联表
        wifi.ssids.forEach { ssid ->
            assoc[ssid.ifname].orEmpty().forEach { client ->
                val key = ensure(client.mac) ?: return@forEach
                val d = devices.getValue(key)
                devices[key] = d.copy(
                    online = true,
                    kind = DeviceKind.WIFI,
                    ifname = ssid.ifname,
                    ssid = ssid.ssid,
                    band = ssid.band,
                    channel = ssid.channel,
                    signal = client.signal,
                    bitrate = client.bitrate,
                    connectedTime = client.connectedTime,
                    rxBytes = client.rxBytes,
                    txBytes = client.txBytes,
                    rate = clientRates[key] ?: Rates(),
                    source = "wifi",
                )
            }
        }

        // 2) 有线候选（ARP 邻居表 + DHCP 租约定在线；装了 vantage 时叠加 FDB 的端口）
        val portCounts = HashMap<String, Int>()
        wiredHosts.forEach { host ->
            val key = mac(host.mac)
            if (key.isEmpty() || deviceMacs.contains(key)) return@forEach
            val ensured = ensure(key) ?: return@forEach
            val current = devices.getValue(ensured)
            if (current.kind == DeviceKind.WIFI) return@forEach
            devices[ensured] = if (host.online) {
                current.copy(
                    online = true,
                    kind = DeviceKind.WIRED,
                    port = host.port,
                    bridge = host.bridge,
                    // 有线结论来自 vantage FDB 时如实标出来源；没有 FDB（端口为空）仍是邻居表
                    source = if (host.port.isNotBlank()) "fdb" else "neigh",
                )
            } else {
                // 只有租约、邻居表里没有它：承认它存在，但不敢断言是有线还是无线
                current.copy(online = false, source = current.source.ifBlank { "lease" })
            }
            // 只有在线终端才算「同端口共享」；离线条目留着端口字段但不参与计数
            if (host.online && host.port.isNotBlank()) portCounts[host.port] = (portCounts[host.port] ?: 0) + 1
        }

        // 3) 租约补名字/IP
        leases.forEach { lease ->
            if (lease.ip.isNotBlank() && (selfIps.contains(lease.ip) || !inLan(lease.ip))) return@forEach
            val key = ensure(lease.mac) ?: return@forEach
            val d = devices.getValue(key)
            devices[key] = d.copy(
                ip = d.ip.ifBlank { lease.ip },
                hostname = d.hostname.ifBlank { lease.hostname },
                leaseExpires = lease.expires.takeIf { it > 0 } ?: d.leaseExpires,
                source = d.source.ifBlank { "lease" },
            )
        }

        // 4) hostHints 补名字/IP/IPv6
        hints.forEach { (rawMac, hint) ->
            if (hint.ip.isNotBlank() && (selfIps.contains(hint.ip) || !inLan(hint.ip))) return@forEach
            val key = ensure(rawMac) ?: return@forEach
            val d = devices.getValue(key)
            devices[key] = d.copy(
                ip = d.ip.ifBlank { hint.ip },
                ipv6 = d.ipv6.ifBlank { hint.ip6 },
                ipv6List = d.ipv6List.ifEmpty { hint.ip6List },
                hostname = d.hostname.ifBlank { hint.name },
                source = d.source.ifBlank { "hint" },
            )
        }

        // 4.5) 有备注但当前无任何来源的设备：补成离线条目，保证备注后的设备断开也不消失
        remarks.forEach { (rawMac, name) ->
            val key = mac(rawMac)
            if (key.isEmpty() || name.isBlank()) return@forEach
            if (!devices.containsKey(key)) {
                devices[key] = Device(mac = key, remark = name, source = "remark")
            }
        }

        // 5) 离线类型记忆 + 有线速率
        val kinds = HashMap(lastKinds)
        val result = devices.values.map { original ->
            var out = original
            if (original.online) {
                kinds[original.mac] = original.kind to now
            } else {
                val last = lastKinds[original.mac]
                if (last != null && now - last.second < 30L * 60 * 1000) out = out.copy(kind = last.first)
            }
            remarks[out.mac]?.takeIf { it.isNotBlank() }?.let { name -> out = out.copy(remark = name) }
            if (out.online && out.kind == DeviceKind.WIRED && out.port.isNotBlank()) {
                // 端口计数是路由器视角：port.rx = 从终端收到的（终端上传），port.tx = 发给终端的（终端下载）。
                // 与 WiFi 口径（down = tx、up = rx）对齐，这里必须互换，否则上下行是反的。
                val portRate = portRates[out.port]
                val peers = portCounts[out.port] ?: 0
                val totals = portTotals[out.port]
                out = out.copy(
                    rate = if (portRate != null) Rates(down = portRate.up, up = portRate.down) else Rates(),
                    downTotal = totals?.down,
                    upTotal = totals?.up,
                    portPeers = peers,
                    rateShared = peers > 1,
                )
            }
            out
        }.sortedWith(comparator())

        val online = result.filter { it.online }
        val totals = DeviceTotals(
            total = result.size,
            wifi = online.count { it.kind == DeviceKind.WIFI },
            wired = online.count { it.kind == DeviceKind.WIRED },
            online = online.size,
        )
        return DeviceSnapshot(result, totals) to kinds
    }

    private fun comparator(): Comparator<Device> = Comparator { a, b ->
        val ra = rank(a)
        val rb = rank(b)
        if (ra != rb) return@Comparator ra - rb
        if (a.online != b.online) return@Comparator if (a.online) -1 else 1
        (b.signal ?: -999) - (a.signal ?: -999)
    }

    private fun rank(d: Device): Int = when {
        !d.online -> 9
        d.kind == DeviceKind.WIRED -> 0
        d.kind == DeviceKind.WIFI && d.band.contains("5") -> 1
        d.kind == DeviceKind.WIFI && d.band.contains("2") -> 2
        else -> 3
    }

    /** "10.10.10.11/24" 形式；解析失败时按"在网段内"处理，宁可多显示一台也不吞设备。 */
    fun ipv4InCidr(ip: String, cidr: String): Boolean {
        val address = cidr.substringBefore('/')
        val prefix = cidr.substringAfter('/', "").toIntOrNull() ?: return true
        fun parts(v: String): IntArray? {
            val p = v.split('.')
            if (p.size != 4) return null
            val nums = p.map { it.toIntOrNull() ?: return null }
            return nums.toIntArray()
        }
        val a = parts(ip) ?: return true
        val b = parts(address) ?: return true
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        val ipInt = (a[0] shl 24) or (a[1] shl 16) or (a[2] shl 8) or a[3]
        val netInt = (b[0] shl 24) or (b[1] shl 16) or (b[2] shl 8) or b[3]
        return (ipInt and mask) == (netInt and mask)
    }
}
