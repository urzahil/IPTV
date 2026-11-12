package rocks.urzahil.iptv.player.model

/** The two playback engines. */
enum class EngineKind { MEDIA3, VLC }

/** User choice for the engine (global setting, or per channel when stored in channel_prefs). */
enum class EnginePreference {
    AUTO, MEDIA3, VLC;

    val forcedKind: EngineKind?
        get() = when (this) {
            AUTO -> null
            MEDIA3 -> EngineKind.MEDIA3
            VLC -> EngineKind.VLC
        }

    companion object {
        fun parse(value: String?): EnginePreference =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: AUTO
    }
}

/** Media3 load control profile. */
enum class BufferProfile(val minBufferMs: Int, val maxBufferMs: Int, val playbackMs: Int, val rebufferMs: Int) {
    LOW_LATENCY(5_000, 15_000, 1_500, 3_000),
    BALANCED(15_000, 50_000, 2_500, 5_000),
    LARGE(30_000, 90_000, 4_000, 8_000);

    companion object {
        fun parse(value: String?): BufferProfile = entries.firstOrNull { it.name == value } ?: BALANCED
    }
}

/** libVLC deinterlacing: AUTO lets VLC decide, ON forces yadif. */
enum class DeinterlaceMode(val vlcValue: Int) {
    AUTO(-1), ON(1), OFF(0);

    companion object {
        fun parse(value: String?): DeinterlaceMode = entries.firstOrNull { it.name == value } ?: AUTO
    }
}

enum class AspectMode {
    FIT, FILL, ZOOM;

    fun next(): AspectMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun parse(value: String?): AspectMode = entries.firstOrNull { it.name == value } ?: FIT
    }
}

enum class TrackType { AUDIO, TEXT, VIDEO }

/** One selectable track in the track sheet. [id] is engine specific and opaque to the UI. */
data class TrackOption(
    val id: String,
    val label: String,
    val language: String? = null,
    val detail: String? = null,
    val selected: Boolean = false,
)

/**
 * Whether the engine can decode the tracks of one group; used by the fallback rule
 * "a group exists but nothing in it is supported".
 */
data class TrackGroupInfo(val type: TrackType, val supported: List<Boolean>)

data class TrackSet(
    val audio: List<TrackOption> = emptyList(),
    val text: List<TrackOption> = emptyList(),
    val video: List<TrackOption> = emptyList(),
    val groups: List<TrackGroupInfo> = emptyList(),
    val textOff: Boolean = true,
    val videoAuto: Boolean = true,
) {
    companion object {
        val EMPTY = TrackSet()
    }
}

data class VideoSize(val width: Int, val height: Int, val pixelRatio: Float = 1f) {
    val aspect: Float? get() = if (width > 0 && height > 0) width * pixelRatio / height else null
}

/** What went wrong, independent of the engine. Messages must already be redacted. */
enum class FailureKind {
    /** No decoder or the decoder crashed. */
    DECODER,
    /** The codec/profile is not supported or exceeds the device. */
    FORMAT_UNSUPPORTED,
    CONTAINER_UNSUPPORTED,
    CONTAINER_MALFORMED,
    AUDIO_SINK,
    NETWORK,
    HTTP_CLIENT_ERROR,
    HTTP_SERVER_ERROR,
    BEHIND_LIVE_WINDOW,
    /** The engine could not open the stream and gives no reason (libVLC). */
    OPEN_FAILED,
    OTHER,
}

data class PlayerFailure(
    val kind: FailureKind,
    val code: Int = 0,
    val httpStatus: Int? = null,
    val message: String = "",
)

sealed interface EngineState {
    data object Idle : EngineState
    data object Buffering : EngineState
    data object Ready : EngineState
    data object Ended : EngineState
    data class Error(val failure: PlayerFailure) : EngineState
}

/** Everything an engine needs to open a stream. [url] carries credentials: never log or persist. */
class StreamRequest(
    val url: String,
    val userAgent: String?,
    val referrer: String?,
    /** All headers incl. User-Agent and Referer. */
    val headers: Map<String, String>,
    /** "hls", "dash" or a MIME type; null = infer. */
    val mime: String?,
    val live: Boolean,
    val title: String,
    val subtitle: String?,
) {
    val scheme: String get() = url.substringBefore("://", "").lowercase()

    override fun toString(): String = "StreamRequest(scheme=$scheme, live=$live)" // never print the URL
}
