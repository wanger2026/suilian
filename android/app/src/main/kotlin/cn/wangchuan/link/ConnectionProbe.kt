package cn.wangchuan.link

import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Uses the application's normal sockets, so the check traverses Android's VPN/TUN. */
object ConnectionProbe {
    data class Result(val ok: Boolean, val detail: String)
    internal val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
        .followRedirects(false).followSslRedirects(false).build()

    fun check(address: String, token: String? = null, timeoutMillis: Long = 6000,
              transport: OkHttpClient = client): Result {
        val request = Request.Builder().url(address).apply {
            if (token != null) header("Authorization", "Bearer $token")
        }.build()
        val call = transport.newCall(request)
        call.timeout().timeout(timeoutMillis, TimeUnit.MILLISECONDS)
        val result = AtomicReference(Result(false, "连接检测超时（含 DNS），请检查手机和电脑网络"))
        val done = CountDownLatch(1)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                result.set(Result(false, e.javaClass.simpleName + ": " + (e.message ?: "无详细原因")))
                done.countDown()
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { result.set(Result(it.code in 200..299, "HTTP ${it.code}")) }
                done.countDown()
            }
        })
        // DNS resolution is not covered by HttpURLConnection.connectTimeout.
        // Bound the caller even if the platform resolver cannot be interrupted.
        return try {
            if (done.await(timeoutMillis + 250, TimeUnit.MILLISECONDS)) result.get()
            else Result(false, "连接检测超时（含 DNS），请检查手机和电脑网络")
        } finally { call.cancel() }
    }
}
