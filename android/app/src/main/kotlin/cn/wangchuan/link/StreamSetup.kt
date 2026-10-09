package cn.wangchuan.link

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object StreamSetup {
    private fun request(profile: Profile, path: String, body: JSONObject): JSONObject {
        val call = ConnectionProbe.client.newCall(Request.Builder()
            .url("http://${profile.computerIp}:10809/streaming/$path")
            .header("Authorization", "Bearer ${profile.ruleToken()}")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build())
        call.timeout().timeout(8, TimeUnit.SECONDS)
        call.execute().use {
            val data = runCatching { JSONObject(it.body?.string() ?: "{}") }.getOrDefault(JSONObject())
            if (!it.isSuccessful) {
                val detail = data.optString("error")
                error(detail.ifBlank { "电脑串流准备未完成，请检查连接" })
            }
            return data
        }
    }
    fun prepare(activity: Activity, profile: Profile, ready: () -> Unit) {
        val cancelled = AtomicBoolean(false)
        val dialog = AlertDialog.Builder(activity).setTitle("准备高帧率模式")
            .setMessage("正在检测电脑硬件编码器…\n首次启动可能需要约 30 秒，请保持连接。")
            .setNegativeButton("取消") { _, _ -> cancelled.set(true) }.create()
        dialog.setOnCancelListener { cancelled.set(true) }
        dialog.show()
        Thread {
            val result = runCatching {
                var state = request(profile, "prepare", JSONObject())
                val deadline = android.os.SystemClock.elapsedRealtime() + 55000
                while (!state.optBoolean("ready")) {
                    if (cancelled.get() || activity.isDestroyed) return@Thread
                    val failure = state.optString("error")
                    check(failure.isBlank()) { failure }
                    check(android.os.SystemClock.elapsedRealtime() < deadline) { "电脑串流准备超时，请检查编码器与显示器状态" }
                    Thread.sleep(1000)
                    state = request(profile, "status", JSONObject())
                }
            }
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    dialog.dismiss()
                    if (!cancelled.get()) result.onSuccess { ready() }
                    .onFailure { Toast.makeText(activity, it.message ?: "电脑暂时无法连接", Toast.LENGTH_LONG).show() }
                }
            }
        }.start()
    }
    fun pair(context: Context, host: String?, pin: String?) {
        require(LinkVpnService.active && pin?.matches(Regex("[0-9]{4}")) == true)
        val profile = requireNotNull(ProfileStore(context).load())
        require(profile.computerIp == host)
        request(profile, "pair", JSONObject().put("pin", pin))
    }
}

/** Only our embedded Moonlight component can invoke this non-exported receiver. */
class StreamPairReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try { StreamSetup.pair(context.applicationContext, intent.getStringExtra("host"), intent.getStringExtra("pin")) }
            catch (_: Exception) { Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "自动配对未完成，请检查电脑连接后重试", Toast.LENGTH_LONG).show()
            } }
            finally { pending.finish() }
        }.start()
    }
}
