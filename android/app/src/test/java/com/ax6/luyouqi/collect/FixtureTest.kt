package com.ax6.luyouqi.collect

import com.ax6.luyouqi.fmt.Fmt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真机 fixtures（Redmi AX6 / ImmortalWrt 24.10.6 r33869，2026-11-24 抓取，已脱敏）。
 * 这是"App 解析口径 = 路由器真实返回"的守门测试：换固件后重抓 fixtures，这里会立刻报出差异。
 */
class FixtureTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun load(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "缺少 fixtures/$name"
        }.bufferedReader().readText()

    private fun jsonOf(name: String) = json.parseToJsonElement(load(name))

    private fun JsonObject.text(key: String): String =
        (get(key) as kotlinx.serialization.json.JsonPrimitive).content

    @Test
    fun board_fieldsMatchWebVersion() {
        val board = jsonOf("system.board.json").jsonObject
        assertEquals("Redmi AX6 (stock layout)", board["model"]?.toString()?.trim('"'))
        val release = board["release"]?.jsonObject!!
        assertEquals("ImmortalWrt 24.10.6 r33869-cf234f8de6d5", release["description"]?.toString()?.trim('"'))
        assertEquals("qualcommax/ipq807x", release["target"]?.toString()?.trim('"'))
    }

    @Test
    fun interfaces_wanIsDhcpAndWan6HasPd() {
        val list = Normalizer.interfaces(jsonOf("network.interface.dump.json"), 1_700_000_000)
        val wan = list.first { it.id == "wan" }
        val wan6 = list.first { it.id == "wan6" }

        assertTrue(wan.up)
        assertEquals("dhcp", wan.proto)
        assertEquals("192.168.1.13/24", wan.ipv4.first())
        assertEquals("192.168.1.13", wan.ipv4First)
        assertTrue(wan6.up)
        assertEquals("dhcpv6", wan6.proto)
        assertTrue(wan6.ipv6.isNotEmpty())
        assertTrue(wan6.pd.isNotEmpty())
        // 链路本地网关保留，前端 WAN6 副行不显示网关，不影响展示
        assertEquals("fe80::1", wan6.gateway)
        assertTrue(wan6.ipv6.none { it.startsWith("fe80") })
    }

    @Test
    fun networkDevices_reportLinkStateAndStats() {
        val devices = jsonOf("luci-rpc.getNetworkDevices.json").jsonObject
        val wan = devices["wan"]!!.jsonObject
        val link = wan["link"]!!.jsonObject
        assertEquals(1000, link["speed"].toString().toInt())
        assertEquals("full", link["duplex"].toString().trim('"'))
        assertEquals("true", link["carrier"].toString())
        assertTrue(wan["stats"]!!.jsonObject["rx_bytes"].toString().toLong() > 0)

        // 四个物理口都在
        listOf("wan", "lan1", "lan2", "lan3").forEach { assertNotNull(devices[it]) }
        // MAC 脱敏后仍然互不相同（不然端口映射没法测）
        val macs = devices.values.mapNotNull { it.jsonObject["mac"]?.toString() }.toSet()
        assertTrue(macs.size >= 3)
    }

    @Test
    fun wireless_twoBandsWithEncryptionAndClients() {
        val raw = jsonOf("luci-rpc.getWirelessDevices.json")
        val assoc = Normalizer.assocList(jsonOf("iwinfo.assoclist.phy1-ap0.json"))
        assertTrue(assoc.isNotEmpty())

        val info5g = jsonOf("iwinfo.info.phy0-ap0.json").jsonObject
        assertEquals(44, info5g["channel"].toString().toInt())
        assertTrue(info5g.text("ssid").contains("SSID"))
        assertEquals("WPA2/AES", Normalizer.encryptionLabel("psk2+ccmp", info5g))

        // 5G 信道 44 映射正确，2.4G 信道 5 映射正确
        assertEquals("5G", Normalizer.bandOfChannel(44))
        assertEquals("2.4G", Normalizer.bandOfChannel(5))

        // luci-rpc 已经把 iwinfo 嵌在每个 interface 里，不需要再单独调 iwinfo info
        val radios = raw.jsonObject
        val ifnames = radios.values.flatMap { radio ->
            val obj = radio.jsonObject
            (obj["interfaces"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
                (it as? JsonObject)?.get("ifname")?.toString()?.trim('"')
            } ?: emptyList()
        }
        assertEquals(listOf("phy0-ap0", "phy1-ap0"), ifnames)
    }

    @Test
    fun temperature_textUsesComma() {
        val text = jsonOf("luci.getTempInfo.json").jsonObject.text("tempinfo")
        // 真机格式是逗号分隔："CPU: 48.9°C, WiFi: 53.0°C 59.0°C"（温度会变，不写死具体值）
        assertTrue(Regex("CPU: [0-9.]+°C, WiFi: [0-9.]+°C [0-9.]+°C").matches(text))
        val parsed = checkNotNull(Normalizer.temperature(text))
        assertTrue(parsed.first!! in 20.0..120.0)
        assertEquals(2, parsed.second.size)
        assertEquals("°", "${Fmt.num(parsed.first, 1)}°".takeLast(1))
    }

    @Test
    fun cpuInfo_yieldsFourCores() {
        val text = jsonOf("luci.getCPUInfo.json").jsonObject["cpuinfo"]!!.toString().trim('"')
        assertTrue(text.contains("x 4"))
        val match = Regex("x\\s*(\\d+)", RegexOption.IGNORE_CASE).find(text)
        assertEquals(4, match!!.groupValues[1].toInt())
    }

    @Test
    fun conntrack_readsAsInteger() {
        val data = jsonOf("file.read.conntrack_count.json").jsonObject.text("data")
        assertTrue(data.trim().toInt() > 0)
        val max = jsonOf("file.read.conntrack_max.json").jsonObject.text("data")
        assertTrue(max.trim().toInt() > data.trim().toInt())
    }

    @Test
    fun neighbourTable_hasLanEntriesAndWanNoise() {
        val raw = jsonOf("file.exec.ip4.neigh.json").jsonObject
        assertEquals(0, raw.text("code").toInt())
        val entries = Neighbors.parse(raw.text("stdout"))
        assertTrue(entries.isNotEmpty())
        // 局域网侧：带 lladdr、dev 为 br-lan
        val lan = entries.filter { Neighbors.isLanDev(it.dev) && it.mac.isNotEmpty() }
        assertTrue(lan.isNotEmpty())
        assertTrue(lan.any { it.state == Neighbors.State.PRESENT })
        assertTrue(lan.any { it.state == Neighbors.State.UNCONFIRMED })
        // wan 侧：上游网段的 FAILED 条目，必须被 isLanDev 挡掉
        assertTrue(entries.any { it.dev == "wan" && it.state == Neighbors.State.GONE })
        assertTrue(entries.none { it.dev == "wan" && Neighbors.isLanDev(it.dev) })
    }

    @Test
    fun dhcpLeases_haveHostnameMacIpExpires() {
        val leases = (jsonOf("luci-rpc.getDHCPLeases.json").jsonObject["dhcp_leases"] as kotlinx.serialization.json.JsonArray)
            .map { it.jsonObject }
        assertTrue(leases.isNotEmpty())
        leases.forEach { lease ->
            assertNotNull(lease["macaddr"])
            assertNotNull(lease["ipaddr"])
        }
    }
}
