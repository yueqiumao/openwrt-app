package com.ax6.luyouqi.collect

import kotlinx.coroutines.Dispatchers
import com.ax6.luyouqi.domain.DeviceKind
import com.ax6.luyouqi.domain.DeviceSnapshot
import com.ax6.luyouqi.domain.HostHint
import com.ax6.luyouqi.domain.IfaceTraffic
import com.ax6.luyouqi.domain.ScanAp
import com.ax6.luyouqi.domain.HomeData
import com.ax6.luyouqi.domain.Lease
import com.ax6.luyouqi.domain.Iface
import com.ax6.luyouqi.domain.Mem
import com.ax6.luyouqi.domain.Port
import com.ax6.luyouqi.domain.Rates
import com.ax6.luyouqi.domain.SystemState
import com.ax6.luyouqi.domain.WifiSsid
import com.ax6.luyouqi.domain.WifiState
import com.ax6.luyouqi.domain.WiredHost
import com.ax6.luyouqi.collect.Normalizer.arr
import com.ax6.luyouqi.collect.Normalizer.int
import com.ax6.luyouqi.collect.Normalizer.num
import com.ax6.luyouqi.collect.Normalizer.obj
import com.ax6.luyouqi.collect.Normalizer.str
import com.ax6.luyouqi.fmt.Fmt
import com.ax6.luyouqi.net.RouterSession
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 首页采集编排。
 *
 * 与网页版 collector.js 的差异只有一处：数据源优先走 `/ubus` 直连。
 * 真机验证结果：`/cgi-bin/luci/admin/ubus` 对 ubus 会话返回 code 5，但
 * `file.read` 与 `luci.vantage wired` 在 `/ubus` 上是放行的，因此直连即可，
 * LuCI 通道只作为其它固件的兜底。
 *
 * 所有请求天然串行（RouterSession 内部 Mutex），不为速度并发打路由器。
 */
class RouterRepository(private val session: RouterSession) {

    private data class NetDev(
        val mac: String,
        val rx: Long,
        val tx: Long,
        val speed: Int,
        val duplex: String,
        val carrier: Boolean,
        val type: String,
    )

    private val ifaceRates = RateTracker.Tracker()
    private val trafficRates = RateTracker.Tracker()
    private val portRates = RateTracker.Tracker()
    private var lastPortTotals: Map<String, Rates> = emptyMap()

    private var lastTs = 0L
    private var lastPrimaryKey = ""

    private var lastPrimaryRx = 0L
    private var lastPrimaryTx = 0L

    private var tempAt = 0L
    private var tempCpu: Double? = null
    private var tempWifi: List<Double> = emptyList()

    private var devAt = 0L
    private var devCache: Map<String, NetDev>? = null

    private var wifiAt = 0L
    private var wifiCache: WifiState = WifiState()
    private val infoCache = HashMap<String, Pair<Long, JsonObject?>>()
    private val assocCache = HashMap<String, Pair<Long, List<AssocClient>>>()

    private var neighAt = 0L
    private var neighCache: List<Neighbors.Entry> = emptyList()
    private var wiredMacs: Set<String> = emptySet()

    private var connAt = 0L
    private var connCache: Pair<Int?, Int?>? = null

    private var cores = 4
    private var coresAt = 0L
    private var cpuPrev: Normalizer.CpuTicks? = null
    private var cpuUse: Double? = null
    private var cpuStatFailedAt = 0L

    // ---- 终端页状态（sample 与 devices 共用，避免重复请求） ----
    private var lastIfaces: List<Iface> = emptyList()
    private var lastWifi: WifiState = WifiState()
    private var lastDeviceMacs: Set<String> = emptySet()
    private var lastPortRates: Map<String, Rates> = emptyMap()

    private var leasesAt = 0L
    private var leaseCache: List<Lease> = emptyList()
    private var hintsAt = 0L
    private var hintCache: Map<String, HostHint> = emptyMap()

    private var wiredRaw: List<WiredHost> = emptyList()
    private val clientTracker = RateTracker.Tracker()
    private var clientRates: Map<String, Rates> = emptyMap()
    private val lastKinds = HashMap<String, Pair<DeviceKind, Long>>()

