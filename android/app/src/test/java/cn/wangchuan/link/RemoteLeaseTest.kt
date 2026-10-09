package cn.wangchuan.link

import org.junit.Assert.*
import org.junit.Test

class RemoteLeaseTest {
    @Test fun vpnOrIdleUiCannotGrantDevelopment() {
        val lease = RemoteLease { 1000L }
        assertFalse(lease.grant())
        assertFalse(lease.allowed())
    }
    @Test fun remoteSessionNeedsExplicitConsentAndDisconnectRevokesIt() {
        var time = 1000L
        val lease = RemoteLease { time }
        lease.heartbeat(true)
        assertFalse(lease.allowed())
        assertTrue(lease.grant())
        time += 4000; lease.heartbeat(true)
        assertTrue(lease.allowed())
        lease.heartbeat(false)
        assertFalse(lease.allowed())
        lease.heartbeat(true)
        assertFalse(lease.allowed())
    }
    @Test fun missedHeartbeatsCannotKeepOrRestoreConsent() {
        var time = 1000L
        val lease = RemoteLease { time }
        lease.heartbeat(true); lease.grant()
        time += 12000
        assertFalse(lease.allowed())
        lease.heartbeat(true)
        assertFalse(lease.allowed())
        lease.grant(); lease.revoke()
        assertFalse(lease.allowed())
    }
}
