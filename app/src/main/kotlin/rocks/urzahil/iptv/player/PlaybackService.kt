package rocks.urzahil.iptv.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaStyleNotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import rocks.urzahil.iptv.R
import rocks.urzahil.iptv.ui.appContainer

/**
 * Keeps audio playing (Media3 engine) while the app is in the background, with a media notification.
 *
 * It is a plain foreground service, not a MediaSessionService: the activity starts it in onPause (while the app
 * is still foreground, which Android requires) and stops it in onResume. The engine and its MediaSession live in
 * the app-scoped [PlaybackController]; this service only shows the notification and stops itself when nothing
 * is left to keep alive. It is not exported and has no intent filter. The notification carries the channel and
 * programme names only, never a URL.
 */
@OptIn(UnstableApi::class)
class PlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = appContainer.playback
        when (intent?.action) {
            ACTION_TOGGLE -> controller.togglePlayPause()
            ACTION_STOP -> {
                controller.stopAll()
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }
        }
        ensureChannel()
        // Must be called promptly after startForegroundService.
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, build(controller.ui.value),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
            )
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (job == null) {
            job = scope.launch {
                combine(controller.ui, controller.backgroundHold, controller.uiVisible) { ui, hold, visible -> Triple(ui, hold, visible) }
                    .collect { (ui, hold, visible) ->
                        if (ui.phase == Phase.IDLE || (!hold && !visible)) {
                            stopForegroundAndSelf()
                        } else {
                            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(ui))
                        }
                    }
            }
        }
        return START_NOT_STICKY
    }

    private fun stopForegroundAndSelf() {
        job?.cancel(); job = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away ends playback.
        appContainer.playback.stopAll()
        stopForegroundAndSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        }
    }

    private fun build(ui: PlayerUiState): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun action(code: Int, a: String) = PendingIntent.getService(
            this, code, Intent(this, PlaybackService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_player_play)
            .setContentTitle(ui.channel?.name ?: "IPTV")
            .setContentText(ui.timeline?.window?.programme?.title)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(ui.playing)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                if (ui.playing) R.drawable.ic_player_pause else R.drawable.ic_player_play,
                if (ui.playing) "Pause" else "Play", action(1, ACTION_TOGGLE),
            )
            .addAction(R.drawable.ic_player_stop, "Stop", action(2, ACTION_STOP))
        appContainer.playback.engine.value?.mediaSession?.let {
            b.setStyle(MediaStyleNotificationHelper.MediaStyle(it).setShowActionsInCompactView(0, 1))
        }
        return b.build()
    }

    companion object {
        const val CHANNEL_ID = "playback"
        const val NOTIFICATION_ID = 42
        const val ACTION_TOGGLE = "rocks.urzahil.iptv.player.TOGGLE"
        const val ACTION_STOP = "rocks.urzahil.iptv.player.STOP"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
            } catch (_: Exception) {
                // Not allowed right now (restricted background state): playback just stops with the screen.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
