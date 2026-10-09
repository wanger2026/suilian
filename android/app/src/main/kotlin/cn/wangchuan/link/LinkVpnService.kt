package cn.wangchuan.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.LinkProperties
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.Looper
import com.follow.clash.core.Core
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

class LinkVpnService : VpnService() {
    private val serial = Executors.newSingleThreadExecutor()
    private val probes = Executors.newSingleThreadExecutor()
    private val probing = AtomicBoolean(false)
    private val generation = AtomicLong()
    @Volatile private var running = false
    @Volatile private var starting = false
    private var tunDescriptor: ParcelFileDescriptor? = null
    @Volatile private var underlying: Network? = null
    private var physicalKey = ""
    private val mainHandler = Handler(Looper.getMainLooper())
    private val healthCheck = Runnable { if (running) checkConnectivity() }
    private val linkRecovery = DirectLinkRecovery { android.os.SystemClock.elapsedRealtime() }
    private var registered = false
    private val networks = linkedSetOf<Network>()
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val preferences by lazy { getSharedPreferences("service", MODE_PRIVATE) }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleNetworkUpdate(network, true)
        override fun onLost(network: Network) = scheduleNetworkUpdate(network, false)
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = scheduleNetworkUpdate(network, true)
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = scheduleNetworkUpdate(network, true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CHECK) {
            if (running) { checkConnectivity(); return START_STICKY }
            status = "请先连接，再检测实际连通"
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == STOP) {
            preferences.edit().putBoolean("wanted", false).apply()
            generation.incrementAndGet()
            serial.execute { shutdown(); status = "已断开"; stopSelf(startId) }
            return START_NOT_STICKY
        }
        if (intent == null && !preferences.getBoolean("wanted", false)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (running || starting) return START_STICKY
        preferences.edit().putBoolean("wanted", true).apply()
        starting = true
        connecting = true
        NetworkOverview.reset()
        publish("正在启动分流")
        val ticket = generation.incrementAndGet()
        mainHandler.postDelayed({
            if (starting && ticket == generation.get() && !serial.isShutdown) {
                generation.incrementAndGet()
                serial.execute { fail("初始化超时，请稍后重新连接") }
            }
        }, 45000)
        serial.execute {
            if (!running && ticket == generation.get()) startConnection(ticket)
        }
        return START_STICKY
    }

