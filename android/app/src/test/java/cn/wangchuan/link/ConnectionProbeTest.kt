package cn.wangchuan.link

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConnectionProbeTest {
    @Test fun consecutiveChecksUseFreshTransportAfterNetworkChanges() {
        val listener = ServerSocket(0).apply { soTimeout = 2500 }
        val held = java.util.Collections.synchronizedList(mutableListOf<java.net.Socket>())
        val worker = Executors.newSingleThreadExecutor()
        try {
            val accepted = worker.submit<Int> {
                repeat(2) {
                    val socket = listener.accept().also { held.add(it) }
                    socket.soTimeout = 2000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n".toByteArray())
                    // Simulate an old network path that stays half-open. The
                    // next diagnostic must make a new connection, not reuse it.
                }
                held.size
            }
            val url = "http://127.0.0.1:${listener.localPort}/"
            assertTrue(ConnectionProbe.check(url, timeoutMillis = 1000).ok)
            assertTrue(ConnectionProbe.check(url, timeoutMillis = 1000).ok)
            assertEquals(2, accepted.get(3, TimeUnit.SECONDS))
        } finally { listener.close(); held.forEach { it.close() }; worker.shutdownNow() }
    }
    @Test fun blockedDnsCannotLeaveConnectivityCheckRunningForever() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val client = ConnectionProbe.client.newBuilder().dispatcher(okhttp3.Dispatcher()).dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                entered.countDown()
                release.await(3, TimeUnit.SECONDS)
                return listOf(java.net.InetAddress.getByName("127.0.0.1"))
            }
        }).build()
        try {
            val start = System.nanoTime()
            val result = ConnectionProbe.check("http://dns-stall.invalid/", timeoutMillis = 150, transport = client)
            assertEquals(0L, entered.count)
            assertFalse(result.ok)
            assertTrue("DNS stall exceeded caller deadline", TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start) < 1500)
        } finally { release.countDown(); client.dispatcher.executorService.shutdown() }
    }
    @Test fun authenticatedProbeUsesNormalHTTPAndDoesNotFollowRedirects() {
        val listener = ServerSocket(0)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val observed = worker.submit<String> {
                listener.accept().use { client ->
                    client.soTimeout = 3000
                    val reader = client.getInputStream().bufferedReader()
                    val headers = buildString { while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; appendLine(line) } }
                    client.getOutputStream().write("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:1/forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    headers
                }
            }
            val result = ConnectionProbe.check("http://127.0.0.1:${listener.localPort}/manifest.json", "test-fixture")
            assertFalse(result.ok)
            assertEquals("HTTP 302", result.detail)
            assertTrue(observed.get(3, TimeUnit.SECONDS).contains("Authorization: Bearer test-fixture", ignoreCase = true))
        } finally { listener.close(); worker.shutdownNow() }
    }
}
