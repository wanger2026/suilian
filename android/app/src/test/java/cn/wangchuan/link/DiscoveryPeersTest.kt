package cn.wangchuan.link

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoveryPeersTest {
    @Test fun repairsLegacyDiscoveryWithoutLosingPrivateEntry() {
        val original = listOf("tcp://private.example:11010", "tcp://public.easytier.top:11010", "tcp://public.easytier.cn:11010")
        assertEquals(listOf("tcp://private.example:11010", "tcp://225284.xyz:11010", "wss://et.vv1234.cn"), DiscoveryPeers.repair(original))
    }
}