    private fun startConnection(ticket: Long) {
        var currentProfile: Profile? = null
        var stage = "读取配对信息"
        try {
            val profile = ProfileStore(this).load() ?: error("NO_PROFILE")
            currentProfile = profile
            NetworkDiagnostics.record(this, "正在启动", "尚未完成实际连通检测", profile)
            stage = "安装内置规则"
            val home = File(filesDir, "network-runtime").apply { mkdirs() }
            val bundled = RuleStore(this).install(home)
            val physicalNetwork = preferredNetwork(connectivity.allNetworks.toList())
            val physicalAddresses = addressesOf(physicalNetwork)
            underlying = physicalNetwork
            physicalKey = physicalNetwork.toString() + physicalAddresses.joinToString()
            File(home, "config.yaml").writeText(profile.config(bundled, physicalAddresses))
            stage = "初始化网络内核"
            check(initializing.compareAndSet(false, true)) { "CORE_BUSY" }
            Core.quickSetup(JSONObject().put("home-dir", home.path).put("version", Build.VERSION.SDK_INT).toString(), "{}") { result ->
                if (ticket != generation.get() || serial.isShutdown) {
                    Core.invokeMethod("{\"method\":\"shutdown\",\"arguments\":null}") { initializing.set(false) }
                    return@quickSetup
                }
                serial.execute {
                    starting = false
                    initializing.set(false)
                    if (ticket != generation.get()) { shutdown(); return@execute }
                    if (!result.isNullOrEmpty()) {
                        val reason = NetworkDiagnostics.record(this, "初始化网络内核", result, profile)
                        fail("网络内核初始化失败：$reason\n请点下方“查看连接诊断”")
                        return@execute
                    }
                    try {
                        val builder = Builder().setSession("随连 · 智能分流")
                            .addAddress("172.19.0.1", 30).addRoute("0.0.0.0", 0)
                            .addAddress("fdfe:dcba:9876::1", 126).addRoute("::", 0)
                            .addDnsServer("172.19.0.2").setMtu(1380)
                            .setConfigureIntent(PendingIntent.getActivity(this, 0, Intent(this, HomeActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
                        val appliedApps = mutableListOf<String>()
                        val missingApps = mutableListOf<String>()
                        profile.directApps.filter { it != packageName }.forEach { name ->
                            try { builder.addDisallowedApplication(name); appliedApps.add(name) }
                            catch (_: android.content.pm.PackageManager.NameNotFoundException) { missingApps.add(name) }
                        }
                        builder.setUnderlyingNetworks(underlying?.let { arrayOf(it) })
                        val descriptor = builder.establish() ?: error("VPN_PERMISSION")
                        tunDescriptor = descriptor
                        val fd = ParcelFileDescriptor.dup(descriptor.fileDescriptor).detachFd()
                        val accepted = Core.startTun(fd, this::protectPhysicalSocket, { protocol, local, remote ->
                            if (Build.VERSION.SDK_INT >= 29) try { connectivity.getConnectionOwnerUid(protocol, local, remote) } catch (_: Exception) { -1 } else -1
                        }, { uid -> packageManager.getPackagesForUid(uid)?.firstOrNull() ?: "" }, "mixed", "172.19.0.1/30,fdfe:dcba:9876::1/126", "172.19.0.2")
                        if (!accepted) { error("TUN_REJECTED") }
                        val lockdown = Build.VERSION.SDK_INT >= 29 && isLockdownEnabled
                        appRoutingStatus = "应用直连已应用：${appliedApps.joinToString().ifEmpty { "无" }}\n" +
                            "未找到的应用：${missingApps.joinToString().ifEmpty { "无" }}\n" +
                            "系统阻止非 VPN 连接：${if (lockdown) "已开启，会阻断上述直连应用，请到系统 VPN 设置关闭" else "未开启"}"
                        running = true
                        active = true
                        TrafficHistory.start()
                        connecting = false
                        if (!registered) {
                            connectivity.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), networkCallback)
                            registered = true
                        }
                        publish("VPN 已启动 · 正在验证电脑连接")
                        mainHandler.postDelayed({ if (running) checkConnectivity() }, 2000)
                    } catch (e: Exception) {
                        val reason = NetworkDiagnostics.record(this, "建立系统VPN", e.javaClass.simpleName + ": " + (e.message ?: "无详细信息"), profile)
                        fail("分流启动失败：$reason")
                    }
                }
            }
        } catch (e: Exception) {
            val reason = NetworkDiagnostics.record(this, stage, e.javaClass.simpleName + ": " + (e.message ?: "无详细信息"), currentProfile)
            fail("$stage 失败：$reason")
        }
        catch (e: LinkageError) {
            initializing.set(false)
            NetworkDiagnostics.record(this, "加载原生组件", e.message ?: e.javaClass.simpleName, currentProfile)
            fail("网络组件加载失败，请打开连接诊断")
        }
    }

    // protect() excludes the VPN, but does not select Wi-Fi vs cellular. All
    // transport sockets must use the same physical network as the advertised IPs.
    private fun protectPhysicalSocket(fd: Int): Boolean {
        val network = underlying ?: return false
        if (!protect(fd)) return false
        return runCatching {
            ParcelFileDescriptor.fromFd(fd).use { network.bindSocket(it.fileDescriptor) }
            true
        }.getOrDefault(false)
    }

    private fun addressesOf(network: Network?): List<String> = network?.let {
        connectivity.getLinkProperties(it)?.linkAddresses.orEmpty().mapNotNull { a -> a.address.hostAddress }.distinct().sorted()
    }.orEmpty()
    private fun preferredNetwork(candidates: List<Network>): Network? = candidates.filter {
        val caps = connectivity.getNetworkCapabilities(it)
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.sortedByDescending {
        val caps = connectivity.getNetworkCapabilities(it)
        (if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) 10 else 0) +
            (if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) 1 else 0)
    }.firstOrNull()

