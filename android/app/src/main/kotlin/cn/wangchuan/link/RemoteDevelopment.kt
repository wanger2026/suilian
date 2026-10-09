package cn.wangchuan.link

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

object RemoteDevelopment {
    val lease = RemoteLease { SystemClock.elapsedRealtime() }
    @Volatile var status = "联调未开启"
    @Volatile var inputDiagnostics = "{}"
    private val moonHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var moonActivity: java.lang.ref.WeakReference<Activity>? = null
    private val moonTick = object : Runnable {
        override fun run() {
            val activity = moonActivity?.get()
            if (activity == null || activity.isDestroyed) { lease.heartbeat(false); return }
            lease.heartbeat(true)
            inputDiagnostics = org.json.JSONObject().put("mode", "streaming")
                .put("keyEvents", com.limelight.binding.DevelopmentObserver.keyEvents)
                .put("pointerEvents", com.limelight.binding.DevelopmentObserver.pointerEvents)
                .put("keySends", com.limelight.binding.DevelopmentObserver.keySends)
                .put("buttonSends", com.limelight.binding.DevelopmentObserver.buttonSends)
                .put("grabbed", com.limelight.binding.DevelopmentObserver.grabbed)
                .put("automationInputSuppressed", com.limelight.binding.DevelopmentObserver.automationInputSuppressed).toString()
            moonHandler.postDelayed(this, 4000)
        }
    }
    fun installMoonlightObserver() {
        com.limelight.binding.DevelopmentObserver.listener = com.limelight.binding.DevelopmentObserver.Listener { activity, host, ready ->
            moonHandler.post {
                if (ready) {
                    val profile = runCatching { ProfileStore(activity).load() }.getOrNull()
                    if (profile == null || host != profile.computerIp) return@post
                    moonHandler.removeCallbacks(moonTick)
                    moonActivity = java.lang.ref.WeakReference(activity)
                    moonHandler.post(moonTick)
                    addButton(activity)
                } else if (moonActivity?.get() === activity) {
                    moonHandler.removeCallbacks(moonTick)
                    moonActivity = null
                    leave(activity)
                }
            }
        }
    }