    fun reset() {
        clientTracker.reset()
        lastKinds.clear()
        leaseCache = emptyList()
        leasesAt = 0
        hintCache = emptyMap()
        hintsAt = 0
        ifaceRates.reset()
        trafficRates.reset()
        portRates.reset()
        lastTs = 0
        lastPrimaryKey = ""
        tempAt = 0
        devAt = 0
        wifiAt = 0
        neighAt = 0
        neighCache = emptyList()
        wiredMacs = emptySet()
        wiredRaw = emptyList()
        connAt = 0
        infoCache.clear()
        assocCache.clear()
        devCache = null
        connCache = null
    }

    // ---- 一次采样 ------------------------------------------------------------

    suspend fun sample(): HomeData {
        val now = System.currentTimeMillis()
        val nowSec = now / 1000

        // 串行采集：同一时刻只允许一个在途 ubus 请求，行为最可预期
        val dump = session.call("network.interface", "dump")
        val info = session.call("system", "info").let { it as? JsonObject } ?: JsonObject(emptyMap())
        val ifaces = Normalizer.interfaces(dump, nowSec)

        val inline = ifaces.any { it.rx > 0 || it.tx > 0 }
        val devs = networkDevices()
        val counters = HashMap<String, Pair<Long, Long>>()
        ifaces.forEach { iface ->
            val source = if (inline) iface.id else iface.device.ifEmpty { iface.id }
            val dev = devs[source]
            counters[iface.id] = (if (inline) iface.rx else dev?.rx ?: 0L) to
                (if (inline) iface.tx else dev?.tx ?: 0L)
        }

        val primary = ifaces.firstOrNull { it.id == "wan" }
            ?: ifaces.firstOrNull { it.id == "wan6" }
            ?: ifaces.firstOrNull { it.up && it.id != "lan" }
        val primaryKey = primary?.let { p -> if (inline) p.id else p.device.ifEmpty { p.id } }.orEmpty()

        val rx = primary?.let { counters[it.id]?.first } ?: 0L
        val tx = primary?.let { counters[it.id]?.second } ?: 0L

        val rates = if (lastTs != 0L && primaryKey == lastPrimaryKey) {
            val dt = (now - lastTs) / 1000.0
            Rates(
                down = RateTracker.rate(rx, lastPrimaryRx, dt),
                up = RateTracker.rate(tx, lastPrimaryTx, dt),
            )
        } else {
            Rates()
        }
        val ifaceSpeed = ifaceRates.update(now, counters)
        lastTs = now
        lastPrimaryKey = primaryKey
        lastPrimaryRx = rx
        lastPrimaryTx = tx

        val temp = temperature()
        val cores = cores()
        val cpuUse = cpuUsage(now) ?: this.cpuUse
        val memory = Normalizer.memory(info.obj("memory"))

        val system = SystemState(
            load = Normalizer.load(info.get("load")),
            cores = cores,
            cpuUse = cpuUse,
            mem = memory,
            tempC = temp.first,
            wifiTemps = temp.second,
            uptime = Normalizer.asList(info.get("uptime")).firstOrNull()
                ?.let { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() } ?: 0L,
        )

        val wifi = wireless()
        val traffic = collectTraffic(now, devs, wifi)
        val ports = collectPorts(now, devs)
        lastIfaces = ifaces
        lastWifi = wifi
        lastDeviceMacs = devs.values.map { it.mac.lowercase() }.filter { it.isNotEmpty() }.toSet()
        val wired = wiredScan(devs, wifi)
        val connections = connections()

        fun withRate(iface: Iface?): Iface? = iface?.let { i ->
            val pair = ifaceSpeed[i.id]
            if (pair == null) i else i.copy(rate = Rates(pair.first, pair.second))
        }
        val wan = withRate(ifaces.firstOrNull { it.id == "wan" })
        val wan6 = withRate(ifaces.firstOrNull { it.id == "wan6" })

        return HomeData(
            ts = now,
            connected = true,
            rates = rates,
            rxTotal = rx,
            txTotal = tx,
            system = system,
            ports = ports,
            wan = wan,
            wan6 = wan6,
            wifi = wifi,
            traffic = traffic,
            wiredClients = wired,
            connections = connections.first,
            connectionsMax = connections.second,
        )
    }

