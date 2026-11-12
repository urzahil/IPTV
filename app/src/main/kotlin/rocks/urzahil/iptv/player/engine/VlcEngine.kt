package rocks.urzahil.iptv.player.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import rocks.urzahil.iptv.core.model.StreamHeaders
import rocks.urzahil.iptv.player.model.AspectMode
import rocks.urzahil.iptv.player.model.EngineKind
import rocks.urzahil.iptv.player.model.EngineState
import rocks.urzahil.iptv.player.model.FailureKind
import rocks.urzahil.iptv.player.model.PlayerFailure
import rocks.urzahil.iptv.player.model.StreamRequest
import rocks.urzahil.iptv.player.model.TrackOption
import rocks.urzahil.iptv.player.model.TrackSet
import rocks.urzahil.iptv.player.model.VideoSize
import rocks.urzahil.iptv.util.AppLog

/**
 * libVLC fallback engine: software video decoding (MPEG-2 ...), deinterlacing, odd containers and the schemes
 * Media3 cannot open (rtp, mms, srt). The constructor throws when the native libraries cannot be loaded
 * (the controller then stays on Media3).
 *
 * libVLC 3 can only send User-Agent and Referer; other custom headers are not supported (the engine selector
 * keeps such channels on Media3 in automatic mode).
 */
class VlcEngine(private val context: Context, config: EngineConfig) : PlayerEngine {
    override val kind: EngineKind = EngineKind.VLC

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    override val state: StateFlow<EngineState> = _state.asStateFlow()
    private val _playing = MutableStateFlow(false)
    override val playing: StateFlow<Boolean> = _playing.asStateFlow()
    private val _tracks = MutableStateFlow(TrackSet.EMPTY)
    override val tracks: StateFlow<TrackSet> = _tracks.asStateFlow()
    private val _videoSize = MutableStateFlow<VideoSize?>(null)
    override val videoSize: StateFlow<VideoSize?> = _videoSize.asStateFlow()
    private val aspect = MutableStateFlow(AspectMode.FIT)

