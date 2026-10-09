package cn.wangchuan.link

import android.app.*
import android.content.*
import android.os.*

class DevelopmentService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var bridge: DevelopmentBridge? = null
    private var registered = false
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { stopSelf() }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!RemoteDevelopment.lease.allowed()) { stopSelf(); return }
            bridge?.heartbeat()
            handler.postDelayed(this, 2000)
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == "stop" || Build.VERSION.SDK_INT < 30 || !RemoteDevelopment.lease.allowed()) {
            stopSelf(); return START_NOT_STICKY
        }
        if (bridge != null) return START_NOT_STICKY
        try {
            val adbPort = intent.getIntExtra("adbPort", 0)
            val pairPort = intent.getIntExtra("pairPort", 0)
            val code = intent.getStringExtra("pairCode") ?: ""
            intent.removeExtra("pairCode")
            require(adbPort in 1024..65535)
            require((code.isEmpty() && pairPort == 0) || (code.matches(Regex("[0-9]{6}")) && pairPort in 1024..65535))
            val profile = ProfileStore(this).load() ?: error("profile")
            notifyStatus("正在建立真机联调通道")
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
            else registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
            registered = true
            bridge = DevelopmentBridge(profile, adbPort, pairPort, code,
                { message -> handler.post { if (RemoteDevelopment.lease.allowed()) notifyStatus(message) } },
                { handler.post { stopSelf() } }).also { it.start() }
            handler.post(tick)
        } catch (_: Exception) { stopSelf() }
        return START_NOT_STICKY
    }
    private fun notifyStatus(text: String) {
        RemoteDevelopment.status = text
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("development", "AI 真机联调", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 871, Intent(this, DevelopmentService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "development").setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("AI 真机联调正在使用手机").setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "立即关闭", stop).build()).build()
        startForeground(7781, notification)
    }
    override fun onDestroy() {
        RemoteDevelopment.lease.revoke()
        RemoteDevelopment.status = "联调已关闭"
        handler.removeCallbacksAndMessages(null)
        bridge?.close(); bridge = null
        if (registered) unregisterReceiver(screenOff)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
