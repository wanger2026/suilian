package cn.wangchuan.link

class RemoteLease(private val now: () -> Long) {
    private var deadline = 0L
    private var consent = false
    @Synchronized fun heartbeat(ready: Boolean) {
        if (!ready) { deadline = 0; consent = false }
        else { if (now() >= deadline) consent = false; deadline = now() + 12000 }
    }
    @Synchronized fun available(): Boolean = deadline != 0L && now() < deadline
    @Synchronized fun grant(): Boolean { consent = available(); return consent }
    @Synchronized fun allowed(): Boolean = consent && available()
    @Synchronized fun revoke() { consent = false }
}
