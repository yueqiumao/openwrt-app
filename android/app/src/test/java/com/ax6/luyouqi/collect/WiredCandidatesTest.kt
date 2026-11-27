package com.ax6.luyouqi.collect

import com.ax6.luyouqi.domain.WiredHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FDB 权威 / 回退口径的两条主线，重点是"刚掉线的无线客户端不能变成有线"。 */
class WiredCandidatesTest {

    private val wiredMac = "aa:aa:aa:aa:aa:01"   // 有线：FDB 里有 lan2
    private val lostWifiMac = "aa:aa:aa:aa:aa:02" // 刚断开 WiFi：ARP 还残留 STALE，FDB 里已没有
    private val leaseOnlyMac = "aa:aa:aa:aa:aa:03" // 只有租约
    private val fdbOnlyMac = "aa:aa:aa:aa:aa:04"   // FDB 有、邻居表还没学到
    private val selfMac = "aa:aa:aa:aa:aa:05"

    private val neighbors = mapOf(
        wiredMac to true,
        lostWifiMac to true,
    )
    private val leases = listOf(leaseOnlyMac)
    private val fdb = mapOf(
        wiredMac to WiredHost(mac = wiredMac, port = "lan2", bridge = "br-lan", online = true),
        fdbOnlyMac to WiredHost(mac = fdbOnlyMac, port = "lan3", bridge = "br-lan", online = true),
    )

    @Test
    fun authoritativeFdbDropsJustDisconnectedWifiClient() {
        val hosts = WiredCandidates.pick(
            online = neighbors,
            leaseMacs = leases,
            selfMacs = setOf(selfMac),
            wifiMacs = emptySet(),
            fdb = fdb,
            fdbAuthoritative = true,
        )
        val macs = hosts.map { it.mac }.toSet()
        assertFalse("ARP 残留的无线客户端不能被判成有线", macs.contains(lostWifiMac))
        assertTrue(macs.contains(wiredMac))
        assertEquals("lan2", hosts.first { it.mac == wiredMac }.port)
        // FDB 独有条目保留（端口先记下，在线由 seen 决定）
        assertTrue(macs.contains(fdbOnlyMac))
        // 只有租约、FDB 不认识它：权威模式下也不进有线
        assertFalse(macs.contains(leaseOnlyMac))
    }

    @Test
    fun emptyFdbFallsBackToNeighborTable() {
        // 没装插件 / 拉空 / 拉失败：退回老口径，邻居在线照旧算有线，端口留空
        val hosts = WiredCandidates.pick(
            online = neighbors,
            leaseMacs = leases,
            selfMacs = setOf(selfMac),
            wifiMacs = emptySet(),
            fdb = emptyMap(),
            fdbAuthoritative = false,
        )
        val macs = hosts.map { it.mac }.toSet()
        assertTrue(macs.contains(wiredMac))
        assertTrue(macs.contains(lostWifiMac))
        assertTrue(macs.contains(leaseOnlyMac))
        assertTrue(hosts.all { it.port.isBlank() })
        assertFalse(hosts.first { it.mac == leaseOnlyMac }.online)
    }

    @Test
    fun selfAndCurrentWifiMacsAreNeverWired() {
        val hosts = WiredCandidates.pick(
            online = neighbors + (selfMac to true),
            leaseMacs = emptyList(),
            selfMacs = setOf(selfMac),
            wifiMacs = setOf(lostWifiMac),
            fdb = fdb,
            fdbAuthoritative = true,
        )
        val macs = hosts.map { it.mac }.toSet()
        assertFalse(macs.contains(selfMac))
        assertFalse(macs.contains(lostWifiMac))
        assertTrue(macs.contains(wiredMac))
    }
}
