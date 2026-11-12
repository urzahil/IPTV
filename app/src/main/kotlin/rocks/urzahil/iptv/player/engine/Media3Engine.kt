package rocks.urzahil.iptv.player.engine

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import rocks.urzahil.iptv.player.PlayerActivity
import rocks.urzahil.iptv.player.model.AspectMode
import rocks.urzahil.iptv.player.model.BufferProfile
import rocks.urzahil.iptv.player.model.EngineKind
import rocks.urzahil.iptv.player.model.EngineState
import rocks.urzahil.iptv.player.model.PlayerFailure
import rocks.urzahil.iptv.player.model.StreamRequest
import rocks.urzahil.iptv.player.model.TrackGroupInfo
import rocks.urzahil.iptv.player.model.TrackOption
import rocks.urzahil.iptv.player.model.TrackSet
import rocks.urzahil.iptv.player.model.TrackType
import rocks.urzahil.iptv.player.model.VideoSize
import rocks.urzahil.iptv.util.AppLog
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * ExoPlayer with platform (hardware) decoders first and the FFmpeg extension as software fallback for audio
 * (AC3, E-AC3, DTS, MP2, ...). Video is never decoded in software: MPEG-2 on devices without a decoder is the
 * job of the VLC fallback.
 */
@OptIn(UnstableApi::class)
class Media3Engine(
    private val context: Context,
    private val okHttp: OkHttpClient,
    options: EngineConfig,
) : PlayerEngine {
    override val kind: EngineKind = EngineKind.MEDIA3

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    override val state: StateFlow<EngineState> = _state.asStateFlow()
    private val _playing = MutableStateFlow(false)
    override val playing: StateFlow<Boolean> = _playing.asStateFlow()
    private val _tracks = MutableStateFlow(TrackSet.EMPTY)
    override val tracks: StateFlow<TrackSet> = _tracks.asStateFlow()
    private val _videoSize = MutableStateFlow<VideoSize?>(null)
    override val videoSize: StateFlow<VideoSize?> = _videoSize.asStateFlow()
    private val aspect = MutableStateFlow(AspectMode.FIT)

    private val player: ExoPlayer
    private var lastTracks: Tracks = Tracks.EMPTY
    private var released = false
    private val views = mutableSetOf<PlayerView>()

    init {
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParameters: Boolean): AudioSink? =
                if (options.passthrough) {
                    super.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParameters)
                } else {
                    // PCM only: AC3/DTS are decoded (by the platform or FFmpeg) instead of passed to the sink.
                    DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParameters)
                        .setAudioCapabilities(androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                        .build()
                }
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        val b = options.buffer
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(b.minBufferMs, b.maxBufferMs, b.playbackMs, b.rebufferMs)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        player = ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .setTrackSelector(DefaultTrackSelector(context))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setLooper(Looper.getMainLooper())
            .build()
        setLanguages(options.audioLanguage, options.textLanguage)
        player.addListener(Listener())
        if (rocks.urzahil.iptv.BuildConfig.DEBUG) {
            player.addAnalyticsListener(androidx.media3.exoplayer.util.EventLogger())
        }
    }

    /** Only created when the engine is the Media3 one: media keys and the background notification use it. */
    override val mediaSession: MediaSession = MediaSession.Builder(context, player)
        .setId("live-${SESSION_COUNTER.incrementAndGet()}")
        .setCallback(RestrictedSessionCallback())
        .setSessionActivity(
            PendingIntent.getActivity(
                context, 0, Intent(context, PlayerActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    override fun setLanguages(audio: String, text: String) {
        if (released) return
        val lang = audio.ifBlank { Locale.getDefault().language }
        val params = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(lang)
            .setPreferredTextLanguage(text.ifBlank { null })
            .build()
        player.trackSelectionParameters = params
    }

    override fun prepare(request: StreamRequest, startPositionMs: Long) {
        if (released) return
        _state.value = EngineState.Buffering
        _tracks.value = TrackSet.EMPTY
        _videoSize.value = null
        // MediaSession invariant: the stream URL (it can carry Xtream credentials) must never reach the session
        // metadata that other apps can read. So never call setRequestMetadata(mediaUri = ...) and never use a URL
        // as the mediaId; only the title and artist below are exposed.
        val item = MediaItem.Builder()
            .setUri(request.url)
            .setMimeType(mimeFor(request))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(request.title).setArtist(request.subtitle).build())
            .build()
        // Clear overrides from the previous stream; the preferred languages stay.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverrides().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
        player.setMediaSource(createSource(item, request), if (startPositionMs > 0) startPositionMs else C.TIME_UNSET)
        player.playWhenReady = true
        player.prepare()
    }

    private fun mimeFor(r: StreamRequest): String? = when (r.mime?.lowercase()) {
        null, "" -> if (r.scheme == "udp") MimeTypes.VIDEO_MP2T else null
        "hls" -> MimeTypes.APPLICATION_M3U8
        "dash" -> MimeTypes.APPLICATION_MPD
        else -> r.mime
    }

    private fun createSource(item: MediaItem, r: StreamRequest): MediaSource {
        val http = OkHttpDataSource.Factory(okHttp).apply {
            r.userAgent?.let { setUserAgent(it) }
            // User-Agent goes through setUserAgent; everything else (Referer, Cookie, ...) as default properties.
            setDefaultRequestProperties(r.headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) })
        }
        val dataSource: DataSource.Factory = DefaultDataSource.Factory(context, http)
        val policy = DefaultLoadErrorHandlingPolicy(6)
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
                    DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS,
            )
            .setTsExtractorTimestampSearchBytes(1500 * 188)
            .setConstantBitrateSeekingEnabled(true)
        return when {
            item.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8 ->
                HlsMediaSource.Factory(dataSource).setAllowChunklessPreparation(true).setLoadErrorHandlingPolicy(policy).createMediaSource(item)
            item.localConfiguration?.mimeType == MimeTypes.APPLICATION_MPD ->
                DashMediaSource.Factory(dataSource).setLoadErrorHandlingPolicy(policy).createMediaSource(item)
            r.scheme == "rtsp" ->
                RtspMediaSource.Factory().apply { r.userAgent?.let { setUserAgent(it) } }.createMediaSource(item)
            else -> DefaultMediaSourceFactory(dataSource, extractors).setLoadErrorHandlingPolicy(policy).createMediaSource(item)
        }
    }

    override fun play() { if (!released) player.play() }
    override fun pause() { if (!released) player.pause() }
    override fun seekTo(positionMs: Long) { if (!released) player.seekTo(positionMs.coerceAtLeast(0)) }
    override fun positionMs(): Long = if (released) 0 else player.currentPosition.coerceAtLeast(0)
    override fun durationMs(): Long? = if (released) null else player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
    override fun isSeekable(): Boolean = !released && player.isCurrentMediaItemSeekable

    override fun stop() {
        if (released) return
        player.stop()
        player.clearMediaItems()
        _state.value = EngineState.Idle
    }

    override fun release() {
        if (released) return
        released = true
        // The Compose view may outlive the engine by a frame: detach it first so it never touches a dead player.
        views.toList().forEach { it.player = null }
        views.clear()
        mediaSession.release()
        player.release()
        _state.value = EngineState.Idle
    }

    // ---- tracks ----

    override fun selectAudio(id: String?) = select(C.TRACK_TYPE_AUDIO, id)
    override fun selectVideo(id: String?) = select(C.TRACK_TYPE_VIDEO, id)

    override fun selectText(id: String?) {
        if (released) return
        if (id == null) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        } else {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
            select(C.TRACK_TYPE_TEXT, id)
        }
    }

    private fun select(type: Int, id: String?) {
        if (released) return
        val b = player.trackSelectionParameters.buildUpon()
        if (id == null) {
            b.clearOverridesOfType(type)
        } else {
            val (g, i) = parseId(id) ?: return
            val group = lastTracks.groups.filter { it.type == type }.getOrNull(g) ?: return
            b.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
        }
        player.trackSelectionParameters = b.build()
    }

    private fun parseId(id: String): Pair<Int, Int>? {
        val p = id.split(':')
        if (p.size != 2) return null
        return (p[0].toIntOrNull() ?: return null) to (p[1].toIntOrNull() ?: return null)
    }

    private fun buildTrackSet(tracks: Tracks): TrackSet {
        val audio = ArrayList<TrackOption>()
        val text = ArrayList<TrackOption>()
        val video = ArrayList<TrackOption>()
        val infos = ArrayList<TrackGroupInfo>()
        val perType = HashMap<Int, Int>()
        for (g in tracks.groups) {
            val type = g.type
            val idx = perType.merge(type, 1, Int::plus)!! - 1
            val ttype = when (type) {
                C.TRACK_TYPE_AUDIO -> TrackType.AUDIO
                C.TRACK_TYPE_TEXT -> TrackType.TEXT
                C.TRACK_TYPE_VIDEO -> TrackType.VIDEO
                else -> continue
            }
            infos += TrackGroupInfo(ttype, List(g.length) { g.isTrackSupported(it) })
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                val id = "$idx:$i"
                val lang = f.language?.takeIf { it != "und" }
                val label = f.label ?: lang?.let { Locale.forLanguageTag(it).displayLanguage.ifBlank { it } } ?: "Track ${idx + 1}"
                when (type) {
                    C.TRACK_TYPE_AUDIO -> audio += TrackOption(id, label, lang, audioDetail(f), g.isTrackSelected(i))
                    C.TRACK_TYPE_TEXT -> text += TrackOption(id, label, lang, null, g.isTrackSelected(i))
                    C.TRACK_TYPE_VIDEO -> video += TrackOption(
                        id, if (f.height > 0) "${f.height}p" else label, null,
                        listOfNotNull(f.bitrate.takeIf { it > 0 }?.let { "${it / 1000} kbit/s" }).joinToString(), g.isTrackSelected(i),
                    )
                }
            }
        }
        val params = player.trackSelectionParameters
        return TrackSet(
            audio = audio, text = text, video = video.sortedByDescending { it.label.removeSuffix("p").toIntOrNull() ?: 0 },
            groups = infos,
            textOff = params.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || text.none { it.selected },
            videoAuto = params.overrides.keys.none { it.type == C.TRACK_TYPE_VIDEO },
        )
    }

    private fun audioDetail(f: androidx.media3.common.Format): String {
        val codec = when (f.sampleMimeType) {
            MimeTypes.AUDIO_AC3 -> "AC-3"
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "E-AC-3"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_MPEG_L2 -> "MP2"
            MimeTypes.AUDIO_MPEG -> "MP3"
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "DTS"
            MimeTypes.AUDIO_OPUS -> "Opus"
            else -> f.sampleMimeType?.substringAfter('/')?.uppercase()
        }
        val ch = when (f.channelCount) { 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; Format_NO -> null; else -> "${f.channelCount} ch" }
        return listOfNotNull(codec, ch).joinToString(" ")
    }

    // ---- listener ----

    private inner class Listener : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_IDLE -> if (_state.value !is EngineState.Error) _state.value = EngineState.Idle
                Player.STATE_BUFFERING -> _state.value = EngineState.Buffering
                Player.STATE_READY -> _state.value = EngineState.Ready
                Player.STATE_ENDED -> _state.value = EngineState.Ended
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { _playing.value = playWhenReady }

        override fun onPlayerError(error: PlaybackException) {
            var http: Int? = null
            var c: Throwable? = error
            while (c != null) {
                if (c is HttpDataSource.InvalidResponseCodeException) { http = c.responseCode; break }
                c = c.cause
            }
            val msg = AppLog.redactor.redact(error.errorCodeName + (error.message?.let { ": $it" } ?: ""))
            _state.value = EngineState.Error(Media3Errors.classify(error.errorCode, http, msg))
        }

        override fun onTracksChanged(tracks: Tracks) {
            lastTracks = tracks
            _tracks.value = buildTrackSet(tracks)
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            _videoSize.value = if (videoSize.width > 0) VideoSize(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio) else null
        }
    }

    override fun setAspect(mode: AspectMode) { aspect.value = mode }

    @Composable
    override fun Surface(modifier: Modifier) {
        val mode by aspect.collectAsState()
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    setKeepContentOnPlayerReset(true)
                    if (!released) { player = this@Media3Engine.player; views += this }
                }
            },
            update = { view ->
                view.resizeMode = when (mode) {
                    AspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    AspectMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    AspectMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
            },
            onRelease = { view -> views -= view; view.player = null }, // detach the surface before the player can be released
        )
    }

    private companion object {
        val SESSION_COUNTER = AtomicInteger()
        const val Format_NO = androidx.media3.common.Format.NO_VALUE
    }
}

/** Lets only this app and trusted system controllers (media keys, notification) talk to the session. */
@OptIn(UnstableApi::class)
internal class RestrictedSessionCallback : MediaSession.Callback {
    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
        // The uid comes from Binder.getCallingUid(); the package name is supplied by the caller and can be forged.
        val allowed = controller.uid == android.os.Process.myUid() || controller.isTrusted ||
            session.isMediaNotificationController(controller)
        if (!allowed) return MediaSession.ConnectionResult.reject()
        // No seeking, playlist or track changes from outside: the app's own timeline logic owns those.
        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_AUDIO_ATTRIBUTES, Player.COMMAND_GET_VOLUME,
                Player.COMMAND_GET_DEVICE_VOLUME, Player.COMMAND_ADJUST_DEVICE_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME,
            )
            .build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailablePlayerCommands(commands).build()
    }
}
