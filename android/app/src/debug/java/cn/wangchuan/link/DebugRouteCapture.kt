package cn.wangchuan.link

import com.follow.clash.core.Core
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Opt-in USB diagnostics only. Bounded metadata, no payload, automatic expiry. */
object DebugRouteCapture {
    private val rows = ArrayDeque<JSONObject>()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private var epoch = 0L
    private var enabled = false

    @Synchronized fun start() {
        check(LinkVpnService.active) { "VPN is not active" }
        epoch++
        val ticket = epoch
        rows.clear()
        enabled = true
        Core.updateEventListener { message ->
            runCatching {
                for (event in events(message)) {
                if (event.optString("type") == "log") {
                    val data = event.optJSONObject("data") ?: continue
                    val text = data.optString("Payload", data.optString("payload"))
                    // Only connection metadata; exclude peer identity/configuration logs.
                    if (text.contains("[TCP]") || text.contains("[UDP]")) {
                        val safe = text.replace(Regex("https?://[^\\s]+"), "[url hidden]").take(1500)
                        synchronized(this) {
                            if (enabled && epoch == ticket) {
                                if (rows.size >= 1000) rows.removeFirst()
                                rows.addLast(JSONObject().put("time", System.currentTimeMillis())
                                    .put("level", data.optString("LogLevel", data.optString("type"))).put("message", safe))
                            }
                        }
                    }
                }
                }
            }
        }
        Core.invokeMethod("{\"method\":\"startLog\",\"arguments\":null}") { }
        Core.invokeMethod("{\"method\":\"updateConfig\",\"arguments\":{\"log-level\":\"info\"}}") { }
        timer.schedule({ synchronized(this) { if (epoch == ticket) stop() } }, 3, TimeUnit.MINUTES)
    }

    @Synchronized fun snapshot() = JSONObject().put("enabled", enabled).put("rows", JSONArray(rows.toList()))

    internal fun events(message: String?): List<JSONObject> {
        val root = JSONObject(message ?: "{}")
        val batch = root.optJSONArray("arguments") ?: return listOf(root)
        return (0 until batch.length()).mapNotNull { batch.optJSONObject(it) }
    }

    @Synchronized fun stop() {
        enabled = false
        epoch++
        Core.invokeMethod("{\"method\":\"stopLog\",\"arguments\":null}") { }
        Core.invokeMethod("{\"method\":\"updateConfig\",\"arguments\":{\"log-level\":\"silent\"}}") { }
        Core.updateEventListener(null)
    }
}
