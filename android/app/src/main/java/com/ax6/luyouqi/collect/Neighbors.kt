package com.ax6.luyouqi.collect

/**
 * `ip -4 neigh show` 的解析。
 *
 * 默认固件里，这是唯一能回答"局域网内谁在线"的文本来源（FDB 只有 netlink 和 bridge 命令，
 * 都被 ACL 挡着）。行格式（iproute2 6.x）：
 *
 *   192.168.99.142 dev br-lan lladdr d4:50:ee:b2:b4:92 ref 1 used 0/0/0 probes 1 REACHABLE
 *   192.168.1.3    dev wan    used 0/0/0 probes 6 FAILED
 *
 * 状态词固定在行尾，`lladdr` 可能缺失（FAILED / INCOMPLETE 的行没有 MAC）。
 * 解析刻意宽松：认不出的行直接丢，宁可少一台也不误判。
 */
object Neighbors {

    enum class State { PRESENT, UNCONFIRMED, GONE }

    data class Entry(val ip: String, val dev: String, val mac: String, val state: State)

    private val whitespace = Regex("\\s+")

    fun parse(text: String): List<Entry> {
        val out = ArrayList<Entry>()
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val words = trimmed.split(whitespace)
            if (words.size < 3) return@forEach

            val devIndex = words.indexOf("dev")
            if (devIndex < 0 || devIndex + 1 >= words.size) return@forEach
            val lladdrIndex = words.indexOf("lladdr")
            val mac = if (lladdrIndex >= 0 && lladdrIndex + 1 < words.size) {
                words[lladdrIndex + 1].lowercase().replace('-', ':')
            } else {
                ""
            }
            out += Entry(
                ip = words[0],
                dev = words[devIndex + 1],
                mac = mac,
                state = stateOf(words.last()),
            )
        }
        return out
    }

    /** REACHABLE / DELAY / PROBE / PERMANENT → 在线；STALE → 存在但未确认；FAILED / INCOMPLETE → 不在线。 */
    fun stateOf(word: String): State = when (word.uppercase()) {
        "REACHABLE", "DELAY", "PROBE", "PERMANENT", "NOARP" -> State.PRESENT
        "FAILED", "INCOMPLETE" -> State.GONE
        else -> State.UNCONFIRMED
    }

    /** 只有 br-lan 及其子接口（br-lan.20 等）上的邻居属于局域网终端，wan 侧的上游设备必须排除。 */
    fun isLanDev(dev: String): Boolean = dev == "br-lan" || dev.startsWith("br-lan.")
}
