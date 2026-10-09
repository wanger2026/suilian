package cn.wangchuan.link

import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Fixed public endpoints, no cookies. Logs metadata only, never page/account data. */
internal object NetworkAudit {
    fun check(initialUrl: String): JSONObject {
        val report = JSONObject()
        val hops = JSONArray()
        report.put("hops", hops)
        val start = android.os.SystemClock.elapsedRealtime()
        val deadline = start + 18000
        var url = initialUrl
        try {
            for (i in 0 until 10) {
                val request = Request.Builder().url(url).header("User-Agent", "SuiLian-Connection-Test/1.0").build()
                val hop = JSONObject().put("host", request.url.host).put("path", request.url.encodedPath)
                hops.put(hop)
                // Record the app's normal resolver result, including VPN fake addresses.
                hop.put("addresses", JSONArray(InetAddress.getAllByName(request.url.host).map { it.hostAddress }))
                val call = ConnectionProbe.client.newCall(request)
                call.timeout().timeout((deadline - android.os.SystemClock.elapsedRealtime()).coerceIn(1, 12000), TimeUnit.MILLISECONDS)
                call.execute().use { response ->
                    hop.put("status", response.code).put("protocol", response.protocol.toString())
                        .put("tls", response.handshake?.tlsVersion?.javaName)
                    val next = response.header("Location")?.let { request.url.resolve(it) }
                    if (response.code in listOf(301, 302, 303, 307, 308) && next != null) {
                        url = next.toString()
                    } else {
                        val source = response.body?.source()
                        var size = 0L
                        val buffer = okio.Buffer()
                        while (source != null && size < 65536) {
                            val count = source.read(buffer, minOf(8192, 65536 - size))
                            if (count < 0) break
                            size += count
                            buffer.clear()
                        }
                        hop.put("bytesRead", size)
                        report.put("ok", response.isSuccessful)
                        return report.put("ms", android.os.SystemClock.elapsedRealtime() - start)
                    }
                }
                check(android.os.SystemClock.elapsedRealtime() < deadline) { "Audit deadline exceeded" }
            }
            error("Redirect limit")
        } catch (e: Exception) {
            report.put("ok", false).put("error", e.javaClass.simpleName)
        }
        return report.put("ms", android.os.SystemClock.elapsedRealtime() - start)
    }
}