    // ---- 系统 ---------------------------------------------------------------

    private suspend fun temperature(): Pair<Double?, List<Double>> {
        val now = System.currentTimeMillis()
        if (tempAt != 0L && now - tempAt < 20_000) return tempCpu to tempWifi

        val info = runCatching { session.call("luci", "getTempInfo") }.getOrNull() as? JsonObject
        val text = info?.str("tempinfo").orEmpty()
        val parsed = Normalizer.temperature(text)
        if (parsed != null) {
            tempCpu = parsed.first
            tempWifi = parsed.second
            tempAt = now
            return tempCpu to tempWifi
        }

        val raw = runCatching {
            session.call("file", "read", JsonObject(mapOf("path" to JsonPrimitive("/sys/class/thermal/thermal_zone0/temp")))
                .let { it })
        }.getOrNull() as? JsonObject
        tempCpu = Normalizer.thermalCelsius(raw?.str("data").orEmpty())
        tempWifi = emptyList()
        tempAt = now
        return tempCpu to tempWifi
    }

    /**
     * 读 /proc/stat 做差分算真实占用率。rpcd 的 file.read 是白名单制，
     * 被拒时静默退回 load 折算，并 60 秒内不再重试，避免拖慢轮询。
     */
    private suspend fun cpuUsage(now: Long): Double? {
        if (cpuStatFailedAt != 0L && now - cpuStatFailedAt < 60_000) return null
        val payload = runCatching {
            session.call("file", "read", JsonObject(mapOf("path" to JsonPrimitive("/proc/stat"))))
        }.getOrNull() as? JsonObject
        val text = payload?.str("data").orEmpty()
        val ticks = Normalizer.cpuTicks(text)
        if (ticks == null) {
            cpuStatFailedAt = now
            return null
        }
        cpuStatFailedAt = 0
        val pct = Normalizer.cpuUsage(cpuPrev, ticks)
        cpuPrev = ticks
        if (pct != null) cpuUse = pct.coerceIn(0.0, 100.0)
        return cpuUse
    }

    private suspend fun cores(): Int {
        val now = System.currentTimeMillis()
        if (coresAt != 0L && now - coresAt < 10 * 60 * 1000) return cores

        val cpuInfo = runCatching { session.call("luci", "getCPUInfo") }.getOrNull() as? JsonObject
        val label = cpuInfo?.str("cpuinfo").orEmpty()
        val match = Regex("x\\s*(\\d+)", RegexOption.IGNORE_CASE).find(label)
            ?: Regex("(\\d+)\\s*(?:cores|核)", RegexOption.IGNORE_CASE).find(label)
        match?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { if (it in 1..64) cores = it }

        if (match == null) {
            val file = runCatching {
                session.call("file", "read", JsonObject(mapOf("path" to JsonPrimitive("/proc/cpuinfo")))
                    .let { it })
            }.getOrNull() as? JsonObject
            val count = Regex("processor\\s*:").findAll(file?.str("data").orEmpty()).count()
            if (count in 1..64) cores = count
        }
        coresAt = now
        return cores
    }

    // ---- 网络设备 / 端口 ------------------------------------------------------

    private suspend fun networkDevices(): Map<String, NetDev> {
        val now = System.currentTimeMillis()
        devCache?.let { if (now - devAt < 400) return it }

        val raw = runCatching { session.call("luci-rpc", "getNetworkDevices") }.getOrNull() as? JsonObject
        val map = HashMap<String, NetDev>()
        raw?.forEach { (name, element) ->
            val obj = element as? JsonObject ?: return@forEach
            val stats = obj.obj("stats") ?: JsonObject(emptyMap())
            val link = obj.obj("link") ?: JsonObject(emptyMap())
            map[name] = NetDev(
                mac = obj.str("mac"),
                rx = stats.num("rx_bytes"),
                tx = stats.num("tx_bytes"),
                speed = link.int("speed") ?: 0,
                duplex = link.str("duplex"),
                carrier = (link.get("carrier") as? JsonPrimitive)?.contentOrNull == "true",
                type = obj.str("type"),
            )
        }
        devCache = map
        devAt = now
        return map
    }

