package rocks.urzahil.iptv.player

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.core.content.ContextCompat
import rocks.urzahil.iptv.R

/**
 * Picture-in-picture for phones and tablets: parameters follow the video size and play state, API 31+ enters
 * automatically when the user leaves while playing, older versions enter from onUserLeaveHint.
 */
// Every PiP call is guarded by [supported], which includes the API 26 check.
@android.annotation.SuppressLint("NewApi")
class PipController(private val activity: Activity, private val onTogglePlay: () -> Unit) {
    val supported: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_TOGGLE) onTogglePlay()
        }
    }
    private var registered = false

    fun register() {
        if (!supported || registered) return
        // Not exported: only this app can send the remote-action broadcast.
        ContextCompat.registerReceiver(activity, receiver, IntentFilter(ACTION_TOGGLE), ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
    }

    fun unregister() {
        if (!registered) return
        try { activity.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) { }
        registered = false
    }

    /** Aspect ratio is clamped to what the system accepts (1:2.39 .. 2.39:1). */
    fun params(videoAspect: Float?, playing: Boolean, autoEnter: Boolean): PictureInPictureParams? {
        if (!supported) return null
        val a = (videoAspect ?: 16f / 9f).coerceIn(1f / 2.39f, 2.39f)
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational((a * 1000).toInt(), 1000))
        val pi = PendingIntent.getBroadcast(
            activity, 0, Intent(ACTION_TOGGLE).setPackage(activity.packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val label = if (playing) "Pause" else "Play"
        val icon = Icon.createWithResource(activity, if (playing) R.drawable.ic_player_pause else R.drawable.ic_player_play)
        b.setActions(listOf(RemoteAction(icon, label, label, pi)))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setAutoEnterEnabled(autoEnter)
            b.setSeamlessResizeEnabled(false)
        }
        return b.build()
    }

    fun update(videoAspect: Float?, playing: Boolean, autoEnter: Boolean) {
        val p = params(videoAspect, playing, autoEnter) ?: return
        try { activity.setPictureInPictureParams(p) } catch (_: IllegalStateException) { } catch (_: IllegalArgumentException) { }
    }

    fun enter(videoAspect: Float?, playing: Boolean): Boolean {
        val p = params(videoAspect, playing, true) ?: return false
        return try { activity.enterPictureInPictureMode(p) } catch (_: IllegalStateException) { false } catch (_: IllegalArgumentException) { false }
    }

    companion object {
        const val ACTION_TOGGLE = "rocks.urzahil.iptv.player.PIP_TOGGLE"
    }
}