    fun bind(activity: Activity, engine: FlutterEngine) {
        val button = addButton(activity)
        MethodChannel(engine.dartExecutor.binaryMessenger, "wangchuan/development").setMethodCallHandler { call, result ->
            val profile = runCatching { ProfileStore(activity).load() }.getOrNull()
            val peer = call.argument<String>("peer") ?: ""
            val pairedComputer = profile != null && (peer == profile.computerIp || peer == "${profile.computerIp}:21118")
            if (call.method == "pairedRemotePassword") {
                result.success(if (pairedComputer) profile?.remotePassword() else null)
                return@setMethodCallHandler
            }
            if (call.method != "remoteState") { result.notImplemented(); return@setMethodCallHandler }
            val ready = call.argument<Boolean>("ready") == true && pairedComputer
            if (pairedComputer) {
                val input = call.argument<Map<String, Any>>("inputState")
                val state = org.json.JSONObject().put("ready", ready)
                input?.let {
                    for (key in listOf("mouseAttempts", "keyAttempts", "permitted", "camera", "sessionMatches"))
                        state.put(key, it[key])
                }
                call.argument<Map<String, Any>>("gestureState")?.let { gestures ->
                    val allowed = setOf("tapDown", "tapUp", "cursorMove", "tap", "tapDownResult", "tapUpResult", "cursorMoveResult", "tapResult")
                    state.put("gestures", org.json.JSONObject(gestures.filterKeys { it in allowed }))
                }
                inputDiagnostics = state.toString()
            }
            lease.heartbeat(ready)
            if (!ready) activity.stopService(Intent(activity, DevelopmentService::class.java))
            button.text = if (lease.allowed()) "关闭/管理 AI 联调" else "AI 联调"
            result.success(null)
        }
    }
    private fun addButton(activity: Activity): Button {
        activity.window.decorView.findViewWithTag<Button>("wangchuan-development")?.let { return it }
        val button = Button(activity).apply {
            tag = "wangchuan-development"
            text = "AI 联调"
            setOnClickListener { guide(activity) }
        }
        activity.window.decorView.post {
            if (!activity.isDestroyed) activity.addContentView(button, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = (36 * activity.resources.displayMetrics.density).toInt()
            })
        }
        return button
    }
    fun leave(activity: Activity) {
        lease.heartbeat(false)
        activity.stopService(Intent(activity, DevelopmentService::class.java))
    }
    fun guide(activity: Activity) {
        val prefs = activity.getSharedPreferences("development-ports", Activity.MODE_PRIVATE)
        val form = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 12, 28, 12) }
        val advanced = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE }
        var section = form
        fun label(text: String) { section.addView(TextView(activity).apply { this.text = text; textSize = 14f; setPadding(0, 14, 0, 8) }) }
        fun jump(title: String, action: String, fallback: String = Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) {
            section.addView(Button(activity).apply { text = title; textSize = 14f; setOnClickListener {
                try { activity.startActivity(Intent(action)) }
                catch (_: Exception) { runCatching { activity.startActivity(Intent(fallback)) } }
            } })
        }
        fun number(title: String, value: String, secret: Boolean = false): EditText {
            label(title)
            return EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or if (secret) InputType.TYPE_NUMBER_VARIATION_PASSWORD else 0
                isSaveEnabled = !secret
                setText(value)
                textSize = 16f
                section.addView(this)
            }
        }
        label(if (lease.available()) "已连接已配对电脑，可授权本次真机联调。" else "只有连接并进入已配对电脑的远控画面后，才能开启。现在可以先准备系统权限。")
        label("允许电脑安装测试包、读日志、截屏和操作手机。退出远控、断线或锁屏会撤销；普通 VPN 常驻不会开启此权限。")
        val discoveryStatus = TextView(activity).apply { text = "正在识别本机无线调试端口…"; textSize = 14f; form.addView(this) }
        val adb = number("连接端口（自动识别）", prefs.getInt("adb", 0).takeIf { it > 0 }?.toString() ?: "")
        val consent = CheckBox(activity).apply { text = "允许已配对电脑进行本次真机联调"; textSize = 14f; form.addView(this) }
        label("当前状态：$status")
        form.addView(Button(activity).apply {
            text = "首次配对 / 系统准备"; textSize = 14f
            setOnClickListener { advanced.visibility = if (advanced.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE }
        })
        form.addView(advanced)
        section = advanced
        label("首次推荐：USB 接电脑，在电脑端点“无线配对”，无需手抄端口和配对码。无线调试必须由你在系统设置中开启并允许当前 Wi-Fi。")
        jump("打开开发者选项", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, Settings.ACTION_SETTINGS)
        jump("打开无线调试设置", "android.settings.WIRELESS_DEBUGGING_SETTINGS")
        jump("打开 Wi-Fi 设置", Settings.ACTION_WIFI_SETTINGS)
        advanced.addView(Button(activity).apply { text = "通知与后台权限"; textSize = 14f; setOnClickListener {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${activity.packageName}")))
        } })
        val pair = number("配对端口（首次/重新配对填写）", "")
        val code = number("六位配对码（已配对留空）", "", true)
        var discovery: WirelessDebugDiscovery? = null
        fun discover() {
            discovery?.close()
            discovery = WirelessDebugDiscovery(activity, { pairing, port ->
                (if (pairing) pair else adb).setText(port.toString())
                discoveryStatus.text = if (pairing) "已识别本机配对端口；只需输入六位码。" else "已识别本机连接端口；已配对可直接授权。"
            }, { discoveryStatus.text = it }).also { it.start() }
        }
        advanced.addView(Button(activity).apply { text = "重新识别端口"; textSize = 14f; setOnClickListener { discover() } })
        label("自动识别失败时，可手动填系统页面冒号后的端口。首次配对需保持系统配对弹窗（可分屏），六位码仅用于本次配对，不保存。")
        label("小米若拦截装包或点击，请按系统提示开启“USB 安装”“USB 调试（安全设置）”；开关名称不代表必须插 USB。不要关闭系统对敏感操作的确认。")
        label("电脑和手机不必在同一 Wi-Fi，联调数据经随连直连通道传送。日常远控保持连接时可切到被测 App；高帧率退到后台会结束串流及联调，开发建议使用日常远控。")
        label("仅移动网络、重启或系统关闭无线调试时可能不可用。USB/fastboot/Recovery 操作不在此模式范围。更新本 APK 自身会中断通道，需重新打开。\n当前状态：$status")
        val dialog = AlertDialog.Builder(activity).setTitle("AI 真机联调授权")
            .setView(ScrollView(activity).apply { addView(form) })
            .setPositiveButton("开启本次联调", null)
            .setNeutralButton("立即关闭") { _, _ -> lease.revoke(); activity.stopService(Intent(activity, DevelopmentService::class.java)) }
            .setNegativeButton("暂不开启", null).create()
        dialog.setOnDismissListener { discovery?.close(); code.text.clear() }
        dialog.setOnShowListener {
            discover()
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                fun notice(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
                if (Build.VERSION.SDK_INT < 30) { notice("本模式需要 Android 11 或更高版本的无线调试"); return@setOnClickListener }
                if (lease.allowed()) { notice("联调已开启。如需修改端口，请先点立即关闭，再重新授权。"); return@setOnClickListener }
                if (!consent.isChecked || !lease.available()) { notice("请先进入已配对电脑的远控画面，并勾选本次授权"); return@setOnClickListener }
                val adbPort = adb.text.toString().toIntOrNull() ?: 0
                val pairCode = code.text.toString()
                val pairPort = if (pairCode.isEmpty()) 0 else pair.text.toString().toIntOrNull() ?: 0
                if (adbPort !in 1024..65535 || (pairCode.isNotEmpty() && (!pairCode.matches(Regex("[0-9]{6}")) || pairPort !in 1024..65535))) {
                    notice("请核对连接端口，以及首次配对的六位码/配对端口"); return@setOnClickListener
                }
                if (!lease.grant()) { notice("远控连接已断开，请重新连接"); return@setOnClickListener }
                prefs.edit().putInt("adb", adbPort).apply()
                val intent = Intent(activity, DevelopmentService::class.java).putExtra("adbPort", adbPort)
                    .putExtra("pairPort", pairPort).putExtra("pairCode", pairCode)
                activity.startForegroundService(intent)
                code.text.clear()
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
