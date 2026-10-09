package cn.wangchuan.link

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProfileTest {
    @Test fun browserDnsSurvivesRestartAndIndependentResolversKeepHostRules() {
        val config = JSONObject(Profile(pairing()).config())
        assertTrue(config.getJSONObject("profile").getBoolean("store-fake-ip"))
        val sniff = config.getJSONObject("sniffer")
        assertTrue(sniff.getBoolean("enable"))
        assertTrue(sniff.getBoolean("parse-pure-ip"))
        assertTrue(sniff.getBoolean("override-destination"))
        assertTrue(sniff.getJSONObject("sniff").has("HTTP"))
        assertTrue(sniff.getJSONObject("sniff").has("TLS"))
        assertTrue(sniff.getJSONObject("sniff").has("QUIC"))
    }
    private fun pairing(): JSONObject = JSONObject()
        .put("version", 1).put("computerIp", "10.144.77.1")
        .put("networkName", "unit-test-network")
        .put("networkSecret", "0".repeat(40)).put("proxyPassword", "1".repeat(40))
        .put("peers", org.json.JSONArray(listOf("tcp://192.0.2.1:11010")))
        .put("directDomains", org.json.JSONArray(listOf("example.cn")))
        .put("computerDomains", org.json.JSONArray(listOf("example.com")))

    @Test fun directAndComputerRoutesAreIndependent() {
        val config = JSONObject(Profile(pairing()).config())
        val rules = config.getJSONArray("rules")
        assertEquals("IP-CIDR,10.144.77.0/24,LINK,no-resolve", rules.getString(0))
        assertEquals("DOMAIN-SUFFIX,example.cn,DIRECT", rules.getString(1))
        assertEquals("DOMAIN-SUFFIX,example.com,COMPUTER", rules.getString(2))
        assertEquals("MATCH,DIRECT", rules.getString(rules.length()-1))
        val proxy = config.getJSONArray("proxies").getJSONObject(1)
        assertEquals("LINK", proxy.getString("dialer-proxy"))
        assertTrue(proxy.getBoolean("udp"))
    }
    @Test fun loopbackControllerAndLanProxyAreNotExposed() {
        val config = JSONObject(Profile(pairing()).config())
        assertFalse(config.getBoolean("allow-lan"))
        assertEquals(0, config.getInt("mixed-port"))
        assertEquals("", config.getString("external-controller"))
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectRuleInjection() { Profile(pairing().put("computerDomains", org.json.JSONArray(listOf("example.com,DIRECT")))) }
    @Test(expected = IllegalArgumentException::class)
    fun rejectArbitraryProxyTarget() { Profile(pairing().put("computerIp", "127.0.0.1")) }
    @Test fun ipv6CannotSilentlyEscapeProxyPolicy() {
        val config = JSONObject(Profile(pairing()).config())
        assertFalse(config.getJSONObject("dns").getBoolean("ipv6"))
        assertTrue(config.getJSONArray("rules").toString().contains("IP-CIDR6,::/0,REJECT,no-resolve"))
    }
    @Test fun groupOverridesPreserveRulesAndExportNativeFixture() {
        val asset = java.io.File("src/main/assets/network-rules")
        val manifest = JSONObject(java.io.File(asset, "manifest.json").readText())
        val providers = manifest.getJSONArray("providers")
        assertTrue(providers.length() in 1..100)
        val group = providers.getJSONObject(0).getString("group")
        val config = Profile(pairing().put("groups", JSONObject().put(group, "REJECT"))).config(providers)
        assertTrue(config.contains("RULE-SET," + providers.getJSONObject(0).getString("name") + ",REJECT"))
        val output = java.io.File("build/native-config-test").apply { mkdirs() }
        for (i in 0 until providers.length()) {
            val item = providers.getJSONObject(i)
            val file = java.io.File(output, item.getString("path"))
            file.parentFile.mkdirs()
            java.io.File(asset, item.getString("asset")).copyTo(file, overwrite = true)
        }
        // Only non-secret fixtures from pairing() are exported for the real native parser check.
        // Model Android JSONStringer's solidus escaping; desktop org.json omits it.
        val androidJson = config.replace("/", "\\/")
        java.io.File(output, "android-escaped.json").writeText(androidJson)
        java.io.File(output, "config.yaml").writeText(Profile.yamlCompatibleJson(androidJson))
    }
    @Test fun androidSlashEscapesAreRemovedWithoutChangingStringValues() {
        val androidJson = """{"cidr":"10.144.77.2\/24","peer":"tcp:\/\/example.com","literal":"\\\/","quote":"\"","control":"\n\t\u0001"}"""
        val normalized = Profile.yamlCompatibleJson(androidJson)
        val original = JSONObject(androidJson)
        val decoded = JSONObject(normalized)
        for (key in original.keys()) assertEquals(key, original.getString(key), decoded.getString(key))
        assertTrue(normalized.contains("10.144.77.2/24"))
        assertTrue(normalized.contains("tcp://example.com"))
        assertEquals(normalized, Profile.yamlCompatibleJson(normalized))
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectUnknownGroupTarget() { Profile(pairing().put("groups", JSONObject().put("Google", "arbitrary-node"))) }

    @Test fun domesticCompatibilityMigratesOldProfileWithoutExcludingX() {
        val original = pairing().put("directApps", org.json.JSONArray(listOf("com.example.local")))
        val profile = Profile(original)
        assertEquals(listOf("com.example.local", "com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.lite"), profile.directApps)
        assertFalse(profile.directApps.contains("com.twitter.android"))
        assertFalse(original.has("appRoutingVersion"))
    }

    @Test fun appPickerPreservesCredentialsAndCustomRulesAndSupportsOptOut() {
        val original = pairing().put("groups", JSONObject().put("Google", "COMPUTER"))
            .put("ruleSets", org.json.JSONArray()).put("fallback", "DIRECT")
        val initial = Profile(original)
        val saved = initial.withDirectApps(listOf("com.example.local", "com.example.local"))
        val decoded = JSONObject(saved)
        for (key in listOf("networkSecret", "proxyPassword", "computerIp", "networkName", "fallback"))
            assertEquals(original.getString(key), decoded.getString(key))
        assertEquals(original.getJSONObject("groups").toString(), decoded.getJSONObject("groups").toString())
        assertEquals(listOf("com.example.local"), Profile.parse(saved).directApps)
        assertEquals(initial.config(), Profile.parse(saved).config())
        assertTrue(Profile.parse(Profile.parse(saved).withDirectApps(emptyList())).directApps.isEmpty())
    }

    @Test fun liteMigrationCompletesOldDouyinSelectionButRespectsLaterOptOut() {
        val old = pairing().put("appRoutingVersion", 1)
            .put("directApps", org.json.JSONArray(listOf("com.ss.android.ugc.aweme")))
        assertTrue(Profile(old).directApps.contains("com.ss.android.ugc.aweme.lite"))
        val saved = Profile(old).withDirectApps(listOf("com.ss.android.ugc.aweme"))
        assertEquals(listOf("com.ss.android.ugc.aweme"), Profile.parse(saved).directApps)
        old.put("directApps", org.json.JSONArray())
        assertTrue(Profile(old).directApps.isEmpty())
    }

    @Test fun advancedRulesSaveDoesNotReAddDeselectedApps() {
        val profile = Profile(pairing())
        val saved = profile.edited(profile.directDomains, profile.computerDomains, emptyList(), JSONObject())
        assertTrue(Profile.parse(saved).directApps.isEmpty())
        assertEquals(profile.directDomains, Profile.parse(saved).directDomains)
    }
}
