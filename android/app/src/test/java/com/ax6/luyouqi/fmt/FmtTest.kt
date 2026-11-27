package com.ax6.luyouqi.fmt

import org.junit.Assert.assertEquals
import org.junit.Test

/** 与 public/js/format.js 的期望输出逐条对齐，JS 侧算过的值直接写死在这里。 */
class FmtTest {

    @Test
    fun bytes_matchesJs() {
        assertEquals("0 B", Fmt.bytes(0))
        assertEquals("0 B", Fmt.bytes(-5))
        assertEquals("512 B", Fmt.bytes(512))
        assertEquals("1.0 KB", Fmt.bytes(1024))
        assertEquals("1.5 KB", Fmt.bytes(1536))
        assertEquals("100 KB", Fmt.bytes(100 * 1024))
        assertEquals("1.0 MB", Fmt.bytes(1024L * 1024))
        assertEquals("1.9 MB", Fmt.bytes(2000000))
    }

    @Test
    fun rate_matchesJs() {
        assertEquals("0 B/s", Fmt.rate(0))
        assertEquals("1 KB/s", Fmt.rate(1500))
        assertEquals("2.0 MB/s", Fmt.rate(2L * 1024 * 1024))
        assertEquals("0 B/s", Fmt.rate(-1))
    }

    @Test
    fun duration_usesChineseUnitsWithMinutes() {
        assertEquals("0分", Fmt.durationCN(0))
        assertEquals("0分", Fmt.durationCN(59))
        assertEquals("1分", Fmt.durationCN(60))
        assertEquals("1时 1分", Fmt.durationCN(3661))
        assertEquals("1天 1时 1分", Fmt.durationCN(90061))
        assertEquals("2天 0时 5分", Fmt.durationCN(2 * 86400L + 5 * 60))
        // 满 30 天才折月，29 天仍走天
        assertEquals("29天 23时 59分", Fmt.durationCN(30 * 86400L - 60))
        assertEquals("1月 0天 0时 0分", Fmt.durationCN(30 * 86400L))
        assertEquals("1月 5天 3时 9分", Fmt.durationCN(35 * 86400L + 3 * 3600 + 9 * 60))
        assertEquals("2月 1天 1时 0分", Fmt.durationCN(61 * 86400L + 3600))
    }

    @Test
    fun num_trimsTrailingZeroAndHandlesNull() {
        assertEquals("52.1", Fmt.num(52.135, 1))
        assertEquals("52", Fmt.num(52.0, 1))
        assertEquals("100", Fmt.num(100.0, 0))
        assertEquals("—", Fmt.num(null))
        assertEquals("—", Fmt.num(Double.NaN))
        assertEquals("0.03", Fmt.num(0.032, 2))
    }

    @Test
    fun labels_and_linkSpeed() {
        assertEquals("PPPoE", Fmt.protoLabel("pppoe"))
        assertEquals("DHCP", Fmt.protoLabel("dhcp"))
        assertEquals("静态", Fmt.protoLabel("static"))
        assertEquals("—", Fmt.protoLabel(null))

        assertEquals("1G", Fmt.portSpeed(1000))
        assertEquals("100M", Fmt.portSpeed(100))
        assertEquals("—", Fmt.portSpeed(0))
        assertEquals("—", Fmt.portSpeed(null))

        assertEquals("2.4 GHz", Fmt.bandLabel("2.4G"))
        assertEquals("5 GHz", Fmt.bandLabel("5G"))
        assertEquals("6 GHz", Fmt.bandLabel("6G"))
        assertEquals("", Fmt.bandLabel(null))
    }

    @Test
    fun signalPercentBoundaries() {
        assertEquals(0, Fmt.signalPercent(null))
        assertEquals(100, Fmt.signalPercent(-50))
        assertEquals(50, Fmt.signalPercent(-75))
        assertEquals(0, Fmt.signalPercent(-110))
    }

    @Test
    fun durationHmsKeepsSeconds() {
        assertEquals("5h 26m 48s", Fmt.durationHMS(5 * 3600 + 26 * 60 + 48))
        assertEquals("0h 0m 0s", Fmt.durationHMS(-5))
        // 满 24h 折天
        assertEquals("23h 59m 59s", Fmt.durationHMS(86400 - 1))
        assertEquals("1d 2h 9m 35s", Fmt.durationHMS(86400L + 2 * 3600 + 9 * 60 + 35))
        // 满 30 天才折月
        assertEquals("29d 23h 59m 59s", Fmt.durationHMS(30 * 86400L - 1))
        assertEquals("1mo 5d 3h 9m 35s", Fmt.durationHMS(35 * 86400L + 3 * 3600 + 9 * 60 + 35))
    }

    @Test
    fun linkSpeedFromRxRate() {
        // 真机 phy0-ap0：rx.rate=1200900 kbps → 1.2 Gbps
        assertEquals("1.2 Gbps", Fmt.linkSpeedText(1_200_900))
        assertEquals("867 Mbps", Fmt.linkSpeedText(866_700))
        assertEquals("—", Fmt.linkSpeedText(null))
    }

    @Test
    fun portSpeedText() {
        assertEquals("1 Gbps", Fmt.portSpeedText(1000))
        assertEquals("100 Mbps", Fmt.portSpeedText(100))
        assertEquals("—", Fmt.portSpeedText(0))
    }
}
