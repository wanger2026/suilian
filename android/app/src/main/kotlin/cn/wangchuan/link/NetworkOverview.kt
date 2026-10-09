package cn.wangchuan.link

import com.follow.clash.core.Core
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** On-screen snapshots; bypassed apps never enter the core and are not counted. */
object NetworkOverview {
    data class Connection(val app: String, val uid: Int, val host: String, val route: String,
                          val rule: String, val upload: Long, val download: Long,
                          val id: String = "", val protocol: String = "")
    data class Snapshot(val up: Long = 0, val down: Long = 0, val connections: List<Connection> = emptyList(),
                        val time: Long = 0, val error: Boolean = false)
    data class Health(val direct: Boolean? = null, val computer: Boolean? = null,
                      val exit: Boolean? = null, val checkedAt: Long = 0)
    @Volatile var snapshot = Snapshot(); private set
    @Volatile var health = Health()
    private val pending = AtomicBoolean(false)
    @Volatile private var requestedAt = 0L
    @Volatile private var epoch = 0L
    fun reset() { epoch++; snapshot = Snapshot(); health = Health(); requestedAt=0; pending.set(false) }
    fun refresh() {
        if (!LinkVpnService.active) return
        val now = System.currentTimeMillis()
        if(now>=requestedAt && now-requestedAt<2500)return
        if (pending.get() && now - requestedAt > 6000) pending.set(false)
        if (!pending.compareAndSet(false, true)) return
        requestedAt = now
        val ticket = epoch
        try {
            Core.invokeMethod("{\"method\":\"getConnections\",\"arguments\":null}") { bytes ->
                try {
                    val root = JSONObject(bytes?.decodeToString() ?: "{}")
                    val result = root.optJSONObject("result") ?: root
                    if (!result.has("connections")) error("No snapshot")
                    val items = result.optJSONArray("connections")
                    val rows = (0 until minOf(items?.length() ?: 0, 500)).map { i ->
                        val c = items!!.getJSONObject(i)
                        val m = c.optJSONObject("metadata") ?: JSONObject()
                        val chains = c.optJSONArray("chains")
                        val chain = (0 until (chains?.length() ?: 0)).map { chains!!.optString(it) }
                        val route = when { "COMPUTER" in chain -> "电脑出口"; "LINK" in chain -> "电脑通道";
                            "DIRECT" in chain -> "手机直连"; "REJECT" in chain -> "已阻止"; else -> "规则分流" }
                        Connection(m.optString("processPath").ifBlank { m.optString("process") }.take(180),
                            m.optInt("uid", -1), m.optString("host").ifBlank { m.optString("destinationIP") }.take(180),
                            route, c.optString("rule").take(60), c.optLong("upload").coerceAtLeast(0),
                            c.optLong("download").coerceAtLeast(0), c.optString("id"),m.optString("network").uppercase())
                    }
                    if (ticket == epoch && LinkVpnService.active) {
                        snapshot = Snapshot(result.optLong("uploadTotal"),result.optLong("downloadTotal"), rows, System.currentTimeMillis())
                        TrafficHistory.record(rows)
                    }
                } catch (_: Exception) { if (ticket == epoch) snapshot = snapshot.copy(error = true) }
                finally { if (ticket == epoch) pending.set(false) }
            }
        } catch (_: Throwable) { pending.set(false) }
    }
    fun bytes(value: Long): String = when {
        value >= 1073741824L -> "%.2f GB".format(java.util.Locale.ROOT, value / 1073741824.0)
        value >= 1048576L -> "%.1f MB".format(java.util.Locale.ROOT, value / 1048576.0)
        value >= 1024 -> "%.1f KB".format(java.util.Locale.ROOT, value / 1024.0)
        else -> "$value B"
    }
}
