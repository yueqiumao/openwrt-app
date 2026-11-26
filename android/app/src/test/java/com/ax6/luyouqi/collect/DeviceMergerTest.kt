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

    private val wiredHosts by lazy {
        (obj("luci.vantage.wired.json")["hosts"] as JsonArray).map { element ->
            val o = element.jsonObject
            WiredHost(text(o, "mac"), text(o, "port"), text(o, "bridge"), "")
        }
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
        assertEquals(2, snapshot.totals.wired) // 真机 vantage 里只有 lan2、lan3 两台上游设备
        assertEquals(expectedWifi + 2, snapshot.totals.online)
        assertEquals(snapshot.devices.count { it.online }, snapshot.totals.online)
    }

    @Test
    fun wifiClientsAreNotCountedAsWired() {
        val (snapshot, _) = runMerge()
        // vantage 的 hosts 里混着 phy1-ap0 的 7 台 WiFi 客户端，不能被算成有线
        val wired = snapshot.devices.filter { it.online && it.kind == DeviceKind.WIRED }
        assertEquals(setOf("lan2", "lan3"), wired.map { it.port }.toSet())
        assertFalse(wired.any { it.port.contains("phy") })
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
    fun wiredDeviceUsesPortRateAndSharesFlag() {
        val wired = wiredHosts.first { !it.port.startsWith("phy") && it.port in setOf("lan2", "lan3") }
        // portRates 是路由器端口视角：down = 端口收到的（终端上传），up = 端口发出的（终端下载）。
        // 设备侧口径与 WiFi 对齐（down = 终端下载），因此必须互换。
        val (snapshot, _) = runMerge(portRates = mapOf(wired.port to Rates(down = 2048, up = 1024)))
        val device = snapshot.devices.first { it.mac == DeviceMerger.mac(wired.mac) }
        assertEquals(1024L, device.rate.down)
        assertEquals(2048L, device.rate.up)
        assertFalse(device.rateShared) // 该端口只有 1 台设备
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
        assertTrue(DeviceMerger.ipv4InCidr("192.168.99.125", "192.168.99.11/24"))
        assertFalse(DeviceMerger.ipv4InCidr("192.168.1.13", "192.168.99.11/24"))
        assertTrue(DeviceMerger.ipv4InCidr("192.168.1.13", "192.168.1.0/24"))
    }
}
