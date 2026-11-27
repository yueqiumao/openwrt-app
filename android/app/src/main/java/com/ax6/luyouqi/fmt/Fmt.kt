package com.ax6.luyouqi.fmt

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 与网页版 public/js/format.js 逐函数对齐。
 * 单位、取整位数、文案都不能"看起来差不多"，否则同屏对比会露馅。
 */
object Fmt {

    private val BYTE_UNITS = listOf("B", "KB", "MB", "GB", "TB")

    fun bytes(bytes: Long, digits: Int = 1): String {
        val n = bytes.toDouble()
        if (!n.isFinite() || n <= 0) return "0 B"
        var value = n
        var unit = 0
        while (value >= 1024 && unit < BYTE_UNITS.size - 1) {
            value /= 1024
            unit += 1
        }
        val fixed = if (value >= 100 || unit == 0) 0 else digits
        return "${fixed(value, fixed)} ${BYTE_UNITS[unit]}"
    }

    fun rate(bytesPerSec: Long): String {
        val n = bytesPerSec.toDouble()
        if (!n.isFinite() || n < 1) return "0 B/s"
        return "${bytes(bytesPerSec, if (n > 1024 * 1024) 1 else 0)}/s"
    }

    /** 汉语单位，始终带分钟：2天 0时 5分 / 23时 57分 / 57分；满 30 天起按月（30 天/月）：1月 5天 3时 9分 */
    fun durationCN(seconds: Long): String {
        val n = seconds.coerceAtLeast(0)
        val mo = n / (86400 * 30)
        val d = (n % (86400 * 30)) / 86400
        val h = (n % 86400) / 3600
        val m = (n % 3600) / 60
        return when {
            mo > 0 -> "${mo}月 ${d}天 ${h}时 ${m}分"
            d > 0 -> "${d}天 ${h}时 ${m}分"
            h > 0 -> "${h}时 ${m}分"
            else -> "${m}分"
        }
    }

    /** 详情页用的宽松格式：1 天 2 时 3 分 / 2 时 3 分 / 3 分 / 12 秒 */
    fun duration(seconds: Long): String {
        val n = seconds.coerceAtLeast(0)
        val d = n / 86400
        val h = (n % 86400) / 3600
        val m = (n % 3600) / 60
        return when {
            d > 0 -> "$d 天 $h 时 $m 分"
            h > 0 -> "$h 时 $m 分"
            m > 0 -> "$m 分"
            else -> "$n 秒"
        }
    }

    /** 数值格式化：非有限值显示 —，整数值去掉小数点（52.0 → 52）。 */
    fun num(value: Double?, digits: Int = 1): String {
        if (value == null || !value.isFinite()) return "—"
        val text = fixed(value, digits)
        return text.removeSuffix(".0")
    }

    fun signalPercent(dbm: Int?): Int {
        if (dbm == null) return 0
        return ((dbm + 100) * 2).coerceIn(0, 100)
    }


    /** iwinfo 多数字段是 kbps，个别固件直接给 Mbps。 */
    fun linkSpeedMbps(bitrate: Long?): Int? {
        if (bitrate == null || bitrate <= 0) return null
        val mbps = if (bitrate > 10000) bitrate / 1000.0 else bitrate.toDouble()
        if (mbps < 1) return null
        return mbps.roundToInt()
    }

    fun bandLabel(band: String?): String {
        if (band.isNullOrBlank()) return ""
        return when {
            Regex("6", RegexOption.IGNORE_CASE).containsMatchIn(band) -> "6 GHz"
            Regex("5", RegexOption.IGNORE_CASE).containsMatchIn(band) -> "5 GHz"
            Regex("2", RegexOption.IGNORE_CASE).containsMatchIn(band) -> "2.4 GHz"
            else -> band
        }
    }

    fun protoLabel(proto: String?): String = when (proto) {
        "pppoe" -> "PPPoE"
        "dhcp" -> "DHCP"
        "static" -> "静态"
        "none" -> "未配置"
        "dhcpv6" -> "DHCPv6 客户端"
        "pppoe-wan" -> "PPPoE"
        else -> proto?.uppercase() ?: "—"
    }

    /** 协商速率：1000 → 1G，100 → 100M */
    fun portSpeed(speed: Int?): String {
        if (speed == null || speed <= 0) return "—"
        return if (speed >= 1000) "${speed / 1000}G" else "${speed}M"
    }

    /** JS toFixed 的等价实现，避免 JVM 默认的 RoundingMode.HALF_UP 与 JS 有细微差异。 */
    private fun fixed(value: Double, digits: Int): String {
        if (abs(value) >= 1e21) return value.toLong().toString()
        val factor = Math.pow(10.0, digits.toDouble())
        val scaled = Math.round(value * factor).toDouble() / factor
        return if (digits == 0) {
            scaled.toLong().toString()
        } else {
            String.format("%.${digits}f", scaled)
        }
    }

    /** 头像文字：中文取首字，英文取前两个词首字母，IP/MAC 一律给 ?（与 format.js 一致）。 */
    fun initials(name: String?): String {
        val text = name.orEmpty().trim()
        if (text.isEmpty()) return "?"
        if (Regex("^[0-9a-f]{1,4}:|^\\d{1,3}\\.\\d{1,3}\\.", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "?"
        if (Regex("[\\u4e00-\\u9fa5]").containsMatchIn(text)) return text.take(1)
        val parts = text.split(Regex("[\\s\\-_]+")).filter { it.isNotEmpty() }
        if (parts.size >= 2) {
            return if (parts[1].firstOrNull()?.isDigit() == true) {
                parts[0].take(2).uppercase()
            } else {
                (parts[0].take(1) + parts[1].take(1)).uppercase()
            }
        }
        return text.take(2).uppercase()
    }

    /** 与 format.js 的同一套哈希，决定头像配色，保证同一台设备颜色稳定。 */
    fun avatarIndex(seed: String?): Int {
        val text = seed.orEmpty()
        var hash = 0
        text.forEach { ch -> hash = (hash * 31 + ch.code) % 9973 }
        return hash % 6
    }

    /** 运行时间小字格式：5h 35m 48s / 1d 2h 9m 35s / 1mo 5d 3h 9m 35s（满 24h 折天，满 30 天折月，秒必须出现） */
    fun durationHMS(seconds: Long): String {
        val n = seconds.coerceAtLeast(0)
        val mo = n / (86400 * 30)
        val d = (n % (86400 * 30)) / 86400
        val h = (n % 86400) / 3600
        val m = (n % 3600) / 60
        val s = n % 60
        return when {
            mo > 0 -> "${mo}mo ${d}d ${h}h ${m}m ${s}s"
            d > 0 -> "${d}d ${h}h ${m}m ${s}s"
            else -> "${h}h ${m}m ${s}s"
        }
    }

    /** 协商速率：1.2 Gbps / 866 Mbps，空值 —。 */
    fun linkSpeedText(bitrateKbps: Long?): String {
        val mbps = linkSpeedMbps(bitrateKbps) ?: return "—"
        return if (mbps >= 1000) "${num(mbps / 1000.0, 1)} Gbps" else "$mbps Mbps"
    }

    /** 端口协商速率：1000 → 1 Gbps。 */
    fun portSpeedText(speed: Int?): String {
        if (speed == null || speed <= 0) return "—"
        return if (speed >= 1000) "${num(speed / 1000.0, 1)} Gbps" else "$speed Mbps"
    }
}
