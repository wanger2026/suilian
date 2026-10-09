package cn.wangchuan.link

class LinkApplication : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        TrafficHistory.initialize(this)
        RemoteDevelopment.installMoonlightObserver()
    }
}
