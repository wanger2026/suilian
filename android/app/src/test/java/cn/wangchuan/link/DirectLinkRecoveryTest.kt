package cn.wangchuan.link

import org.junit.Assert.*
import org.junit.Test

class DirectLinkRecoveryTest {
    @Test fun recoversOnlyLostDirectPeerWithGraceAndBackoff() {
        var now = 0L
        val recovery = DirectLinkRecovery { now }
        assertFalse(recovery.shouldRecover(false,true,false))
        now=29000;assertFalse(recovery.shouldRecover(false,true,false))
        now=30000;assertTrue(recovery.shouldRecover(false,true,false))
        now=61000;assertFalse(recovery.shouldRecover(false,true,false))
        now=90000;assertTrue(recovery.shouldRecover(false,true,false))
        now=200000;assertFalse(recovery.shouldRecover(false,true,false))
        now=210000;assertTrue(recovery.shouldRecover(false,true,false))
        assertFalse(recovery.shouldRecover(true,true,true))
        now=220000;assertFalse(recovery.shouldRecover(false,true,false))
        now=250000;assertTrue(recovery.shouldRecover(false,true,false))
    }
    @Test fun keepsTransportWhenInternetOrTopologyIsUnknown() {
        var now=0L;val recovery=DirectLinkRecovery { now }
        assertFalse(recovery.shouldRecover(false,false,false))
        now=100000;assertFalse(recovery.shouldRecover(false,false,false))
        assertFalse(recovery.shouldRecover(false,true,null))
        now=200000;assertFalse(recovery.shouldRecover(false,true,true))
    }
    @Test fun discoveryRouteIsNotMistakenForDirectComputer() {
        val direct="""{"result":{"routes":[{"ipv4":{"address":{"addr":177229057}},"cost":1,"peerId":11,"nextHop":11}]}}"""
        assertEquals(true,OverlayRouteState.directPeer(direct,"10.144.77.1"))
        assertEquals(false,OverlayRouteState.directPeer(direct.replace("\"cost\":1","\"cost\":2"),"10.144.77.1"))
        assertEquals(false,OverlayRouteState.directPeer("""{"result":{"routes":[]}}""","10.144.77.1"))
        assertNull(OverlayRouteState.directPeer("""{"error":"not_ready"}""","10.144.77.1"))
    }
}
