package cn.wangchuan.link

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class RuleStore(private val context: Context) {
    private val cache = File(context.filesDir, "rules-cache").apply { mkdirs() }
    private val current = AtomicFile(File(cache, "manifest.json"))
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun validate(manifest: JSONObject): JSONArray {
        val providers = manifest.getJSONArray("providers")
        require(providers.length() in 1..100)
        val names = mutableSetOf<String>()
        val paths = mutableSetOf<String>()
        for (i in 0 until providers.length()) {
            val p = providers.getJSONObject(i)
            require(p.getString("asset").matches(Regex("[0-9]{2}\\.(mrs|text)")))
            require(p.getString("path") == "rules/" + p.getString("asset"))
            require(paths.add(p.getString("path")))
            require(p.getString("sha256").matches(Regex("[0-9a-f]{64}")))
            require(p.getString("name").matches(Regex("[A-Za-z0-9_-]{1,100}")))
            require(names.add(p.getString("name")))
            require(p.getString("behavior") in listOf("domain", "ipcidr", "classical"))
            require(p.getString("format") in listOf("mrs", "text"))
            require(p.getString("target") in listOf("DIRECT", "COMPUTER", "REJECT"))
        }
        return providers
    }
    fun install(home: File): JSONArray {
        val updated = current.baseFile.exists()
        val manifest = JSONObject(if (updated) current.openRead().bufferedReader().use { it.readText() }
            else context.assets.open("network-rules/manifest.json").bufferedReader().use { it.readText() })
        val providers = validate(manifest)
        File(home, "rules").mkdirs()
        for (i in 0 until providers.length()) {
            val p = providers.getJSONObject(i)
            val bytes = if (updated) File(cache, p.getString("sha256")).readBytes()
                else context.assets.open("network-rules/" + p.getString("asset")).use { it.readBytes() }
            check(digest(bytes) == p.getString("sha256"))
            File(home, p.getString("path")).writeBytes(bytes)
        }
        return providers
    }
    fun sync(profile: Profile): Int {
        fun fetch(path: String, maxBytes: Int): ByteArray {
            val connection = URL("http://${profile.computerIp}:10809/$path").openConnection() as HttpURLConnection
            connection.connectTimeout = 8000; connection.readTimeout = 15000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer " + profile.ruleToken())
            try {
                check(connection.responseCode == 200)
                val bytes = connection.inputStream.use { input ->
                    val result = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16384)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(result.size() + count <= maxBytes)
                        result.write(buffer, 0, count)
                    }
                    result.toByteArray()
                }
                return bytes
            } finally { connection.disconnect() }
        }
        val manifestBytes = fetch("manifest.json", 262144)
        val providers = validate(JSONObject(String(manifestBytes, Charsets.UTF_8)))
        for (i in 0 until providers.length()) {
            val p = providers.getJSONObject(i)
            val dest = File(cache, p.getString("sha256"))
            if (dest.exists() && digest(dest.readBytes()) == p.getString("sha256")) continue
            val bytes = fetch(p.getString("asset"), 16 * 1024 * 1024)
            check(digest(bytes) == p.getString("sha256"))
            dest.writeBytes(bytes)
        }
        val output = current.startWrite()
        try { output.write(manifestBytes); current.finishWrite(output) }
        catch (e: Exception) { current.failWrite(output); throw e }
        return providers.length()
    }
}
