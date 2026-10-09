package cn.wangchuan.link

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Read-only status. OEM permission pages still require the owner's explicit choices. */
object BackgroundStatus {
    fun describe(c:Context):String {
        val exempt=runCatching { c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName) }.getOrNull()
        // always_on_vpn_app is a restricted @hide settings key on Android 12+.
        // A UI context must not read it or infer that a denied read means disabled.
        val lines=mutableListOf("系统电池优化豁免：${when(exempt){true->"已开启";false->"未开启";else->"无法读取，请在系统设置核对"}}","始终开启 VPN：请在系统 VPN 设置中核对")
        if(Build.VERSION.SDK_INT>=30)runCatching {
            val exit=c.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(c.packageName,0,8)
                .firstOrNull { it.reason!=android.app.ApplicationExitInfo.REASON_PACKAGE_UPDATED }
            if(exit!=null){
                val time=SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(Date(exit.timestamp))
                val why=when {
                    exit.description.orEmpty().contains("LockScreenClean")->"被系统锁屏清理"
                    exit.description.orEmpty().contains("SwipeUpClean")->"被系统划卡清理"
                    exit.reason==android.app.ApplicationExitInfo.REASON_USER_REQUESTED->"被强制停止"
                    exit.reason==android.app.ApplicationExitInfo.REASON_CRASH||exit.reason==android.app.ApplicationExitInfo.REASON_CRASH_NATIVE->"应用崩溃"
                    exit.reason==android.app.ApplicationExitInfo.REASON_ANR->"应用无响应"
                    exit.reason==android.app.ApplicationExitInfo.REASON_LOW_MEMORY->"系统内存不足回收"
                    else->"进程退出（原因 ${exit.reason}）"
                }
                lines.add("最近非升级退出：$time $why")
            }
        }
        return lines.joinToString("\n")
    }
}
