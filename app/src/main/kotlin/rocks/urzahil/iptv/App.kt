package rocks.urzahil.iptv

import android.app.Application
import rocks.urzahil.iptv.di.AppContainer
import rocks.urzahil.iptv.sync.SyncNotifications

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SyncNotifications.createChannel(this)
        container.syncScheduler.schedulePeriodic()
        container.syncScheduler.checkNow()
    }
}