    private val libVlc: LibVLC
    private val player: MediaPlayer
    private var layout: VLCVideoLayout? = null
    private var released = false
    private var live = true
    private val focus = AudioFocusHelper(context, onPause = { pause() }, onResume = { play() }, onDuck = { duck -> setDuck(duck) })
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (i.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause() }
    }

    init {
        val options = arrayListOf(
            // libVLC logs the full MRL (user and password of an Xtream URL included) to logcat when an open fails,
            // and that native log bypasses the app's redaction. Silence it, in debug builds too.
            "--verbose=-1",
            "--http-reconnect",
            "--audio-time-stretch",
            "--no-stats",
            "--no-sub-autodetect-file",
            "--deinterlace=${config.deinterlace.vlcValue}",
            "--deinterlace-mode=yadif",
        )
        libVlc = LibVLC(context, options)
        player = MediaPlayer(libVlc).apply { setEventListener { onEvent(it) } }
        androidx.core.content.ContextCompat.registerReceiver(
            context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun prepare(request: StreamRequest, startPositionMs: Long) {
        if (released) return
        live = request.live
        _state.value = EngineState.Buffering
        _tracks.value = TrackSet.EMPTY
        _videoSize.value = null
        val media = Media(libVlc, Uri.parse(request.url))
        media.setHWDecoderEnabled(true, false)
        media.addOption(":network-caching=${if (request.live) LIVE_CACHING_MS else CATCHUP_CACHING_MS}")
        // Values come from playlists: printable ASCII only, so no option can be smuggled in with a line break.
        request.userAgent?.let(StreamHeaders::sanitizeValue)?.takeIf { it.isNotEmpty() }?.let { media.addOption(":http-user-agent=$it") }
        request.referrer?.let(StreamHeaders::sanitizeValue)?.takeIf { it.isNotEmpty() }?.let { media.addOption(":http-referrer=$it") }
        if (startPositionMs > 0) media.addOption(":start-time=${startPositionMs / 1000.0}")
        player.media = media
        media.release()
        focus.request()
        _playing.value = true
        player.play()
    }

    private fun onEvent(e: MediaPlayer.Event) {
        if (released) return
        when (e.type) {
            MediaPlayer.Event.Opening -> _state.value = EngineState.Buffering
            MediaPlayer.Event.Buffering -> {
                // Only the start/stop of buffering matters; 100% means enough data.
                if (e.buffering < 100f) {
                    if (_state.value != EngineState.Ended) _state.value = EngineState.Buffering
                } else if (_state.value == EngineState.Buffering) _state.value = EngineState.Ready
            }
            MediaPlayer.Event.Playing -> { _state.value = EngineState.Ready; refreshTracks() }
            MediaPlayer.Event.Paused -> Unit
            MediaPlayer.Event.EndReached -> _state.value = EngineState.Ended
            MediaPlayer.Event.EncounteredError ->
                _state.value = EngineState.Error(PlayerFailure(FailureKind.OPEN_FAILED, message = "libVLC could not play the stream"))
            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> refreshTracks()
            MediaPlayer.Event.Vout -> updateVideoSize()
        }
    }

    private fun updateVideoSize() {
        val t = player.currentVideoTrack ?: return
        if (t.width > 0 && t.height > 0) {
            val sar = if (t.sarNum > 0 && t.sarDen > 0) t.sarNum.toFloat() / t.sarDen else 1f
            _videoSize.value = VideoSize(t.width, t.height, sar)
        }
    }

    private fun refreshTracks() {
        if (released) return
        try {
            val audioSel = player.audioTrack
            val spuSel = player.spuTrack
            val audio = player.audioTracks.orEmpty().filter { it.id >= 0 }.map { TrackOption(it.id.toString(), it.name, null, null, it.id == audioSel) }
            val text = player.spuTracks.orEmpty().filter { it.id >= 0 }.map { TrackOption(it.id.toString(), it.name, null, null, it.id == spuSel) }
            _tracks.value = TrackSet(audio = audio, text = text, textOff = spuSel < 0)
            updateVideoSize()
        } catch (t: Throwable) {
            AppLog.w("Vlc", "track query failed", t)
        }
    }

    override fun play() { if (!released) { focus.request(); player.play(); _playing.value = true } }
    override fun pause() { if (!released) { player.pause(); _playing.value = false } }
    override fun seekTo(positionMs: Long) { if (!released) player.time = positionMs.coerceAtLeast(0) }
    override fun positionMs(): Long = if (released) 0 else player.time.coerceAtLeast(0)
    override fun durationMs(): Long? = if (released) null else player.length.takeIf { it > 0 }
    override fun isSeekable(): Boolean = !released && player.isSeekable

    override fun stop() {
        if (released) return
        player.stop()
        _state.value = EngineState.Idle
        _playing.value = false
    }

    override fun release() {
        if (released) return
        released = true
        focus.abandon()
        try { context.unregisterReceiver(noisy) } catch (_: IllegalArgumentException) { }
        player.setEventListener(null)
        // Detach the views first (main thread), then free native objects off the main thread: stopping a
        // network stream can block.
        try { player.detachViews() } catch (_: Throwable) { }
        layout = null
        val p = player
        val l = libVlc
        Thread({
            try { p.stop() } catch (_: Throwable) { }
            try { p.release() } catch (_: Throwable) { }
            try { l.release() } catch (_: Throwable) { }
        }, "vlc-release").start()
        _state.value = EngineState.Idle
    }

    override fun selectAudio(id: String?) { if (!released) id?.toIntOrNull()?.let { player.audioTrack = it }.also { refreshTracks() } }
    override fun selectText(id: String?) {
        if (released) return
        player.spuTrack = id?.toIntOrNull() ?: -1
        refreshTracks()
    }
    override fun selectVideo(id: String?) = Unit // no adaptive quality in libVLC's API
    override fun setLanguages(audio: String, text: String) = Unit

    override fun setAspect(mode: AspectMode) {
        aspect.value = mode
        if (!released) applyScale(mode)
    }

    private fun applyScale(mode: AspectMode) {
        player.videoScale = when (mode) {
            AspectMode.FIT -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
            AspectMode.FILL -> MediaPlayer.ScaleType.SURFACE_FILL
            AspectMode.ZOOM -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
        }
    }

    private fun setDuck(duck: Boolean) { if (!released) player.volume = if (duck) 30 else 100 }

    @Composable
    override fun Surface(modifier: Modifier) {
        val mode by aspect.collectAsState()
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                VLCVideoLayout(ctx).also { v ->
                    if (!released) {
                        // enableSubtitles = true so DVB subtitles and teletext can render.
                        player.attachViews(v, null, true, false)
                        layout = v
                        applyScale(aspect.value)
                    }
                }
            },
            update = { if (!released) applyScale(mode) },
            onRelease = { v -> if (!released && layout === v) { try { player.detachViews() } catch (_: Throwable) { }; layout = null } },
        )
    }

    private companion object {
        const val LIVE_CACHING_MS = 1500
        const val CATCHUP_CACHING_MS = 3000
    }
}

/** Audio focus for libVLC (Media3 handles its own). */
internal class AudioFocusHelper(
    context: Context,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
    private val onDuck: (Boolean) -> Unit,
) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var wasPlaying = false
    private var request: AudioFocusRequest? = null

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> onPause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { wasPlaying = true; onPause() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onDuck(true)
            AudioManager.AUDIOFOCUS_GAIN -> { onDuck(false); if (wasPlaying) { wasPlaying = false; onResume() } }
        }
    }

    @Suppress("DEPRECATION")
    fun request() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val r = request ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setOnAudioFocusChangeListener(listener)
                .build().also { request = it }
            am.requestAudioFocus(r)
        } else {
            am.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    @Suppress("DEPRECATION")
    fun abandon() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) request?.let { am.abandonAudioFocusRequest(it) }
        else am.abandonAudioFocus(listener)
    }
}