    /** LAN 与各无线 AP 接口的累计与实时收发（对齐 collector.js 的 wifiTraffic）。 */
    private fun collectTraffic(now: Long, devs: Map<String, NetDev>, wifi: WifiState): List<IfaceTraffic> {
        val counters = HashMap<String, Pair<Long, Long>>()
        val names = ArrayList<String>()
        val lan = devs["br-lan"]
        if (lan != null) {
            counters["br-lan"] = lan.rx to lan.tx
            names += "br-lan"
        }
        val apNames = devs.keys.filter { Regex("^phy\\d+-ap\\d+$", RegexOption.IGNORE_CASE).matches(it) }.sorted()
        apNames.forEach { name ->
            devs[name]?.let { counters[name] = it.rx to it.tx }
            names += name
        }
        val speed = trafficRates.update(now, counters)

        return names.map { name ->
            val cur = counters[name] ?: (0L to 0L)
            val pair = speed[name] ?: (0L to 0L)
            if (name == "br-lan") {
                IfaceTraffic(
                    device = name,
                    label = "LAN",
                    sub = "局域网 · br-lan",
                    rx = cur.first,
                    tx = cur.second,
                    // LAN 接口视角：tx 是发往局域网设备的方向 = 终端下载，与设备口径一致
                    rate = Rates(down = pair.second, up = pair.first),
                )
            } else {
                val ssid = wifi.ssids.firstOrNull { it.ifname == name }
                IfaceTraffic(
                    device = name,
                    label = ssid?.ssid?.ifBlank { name } ?: name,
                    // 副标题只留频段，接口名 phyX-apX 不再暴露
                    sub = Fmt.bandLabel(ssid?.band ?: ""),
                    rx = cur.first,
                    tx = cur.second,
                    // AP 视角：tx 是发给客户端 = 下行
                    rate = Rates(down = pair.second, up = pair.first),
                )
            }
        }
    }

    private var remarksCache: Map<String, String> = emptyMap()
    private var remarksAt = 0L

    /**
     * Vantage 仪表盘自定义的设备备注：uci get config=vantage 的 client 段（mac + name）。
     * file.read /etc/config/vantage 被固件拒绝（code 6），uci get 可用。
     */
    suspend fun remarks(): Map<String, String> {
        val now = System.currentTimeMillis()
        if (now - remarksAt < 60_000L) return remarksCache
        val payload = runCatching {
            session.call("uci", "get", buildJsonObject { put("config", JsonPrimitive("vantage")) })
        }.getOrNull()
        val values = payload?.jsonObject?.get("values") as? JsonObject
        val map = LinkedHashMap<String, String>()
        values?.forEach { (_, element) ->
            val obj = element as? JsonObject ?: return@forEach
            if (obj[".type"]?.jsonPrimitive?.contentOrNull != "client") return@forEach
            val mac = obj["mac"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: return@forEach
            val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (mac.isNotEmpty() && name.isNotEmpty()) map[mac] = name
        }
        remarksCache = map
        remarksAt = now
        return map
    }

    /** 重启路由器：网页版 /api/system/reboot 同样走 system.reboot。 */
    suspend fun reboot() {
        session.call("system", "reboot", buildJsonObject { })
    }

    /** iwinfo scan：周边 WiFi 列表。固件未放行时抛 RouterException。 */
    suspend fun scan(ifname: String): List<ScanAp> {
        val payload = buildJsonObject { put("device", JsonPrimitive(ifname)) }
        val reply = session.call("iwinfo", "scan", payload) as? JsonObject ?: JsonObject(emptyMap())
        val results = reply.get("results") as? JsonArray ?: JsonArray(emptyList())
        return results.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val bssid = obj.str("bssid")
            if (bssid.isBlank()) return@mapNotNull null
            ScanAp(
                ssid = obj.str("ssid"),
                bssid = bssid.uppercase(),
                band = obj.num("band").toInt(),
                channel = obj.num("channel").toInt(),
                signal = obj.num("signal").toInt(),
                encryption = encryptionLabel(obj),
            )
        }.sortedByDescending { it.signal }
    }

