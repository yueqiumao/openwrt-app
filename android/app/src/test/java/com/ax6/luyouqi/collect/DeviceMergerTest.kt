package com.ax6.luyouqi.collect

import com.ax6.luyouqi.domain.DeviceKind
import com.ax6.luyouqi.domain.HostHint
import com.ax6.luyouqi.domain.Lease
import com.ax6.luyouqi.domain.Rates
import com.ax6.luyouqi.domain.WifiSsid
import com.ax6.luyouqi.domain.WifiState
import com.ax6.luyouqi.domain.WiredHost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 终端合并用真机样本验证：有线/WiFi 判定、离线记忆、排序与端口速率。 */
class DeviceMergerTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun load(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) { "缺少 fixtures/$name" }
            .bufferedReader().readText()

    private fun obj(name: String) = json.parseToJsonElement(load(name)).jsonObject

    private fun text(o: JsonObject, key: String) =
        (o[key] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""

    private val ifaces by lazy { Normalizer.interfaces(json.parseToJsonElement(load("network.interface.dump.json")), 1_700_000_000) }

    private val assoc by lazy {
        mapOf(
            "phy0-ap0" to Normalizer.assocList(json.parseToJsonElement(load("iwinfo.assoclist.phy0-ap0.json"))),
            "phy1-ap0" to Normalizer.assocList(json.parseToJsonElement(load("iwinfo.assoclist.phy1-ap0.json"))),
        )
    }

    private val wifi by lazy {
        WifiState(
            available = true,
            ssids = listOf(
                WifiSsid("(已脱敏 SSID)", "phy0-ap0", "5G", 44, assoc["phy0-ap0"].orEmpty().size, "WPA2/AES"),
                WifiSsid("(已脱敏 SSID)", "phy1-ap0", "2.4G", 5, assoc["phy1-ap0"].orEmpty().size, "WPA2/AES"),
            ),
        )
    }

    private val leases by lazy {
        (obj("luci-rpc.getDHCPLeases.json")["dhcp_leases"] as JsonArray).map { item ->
            val o = item.jsonObject
            Lease(
                mac = text(o, "macaddr").lowercase(),
                ip = text(o, "ipaddr"),
                hostname = text(o, "hostname").removeSuffix(".lan"),
                expires = text(o, "expires").toLongOrNull() ?: 0,
            )
        }
    }

    private val hints by lazy {
        val raw = obj("luci-rpc.getHostHints.json")
        raw.entries.associate { (mac, element) ->
            val o = element.jsonObject
            mac.lowercase() to HostHint(
                name = text(o, "name").removeSuffix(".lan"),
                ip = (o["ipaddrs"] as? JsonArray)?.firstOrNull()?.toString()?.trim('"')?.substringBefore('/').orEmpty(),
                ip6 = "",
                ip6List = emptyList(),
            )
        }
    }

    private val wifiMacs by lazy { assoc.values.flatten().map { it.mac.lowercase() }.toSet() }

    /** 与 RouterRepository.wiredScan 同口径：邻居表判在线、租约补存在、剔除路由自身与 WiFi 终端。 */
    private val wiredHosts by lazy {
        val online = LinkedHashMap<String, Boolean>()
        val neigh = obj("file.exec.ip4.neigh.json")
        Neighbors.parse(text(neigh, "stdout")).forEach { entry ->
            if (entry.mac.isEmpty() || !Neighbors.isLanDev(entry.dev)) return@forEach
            online[entry.mac] = entry.state != Neighbors.State.GONE
        }
        leases.forEach { online.putIfAbsent(it.mac.lowercase(), false) }
        online.entries
            .filter { (mac, _) -> !mac.isEmpty() && !deviceMacs.contains(mac) && !wifiMacs.contains(mac) }
            .map { (mac, up) -> WiredHost(mac = mac, online = up) }
    }

    private val deviceMacs by lazy {
        obj("luci-rpc.getNetworkDevices.json").values.mapNotNull { (it as? JsonObject)?.let { d -> text(d, "mac") } }
            .filter { it.isNotBlank() }.map { it.lowercase() }.toSet()
    }

    private fun runMerge(
        lastKinds: Map<String, Pair<DeviceKind, Long>> = emptyMap(),
        withWifi: Boolean = true,
        portRates: Map<String, Rates> = emptyMap(),
    ) = DeviceMerger.merge(
        interfaces = ifaces,
        wifi = if (withWifi) wifi else WifiState(available = true, ssids = wifi.ssids),
        assoc = if (withWifi) assoc else emptyMap(),
        leases = leases,
        hints = hints,
        wiredHosts = wiredHosts,
        deviceMacs = deviceMacs,
        clientRates = emptyMap(),
        portRates = portRates,
        lastKinds = lastKinds,
        now = 1_700_000_000_000,
    )

    @Test
    fun wifiAndWiredCountsMatchVantageAndAssoc() {
        val (snapshot, _) = runMerge()
        val expectedWifi = assoc.values.flatten().map { it.mac }.toSet().size
        assertEquals(expectedWifi, snapshot.totals.wifi)
        // 邻居表里 lan 侧 3 台：00:04 REACHABLE、00:05 STALE、00:0e 只有邻居没有租约（静态 IP）
        assertEquals(3, snapshot.totals.wired)
        assertEquals(expectedWifi + 3, snapshot.totals.online)
        assertEquals(snapshot.devices.count { it.online }, snapshot.totals.online)
    }

    @Test
    fun wifiClientsAreNotCountedAsWired() {
        val (snapshot, _) = runMerge()
        // 邻居表里同时出现 WiFi 客户端（00:09 STALE）与路由自身（00:01），都不能算成有线
        val wired = snapshot.devices.filter { it.online && it.kind == DeviceKind.WIRED }
        assertEquals(3, wired.size)
        assertTrue(wired.none { wifiMacs.contains(it.mac) })
        assertTrue(wired.none { it.mac == "02:00:00:00:00:01" })
        assertTrue(snapshot.devices.filter { it.kind == DeviceKind.WIFI }.all { it.ifname.startsWith("phy") })
    }

    @Test
    fun sortingPutsWiredFirstThenFiveGhzThenTwoPointFour() {
        val (snapshot, _) = runMerge()
        val online = snapshot.devices.filter { it.online }
        assertEquals(DeviceKind.WIRED, online.first().kind)
        val bands = online.filter { it.kind == DeviceKind.WIFI }.map { it.band }
        assertEquals("5G", bands.first())
        assertEquals("2.4G", bands.last())
    }

    @Test
    fun offlineDevicesKeepLastKindForThirtyMinutes() {
        val (first, kinds) = runMerge()
        val wifiMac = first.devices.first { it.online && it.kind == DeviceKind.WIFI }.mac

        // 60 秒后该设备从关联表消失：应保持 WIFI 类型，但 online=false 且不参与计数
        val (later, _) = DeviceMerger.merge(
            interfaces = ifaces,
            wifi = wifi,
            assoc = assoc.mapValues { (ifname, list) -> list.filterNot { it.mac == wifiMac } },
            leases = leases,
            hints = hints,
            wiredHosts = wiredHosts,
            deviceMacs = deviceMacs,
            clientRates = emptyMap(),
            portRates = emptyMap(),
            lastKinds = kinds,
            now = 1_700_000_060_000,
        )
        val gone = later.devices.first { it.mac == wifiMac }
        assertFalse(gone.online)
        assertEquals(DeviceKind.WIFI, gone.kind)
        assertEquals(later.devices.count { it.online }, later.totals.online)
    }

    @Test
    fun wiredWithoutPortCarriesNoRateOrTotals() {
        // 默认固件没有 FDB：端口未知，速率/累计/共享数都必须留空，由 UI 显示"无法获取数据"
        val (snapshot, _) = runMerge(portRates = mapOf("lan2" to Rates(down = 2048, up = 1024)))
        val wired = snapshot.devices.filter { it.online && it.kind == DeviceKind.WIRED }
        assertTrue(wired.all { it.port.isBlank() })
        assertTrue(wired.all { it.rate == Rates() })
        assertTrue(wired.all { it.downTotal == null && it.upTotal == null })
        assertTrue(wired.all { it.portPeers == 0 && !it.rateShared })
    }

    @Test
    fun fdbPortsRestoreRateSharingAndTotals() {
        // 装了 vantage：FDB 给出端口，端口速率/累计/共享台数应回到详情页
        val fdbHosts = wiredHosts.map { host ->
            if (host.mac.endsWith(":04") || host.mac.endsWith(":05")) {
                host.copy(port = "lan2", bridge = "br-lan", online = true)
            } else {
                host
            }
        }
        val (snapshot, _) = DeviceMerger.merge(
            interfaces = ifaces,
            wifi = wifi,
            assoc = assoc,
            leases = leases,
            hints = hints,
            wiredHosts = fdbHosts,
            deviceMacs = deviceMacs,
            clientRates = emptyMap(),
            portRates = mapOf("lan2" to Rates(down = 3000, up = 1000)),
            portTotals = mapOf("lan2" to Rates(down = 9_000_000, up = 3_000_000)),
            lastKinds = emptyMap(),
            now = 1_700_000_000_000,
        )
        val a = snapshot.devices.first { it.mac.endsWith(":04") }
        val b = snapshot.devices.first { it.mac.endsWith(":05") }
        assertEquals("lan2", a.port)
        assertEquals("br-lan", a.bridge)
        assertEquals(2, a.portPeers)
        assertEquals(2, b.portPeers)
        assertTrue(a.rateShared)
        // 端口计数是路由器视角，展示要换成终端视角：down = 端口 tx，up = 端口 rx
        assertEquals(Rates(down = 1000, up = 3000), a.rate)
        assertEquals(9_000_000L, a.downTotal)
        assertEquals(3_000_000L, a.upTotal)
    }

    @Test
    fun offlineFdbEntryDoesNotCountAsPortPeer() {
        val fdbHosts = wiredHosts.map { host ->
            if (host.mac.endsWith(":04") || host.mac.endsWith(":05")) {
                host.copy(port = "lan2", bridge = "br-lan", online = true)
            } else if (host.mac.endsWith(":0e")) {
                // FDB 里有、邻居表里已消失：端口保留，但在线为 false，不参与同端口计数
                host.copy(port = "lan2", bridge = "br-lan", online = false)
            } else {
                host
            }
        }
        val (snapshot, _) = DeviceMerger.merge(
            interfaces = ifaces,
            wifi = wifi,
            assoc = assoc,
            leases = leases,
            hints = hints,
            wiredHosts = fdbHosts,
            deviceMacs = deviceMacs,
            clientRates = emptyMap(),
            portRates = mapOf("lan2" to Rates(down = 3000, up = 1000)),
            lastKinds = emptyMap(),
            now = 1_700_000_000_000,
        )
        val online = snapshot.devices.first { it.mac.endsWith(":04") }
        assertEquals(2, online.portPeers)
    }

    @Test
    fun wiredSourceIsFdbWhenPortKnown() {
        // FDB 命中（有端口）时来源写 fdb，详情页显示「有线 FDB」
        val fdbHosts = wiredHosts.map { host ->
            if (host.mac.endsWith(":04")) host.copy(port = "lan2", bridge = "br-lan") else host
        }
        val (snapshot, _) = DeviceMerger.merge(
            interfaces = ifaces,
            wifi = wifi,
            assoc = assoc,
            leases = leases,
            hints = hints,
            wiredHosts = fdbHosts,
            deviceMacs = deviceMacs,
            clientRates = emptyMap(),
            portRates = emptyMap(),
            lastKinds = emptyMap(),
            now = 1_700_000_000_000,
        )
        assertEquals("fdb", snapshot.devices.first { it.mac.endsWith(":04") }.source)
        // 没有 FDB 的有线仍标邻居表
        assertEquals("neigh", snapshot.devices.first { it.mac.endsWith(":05") }.source)
    }

    @Test
    fun leaseOnlyEntryStaysOfflineAndUnclassified() {
        val unknown = WiredHost(mac = "02:00:00:00:00:11", online = false)
        val (snapshot, _) = DeviceMerger.merge(
            interfaces = ifaces,
            wifi = wifi,
            assoc = assoc,
            leases = leases,
            hints = hints,
            wiredHosts = wiredHosts + unknown,
            deviceMacs = deviceMacs,
            clientRates = emptyMap(),
            portRates = emptyMap(),
            lastKinds = emptyMap(),
            now = 1_700_000_000_000,
        )
        val device = snapshot.devices.first { it.mac == "02:00:00:00:00:11" }
        assertFalse(device.online)
        assertEquals(DeviceKind.UNKNOWN, device.kind)
        assertEquals("lease", device.source)
    }

    @Test
    fun hostHintsAndLeasesFillNamesWithoutMakingDevicesOnline() {
        val (snapshot, _) = runMerge()
        val offline = snapshot.devices.filterNot { it.online }
        assertTrue(offline.isNotEmpty())
        // 离线设备必须有 MAC，显示名允许回退到 MAC
        assertTrue(offline.all { it.mac.isNotBlank() })
        assertTrue(offline.all { !it.online })
        // 在线设备应尽量有 IP 或名字
        assertTrue(snapshot.devices.any { it.hostname.isNotBlank() || it.ip.isNotBlank() })
    }

    @Test
    fun cidrMatcherHandlesLanRange() {
        assertTrue(DeviceMerger.ipv4InCidr("10.10.10.125", "10.10.10.11/24"))
        assertFalse(DeviceMerger.ipv4InCidr("192.168.1.13", "10.10.10.11/24"))
        assertTrue(DeviceMerger.ipv4InCidr("192.168.1.13", "192.168.1.0/24"))
    }
}