    private fun scheduleNetworkUpdate(network: Network, available: Boolean) {
        if (serial.isShutdown) return
        serial.execute {
            if (available) networks.add(network) else networks.remove(network)
            if (!running) return@execute
            val selected = preferredNetwork(connectivity.allNetworks.toList())
            val addresses = addressesOf(selected)
            val nextKey = selected.toString() + addresses.joinToString()
            if (nextKey != physicalKey) {
                generation.incrementAndGet()
                physicalKey = nextKey
                underlying = selected
                setUnderlyingNetworks(selected?.let { arrayOf(it) })
                if (selected != null) Core.invokeMethod(JSONObject().put("method", "refreshPhysicalNetwork")
                    .put("arguments", org.json.JSONArray(addresses)).toString()) { }
                Core.invokeMethod("{\"method\":\"resetConnections\",\"arguments\":null}") { }
                NetworkOverview.health = NetworkOverview.Health()
                publish(if (selected == null) "等待手机网络 · 连接将自动恢复" else "网络已切换 · 正在验证电脑连接")
                if (selected != null) mainHandler.postDelayed({ if (running) checkConnectivity() }, 2500)
            }
        }
    }

    private fun checkConnectivity() {
        if (!running || probes.isShutdown || !probing.compareAndSet(false, true)) return
        val ticket = generation.get()
        probes.execute probe@ {
            try {
                val profile = ProfileStore(this).load() ?: return@probe
                var computer = ConnectionProbe.check("http://${profile.computerIp}:10809/manifest.json", profile.ruleToken())
                for (attempt in 1..2) {
                    if (computer.ok || !running || ticket != generation.get()) break
                    Thread.sleep(2000L * attempt)
                    computer = ConnectionProbe.check("http://${profile.computerIp}:10809/manifest.json", profile.ruleToken())
                }
                val previous = NetworkOverview.health
                val now = System.currentTimeMillis()
                val fullCheck = previous.checkedAt == 0L || now - previous.checkedAt >= 180000L || !computer.ok
                val direct = if (fullCheck) ConnectionProbe.check("https://www.baidu.com/")
                    else ConnectionProbe.Result(previous.direct == true, "沿用最近一次手机出口检测")
                val internet = if (computer.ok) {
                    if (fullCheck) ConnectionProbe.check("https://www.gstatic.com/generate_204")
                    else ConnectionProbe.Result(previous.exit == true, "沿用最近一次电脑出口检测")
                } else null
                val directPeer = if (!computer.ok && direct.ok) readDirectPeer(profile.computerIp) else null
                if (!running || ticket != generation.get() || serial.isShutdown) return@probe
                NetworkOverview.health = NetworkOverview.Health(direct.ok, computer.ok, internet?.ok, if (fullCheck) now else previous.checkedAt)
                val report = "连接策略：仅 P2P 直连，禁止中继\n手机本地上网：${direct.detail}\n手机到电脑：${computer.detail}\n电脑出口：${internet?.detail ?: "电脑通道未通，未继续检测"}"
                NetworkDiagnostics.record(this, "实际网络连通检测", report, profile)
                serial.execute publishResult@ {
                    if (!running || ticket != generation.get()) return@publishResult
                    if (linkRecovery.shouldRecover(computer.ok, direct.ok, directPeer)) {
                        val addresses = addressesOf(underlying)
                        if (addresses.isNotEmpty()) {
                            generation.incrementAndGet()
                            Core.invokeMethod(JSONObject().put("method", "refreshPhysicalNetwork")
                                .put("arguments", org.json.JSONArray(addresses)).toString()) { }
                            publish("直连已断开 · 正在重新建立电脑通道")
                            mainHandler.postDelayed({ if (running) checkConnectivity() }, 4000)
                            return@publishResult
                        }
                    }
                    publish(when {
                        computer.ok && internet?.ok == true && direct.ok -> "电脑直连已验证 · 抽样联网检测通过"
                        !computer.ok -> "电脑尚未直连 · 本地分流继续运行"
                        internet?.ok != true -> "电脑已连通 · 电脑出口检测失败"
                        else -> "电脑出口正常 · 手机直连检测失败"
                    })
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                probing.set(false)
                if (running) mainHandler.post {
                    mainHandler.removeCallbacks(healthCheck)
                    val health = NetworkOverview.health
                    val delay = when {
                        ticket != generation.get() -> 0L
                        // A private, authenticated request keeps the NAT path warm and
                        // checks the real data path. Public websites are checked only every 3 min.
                        health.direct == true && health.computer == true && health.exit == true -> 20000L
                        else -> 15000L
                    }
                    mainHandler.postDelayed(healthCheck, delay)
                }
            }
        }
    }

    private fun readDirectPeer(computerIp: String): Boolean? {
        val done = java.util.concurrent.CountDownLatch(1)
        val value = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
        Core.invokeMethod("{\"method\":\"getOverlayStatus\",\"arguments\":null}") { bytes ->
            value.set(runCatching { OverlayRouteState.directPeer(bytes?.decodeToString() ?: "{}", computerIp) }.getOrNull())
            done.countDown()
        }
        return if (done.await(3500, java.util.concurrent.TimeUnit.MILLISECONDS)) value.get() else null
    }

    private fun publish(message: String) {
        status = message
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("vpn", "长期连接状态", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, HomeActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, LinkVpnService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "vpn") else Notification.Builder(this)
        startForeground(7701, builder.setSmallIcon(android.R.drawable.stat_sys_upload_done).setContentTitle("随连")
            .setContentText(message).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "断开", stop).build()).build())
    }

    private fun fail(message: String) { shutdown(); status = message; stopSelf() }
    private fun shutdown() {
        TrafficHistory.stop()
        linkRecovery.reset()
        mainHandler.removeCallbacksAndMessages(null)
        if (registered) { runCatching { connectivity.unregisterNetworkCallback(networkCallback) }; registered = false }
        networks.clear()
        underlying = null
        physicalKey = ""
        runCatching { Core.stopTun() }
        runCatching { tunDescriptor?.close() }
        tunDescriptor = null
        if (initializing.compareAndSet(false, true)) {
            runCatching { Core.invokeMethod("{\"method\":\"shutdown\",\"arguments\":null}") { initializing.set(false) } }
                .onFailure { initializing.set(false) }
        }
        running = false
        starting = false
        active = false
        connecting = false
        NetworkOverview.reset()
        appRoutingStatus = "应用直连尚未启用；保存修改后需重新连接"
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
    override fun onRevoke() {
        preferences.edit().putBoolean("wanted", false).apply()
        generation.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(null)
        serial.execute { shutdown(); status = "VPN 权限已取消"; stopSelf() }
    }
    override fun onDestroy() {
        generation.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(null)
        serial.execute { shutdown() }
        serial.shutdown()
        probes.shutdownNow()
        super.onDestroy()
    }
    companion object {
        const val STOP = "cn.wangchuan.link.STOP"
        const val CHECK = "cn.wangchuan.link.CHECK"
        @Volatile var active = false; private set
        @Volatile var connecting = false; private set
        @Volatile var status = "尚未连接"
        @Volatile var appRoutingStatus = "应用直连尚未启用；保存修改后需重新连接"
        private val initializing = AtomicBoolean(false)
    }
}