    private fun encryptionLabel(obj: JsonObject): String {
        val enc = obj.obj("encryption") ?: return "—"
        val enabled = enc.get("enabled") as? JsonPrimitive
        if (enabled?.contentOrNull == "false") return "开放"
        val auth = Normalizer.asList(enc.get("authentication")).mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull
        }
        val wpa = Normalizer.asList(enc.get("wpa")).mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull
        }
        return when {
            auth.any { it.contains("sae") } -> "WPA3-SAE"
            auth.any { it.contains("psk") } && wpa.contains("2") -> "WPA2-PSK"
            auth.any { it.contains("psk") } -> "WPA-PSK"
            auth.isNotEmpty() -> auth.joinToString("/").uppercase()
            else -> "加密"
        }
    }

    private fun collectPorts(now: Long, devs: Map<String, NetDev>): List<Port> {
        val names = listOf("wan", "lan1", "lan2", "lan3")
        val counters = names.mapNotNull { name ->
            devs[name]?.let { name to (it.rx to it.tx) }
        }.toMap()
        val rates = portRates.update(now, counters)
        lastPortRates = rates.mapValues { Rates(it.value.first, it.value.second) }
        // 端口累计走终端视角：down = tx（路由器发给终端）、up = rx（终端发来）
        lastPortTotals = counters.mapValues { Rates(down = it.value.second, up = it.value.first) }

        return names.mapNotNull { name ->
            val dev = devs[name] ?: return@mapNotNull null
            Port(
                name = name,
                label = if (name == "wan") "WAN" else name.uppercase(),
                mac = dev.mac,
                carrier = dev.carrier,
                speed = dev.speed,
                duplex = dev.duplex,
                rx = dev.rx,
                tx = dev.tx,
                rate = rates[name]?.let { Rates(it.first, it.second) } ?: Rates(),
            )
        }
    }

    // ---- 无线 ---------------------------------------------------------------

    private suspend fun wireless(): WifiState {
        val now = System.currentTimeMillis()
        if (wifiAt != 0L && now - wifiAt < 12_000) return wifiCache

        val raw = (runCatching { session.call("luci-rpc", "getWirelessDevices") }.getOrNull() as? JsonObject)
            ?: (runCatching { session.call("network.wireless", "status") }.getOrNull() as? JsonObject)
            ?: run {
                wifiCache = WifiState(available = false)
                wifiAt = now
                return wifiCache
            }

        val ssids = mutableListOf<WifiSsid>()
        raw.forEach { (_, radioElement) ->
            val radio = radioElement as? JsonObject ?: return@forEach
            val config = radio.obj("config") ?: JsonObject(emptyMap())
            var radioChannel = config.int("channel") ?: 0
            Normalizer.asList(radio.get("interfaces")).forEach { itemElement ->
                val item = itemElement as? JsonObject ?: return@forEach
                val ifname = item.str("ifname")
                if (ifname.isEmpty()) return@forEach
                val icfg = item.obj("config") ?: JsonObject(emptyMap())
                val info = iwinfoInfo(ifname)
                val assoc = assocList(ifname)
                val stations = Normalizer.asList(item.get("stations"))
                val channel = info?.int("channel") ?: icfg.int("channel") ?: radioChannel
                if (radioChannel == 0) radioChannel = channel ?: 0
                val ssid = info?.str("ssid")?.takeIf { it.isNotEmpty() } ?: icfg.str("ssid")
                val band = Normalizer.bandOfChannel(channel)
                    .ifEmpty { Normalizer.bandFromConfig(config.str("band")) }
                    .ifEmpty {
                        when (config.str("hwmode")) {
                            "11a" -> "5G"
                            "" -> ""
                            else -> "2.4G"
                        }
                    }
                ssids += WifiSsid(
                    ssid = ssid,
                    ifname = ifname,
                    band = band,
                    channel = channel ?: 0,
                    clients = if (assoc.isNotEmpty()) assoc.size else stations.size,
                    encryption = Normalizer.encryptionLabel(icfg.str("encryption"), info),
                )
            }
        }

        wifiCache = WifiState(available = ssids.isNotEmpty(), ssids = ssids)
        wifiAt = now
        return wifiCache
    }

    private suspend fun iwinfoInfo(ifname: String): JsonObject? {
        val now = System.currentTimeMillis()
        infoCache[ifname]?.let { if (now - it.first < 10_000) return it.second }
        val info = runCatching {
            session.call("iwinfo", "info", JsonObject(mapOf("device" to JsonPrimitive(ifname)))
                .let { it })
        }.getOrNull() as? JsonObject
        infoCache[ifname] = now to info
        return info
    }

    private suspend fun assocList(ifname: String): List<AssocClient> {
        val now = System.currentTimeMillis()
        assocCache[ifname]?.let { if (now - it.first < 15_000) return it.second }
        val raw = runCatching {
            session.call("iwinfo", "assoclist", JsonObject(mapOf("device" to JsonPrimitive(ifname)))
                .let { it })
        }.getOrNull()
        val list = Normalizer.assocList(raw)
        assocCache[ifname] = now to list
        return list
    }

    // ---- 有线终端 / 连接数 ----------------------------------------------------

    /**
     * ARP 邻居表：默认固件里唯一能判断"局域网内谁在线"的来源。
     * 走 `file exec /sbin/ip -4 neigh show`（ACL 放行），5 秒缓存，避免每拍 fork 进程。
     * 失败时返回空表——上层会把有线设备当成"未知"，而不是把设备藏起来。
     */
    private suspend fun neighbors(): List<Neighbors.Entry> {
        val now = System.currentTimeMillis()
        if (neighAt != 0L && now - neighAt < 5_000) return neighCache
        val raw = runCatching {
            session.call(
                "file",
                "exec",
                buildJsonObject {
                    put("command", JsonPrimitive("/sbin/ip"))
                    put("params", JsonArray(listOf(JsonPrimitive("-4"), JsonPrimitive("neigh"), JsonPrimitive("show"))))
                },
            ) as? JsonObject
        }.getOrNull()
        neighCache = Neighbors.parse(raw?.str("stdout").orEmpty())
        neighAt = now
        return neighCache
    }

    /**
     * 有线终端候选 = 邻居表（谁在线）+ DHCP 租约（谁存在过）。
     *
     * 默认固件没有 FDB，也没有其它接口能给出 MAC ↔ 端口，所以 port/bridge 一律留空：
     * 端口归属、同端口共享数、端口累计归到设备这三件事都拿不到，UI 会显示"无法获取数据"。
     */
    private suspend fun wiredScan(devs: Map<String, NetDev>, wifi: WifiState): Int {
        val selfMacs = devs.values.map { it.mac.lowercase() }.filter { it.isNotEmpty() }.toSet()
        val wifiMacs = assocCache.values.map { it.second }.flatten().map { it.mac.lowercase() }.toSet()

        val online = LinkedHashMap<String, Boolean>()
        neighbors().forEach { entry ->
            if (entry.mac.isEmpty() || !Neighbors.isLanDev(entry.dev)) return@forEach
            online[entry.mac] = entry.state != Neighbors.State.GONE
        }
        // 租约只补"存在"，不参与在线判定；已经有邻居记录的以邻居为准
        leases().forEach { online.putIfAbsent(it.mac, false) }

        val hosts = online.entries
            .filter { (mac, _) -> mac.isNotEmpty() && !selfMacs.contains(mac) && !wifiMacs.contains(mac) }
            .map { (mac, up) -> WiredHost(mac = mac, online = up) }
        wiredRaw = hosts
        wiredMacs = hosts.filter { it.online }.map { it.mac }.toSet()
        return wiredMacs.size
    }

    private suspend fun connections(): Pair<Int?, Int?> {
        val now = System.currentTimeMillis()
        connCache?.let { if (now - connAt < 15_000) return it }

        suspend fun readCount(path: String): Int? {
            val raw = runCatching {
                session.call("file", "read", JsonObject(mapOf("path" to JsonPrimitive(path)))
                    .let { it })
            }.getOrNull() as? JsonObject
            return raw?.str("data")?.trim()?.toIntOrNull()
        }

        val count = readCount("/proc/sys/net/netfilter/nf_conntrack_count")
        val max = readCount("/proc/sys/net/netfilter/nf_conntrack_max")
        connCache = count to max
        connAt = now
        return connCache!!
    }

    /** 终端页专用：每拍强制刷新，保证速率是 3 秒粒度。 */
    private suspend fun assocListFresh(ifname: String): List<AssocClient> {
        val params = JsonObject(mapOf("device" to JsonPrimitive(ifname)))
        val raw = runCatching { session.call("iwinfo", "assoclist", params) }.getOrNull()
        return Normalizer.assocList(raw)
    }

    // ---- 终端页 -------------------------------------------------------------

    /**
     * 终端列表：复用上一 tick 的接口/无线/端口数据，只额外拉租约、hostHints 与关联表。
     * 客户端速率每 tick 差分一次（iwinfo assoclist 不做缓存），与 collector.js 一致。
     */
    suspend fun devices(): DeviceSnapshot {
        val now = System.currentTimeMillis()

        val assoc = LinkedHashMap<String, List<AssocClient>>()
        // 终端页要 3 秒粒度：每拍重新拉关联表，不复用 wireless() 的 12 秒缓存
        lastWifi.ifnames.forEach { ifname -> assoc[ifname] = assocListFresh(ifname) }

        // 客户端视角：下行 = 路由器发出去的 tx，上行 = 客户端发进来的 rx
        val counters = HashMap<String, Pair<Long, Long>>()
        assoc.values.forEach { list ->
            list.forEach { client -> counters[client.mac] = client.txBytes to client.rxBytes }
        }
        val diffs = clientTracker.update(now, counters)
        clientRates = diffs.mapValues { Rates(it.value.first, it.value.second) }

        val merged = DeviceMerger.merge(
            interfaces = lastIfaces,
            wifi = lastWifi,
            assoc = assoc,
            leases = leases(),
            hints = hints(),
            wiredHosts = wiredRaw,
            remarks = remarks(),
            deviceMacs = lastDeviceMacs,
            clientRates = clientRates,
            portRates = lastPortRates,
            portTotals = lastPortTotals,
            lastKinds = lastKinds,
            now = now,
        )
        lastKinds.clear()
        lastKinds.putAll(merged.second)
        return merged.first
    }


    private suspend fun leases(): List<Lease> {
        val now = System.currentTimeMillis()
        if (leasesAt != 0L && now - leasesAt < 30_000) return leaseCache

        val raw = runCatching { session.call("luci-rpc", "getDHCPLeases") }.getOrNull() as? JsonObject
        val map = LinkedHashMap<String, Lease>()
        Normalizer.asList(raw?.get("dhcp_leases")).mapNotNull { it as? JsonObject }.forEach { item ->
            val mac = item.str("macaddr").ifEmpty { item.str("mac") }.lowercase()
            if (mac.isEmpty()) return@forEach
            map[mac] = Lease(
                mac = mac,
                ip = item.str("ipaddr").ifEmpty { item.str("ip") },
                hostname = item.str("hostname").replace(Regex("\\.lan$", RegexOption.IGNORE_CASE), ""),
                expires = item.num("expires"),
            )
        }
        leasesAt = now
        leaseCache = map.values.toList()
        return leaseCache
    }

    private suspend fun hints(): Map<String, HostHint> {
        val now = System.currentTimeMillis()
        if (hintsAt != 0L && now - hintsAt < 25_000) return hintCache

        val raw = runCatching { session.call("luci-rpc", "getHostHints") }.getOrNull() as? JsonObject
        val map = LinkedHashMap<String, HostHint>()
        raw?.forEach { (macKey, element) ->
            val obj = element as? JsonObject ?: return@forEach
            val key = macKey.lowercase().replace('-', ':')
            if (key.isEmpty()) return@forEach
            val name = obj.str("name").replace(Regex("\\.lan$", RegexOption.IGNORE_CASE), "")
            val ip = Normalizer.asList(obj.get("ipaddrs")).firstOrNull()
                ?.let { it as? JsonPrimitive }?.contentOrNull.orEmpty().substringBefore('/')
            val ip6List = Normalizer.asList(obj.get("ip6addrs"))
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.substringBefore('/') }
                .filter { it.isNotEmpty() && !it.startsWith("fe80") }
            if (name.isEmpty() && ip.isEmpty()) return@forEach
            map[key] = HostHint(name = name, ip = ip, ip6 = ip6List.firstOrNull().orEmpty(), ip6List = ip6List)
        }
        hintsAt = now
        hintCache = map
        return map
    }
}
