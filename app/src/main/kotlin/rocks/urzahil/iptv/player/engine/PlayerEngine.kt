package rocks.urzahil.iptv.player.engine

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.media3.session.MediaSession
import kotlinx.coroutines.flow.StateFlow
import rocks.urzahil.iptv.player.model.AspectMode
import rocks.urzahil.iptv.player.model.BufferProfile
import rocks.urzahil.iptv.player.model.DeinterlaceMode
import rocks.urzahil.iptv.player.model.EngineKind
import rocks.urzahil.iptv.player.model.EngineState
import rocks.urzahil.iptv.player.model.StreamRequest
import rocks.urzahil.iptv.player.model.TrackSet
import rocks.urzahil.iptv.player.model.VideoSize

/**
 * One playback engine instance. All methods are called on the main thread.
 * An instance is created per play session and replaced (never reused) when the engine is switched.
 */
interface PlayerEngine {
    val kind: EngineKind
    val state: StateFlow<EngineState>

    /** The play intent (playWhenReady): true while playing or buffering to play, false when paused. */
    val playing: StateFlow<Boolean>
    val tracks: StateFlow<TrackSet>
    val videoSize: StateFlow<VideoSize?>

    /** Loads [request] and starts playing from [startPositionMs] (0 = default). */
    fun prepare(request: StreamRequest, startPositionMs: Long = 0)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun positionMs(): Long
    fun durationMs(): Long?
    fun isSeekable(): Boolean
    fun stop()

    /** Frees native resources. The instance must not be used afterwards. */
    fun release()

    /** [id] null = automatic. */
    fun selectAudio(id: String?)

    /** [id] null = subtitles off. */
    fun selectText(id: String?)

    /** [id] null = automatic quality. */
    fun selectVideo(id: String?)
    fun setAspect(mode: AspectMode)

    /** Preferred track languages (blank = default). Engines that cannot choose by language ignore this. */
    fun setLanguages(audio: String, text: String)

    @Composable
    fun Surface(modifier: Modifier)

    /** Media3 only: the session that media keys and the playback notification use. */
    val mediaSession: MediaSession? get() = null
}

/** Settings that are fixed for the lifetime of one engine instance. */
data class EngineConfig(
    val buffer: BufferProfile,
    val passthrough: Boolean,
    val deinterlace: DeinterlaceMode,
    val audioLanguage: String,
    val textLanguage: String,
)

/** Creates engines. A seam for tests and for keeping libVLC out of the Media3 path. */
interface EngineFactory {
    fun create(kind: EngineKind, config: EngineConfig): PlayerEngine
}
