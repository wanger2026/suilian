package cn.wangchuan.link

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import com.google.zxing.integration.android.IntentIntegrator

class HomeActivity : Activity() {
    private val ink = Color.rgb(24, 40, 59)
    private val muted = Color.rgb(108, 123, 139)
    private val blue = Color.rgb(47, 100, 237)
    private val teal = Color.rgb(13, 137, 124)
    private val paper = Color.rgb(244, 247, 250)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var page: LinearLayout
    private lateinit var nav: LinearLayout
    private var tab = 0
    private var statusView: TextView? = null
    private var trafficView: TextView? = null
    private var healthView: TextView? = null
    private var connectionButton: TextView? = null
    private var appRows: LinearLayout? = null
    private val labels = mutableMapOf<String, String>()
    private var lastSnapshot = -1L
    private val update = object : Runnable {
        override fun run() { NetworkOverview.refresh(); updateLive(); handler.postDelayed(this, 2500) }
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun shape(color: Int, radius: Int = 20) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun text(value: String, size: Float = 15f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        this.text = value; textSize = size; setTextColor(color); setLineSpacing(dp(3).toFloat(), 1f)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun gap(parent: LinearLayout, height: Int = 12) { parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height))) }
    private fun card(parent: LinearLayout = page, dark: Boolean = false): LinearLayout = column().also {
        it.background = shape(if (dark) ink else Color.WHITE); it.setPadding(dp(20), dp(20), dp(20), dp(20))
        parent.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
    }
    private fun action(parent: LinearLayout, title: String, primary: Boolean = false, run: () -> Unit): TextView =
        text(title, 15f, if (primary) Color.WHITE else blue, true).also {
            it.gravity = Gravity.CENTER; it.minHeight = dp(50); it.setPadding(dp(12), dp(14), dp(12), dp(14))
            it.background = shape(if (primary) blue else Color.rgb(237, 243, 255), 14)
            it.isClickable = true; it.isFocusable = true; it.setOnClickListener { run() }
            parent.addView(it, LinearLayout.LayoutParams(-1, -2))
        }
    private fun section(title: String, subtitle: String) {
        page.addView(text(title, 28f, ink, true)); gap(page, 5)
        page.addView(text(subtitle, 14f, muted)); gap(page, 22)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0)
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = paper; window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        tab = savedInstanceState?.getInt("tab") ?: 0
        val root = column().apply { setBackgroundColor(paper) }
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom); insets
        }
        page = column().apply { setPadding(dp(22), dp(22), dp(22), dp(10)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(page) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        nav = LinearLayout(this).apply { setBackgroundColor(Color.WHITE); setPadding(dp(12), dp(8), dp(12), dp(8)) }
        root.addView(nav); setContentView(root); render()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 702)
    }
    private fun render() {
        statusView = null; trafficView = null; healthView = null; connectionButton = null; appRows = null
        lastSnapshot = -1; page.removeAllViews(); nav.removeAllViews()
        listOf("连接", "应用", "远控", "设备").forEachIndexed { index, title ->
            val item = text(title, 14f, if (index == tab) blue else muted, index == tab).apply {
                gravity = Gravity.CENTER; minHeight = dp(48)
                background = shape(if (index == tab) 0xffedf3ff.toInt() else Color.WHITE, 14)
                contentDescription = "$title，${if (index == tab) "已选择" else "打开页面"}"
                isFocusable = true; setOnClickListener { tab = index; render() }
            }
            nav.addView(item, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        }
        when (tab) { 0 -> connectionPage(); 1 -> appsPage(); 2 -> remotePage(); else -> devicePage() }
        updateLive()
    }
    private fun connectionPage() {
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(ImageView(this).apply { setImageResource(com.carriez.flutter_hbb.R.drawable.suilian_mark); contentDescription = "随连图标" }, LinearLayout.LayoutParams(dp(42), dp(42)))
        heading.addView(text("  随连", 28f, ink, true)); page.addView(heading); gap(page, 8)
        page.addView(text("你的网络，随时相连。", 14f, muted)); gap(page, 22)
        val hero = card(dark = true)
        hero.addView(text("智能分流", 14f, 0xffa9bdd3.toInt(), true)); gap(hero, 10)
        statusView = text(LinkVpnService.status, 22f, Color.WHITE, true).also { hero.addView(it) }
        gap(hero, 12); hero.addView(text("日常访问使用手机网络\n需要电脑的访问，经加密直连到达电脑", 14f, 0xffc5d1df.toInt()))
        gap(hero, 22)
        connectionButton = action(hero, "开启连接", true) {
            if (LinkVpnService.active || LinkVpnService.connecting) stopConnection() else requestConnect()
        }
        val health = card(); health.addView(text("连接检查", 17f, ink, true)); gap(health, 10)
        healthView = text("尚未检测", 14f, muted).also { health.addView(it) }
        gap(health, 14); action(health, "检测连接 / 查看诊断") { diagnostics() }
        val traffic = card(); traffic.addView(text("本次分流流量", 17f, ink, true)); gap(traffic, 12)
        trafficView = text("尚无统计", 20f, ink, true).also { traffic.addView(it) }
        gap(traffic, 8); traffic.addView(text("统计进入分流内核的流量；应用直连名单中的用量不计入。", 12f, muted))
        page.addView(text("电脑需保持开机联网。仅使用设备直连，无法直连时会明确提示。", 12f, muted))
    }
    private fun appsPage() {
        section("应用与流量", "查看访问路径，决定哪些应用始终使用手机网络。")
        val history=card(); history.addView(text("历史与累计",21f,ink,true)); gap(history,8)
        history.addView(text("按软件、网站、日期和线路查用量；关闭界面后继续采集，重启保留。",14f,muted))
        gap(history,14);action(history,"查看历史统计",true){startActivity(Intent(this,TrafficHistoryActivity::class.java))}
        val config = card(); config.addView(text("手机原网名单", 17f, ink, true)); gap(config, 7)
        val direct = profile()?.directApps.orEmpty()
        config.addView(text(if (direct.isEmpty()) "尚未配置直连应用" else direct.joinToString("、") { appLabel(it) }, 14f, muted))
        gap(config, 14); action(config, "管理绕过应用") { editDirectApps() }
        gap(config, 9); config.addView(text("名单中的应用完全绕过 VPN，DNS 与 IPv6 也使用手机原网，用量不采集。修改后重新连接生效。", 12f, muted))
        val streams = card(); streams.addView(text("实时连接", 17f, ink, true)); gap(streams, 7)
        streams.addView(text("显示当前活跃连接及其已传输用量，不代表应用的全天总流量。", 12f, muted)); gap(streams, 12)
        appRows = column().also { streams.addView(it) }
        action(page, "域名与规则设置") { startActivity(Intent(this, RuleSettingsActivity::class.java)) }
    }
    private fun remotePage() {
        section("远程电脑", "需要时打开，离开远控后分流继续运行。")
        val normal = card(); normal.addView(text("日常远控", 21f, ink, true)); gap(normal, 8)
        normal.addView(text("查看桌面、操作软件和处理文件。\n通过与你配对的电脑直接连接。", 14f, muted)); gap(normal, 18)
        action(normal, "进入电脑桌面", true) {
            val p = profile() ?: return@action notice("先到“设备”页配对电脑")
            if (!LinkVpnService.active) return@action notice("请先在“连接”页开启电脑通道")
            startActivity(Intent(this, com.carriez.flutter_hbb.MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW).setData(Uri.parse("rustdesk://${p.computerIp}:21118")))
        }
        val stream = card(); stream.addView(text("高帧率串流", 21f, ink, true)); gap(stream, 8)
        stream.addView(text("Moonlight · 适合视频、游戏和流畅桌面。\n首次需要与电脑 Sunshine 配对。", 14f, muted)); gap(stream, 18)
        action(stream, "打开高帧率模式") {
            if (profile() == null) return@action notice("先到“设备”页配对电脑")
            if (!LinkVpnService.active) return@action notice("请先开启电脑通道")
            val paired = profile()!!
            StreamSetup.prepare(this, paired) {
                startActivity(Intent(this, com.limelight.PcView::class.java).putExtra("suilian.pairedHost", paired.computerIp))
            }
        }
        val dev = card(); dev.addView(text("AI 真机联调", 18f, ink, true)); gap(dev, 8)
        dev.addView(text("仅在远控会话中按次授权启用。离开远控或关闭授权后，联调通道自动停止。", 14f, muted)); gap(dev, 15)
        action(dev, "查看权限准备") { RemoteDevelopment.guide(this) }
        page.addView(text("日常操作使用电脑桌面；需要流畅画面时开启高帧率。远控与联调按需使用，不影响网络连接常驻。", 12f, muted))
    }
    private fun devicePage() {
        section("设备与设置", "配对、后台运行与连接维护。")
        val paired = card(); paired.addView(text(if (profile() != null) "已配对电脑" else "连接你的电脑", 20f, ink, true)); gap(paired, 8)
        paired.addView(text(profile()?.let { "专用通道  ${it.computerIp}\n配对凭证已加密保存在本机" } ?: "打开电脑管理工具，扫描配对二维码。", 14f, muted))
        gap(paired, 16); action(paired, if (profile() != null) "重新扫描配对" else "扫描电脑二维码", true) {
            if (LinkVpnService.active || LinkVpnService.connecting) return@action notice("请先断开连接，再更换配对电脑")
            IntentIntegrator(this).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt("扫描电脑管理工具的配对二维码").setBeepEnabled(false).initiateScan()
        }
        val settings = card(); settings.addView(text("长期运行", 18f, ink, true)); gap(settings, 10)
        settings.addView(text("保持低优先级通知；远控与串流按需运行。后台不持续点亮屏幕，也不持有永久 CPU 唤醒锁。", 14f, muted)); gap(settings, 14)
        action(settings, "后台与电池设置") { batteryGuide() }; gap(settings, 10)
        action(settings, "同步电脑规则") { syncRules() }; gap(settings, 10)
        action(settings, "连接诊断") { diagnostics() }
        page.addView(text("随连 ${packageManager.getPackageInfo(packageName, 0).versionName}\n测试版本 · EasyTier / Mihomo / RustDesk / Moonlight\n组件完整许可随开发包提供。", 12f, muted))
    }
    private fun updateLive() {
        statusView?.text = LinkVpnService.status
        connectionButton?.text = if (LinkVpnService.active || LinkVpnService.connecting) "断开连接" else "开启连接"
        val h = NetworkOverview.health
        fun state(v: Boolean?) = when (v) { true -> "可用"; false -> "未通过"; null -> "等待检测" }
        val stale = h.checkedAt > 0 && System.currentTimeMillis() - h.checkedAt > 60000
        if (stale && LinkVpnService.active) statusView?.text = "分流已开启 · 上次连通检测已过期"
        healthView?.text = "手机直连    ${state(h.direct)}\n电脑通道    ${state(h.computer)}\n电脑出口    ${state(h.exit)}" +
            if (h.checkedAt == 0L) "\n连接后自动检测" else "\n${if (stale) "上次" else "最近"}检测：${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date(h.checkedAt))}"
        val s = NetworkOverview.snapshot
        trafficView?.text = if (s.time == 0L) "等待连接数据" else "↓ ${NetworkOverview.bytes(s.down)}    ↑ ${NetworkOverview.bytes(s.up)}"
        if (appRows != null && lastSnapshot != s.time) {
            lastSnapshot = s.time; val rows = appRows!!; rows.removeAllViews()
            if (!LinkVpnService.active || s.connections.isEmpty()) rows.addView(text(if (LinkVpnService.active) "暂无活跃连接" else "开启连接后显示实际访问路径", 14f, muted))
            s.connections.take(40).forEach { c ->
                rows.addView(text("${appLabel(c.app, c.uid)}  ·  ${c.route}", 14f, if (c.route == "手机直连") teal else blue, true))
                rows.addView(text("${c.host.ifBlank { "目标未识别" }}\n↓ ${NetworkOverview.bytes(c.download)}   ↑ ${NetworkOverview.bytes(c.upload)}  · ${c.rule}", 12f, muted)); gap(rows, 14)
            }
            if (s.connections.size > 40) rows.addView(text("显示前 40 条，共 ${s.connections.size} 条活跃连接", 12f, muted))
        }
    }
    private fun appLabel(value: String, uid: Int = -1): String {
        val name = value.ifBlank { if (uid >= 0) packageManager.getPackagesForUid(uid)?.firstOrNull().orEmpty() else "" }
        if (name.isBlank()) return "未识别应用"
        return labels.getOrPut(name) { runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(name, 0)).toString() }.getOrDefault(name) }
    }
    private fun profile() = runCatching { ProfileStore(this).load() }.getOrNull()
    private fun requestConnect() {
        if (profile() == null) { tab = 3; render(); notice("请先扫描电脑配对二维码"); return }
        val permission = VpnService.prepare(this)
        if (permission != null) startActivityForResult(permission, 701) else connect()
    }
    private fun connect() { val i = Intent(this, LinkVpnService::class.java); if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i) }
    private fun stopConnection() { startService(Intent(this, LinkVpnService::class.java).setAction(LinkVpnService.STOP)) }
    private fun editDirectApps() {
        val p = profile() ?: return notice("请先配对电脑")
        DirectAppPicker.show(this, p.directApps) { selected ->
            runCatching { ProfileStore(this).save(p.withDirectApps(selected)) }.onSuccess { render(); notice("已保存，重新连接后生效") }
                .onFailure { notice("保存失败，原设置保留") }
        }
    }
    private fun diagnostics() {
        val details = NetworkDiagnostics.read(this)
        AlertDialog.Builder(this).setTitle("连接诊断").setMessage(details)
            .setPositiveButton("复制诊断") { _, _ -> getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("随连诊断", details)); notice("已复制，配对凭证已隐藏") }
            .setNeutralButton("重新检测") { _, _ -> if (LinkVpnService.active) {
                startService(Intent(this, LinkVpnService::class.java).setAction(LinkVpnService.CHECK)); notice("检测中，完成后首页会更新")
            } else notice("请先开启连接") }.setNegativeButton("关闭", null).show()
    }
    private fun syncRules() {
        val p = profile() ?: return notice("请先配对电脑")
        if (!LinkVpnService.active) return notice("请先开启电脑通道")
        if (!syncing.compareAndSet(false, true)) return notice("正在同步")
        notice("开始同步")
        Thread {
            val result = try { "已同步 ${RuleStore(applicationContext).sync(p)} 个规则集，重新连接生效" }
                catch (_: Exception) { "同步失败，原规则继续保留" }
            syncing.set(false); runOnUiThread { if (!isDestroyed) notice(result) }
        }.start()
    }
    private fun batteryGuide() {
        AlertDialog.Builder(this).setTitle("长期连接设置")
            .setMessage(BackgroundStatus.describe(this)+"\n\n小米还需在应用设置中允许自启动、将省电策略设为无限制，并在最近任务中锁定随连，避免划卡和锁屏清理。普通应用无法自行解除系统强制停止。\n\n系统 VPN 可启用“始终开启”；保持“阻止未使用 VPN 的连接”关闭，以免影响原网直连应用。\n\n升级前先断开，升级后再连接。")
            .setPositiveButton("打开设置选项") { _, _ ->
                AlertDialog.Builder(this).setTitle("按需完成后台设置")
                    .setItems(arrayOf("随连应用设置（自启动 / 省电）","系统电池优化","系统 VPN 设置")){_,which->
                        val intent=when(which){
                            0->Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName"))
                            1->Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            else->Intent(Settings.ACTION_VPN_SETTINGS)
                        }
                        runCatching{startActivity(intent)}.onFailure{notice("请在手机设置中搜索对应项目")}
                    }.setNegativeButton("关闭",null).show()
            }
            .setNegativeButton("关闭", null).show()
    }
    private fun notice(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    override fun onResume() {
        super.onResume(); handler.post(update)
        if (LinkVpnService.active && System.currentTimeMillis() - NetworkOverview.health.checkedAt > 30000)
            startService(Intent(this, LinkVpnService::class.java).setAction(LinkVpnService.CHECK))
    }
    override fun onPause() { handler.removeCallbacks(update); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("tab", tab); super.onSaveInstanceState(outState) }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 701) { if (resultCode == RESULT_OK) connect(); return }
        val scan = IntentIntegrator.parseActivityResult(requestCode, resultCode, data) ?: return
        val content = scan.contents ?: return
        if (LinkVpnService.active || LinkVpnService.connecting) return notice("请先断开连接后重新配对")
        runCatching { ProfileStore(this).save(content) }.onSuccess { render(); notice("配对成功，凭证已加密保存") }
            .onFailure { notice("配对二维码无效，原设置未改变") }
    }
    companion object { private val syncing = java.util.concurrent.atomic.AtomicBoolean(false) }
}
