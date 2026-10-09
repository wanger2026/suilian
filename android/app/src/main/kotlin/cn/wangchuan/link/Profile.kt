package cn.wangchuan.link

import org.json.JSONArray
import org.json.JSONObject

class Profile(private val data: JSONObject) {
    val computerIp = data.getString("computerIp")
    // v2 completes the old Douyin default with its Lite edition. Preserve users
    // who opted out of Douyin, and every explicit selection saved by v2 onward.
    val directApps = strings("directApps").toMutableList().apply {
        val version = data.optInt("appRoutingVersion", 0)
        if (version < 1) add("com.ss.android.ugc.aweme")
        if (version < 2 && contains("com.ss.android.ugc.aweme")) add("com.ss.android.ugc.aweme.lite")
    }.distinct()
    val directDomains get() = strings("directDomains")
    val computerDomains get() = strings("computerDomains")
    fun ruleToken(): String = data.getString("proxyPassword")
    fun rotateNetworkSecret(secret: String): String {
        require(secret.matches(Regex("[a-f0-9]{64}")))
        return JSONObject(data.toString()).put("networkSecret", secret).toString()
    }
    fun redactDiagnostic(message: String): String {
        var safe = message
        for (key in listOf("proxyPassword", "networkSecret", "remotePassword")) {
            val secret = data.optString(key)
            if (secret.isNotEmpty()) safe = safe.replace(secret, "[已隐藏]")
        }
        return safe.replace(Regex("[A-Za-z0-9+/=_-]{32,}"), "[已隐藏]")
    }
    fun developmentToken(): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(ruleToken().toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal("wangchuan-development-v1".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    fun remotePassword(): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(ruleToken().toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal("wangchuan-rustdesk-v1".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(24)
    }
    fun groupTarget(group: String, default: String) = data.optJSONObject("groups")?.optString(group, default) ?: default
    fun edited(direct: List<String>, computer: List<String>, apps: List<String>, groups: JSONObject): String =
        JSONObject(data.toString()).put("directDomains", JSONArray(direct)).put("computerDomains", JSONArray(computer))
            .put("directApps", JSONArray(apps)).put("appRoutingVersion", 2).put("groups", groups).toString()
    fun withDirectApps(apps: List<String>): String = JSONObject(data.toString())
        .put("directApps", JSONArray(apps.distinct())).put("appRoutingVersion", 2).toString()
    private fun strings(key: String): List<String> = data.optJSONArray(key)?.let { a ->
        (0 until a.length()).map { a.getString(it) }
    } ?: emptyList()

    init {
        require(data.optInt("version") == 1) { "配对版本不兼容" }
        require(computerIp.matches(Regex("10\\.144\\.77\\.[1-9][0-9]{0,2}"))) { "电脑地址不在专用网段" }
        require(computerIp.substringAfterLast('.').toInt() in 1..254) { "电脑地址无效" }
        require(data.getString("networkName").matches(Regex("[A-Za-z0-9_-]{8,64}"))) { "网络名称无效" }
        require(data.getString("networkSecret").length in 32..256) { "配对密钥无效" }
        require(data.getString("proxyPassword").length in 32..256) { "代理凭证无效" }
        require(strings("peers").isNotEmpty() && strings("peers").size <= 8) { "缺少连接入口" }
        require(strings("peers").all { it.matches(Regex("(tcp|udp|wss)://[^\\s\"']{3,240}")) }) { "连接入口无效" }
        require((strings("directDomains") + strings("computerDomains")).all { validDomain(it) }) { "域名规则无效" }
        require(directApps.all { it.matches(Regex("[a-zA-Z][a-zA-Z0-9_.]{2,180}")) }) { "应用包名无效" }
        data.optJSONObject("groups")?.let { groups ->
            groups.keys().forEach { require(groups.getString(it) in listOf("DIRECT", "COMPUTER", "REJECT")) { "分组策略无效" } }
        }
        require(data.toString().length <= 262144) { "配对内容过大" }
    }

    fun config(bundledRules: JSONArray = JSONArray(), physicalAddresses: List<String> = emptyList()): String {
        val overlay = JSONObject().put("name", "LINK").put("type", "easytier")
            .put("network-name", data.getString("networkName"))
            .put("network-secret", data.getString("networkSecret"))
            .put("interface-addresses", JSONArray(physicalAddresses.take(32)))
            .put("ipv4", "10.144.77.2/24").put("peers", JSONArray(DiscoveryPeers.repair(strings("peers"))))
            .put("udp", true).put("enable-encryption", true).put("no-listener", false)
            .put("listeners", JSONArray(listOf("tcp://0.0.0.0:11010", "udp://0.0.0.0:11010")))
        val computer = JSONObject().put("name", "COMPUTER").put("type", "socks5")
            .put("server", computerIp).put("port", 10808).put("username", "phone")
            .put("password", data.getString("proxyPassword")).put("udp", true).put("dialer-proxy", "LINK")
        val rules = mutableListOf("IP-CIDR,10.144.77.0/24,LINK,no-resolve")
        rules += strings("directDomains").map { "DOMAIN-SUFFIX,$it,DIRECT" }
        rules += strings("computerDomains").map { "DOMAIN-SUFFIX,$it,COMPUTER" }
        rules += listOf("IP-CIDR,127.0.0.0/8,DIRECT,no-resolve", "IP-CIDR,192.168.0.0/16,DIRECT,no-resolve", "IP-CIDR,10.0.0.0/8,DIRECT,no-resolve", "IP-CIDR,172.16.0.0/12,DIRECT,no-resolve")
        val providers = JSONObject()
        val selections = data.optJSONObject("groups") ?: JSONObject()
        for (i in 0 until bundledRules.length()) {
            val item = bundledRules.getJSONObject(i)
            val name = item.getString("name")
            val target = selections.optString(item.getString("group"), item.getString("target"))
            require(target in listOf("DIRECT", "COMPUTER", "REJECT"))
            val path = item.getString("path")
            require(path.matches(Regex("rules/[0-9]{2}\\.(mrs|text)")))
            providers.put(name, JSONObject().put("type", "file").put("behavior", item.getString("behavior"))
                .put("format", item.getString("format")).put("path", path))
            rules += "RULE-SET,$name,$target" + if (item.getString("behavior") == "ipcidr") ",no-resolve" else ""
        }
        val inherited = data.optJSONArray("ruleSets") ?: JSONArray()
        require(inherited.length() <= 100) { "规则集数量超限" }
        for (i in 0 until inherited.length()) {
            val item = inherited.getJSONObject(i)
            val name = "inherited-$i"
            val behavior = item.getString("behavior")
            require(behavior in listOf("domain", "ipcidr", "classical"))
            val target = item.getString("target")
            require(target in listOf("DIRECT", "COMPUTER", "REJECT"))
            val payload = item.getJSONArray("payload")
            require(payload.length() <= 200000)
            providers.put(name, JSONObject().put("type", "inline").put("behavior", behavior).put("payload", payload))
            rules += "RULE-SET,$name,$target" + if (behavior == "ipcidr") ",no-resolve" else ""
        }
        rules += "IP-CIDR6,::/0,REJECT,no-resolve"
        rules += "MATCH," + if (data.optString("fallback", "DIRECT") == "COMPUTER") "COMPUTER" else "DIRECT"
        return JSONObject().put("mode", "rule").put("log-level", "silent").put("ipv6", false)
            // Browsers keep DNS entries across VPN/app restarts. Preserve the
            // reverse mapping, and recover host routing from HTTP/TLS/QUIC when
            // a browser uses its own resolver or a previously cached address.
            .put("profile", JSONObject().put("store-fake-ip", true))
            .put("sniffer", JSONObject().put("enable", true).put("force-dns-mapping", true)
                .put("parse-pure-ip", true).put("override-destination", true)
                .put("sniff", JSONObject()
                    .put("HTTP", JSONObject().put("ports", JSONArray(listOf(80, 8080))))
                    .put("TLS", JSONObject().put("ports", JSONArray(listOf(443, 8443))))
                    .put("QUIC", JSONObject().put("ports", JSONArray(listOf(443))))))
            .put("allow-lan", false).put("mixed-port", 0).put("external-controller", "")
            .put("find-process-mode", "always").put("keep-alive-interval", 30)
            .put("proxies", JSONArray(listOf(overlay, computer))).put("rule-providers", providers)
            .put("dns", JSONObject().put("enable", true).put("ipv6", false).put("enhanced-mode", "fake-ip")
                .put("fake-ip-range", "198.18.0.1/16")
                .put("default-nameserver", JSONArray(listOf("223.5.5.5", "119.29.29.29")))
                .put("nameserver", JSONArray(listOf("223.5.5.5", "119.29.29.29")))
                .put("direct-nameserver", JSONArray(listOf("223.5.5.5", "119.29.29.29"))))
            .put("rules", JSONArray(rules)).toString(2).let(::yamlCompatibleJson)
    }

    companion object {
        // Android JSONStringer escapes '/' as '\/', which this core's YAML parser rejects.
        // Consume escape pairs so a literal backslash followed by '/' remains unchanged.
        internal fun yamlCompatibleJson(json: String): String = buildString(json.length) {
            var i = 0
            while (i < json.length) {
                val c = json[i++]
                if (c == '\\' && i < json.length) {
                    val escaped = json[i++]
                    if (escaped != '/') append(c)
                    append(escaped)
                } else append(c)
            }
        }
        fun parse(text: String): Profile { require(text.length <= 262144); return Profile(JSONObject(text)) }
        fun validDomain(domain: String) = domain.length in 3..253 && domain.matches(Regex("[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)+"))
    }
}
