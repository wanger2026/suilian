package cn.wangchuan.link

object HistoryDelta {
    data class Delta(val up:Long,val down:Long,val count:Long)
    fun needsCheckpoint(delta:Delta,lastSeen:Long,now:Long):Boolean =
        delta.up!=0L || delta.down!=0L || delta.count!=0L || now-lastSeen>=300000L || now<lastSeen
    fun between(previous:Pair<Long,Long>?,up:Long,down:Long):Delta = Delta(
        if(previous==null||up<previous.first)up.coerceAtLeast(0) else up-previous.first,
        if(previous==null||down<previous.second)down.coerceAtLeast(0) else down-previous.second,
        if(previous==null)1 else 0)
}
