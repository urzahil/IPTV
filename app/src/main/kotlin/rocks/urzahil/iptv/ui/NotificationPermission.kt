package rocks.urzahil.iptv.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.map

/**
 * Notifications are only used for the progress of long imports and for background playback, so the Android 13+
 * permission is requested when one of those first becomes relevant instead of at launch. A refusal needs no
 * handling: imports and playback run the same without a visible notification, and the system stops showing the
 * dialog after the user has declined it.
 */
object NotificationPermission {
    private const val PREFS = "ui"
    private const val KEY_ASKED = "notificationsAsked"

    fun needed(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    fun askedBefore(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)

    fun markAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).apply()
    }
}

/** Returns a function that shows the system dialog if the permission is still missing. */
@Composable
fun rememberNotificationPermissionRequest(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    return remember(context, launcher) {
        {
            if (NotificationPermission.needed(context)) {
                NotificationPermission.markAsked(context)
                try {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } catch (e: RuntimeException) {
                    // No activity can show the dialog (or a request is already running): carry on without it.
                }
            }
        }
    }
}

/** Asks once, the first time a source exists: from then on imports run and show their progress notification. */
@Composable
fun AskForNotificationsWithFirstSource() {
    val context = LocalContext.current
    if (!NotificationPermission.needed(context) || NotificationPermission.askedBefore(context)) return
    val request = rememberNotificationPermissionRequest()
    val hasSources by remember { context.appContainer.sourceRepository.sources.map { it.isNotEmpty() } }.collectAsState(false)
    LaunchedEffect(hasSources) { if (hasSources) request() }
}
