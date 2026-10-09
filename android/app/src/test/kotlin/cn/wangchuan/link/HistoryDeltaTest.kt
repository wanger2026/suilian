package cn.wangchuan.link
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
class HistoryDeltaTest {
    @Test fun idleConnectionsDoNotWriteEveryPollButRemainAliveAcrossPruning(){
        val idle=HistoryDelta.between(100L to 300L,100,300)
        assertFalse(HistoryDelta.needsCheckpoint(idle,1000,4000))
        assertTrue(HistoryDelta.needsCheckpoint(idle,1000,301000))
        assertTrue(HistoryDelta.needsCheckpoint(idle,1000,500))
        assertTrue(HistoryDelta.needsCheckpoint(HistoryDelta.between(100L to 300L,101,300),1000,1001))
        assertTrue(HistoryDelta.needsCheckpoint(HistoryDelta.between(null,0,0),1000,1001))
    }
    @Test fun repeatedSnapshotsAndRestartDoNotDoubleCount(){
        val first=HistoryDelta.between(null,100,300)
        val repeated=HistoryDelta.between(100L to 300L,100,300)
        val next=HistoryDelta.between(100L to 300L,140,400)
        assertEquals(140L,first.up+repeated.up+next.up)
        assertEquals(400L,first.down+repeated.down+next.down)
        assertEquals(1L,first.count+repeated.count+next.count)
    }
    @Test fun resetCannotSubtractPreviouslyRecordedTraffic(){
        val reset=HistoryDelta.between(100L to 200L,5,8)
        assertEquals(5L,reset.up);assertEquals(8L,reset.down);assertEquals(0L,reset.count)
    }
}
