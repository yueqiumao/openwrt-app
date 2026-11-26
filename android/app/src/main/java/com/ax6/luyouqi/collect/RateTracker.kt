package com.ax6.luyouqi.collect

import kotlin.math.max

/**
 * 速率差分：网页版用 `Math.max(0, cur - prev) / dt`，且 dt 小于 0.2 秒时不出值。
 * 计数器回绕或设备重启会让 cur < prev，这里一律按 0 处理，不产生负速率。
 */
object RateTracker {

    fun rate(current: Long, previous: Long, dtSeconds: Double): Long {
        if (dtSeconds <= 0.2) return 0L
        return max(0L, current - previous).toDouble().div(dtSeconds).toLong()
    }

    /** 返回本次速率并记录新基线。 */
    class Tracker {
        private var at: Long = 0
        private val counters = HashMap<String, Pair<Long, Long>>()

        fun update(now: Long, current: Map<String, Pair<Long, Long>>): Map<String, Pair<Long, Long>> {
            val dt = (now - at) / 1000.0
            val result = HashMap<String, Pair<Long, Long>>()
            if (at != 0L && dt > 0.2) {
                current.forEach { (key, cur) ->
                    val prev = counters[key] ?: return@forEach
                    result[key] = rate(cur.first, prev.first, dt) to rate(cur.second, prev.second, dt)
                }
            }
            counters.clear()
            counters.putAll(current)
            at = now
            return result
        }

        fun reset() {
            at = 0
            counters.clear()
        }
    }
}
