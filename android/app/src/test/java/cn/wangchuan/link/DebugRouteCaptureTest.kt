package cn.wangchuan.link

import org.junit.Assert.assertEquals
import org.junit.Test

class DebugRouteCaptureTest {
    @Test fun decodesNativeBatchedEventsWithoutDroppingLogs() {
        val events = DebugRouteCapture.events("""{"method":"message","arguments":[{"type":"loaded","data":{}},{"type":"log","data":{"LogLevel":"info","Payload":"[TCP] fixture --> mail.google.com:443 match COMPUTER"}}]}""")
        assertEquals(2, events.size)
        assertEquals("log", events[1].getString("type"))
        assertEquals("info", events[1].getJSONObject("data").getString("LogLevel"))
    }
}
