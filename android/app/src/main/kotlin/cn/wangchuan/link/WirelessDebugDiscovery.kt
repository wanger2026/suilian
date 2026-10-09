package cn.wangchuan.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Bounded discovery of this phone's ADB services, never another LAN device. */
class WirelessDebugDiscovery(context: Context, private val found: (Boolean, Int) -> Unit,
    private val status: (String) -> Unit) : AutoCloseable {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val executor = java.util.concurrent.Executor { handler.post(it) }
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val queue = java.util.ArrayDeque<Pair<NsdServiceInfo, Boolean>>()
    private var resolving = false
    private var closed = false
    private val seen = mutableSetOf<String>()

    fun start() {
        val wifi = cm.allNetworks.firstOrNull {
            val c = cm.getNetworkCapabilities(it)
            c?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        if (wifi == null) { status("请先让手机连接 Wi-Fi，再开启无线调试。"); return }
        status("正在识别本机端口，无需抄写 IP 地址…")
        for (pairing in listOf(false, true)) {
            val type = if (pairing) "_adb-tls-pairing._tcp." else "_adb-tls-connect._tcp."
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(t: String) = Unit
                override fun onDiscoveryStopped(t: String) = Unit
                override fun onStartDiscoveryFailed(t: String, e: Int) { handler.post {
                    listeners.remove(this)
                    if (!closed) status("系统未允许自动发现（$e），可使用下方手动端口。")
                } }
                override fun onStopDiscoveryFailed(t: String, e: Int) = Unit
                override fun onServiceLost(s: NsdServiceInfo) = Unit
                override fun onServiceFound(s: NsdServiceInfo) { handler.post {
                    if (!closed && seen.add(s.serviceName + type)) { queue.add(s to pairing); resolveNext() }
                } }
            }
            listeners.add(listener)
            runCatching {
                if (Build.VERSION.SDK_INT >= 33) nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, wifi, executor, listener)
                else nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            }.onFailure { listeners.remove(listener); status("自动发现暂不可用，可手动填写端口。") }
        }
        // Allows returning from system authorization/pairing, but never scans
        // indefinitely as part of the always-on VPN.
        handler.postDelayed({ close() }, 90_000)
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (closed || resolving || queue.isEmpty()) return
        resolving = true
        val (service, pairing) = queue.removeFirst()
        val callback = object : NsdManager.ResolveListener {
            override fun onResolveFailed(s: NsdServiceInfo, e: Int) { handler.post { resolving = false; resolveNext() } }
            override fun onServiceResolved(s: NsdServiceInfo) { handler.post {
                resolving = false
                if (!closed) {
                    val addresses = if (Build.VERSION.SDK_INT >= 34) s.hostAddresses else listOfNotNull(s.host)
                    val local = cm.allNetworks.flatMap { cm.getLinkProperties(it)?.linkAddresses.orEmpty() }.map { it.address }.toSet()
                    if (s.port in 1024..65535 && addresses.any { it in local || it.isLoopbackAddress }) found(pairing, s.port)
                    resolveNext()
                }
            } }
        }
        runCatching { nsd.resolveService(service, callback) }.onFailure { resolving = false; resolveNext() }
    }

    override fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacksAndMessages(null)
        listeners.toList().forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        listeners.clear(); queue.clear()
    }
}
