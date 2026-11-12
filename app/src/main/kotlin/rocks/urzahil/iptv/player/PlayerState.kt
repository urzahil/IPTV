package rocks.urzahil.iptv.player

import rocks.urzahil.iptv.core.xtream.XtreamAccountInfo
import rocks.urzahil.iptv.player.model.AspectMode
import rocks.urzahil.iptv.player.model.EngineKind
import rocks.urzahil.iptv.player.model.EnginePreference
import rocks.urzahil.iptv.player.model.FailureKind
import rocks.urzahil.iptv.player.model.PlayMode
import rocks.urzahil.iptv.player.model.PlayerFailure
import rocks.urzahil.iptv.player.model.TimelineState
import rocks.urzahil.iptv.player.model.TrackSet

enum class Phase { IDLE, LOADING, BUFFERING, PLAYING, PAUSED, RECONNECTING, ERROR, SUSPENDED }

data class ChannelInfo(
    val id: Long,
    val name: String,
    val number: Int?,
    val logoUrl: String?,
    val isRadio: Boolean,
    val catchupDays: Int,
) {
    val catchupEnabled: Boolean get() = catchupDays > 0
}

enum class ErrorKind { GENERIC, ACCOUNT, CATCHUP_UNAVAILABLE, UNSUPPORTED, NOT_FOUND }

/** All text is already redacted. */
data class ErrorInfo(val kind: ErrorKind, val title: String, val detail: String?)

/** Everything the player UI renders. Never contains URLs. */
data class PlayerUiState(
    val channel: ChannelInfo? = null,
    val phase: Phase = Phase.IDLE,
    /** The play intent: false while paused. */
    val playing: Boolean = false,
    val engine: EngineKind? = null,
    /** The channel's engine override (AUTO when none). */
    val channelEngine: EnginePreference = EnginePreference.AUTO,
    val mode: PlayMode = PlayMode.LIVE,
    val timeline: TimelineState? = null,
    val nextProgrammeTitle: String? = null,
    /** (attempt, max) while reconnecting. */
    val reconnect: Pair<Int, Int>? = null,
    val error: ErrorInfo? = null,
    val tracks: TrackSet = TrackSet.EMPTY,
    /** Ghost target while the user is skipping or scrubbing. */
    val pendingSeekMs: Long? = null,
    /** One-shot message ("Switched to VLC", "Catchup is not available ..."). */
    val notice: String? = null,
    val aspect: AspectMode = AspectMode.FIT,
    val videoAspect: Float? = null,
) {
    val isActive: Boolean get() = phase != Phase.IDLE
}

/** Explains why a stream failed, in words for the overlay. */
object ErrorMessages {
    fun describe(f: PlayerFailure, mode: PlayMode, engine: EngineKind, accountNote: String? = null): ErrorInfo {
        val who = if (engine == EngineKind.VLC) "VLC" else "Media3"
        return when {
            accountNote != null -> ErrorInfo(ErrorKind.ACCOUNT, accountNote, null)
            f.kind == FailureKind.HTTP_CLIENT_ERROR && mode == PlayMode.CATCHUP ->
                ErrorInfo(ErrorKind.CATCHUP_UNAVAILABLE, "Catchup unavailable", "The provider refused this programme. It may be too old or not recorded.")
            f.kind == FailureKind.HTTP_CLIENT_ERROR && f.httpStatus == 404 ->
                ErrorInfo(ErrorKind.NOT_FOUND, "Channel not found", "The server says this stream does not exist (HTTP 404).")
            f.kind == FailureKind.HTTP_CLIENT_ERROR ->
                ErrorInfo(ErrorKind.GENERIC, "The server refused the stream", "HTTP ${f.httpStatus ?: "4xx"}. Check the account or try again later.")
            f.kind == FailureKind.DECODER || f.kind == FailureKind.FORMAT_UNSUPPORTED || f.kind == FailureKind.CONTAINER_UNSUPPORTED || f.kind == FailureKind.AUDIO_SINK ->
                ErrorInfo(ErrorKind.UNSUPPORTED, "This stream can't be decoded by $who", "Try the other player from the menu.")
            f.kind == FailureKind.CONTAINER_MALFORMED -> ErrorInfo(ErrorKind.UNSUPPORTED, "The stream is damaged or in an unknown format", f.message.ifBlank { null })
            f.kind == FailureKind.NETWORK || f.kind == FailureKind.HTTP_SERVER_ERROR ->
                ErrorInfo(ErrorKind.GENERIC, "Can't reach the stream", "Check your connection. ${f.message}".trim())
            else -> ErrorInfo(ErrorKind.GENERIC, "Playback failed", f.message.ifBlank { null })
        }
    }
}

/** Turns an Xtream account answer into a reason for a refused stream (HTTP 401/403/404). */
object AccountDiagnosis {
    fun explain(info: XtreamAccountInfo, nowSec: Long): String? {
        if (!info.auth) return "Login refused: check the username and password."
        if (!info.isActive) return "The account is ${info.status?.lowercase() ?: "inactive"}."
        info.expiresAtSec?.let { if (it in 1..nowSec) return "The account has expired." }
        val max = info.maxConnections
        val active = info.activeConnections
        if (max != null && active != null && max > 0 && active >= max) {
            return "Maximum connections reached ($active of $max). Close the stream on another device."
        }
        return null
    }
}
