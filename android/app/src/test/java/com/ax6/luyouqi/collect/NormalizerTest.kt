package com.ax6.luyouqi.collect

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 归一化口径测试。
 * 样本结构对齐真机 `/ubus` 返回（字段名、嵌套层级一致），数值经过脱敏和简化，
 * 不是线的真实数据；标记 synthetic 只是为了提醒后续用 tools/capture-fixtures.js 补真实样本。
 */
class NormalizerTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val dump = json.parseToJsonElement(
        """
        {
          "interface": [
            {
              "interface": "wan",
              "up": true,
              "proto": "pppoe",
              "device": "eth0.2",
              "l3_device": "pppoe-wan",
              "ipv4-address": [{"address": "100.64.0.2", "mask": 32}],
              "ipv6-address": [],
              "route": [
                {"target": "0.0.0.0", "mask": 0, "nexthop": "100.64.0.1"},
                {"target": "100.64.0.1", "mask": 32, "nexthop": "0.0.0.0"}
              ],
              "dns-server": ["223.5.5.5", "119.29.29.29"],
              "stats": {"rx_bytes": 123456, "tx_bytes": 654321},
              "uptime": 7200,
              "data": {"leasetime": 0}
            },
            {
              "interface": "wan6",
              "up": true,
              "proto": "dhcpv6",
              "device": "eth0.2",
              "ipv4-address": [],
              "ipv6-address": [
                {"address": "240e:3b0:1234::2", "mask": 128, "valid": 3600},
                {"address": "fe80::1", "mask": 64, "valid": 3600}
              ],
              "ipv6-prefix": [{"address": "240e:3b0:1234:5600::", "mask": 60}],
              "ipv6-prefix-assignment": [{"local-address": {"address": "240e:3b0:1234:5601::1", "mask": 64}}],
              "route": [{"target": "::", "mask": 0, "nexthop": "fe80::1"}],
              "stats": {"rx_bytes": 1000, "tx_bytes": 2000},
              "uptime": 7000
            },
            {
              "interface": "lan",
              "up": true,
              "proto": "static",
              "device": "br-lan",
              "ipv4-address": [{"address": "10.10.10.1", "mask": 24}],
              "stats": {"rx_bytes": 10, "tx_bytes": 20},
              "uptime": 9000
            }
          ]
        }
        """.trimIndent(),
    )

    @Test
    fun interfaces_areSortedWanFirstAndWanHasNoIpv6LinkLocal() {
        val list = Normalizer.interfaces(dump, 1_700_000_000)
        assertEquals(listOf("wan", "wan6", "lan"), list.map { it.id })

        val wan = list[0]
        assertEquals("pppoe", wan.proto)
        assertEquals("pppoe-wan", wan.device)
        assertEquals("100.64.0.2", wan.ipv4First)
        assertEquals("100.64.0.1", wan.gateway)
        assertEquals(2, wan.dns.size)
        assertEquals(7200L, wan.uptime)
        // leasetime 为 0 时不产生租约倒计时
        assertNull(wan.leaseRemaining)

        val wan6 = list[1]
        assertEquals(listOf("240e:3b0:1234::2/128", "240e:3b0:1234:5601::1/64"), wan6.ipv6)
        assertEquals("240e:3b0:1234:5600::/60", wan6.pd.first())
        assertEquals("fe80::1", wan6.gateway) // 只有 "::" 会归一为空；链路本地网关保留
    }

    @Test
    fun load_restoresFixedPoint() {
        assertEquals(listOf(0.03, 0.02, 0.01), Normalizer.load(json.parseToJsonElement("[2112,1024,512]")))
        assertEquals(listOf(1.2, 0.8, 0.5), Normalizer.load(json.parseToJsonElement("[1.2,0.8,0.5]")))
        assertEquals(emptyList<Double>(), Normalizer.load(null)) // 与 collector.js 一致：无字段就是空数组
    }

    @Test
    fun memory_prefersAvailableAndFallsBackToFreePlusCache() {
        val withAvailable = Normalizer.memory(
            json.parseToJsonElement("""{"total":512000,"available":300000,"free":100}""") as kotlinx.serialization.json.JsonObject,
        )
        assertEquals(212000L, withAvailable.used)
        assertEquals(41.4, withAvailable.pct, 0.001)

        val noAvailable = Normalizer.memory(
            json.parseToJsonElement("""{"total":512000,"free":200000,"buffered":20000,"cached":100000}""") as kotlinx.serialization.json.JsonObject,
        )
        assertEquals(192000L, noAvailable.used)
    }

    @Test
    fun temperature_parsesCpuAndTwoWifiSensors() {
        val parsed = Normalizer.temperature("CPU: 52.1°C / WiFi: 51.0°C 61.2°C")
        assertEquals(52.1, parsed!!.first!!, 0.001)
        assertEquals(listOf(51.0, 61.2), parsed.second)

        val onlyCpu = Normalizer.temperature("CPU: 48.0°C")
        assertEquals(48.0, onlyCpu!!.first!!, 0.001)
        assertTrue(onlyCpu.second.isEmpty())

        assertNull(Normalizer.temperature("unavailable"))
    }

    @Test
    fun thermalZoneTreatsLargeNumbersAsMilliCelsius() {
        assertEquals(52.3, Normalizer.thermalCelsius("52300")!!, 0.001)
        assertEquals(52.0, Normalizer.thermalCelsius("52")!!, 0.001)
        assertNull(Normalizer.thermalCelsius("0"))
        assertNull(Normalizer.thermalCelsius("abc"))
    }

    @Test
    fun bandMapping() {
        assertEquals("2.4G", Normalizer.bandOfChannel(1))
        assertEquals("2.4G", Normalizer.bandOfChannel(13))
        assertEquals("5G", Normalizer.bandOfChannel(36))
        assertEquals("5G", Normalizer.bandOfChannel(149))
        assertEquals("6G", Normalizer.bandOfChannel(233))
        assertEquals("", Normalizer.bandOfChannel(0))
    }

    @Test
    fun assocList_normalizesMacAndReadsSignal() {
        val raw = json.parseToJsonElement(
            """
            {"results":[
              {"mac":"AA:BB:CC:DD:EE:01","signal":-58,"rx":{"bytes":100},"tx":{"bytes":200}},
              {"addr":"aa-bb-cc-dd-ee-02","signal":-70}
            ]}
            """.trimIndent(),
        )
        val list = Normalizer.assocList(raw)
        assertEquals(2, list.size)
        assertEquals("aa:bb:cc:dd:ee:01", list[0].mac)
        assertEquals(-58, list[0].signal)
        assertEquals("aa:bb:cc:dd:ee:02", list[1].mac)
    }

    @Test
    fun encryptionLabels() {
        assertEquals("开放", Normalizer.encryptionLabel("none", null))
        assertEquals("WPA2/AES", Normalizer.encryptionLabel("psk2+ccmp", null))
        assertEquals("SAE", Normalizer.encryptionLabel("sae", null)) // 与网页版一致：未知算法原样大写
        assertEquals("WPA2", Normalizer.encryptionLabel("psk2", null))
    }

    @Test
    fun rateTracker_clampsAndIgnoresShortWindow() {
        assertEquals(500L, RateTracker.rate(1000, 500, 1.0))
        assertEquals(0L, RateTracker.rate(1000, 500, 0.1))
        assertEquals(0L, RateTracker.rate(500, 1000, 1.0))
    }

    @Test
    fun tracker_firstSampleProducesNoRate() {
        val tracker = RateTracker.Tracker()
        val first = tracker.update(1_000L, mapOf("wan" to (100L to 200L)))
        assertTrue(first.isEmpty())
        val second = tracker.update(4_000L, mapOf("wan" to (400L to 500L)))
        assertEquals(100L to 100L, second["wan"])
    }
}
