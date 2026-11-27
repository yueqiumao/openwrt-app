package com.ax6.luyouqi.collect

import com.ax6.luyouqi.domain.WiredHost

/**
 * 有线候选的最终裁决（纯函数，方便单测）。
 *
 * 输入三路证据：
 *  - 邻居表：谁现在在线（WiFi 客户端在 br-lan 上也有 ARP 条目，所以它不能证明"有线"）；
 *  - 租约：谁存在过（只补存在，不参与在线判定）；
 *  - Vantage FDB：谁真的挂在一个有线端口上（只有装了插件才有）。
 *
 * `fdbAuthoritative` 为 true 时，FDB 是唯一的有线凭据：邻居表里在线但 FDB 没有的 MAC
 * 一律不判有线——刚断开的无线设备 ARP 条目还会残留几十秒，正是靠这条堵住误判。
 * FDB 不可用（没装插件 / 拉空 / 拉失败）时退回老口径：邻居在线即如实反映，
 * 端口字段留空由 UI 自己隐藏。
 */
object WiredCandidates {

    fun pick(
        online: Map<String, Boolean>,
        leaseMacs: List<String>,
        selfMacs: Set<String>,
        wifiMacs: Set<String>,
        fdb: Map<String, WiredHost>,
        fdbAuthoritative: Boolean,
    ): List<WiredHost> {
        val exists = LinkedHashMap<String, Boolean>(online)
        // 租约只补"存在"，已经有邻居记录的以邻居为准
        leaseMacs.forEach { raw ->
            val mac = raw.lowercase().replace('-', ':')
            if (mac.isNotEmpty()) exists.putIfAbsent(mac, false)
        }

        val out = LinkedHashMap<String, WiredHost>()
        exists.forEach { (mac, up) ->
            if (mac.isEmpty() || selfMacs.contains(mac) || wifiMacs.contains(mac)) return@forEach
            val known = fdb[mac]
            // FDB 有权威却不认识它：不算有线（多半是刚掉线的无线客户端）
            if (fdbAuthoritative && known == null) return@forEach
            out[mac] = known?.copy(online = up || known.online) ?: WiredHost(mac = mac, online = up)
        }

        // FDB 里独有、邻居表还没出现的条目也保留：端口先记下，在线与否交给 seen
        if (fdbAuthoritative) {
            fdb.forEach { (mac, host) ->
                if (mac.isEmpty() || selfMacs.contains(mac) || wifiMacs.contains(mac)) return@forEach
                out.putIfAbsent(mac, host)
            }
        }
        return out.values.toList()
    }
}
