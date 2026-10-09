package cn.wangchuan.link

internal object DiscoveryPeers {
    private val defaults = listOf("tcp://225284.xyz:11010", "wss://et.vv1234.cn")
    fun repair(peers: List<String>): List<String> = peers.flatMap {
        if (it in listOf("tcp://public.easytier.top:11010", "tcp://public.easytier.cn:11010")) defaults else listOf(it)
    }.distinct()
}
