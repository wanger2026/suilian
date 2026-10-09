package cn.wangchuan.link

import org.json.JSONObject

object OverlayRouteState {
    fun directPeer(raw: String, computerIp: String): Boolean? {
        val root = JSONObject(raw)
        val data = root.optJSONObject("result") ?: root
        val routes = data.optJSONArray("routes") ?: return null
        val octets = computerIp.split('.').map { it.toIntOrNull() ?: return null }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return null
        val address = octets.fold(0L) { n, octet -> (n shl 8) or octet.toLong() }
        for (i in 0 until routes.length()) {
            val row = routes.optJSONObject(i) ?: continue
            val ip = row.optJSONObject("ipv4")?.optJSONObject("address")?.optLong("addr", -1)
            if (ip == address) return row.optInt("cost", -1) == 1 && row.optLong("peerId", -1) == row.optLong("nextHop", -2)
        }
        return false
    }
}

/** Rebuild only a lost P2P transport, with grace and capped retry backoff. */
class DirectLinkRecovery(private val clock: () -> Long) {
    private var failedSince: Long? = null
    private var nextAttempt = 0L
    private var delay = 60000L
    fun shouldRecover(computerOk: Boolean, directOk: Boolean, directPeer: Boolean?): Boolean {
        val now = clock()
        if (computerOk) { reset(); return false }
        if (!directOk || directPeer != false) { failedSince = null; return false }
        val since = failedSince ?: now.also { failedSince = it }
        if (now - since < 30000 || now < nextAttempt) return false
        nextAttempt = now + delay
        delay = (delay * 2).coerceAtMost(600000L)
        failedSince = now
        return true
    }
    fun reset() { failedSince = null; nextAttempt = 0; delay = 60000 }
}
