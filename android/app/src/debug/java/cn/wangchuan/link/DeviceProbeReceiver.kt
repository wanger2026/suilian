package cn.wangchuan.link

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.follow.clash.core.Core
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** USB/ADB-only diagnostic hook, absent from release; shell DUMP permission required. */
class DeviceProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val mode = intent.getStringExtra("mode") ?: "status"
        val route = intent.getStringExtra("route") ?: "RULE"
        val target = intent.getStringExtra("target") ?: "baidu"
        val pending = goAsync()
        workers.execute {
            val report = JSONObject().put("mode", mode).put("route", route).put("target", target)
                .put("time", System.currentTimeMillis())
            try {
                val profile = ProfileStore(app).load()
                val cm = app.getSystemService(ConnectivityManager::class.java)
                val urls = mapOf("baidu" to "https://www.baidu.com/", "douyin" to "https://www.douyin.com/",
                    "google" to "https://www.gstatic.com/generate_204", "x" to "https://x.com/", "gmail" to "https://gmail.com/", "play" to "https://play.google.com/store",
                    "mail" to "https://mail.google.com/", "accounts" to "https://accounts.google.com/",
                    "gmail-http" to "http://gmail.com/",
                    "computer" to "http://10.144.77.1:10809/manifest.json",
                    "interop" to "http://10.144.77.3:17881/management")
                val url = urls[target] ?: error("Unknown test endpoint")
                if (mode == "history") {
                    val done=CountDownLatch(1)
                    TrafficHistory.query("0000-01-01","9999-12-31","","","","detail",0){data->report.put("history",data);done.countDown()}
                    if(!done.await(8,TimeUnit.SECONDS))report.put("error","history timeout")
                } else if (mode == "capture-start") {
                    DebugRouteCapture.start()
                    report.put("capture", DebugRouteCapture.snapshot())
                } else if (mode == "capture-read") {
                    report.put("capture", DebugRouteCapture.snapshot())
                } else if (mode == "capture-stop") {
                    DebugRouteCapture.stop()
                    report.put("capture", DebugRouteCapture.snapshot())
                } else if (mode == "rotate-network") {
                    DeviceKeyRotation.run(app, intent.getStringExtra("nonce") ?: "")
                    report.put("rotated", true)
                } else if (mode == "stop") {
                    app.startService(Intent(app, LinkVpnService::class.java).setAction(LinkVpnService.STOP))
                    report.put("requested", true)
                } else if (mode == "stacks") {
                    report.put("threads", JSONArray(Thread.getAllStackTraces().entries.filter {
                        it.key.name.startsWith("pool-")
                    }.map { (thread, frames) -> JSONObject().put("name", thread.name)
                        .put("frames", JSONArray(frames.take(12).map { it.toString() })) }))
                } else if (mode == "wireless") {
                    val main = android.os.Handler(android.os.Looper.getMainLooper())
                    val done = CountDownLatch(1)
                    val ports = JSONObject()
                    main.post {
                        val scan = WirelessDebugDiscovery(app, { pairing, port -> ports.put(if (pairing) "pairPort" else "adbPort", port) }, { ports.put("status", it) })
                        scan.start()
                        main.postDelayed({ scan.close(); report.put("wireless", ports); done.countDown() }, 8000)
                    }
                    if (!done.await(10, TimeUnit.SECONDS)) report.put("error", "discovery timeout")
                } else if (mode == "status") {
                    report.put("historySync",TrafficHistory.syncDiagnostics())
                    report.put("remoteInput", JSONObject(RemoteDevelopment.inputDiagnostics))
                    report.put("version", app.packageManager.getPackageInfo(app.packageName, 0).versionName).put("defaultNetwork", cm.activeNetwork?.toString())
                    report.put("active", LinkVpnService.active).put("connecting", LinkVpnService.connecting)
                        .put("vpnStatus", LinkVpnService.status).put("apps", LinkVpnService.appRoutingStatus)
                    report.put("networks", JSONArray(cm.allNetworks.map { n ->
                        val caps = cm.getNetworkCapabilities(n)
                        val link = cm.getLinkProperties(n)
                        JSONObject().put("id", n.toString()).put("wifi", caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                            .put("cell", caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
                            .put("vpn", caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                            .put("validated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                            .put("internet", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                            .put("privateDns", if (android.os.Build.VERSION.SDK_INT >= 28) link?.isPrivateDnsActive else false)
                            .put("dns", JSONArray(link?.dnsServers?.map { it.hostAddress } ?: emptyList<String>()))
                    }))
                } else if (mode == "audit") {
                    require(target != "computer" && target != "interop")
                    report.put("audit", NetworkAudit.check(url))
                } else if (mode == "flows") {
                    val done = CountDownLatch(1)
                    Core.invokeMethod("{\"method\":\"getConnections\",\"arguments\":null}") { bytes ->
                        val root = JSONObject(bytes?.decodeToString() ?: "{}")
                        val items = (root.optJSONObject("result") ?: root).optJSONArray("connections") ?: JSONArray()
                        val rows = JSONArray()
                        for (i in 0 until minOf(items.length(), 200)) {
                            val c = items.getJSONObject(i)
                            val m = c.optJSONObject("metadata") ?: JSONObject()
                            rows.put(JSONObject().put("app", m.optString("processPath").ifBlank { m.optString("process") })
                                .put("host", m.optString("host")).put("ip", m.optString("destinationIP"))
                                .put("port", m.optString("destinationPort")).put("network", m.optString("network"))
                                .put("chains", c.optJSONArray("chains")).put("rule", c.optString("rule"))
                                .put("rulePayload", c.optString("rulePayload"))
                                .put("up", c.optLong("upload")).put("down", c.optLong("download")))
                        }
                        report.put("connections", rows)
                        done.countDown()
                    }
                    if (!done.await(5, TimeUnit.SECONDS)) report.put("error", "flow snapshot timeout")
                } else if (mode == "overlay") {
                    val done = CountDownLatch(1)
                    Core.invokeMethod("{\"method\":\"getOverlayStatus\",\"arguments\":null}") {
                        report.put("result", runCatching { JSONObject(it?.decodeToString() ?: "{}") }.getOrElse { "Invalid response" })
                        done.countDown()
                    }
                    if (!done.await(5, TimeUnit.SECONDS)) report.put("error", "overlay status timeout")
                } else if (mode == "core") {
                    require(route in listOf("RULE", "DIRECT", "LINK", "COMPUTER"))
                    val args = JSONObject().put("url", url).put("timeout", 7000).put("max-body", 0)
                    if (route != "RULE") args.put("proxy-name", route)
                    if (target == "computer") args.put("headers", JSONObject().put("Authorization", "Bearer " + requireNotNull(profile).ruleToken()))
                    val done = CountDownLatch(1)
                    Core.invokeMethod(JSONObject().put("method", "probe").put("arguments", args).toString()) {
                        val data = it?.decodeToString() ?: "null"
                        report.put("result", runCatching { JSONObject(data).apply {
                            remove("body")
                            optJSONObject("result")?.apply {
                                remove("body")
                                if (has("url")) put("url", optString("url").substringBefore('?'))
                            }
                        } }.getOrElse { "无法解析内核诊断" })
                        done.countDown()
                    }
                    if (!done.await(10, TimeUnit.SECONDS)) report.put("error", "core callback timeout")
                } else if (mode == "http") {
                    val selected = cm.allNetworks.firstOrNull { n ->
                        val caps = cm.getNetworkCapabilities(n)
                        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                            caps.hasTransport(if (route == "WIFI") NetworkCapabilities.TRANSPORT_WIFI else NetworkCapabilities.TRANSPORT_CELLULAR)
                    }
                    val client = if (route in listOf("WIFI", "CELL")) {
                        val network = requireNotNull(selected)
                        ConnectionProbe.client.newBuilder().socketFactory(network.socketFactory)
                            .dns(object : okhttp3.Dns {
                                override fun lookup(hostname: String) = network.getAllByName(hostname).toList()
                            }).build()
                    } else ConnectionProbe.client
                    val start = System.nanoTime()
                    val result = ConnectionProbe.check(url, if (target == "computer") requireNotNull(profile).ruleToken() else null,
                        timeoutMillis = 7000, transport = client)
                    report.put("ok", result.ok).put("detail", result.detail).put("ms", (System.nanoTime()-start)/1000000)
                } else error("Unknown mode")
            } catch (e: Exception) { report.put("error", e.javaClass.simpleName + ": " + e.message) }
            // No full config, tokens or response bodies are exported.
            val safe = ProfileStore(app).load()?.redactDiagnostic(report.toString()) ?: report.toString()
            File(app.filesDir, "device-probe.json").writeText(safe)
            pending.finish()
        }
    }
    companion object { private val workers = Executors.newFixedThreadPool(3) }
}
