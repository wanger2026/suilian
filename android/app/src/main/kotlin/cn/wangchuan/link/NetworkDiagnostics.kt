package cn.wangchuan.link

import android.content.Context
import android.os.Build

object NetworkDiagnostics {
    fun record(context: Context, stage: String, error: String, profile: Profile? = null): String {
        val safe = profile?.redactDiagnostic(error) ?: error.replace(Regex("[A-Za-z0-9+/=_-]{32,}"), "[已隐藏]")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date())
        val details = "随连 $version\nAndroid ${Build.VERSION.SDK_INT} / ${Build.MANUFACTURER} ${Build.MODEL}\n检测时间：$now\n阶段：$stage\n结果：${safe.take(1800)}"
        context.getSharedPreferences("network-diagnostics", Context.MODE_PRIVATE).edit().putString("last", details).apply()
        return safe.replace('\n', ' ').take(160)
    }
    fun read(context: Context): String {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        val previous = context.getSharedPreferences("network-diagnostics", Context.MODE_PRIVATE).getString("last", null)
        val header = "当前安装版本：$version\n当前状态：${LinkVpnService.status}\n${LinkVpnService.appRoutingStatus}\n\n${TrafficHistory.syncDescription()}\n\n${BackgroundStatus.describe(context)}\n\n"
        return header + when {
            previous == null -> "尚未检测。连接后会自动验证手机直连、电脑通道和电脑出口。"
            !previous.startsWith("随连 $version\n") -> "以下是旧版本记录，不代表当前连接结果：\n$previous"
            else -> previous
        }
    }
}
