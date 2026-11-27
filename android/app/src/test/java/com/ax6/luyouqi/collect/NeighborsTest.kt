package com.ax6.luyouqi.collect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `ip -4 neigh show` 的解析：这是默认固件里唯一能判"局域网谁在线"的文本，容错必须够硬。 */
class NeighborsTest {

    @Test
    fun parsesReachableAndStaleEntries() {
        val text = """
            192.168.99.142 dev br-lan lladdr d4:50:ee:b2:b4:92 ref 1 used 0/0/0 probes 1 REACHABLE
            192.168.99.88 dev br-lan lladdr D8-CB-8A-E8-CB-52 used 3/2/0 probes 1 STALE
        """.trimIndent()
        val entries = Neighbors.parse(text)
        assertEquals(2, entries.size)
        assertEquals("d4:50:ee:b2:b4:92", entries[0].mac)
        assertEquals(Neighbors.State.PRESENT, entries[0].state)
        // 有的固件/工具会输出大写 MAC 和短横线，统一成小写冒号
        assertEquals("d8:cb:8a:e8:cb:52", entries[1].mac)
        assertEquals(Neighbors.State.UNCONFIRMED, entries[1].state)
    }

    @Test
    fun failedEntriesHaveNoMacAndCountAsGone() {
        val entries = Neighbors.parse("192.168.1.3 dev wan  used 0/0/0 probes 6 FAILED")
        assertEquals(1, entries.size)
        assertEquals("", entries[0].mac)
        assertEquals(Neighbors.State.GONE, entries[0].state)
    }

    @Test
    fun skipsJunkAndIncompleteLines() {
        val text = "\n\nnot a neighbour line\n10.0.0.1 dev\n"
        assertTrue(Neighbors.parse(text).isEmpty())
    }

    @Test
    fun onlyBridgeLanCountsAsLanDevice() {
        assertTrue(Neighbors.isLanDev("br-lan"))
        assertTrue(Neighbors.isLanDev("br-lan.20"))
        assertFalse(Neighbors.isLanDev("wan"))
        assertFalse(Neighbors.isLanDev("eth0"))
        assertFalse(Neighbors.isLanDev("br-guest"))
    }

    @Test
    fun ipv6StyleLineStillParsesDevAndState() {
        // 万一将来读 -6 neigh，格式里没有 lladdr 也能取到 dev/state
        val entries = Neighbors.parse("fe80::4ef9:cb74:59d5:34f7 dev br-lan lladdr d8:cb:8a:e8:cb:52 used 0/0/0 probes 1 STALE")
        assertEquals(1, entries.size)
        assertEquals("fe80::4ef9:cb74:59d5:34f7", entries[0].ip)
        assertEquals(Neighbors.State.UNCONFIRMED, entries[0].state)
    }
}
