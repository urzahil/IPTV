package rocks.urzahil.iptv

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import rocks.urzahil.iptv.di.AppContainer
import rocks.urzahil.iptv.sync.SyncNotifications

class App : Application() {
    lateinit var container: AppContainer
        private set

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // Only Log.v/d/i are stripped from release builds; keep Media3's own warnings and errors out of logcat too.
        if (!BuildConfig.DEBUG) Log.setLogLevel(Log.LOG_LEVEL_OFF)
        container = AppContainer(this)
        SyncNotifications.createChannel(this)
        container.syncScheduler.schedulePeriodic()
        container.syncScheduler.checkNow()
    }
}
