package cn.wangchuan.link

import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DevelopmentBridge(
    private val profile: Profile, private val adbPort: Int, private val pairPort: Int,
    private var pairCode: String, private val status: (String) -> Unit, private val ended: () -> Unit
) {
    private val alive = AtomicBoolean(true)
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS).pingInterval(5, TimeUnit.SECONDS).build()
    private var control: WebSocket? = null
    @Volatile private var controlReady = false
    private class Pipe(val socket: Socket = Socket(), @Volatile var web: WebSocket? = null) {
        fun close() { runCatching { socket.close() }; web?.cancel() }
    }
    private val pipes = ConcurrentHashMap<String, Pipe>()
    private fun request(path: String) = Request.Builder().url("ws://${profile.computerIp}:10809/development/$path")
        .header("Authorization", "Bearer " + profile.developmentToken()).build()
    fun start() {
        control = client.newWebSocket(request("control"), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (!alive.get() || !RemoteDevelopment.lease.allowed()) { ws.cancel(); ended(); return }
                ws.send(JSONObject().put("type", "hello").put("remoteReady", true)
                    .put("adbPort", adbPort).put("pairPort", pairPort).put("pairCode", pairCode).toString())
                pairCode = ""
                controlReady = true
            }
            override fun onMessage(ws: WebSocket, text: String) {
                if (!alive.get() || !RemoteDevelopment.lease.allowed()) { close(); ended(); return }
                try {
                    require(text.length <= 4096)
                    val message = JSONObject(text)
                    when (message.getString("type")) {
                        "status" -> status(message.getString("message").take(240))
                        "open" -> openPipe(message.getString("id"), message.getString("kind"))
                        else -> error("protocol")
                    }
                } catch (_: Exception) { close(); ended() }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { if (alive.get()) { status("调试通道已断开，请重新启用"); close(); ended() } }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { if (alive.get()) { close(); ended() } }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) { close(); ended() }
        })
    }
    fun heartbeat() {
        if (!RemoteDevelopment.lease.allowed()) { close(); ended(); return }
        if (controlReady) control?.send("{\"type\":\"heartbeat\",\"remoteReady\":true}")
    }
    private fun openPipe(id: String, kind: String) {
        require(id.matches(Regex("[0-9a-f]{48}")))
        val port = when (kind) { "adb" -> adbPort; "pair" -> pairPort; else -> error("kind") }
        require(port in 1024..65535 && pipes.size < 8)
        val pipe = Pipe()
        if (pipes.putIfAbsent(id, pipe) != null) error("duplicate")
        Thread {
            try {
                if (!alive.get() || !RemoteDevelopment.lease.allowed()) error("closed")
                pipe.socket.connect(InetSocketAddress("127.0.0.1", port), 4000)
                pipe.socket.tcpNoDelay = true
                if (!alive.get() || !RemoteDevelopment.lease.allowed()) error("closed")
                pipe.web = client.newWebSocket(request("data?id=$id"), object : WebSocketListener() {
                    override fun onOpen(ws: WebSocket, response: Response) {
                        if (!alive.get()) { pipe.close(); return }
                        Thread {
                            try {
                                val bytes = ByteArray(65536)
                                val input = pipe.socket.getInputStream()
                                while (alive.get() && RemoteDevelopment.lease.allowed()) {
                                    val length = input.read(bytes)
                                    if (length < 0) break
                                    check(ws.queueSize() <= 1024*1024 && ws.send(bytes.toByteString(0, length)))
                                }
                            } catch (_: Exception) { }
                            finally { pipes.remove(id); pipe.close() }
                        }.start()
                    }
                    override fun onMessage(ws: WebSocket, bytes: ByteString) {
                        if (!alive.get() || !RemoteDevelopment.lease.allowed() || bytes.size > 65536) { pipe.close(); return }
                        try { pipe.socket.getOutputStream().write(bytes.toByteArray()) }
                        catch (_: Exception) { pipe.close() }
                    }
                    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { pipes.remove(id); pipe.close() }
                    override fun onClosing(ws: WebSocket, code: Int, reason: String) { pipes.remove(id); pipe.close() }
                })
                if (!alive.get()) pipe.close()
            } catch (_: Exception) { pipes.remove(id); pipe.close(); status("手机无线调试端口不可达，请核对连接/配对端口") }
        }.start()
    }
    fun close() {
        if (!alive.compareAndSet(true, false)) return
        control?.cancel()
        pairCode = ""
        pipes.values.forEach { it.close() }; pipes.clear()
        client.dispatcher.cancelAll(); client.connectionPool.evictAll()
    }
}
