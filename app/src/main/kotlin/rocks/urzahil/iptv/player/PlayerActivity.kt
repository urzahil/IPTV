package rocks.urzahil.iptv.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import rocks.urzahil.iptv.data.settings.AppSettings
import rocks.urzahil.iptv.player.model.EngineKind
import rocks.urzahil.iptv.player.ui.PlayerScreen
import rocks.urzahil.iptv.ui.appContainer
import rocks.urzahil.iptv.ui.theme.IptvTheme

/**
 * Full-screen landscape player. The activity is only a view on the app-scoped [PlaybackController]; the intent
 * carries ids and times only (never URLs or credentials).
 */
class PlayerActivity : ComponentActivity() {
    private val controller get() = appContainer.playback
    private lateinit var pip: PipController
    private var inPip by mutableStateOf(false)
    private var settings = AppSettings()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        hideSystemBars()

        pip = PipController(this) { controller.togglePlayPause() }
        pip.register()
        handleIntent(intent, savedInstanceState)

        // Settings snapshot for lifecycle decisions (background audio) and the swipe direction.
        lifecycleScope.launch { appContainer.settings.settings.collect { settings = it } }
        // Keep the screen on while something is playing, and keep the PiP parameters current.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                launch {
                    controller.ui.map { it.phase.let { p -> p == Phase.LOADING || p == Phase.BUFFERING || p == Phase.PLAYING || p == Phase.RECONNECTING } }
                        .distinctUntilChanged()
                        .collect { keep ->
                            if (keep) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                }
                launch {
                    controller.ui.map { Triple(it.videoAspect, it.playing, it.phase == Phase.PLAYING || it.phase == Phase.BUFFERING) }
                        .distinctUntilChanged()
                        .collect { (aspect, playing, active) -> pip.update(aspect, playing, autoEnter = playing && active) }
                }
            }
        }
        setContent {
            IptvTheme {
                PlayerScreen(
                    controller = controller,
                    invertSwipe = settings.invertSwipeZap,
                    inPip = inPip,
                    onBack = { finishByUser() },
                    onPip = if (pip.supported) ({ enterPip() }) else null,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, null)
    }

    private fun handleIntent(intent: Intent, saved: Bundle?) {
        // After process death the saved state (channel zapped to, archive position) wins over the launch intent.
        val savedChannel = saved?.getLong(STATE_CHANNEL, -1L) ?: -1L
        val fromSaved = savedChannel >= 0 && controller.currentChannelId == null
        val channelId = if (fromSaved) savedChannel else intent.getLongExtra(EXTRA_CHANNEL_ID, -1L)
        if (channelId < 0) {
            if (controller.currentChannelId == null) finish()
            return
        }
        val category = (if (fromSaved) saved?.getLong(STATE_CATEGORY, -1L) else intent.getLongExtra(EXTRA_CATEGORY_ID, -1L))?.takeIf { it >= 0 }
        var start = intent.getLongExtra(EXTRA_CATCHUP_START, -1L).takeIf { it >= 0 && !fromSaved }
        var end = intent.getLongExtra(EXTRA_CATCHUP_END, -1L).takeIf { it >= 0 && !fromSaved }
        var programme = intent.getLongExtra(EXTRA_CATCHUP_PROGRAMME, -1L).takeIf { it >= 0 && !fromSaved }
        if (fromSaved) start = saved?.getLong(STATE_WALL, -1L)?.takeIf { it >= 0 }
        val wantsCatchup = start != null || programme != null
        // Re-launching the channel that is already playing (e.g. from PiP) keeps it running.
        if (controller.isPlaying(channelId) && !wantsCatchup) return
        controller.play(PlayRequest(channelId, category, start, end, programme))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        controller.currentChannelId?.let { outState.putLong(STATE_CHANNEL, it) }
        controller.currentCategoryId?.let { outState.putLong(STATE_CATEGORY, it) }
        controller.wallPositionMs()?.let { outState.putLong(STATE_WALL, it) }
    }

    override fun onStart() {
        super.onStart()
        controller.onUiStarted()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        PlaybackService.stop(this)
    }

    override fun onPause() {
        super.onPause()
        // Promote to a foreground service while still in the foreground: it is not allowed from the background.
        if (!isFinishing && settings.backgroundAudio && controller.engine.value?.kind == EngineKind.MEDIA3) {
            PlaybackService.start(this)
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing) controller.onUiStopped(keepAudio = settings.backgroundAudio)
    }

    override fun onDestroy() {
        pip.unregister()
        if (isFinishing) controller.stopAll()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // API 31+ enters automatically (auto-enter params); older versions enter here while playing.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && pip.supported && controller.ui.value.let { it.playing && it.phase == Phase.PLAYING }) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (!isInPictureInPictureMode) hideSystemBars()
    }

    private fun enterPip() {
        val ui = controller.ui.value
        pip.enter(ui.videoAspect, ui.playing)
    }

    private fun finishByUser() {
        finish()
    }

    private fun hideSystemBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    companion object {
        const val EXTRA_CHANNEL_ID = "channelId"
        const val EXTRA_CATEGORY_ID = "categoryId"
        const val EXTRA_CATCHUP_PROGRAMME = "catchupProgrammeId"
        const val EXTRA_CATCHUP_START = "catchupStartMs"
        const val EXTRA_CATCHUP_END = "catchupEndMs"
        private const val STATE_CHANNEL = "state.channelId"
        private const val STATE_CATEGORY = "state.categoryId"
        private const val STATE_WALL = "state.wallMs"

        /**
         * Opens the player.
         *
         * - Live: `intent(context, channelId, categoryId)`.
         * - Past programme: pass [catchupProgrammeId], the programme row id from the guide (ProgrammeLite.id /
         *   ProgrammeId.compose). The player resolves its times (tvg-shift applied) and plays it from the start
         *   through catchup. Ignored when it does not belong to the channel or the channel has no catchup
         *   (the channel then plays live and a notice is shown).
         * - [categoryId] is the category the user came from; it defines the order for channel up/down.
         *
         * Ids and times only: never put stream URLs in an intent.
         */
        fun intent(context: Context, channelId: Long, categoryId: Long? = null, catchupProgrammeId: Long? = null): Intent =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_CHANNEL_ID, channelId).apply {
                if (categoryId != null) putExtra(EXTRA_CATEGORY_ID, categoryId)
                if (catchupProgrammeId != null) putExtra(EXTRA_CATCHUP_PROGRAMME, catchupProgrammeId)
            }

        /**
         * Like [intent] for a programme that has no row id (for example one from the Xtream short-EPG fallback):
         * [startMs] and [endMs] are wall-clock epoch milliseconds with the channel's tvg-shift already applied.
         */
        fun intentAt(context: Context, channelId: Long, categoryId: Long?, startMs: Long, endMs: Long): Intent =
            intent(context, channelId, categoryId).putExtra(EXTRA_CATCHUP_START, startMs).putExtra(EXTRA_CATCHUP_END, endMs)
    }
}
