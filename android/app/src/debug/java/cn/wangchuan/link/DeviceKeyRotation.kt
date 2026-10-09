package cn.wangchuan.link

import android.content.Context
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/** DUMP-authorized USB maintenance, never included in release. No plaintext files. */
object DeviceKeyRotation {
    fun run(context: Context, nonce: String) {
        require(nonce.matches(Regex("[a-f0-9]{32}")))
        check(!LinkVpnService.active && !LinkVpnService.connecting)
        val store = ProfileStore(context)
        val profile = requireNotNull(store.load())
        val report = File(context.filesDir, "key-rotation-status.json")
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 8000
            report.writeText(JSONObject().put("nonce", nonce).put("port", server.localPort).put("state", "ready").toString())
            try {
                server.accept().use { client ->
                    client.soTimeout = 4000
                    val input = DataInputStream(client.getInputStream())
                    val size = input.readInt(); require(size in 1..1024)
                    val payload = JSONObject(String(ByteArray(size).also { input.readFully(it) }, Charsets.UTF_8))
                    val secret = payload.getString("secret")
                    val mac = Mac.getInstance("HmacSHA256").apply {
                        init(SecretKeySpec(profile.ruleToken().toByteArray(), "HmacSHA256"))
                    }
                    val expected = mac.doFinal((nonce + "\n" + secret).toByteArray()).joinToString("") { "%02x".format(it) }
                    check(MessageDigest.isEqual(expected.toByteArray(), payload.getString("proof").toByteArray()))
                    check(!LinkVpnService.active && !LinkVpnService.connecting)
                    store.save(profile.rotateNetworkSecret(secret))
                    File(context.filesDir, "network-runtime/config.yaml").delete()
                    client.getOutputStream().write(1)
                    report.writeText(JSONObject().put("nonce", nonce).put("state", "complete").toString())
                }
            } catch (e: Exception) {
                report.writeText(JSONObject().put("nonce", nonce).put("state", "failed").toString())
                throw IllegalStateException("USB key rotation did not complete")
            }
        }
    }
}
