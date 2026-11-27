package com.ax6.luyouqi.collect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** /proc/stat 解析与差分口径（对照 SSH 里 top 的 usr+sys+nic+irq+sirq 之和）。 */
class CpuNormalizerTest {

    private val snapA = """
        cpu  100 0 50 500 20 0 30 0 0 0
        cpu0 25 0 12 125 5 0 7 0 0 0
        intr 12345
    """.trimIndent()

    private val snapB = """
        cpu  180 0 70 680 30 0 40 0 0 0
        cpu0 45 0 17 170 7 0 10 0 0 0
        intr 23456
    """.trimIndent()

    @Test
    fun parsesFirstCpuLineOnly() {
        val ticks = Normalizer.cpuTicks(snapA)!!
        // 前 8 项：100+0+50+500+20+0+30+0 = 700；idle = 500 + 20(iowait)
        assertEquals(700L, ticks.total)
        assertEquals(520L, ticks.idle)
    }

    @Test
    fun computesBusyRatio() {
        val a = Normalizer.cpuTicks(snapA)!!
        val b = Normalizer.cpuTicks(snapB)!!
        // dTotal = 1000-700 = 300，dIdle = (680+30)-(500+20) = 190 → busy 110/300 ≈ 36.7%
        assertEquals(36.7, Normalizer.cpuUsage(a, b)!!, 0.05)
    }

    @Test
    fun softirqHeavyMatchesTopStyle() {
        // 模拟 top 输出 50% idle + 49% sirq：真实占用应接近 50%
        val a = Normalizer.cpuTicks("cpu  10 0 0 500 0 0 490 0\n")!!
        val b = Normalizer.cpuTicks("cpu  20 0 0 1000 0 0 980 0\n")!!
        val pct = Normalizer.cpuUsage(a, b)!!
        assertTrue("sirq 计入忙时，占用应接近 50%，实际 $pct", pct in 45.0..55.0)
    }

    @Test
    fun firstSampleHasNoUsage() {
        assertNull(Normalizer.cpuUsage(null, Normalizer.cpuTicks(snapA)!!))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(Normalizer.cpuTicks("<html>not found</html>"))
        assertNull(Normalizer.cpuTicks(""))
    }

    // ---- luci getCPUUsage 文本（出厂 ACL 唯一可用的 CPU 通道） ----

    @Test
    fun parsesLuciCpuPercent() {
        // 真机返回 "cpuusage":"3%"；也容忍空格版与小数
        assertEquals(3.0, Normalizer.cpuPercent("3%")!!, 0.001)
        assertEquals(12.5, Normalizer.cpuPercent(" 12.5 % ")!!, 0.001)
        assertEquals(0.0, Normalizer.cpuPercent("0%")!!, 0.001)
        assertEquals(100.0, Normalizer.cpuPercent("100%")!!, 0.001)
    }

    @Test
    fun rejectsBadLuciCpuText() {
        assertNull(Normalizer.cpuPercent(""))
        assertNull(Normalizer.cpuPercent("n/a"))
        assertNull(Normalizer.cpuPercent("-1%"))
        assertNull(Normalizer.cpuPercent("101%"))
        assertNull(Normalizer.cpuPercent("<html>not found</html>"))
    }
}
