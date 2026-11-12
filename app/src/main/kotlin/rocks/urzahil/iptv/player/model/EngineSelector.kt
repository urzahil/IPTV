package rocks.urzahil.iptv.player.model

/** Chooses the engine for a play request and decides when to fall back from Media3 to libVLC. */
object EngineSelector {
    /**
     * Schemes Media3 cannot play: straight to libVLC. RTMP is here too: Media3's RTMP module ships a
     * native library that is not 16 KB page aligned, so it is left out and libVLC plays RTMP instead.
     */
    val VLC_ONLY_SCHEMES = setOf("rtp", "mms", "mmsh", "mmst", "mmsu", "srt", "rist", "rtmp", "rtmps", "rtmpe", "rtmpt")

    /** An automatic switch to libVLC is remembered this long. */
    const val AUTO_ENGINE_TTL_MS = 30L * 24 * 3_600_000

    data class Choice(val kind: EngineKind, val forced: Boolean)

    /**
     * Priority: the channel's forced engine, then the global setting, then (AUTO) an unexpired remembered
     * automatic fallback, then scheme routing, then Media3. Extra HTTP headers cannot be sent by libVLC 3, so
     * AUTO prefers Media3 when they are present (unless the scheme only works in VLC).
     */
    fun choose(
        scheme: String,
        hasExtraHeaders: Boolean,
        global: EnginePreference,
        channel: EnginePreference,
        rememberedAuto: EngineKind?,
        rememberedAutoAgeMs: Long = 0,
    ): Choice {
        channel.forcedKind?.let { return Choice(it, forced = true) }
        global.forcedKind?.let { return Choice(it, forced = true) }
        if (scheme.lowercase() in VLC_ONLY_SCHEMES) return Choice(EngineKind.VLC, forced = false)
        if (rememberedAuto == EngineKind.VLC && rememberedAutoAgeMs in 0 until AUTO_ENGINE_TTL_MS && !hasExtraHeaders) {
            return Choice(EngineKind.VLC, forced = false)
        }
        return Choice(EngineKind.MEDIA3, forced = false)
    }

    /** True when a group of [type] exists but none of its tracks is supported, across all groups of that type. */
    fun hasUnsupportedMainTrack(groups: List<TrackGroupInfo>): Boolean {
        for (type in listOf(TrackType.VIDEO, TrackType.AUDIO)) {
            val ofType = groups.filter { it.type == type && it.supported.isNotEmpty() }
            if (ofType.isNotEmpty() && ofType.none { g -> g.supported.any { it } }) return true
        }
        return false
    }
}

sealed interface FallbackAction {
    data object None : FallbackAction
    data class SwitchToVlc(val reason: String) : FallbackAction
}

/**
 * One instance per play request. Falls back at most once, only in AUTO mode and only from Media3.
 */
class FallbackPolicy(private val automatic: Boolean) {
    private var used = false
    private var malformedInARow = 0

    val hasFallenBack: Boolean get() = used

    fun onFailure(engine: EngineKind, failure: PlayerFailure): FallbackAction {
        if (!eligible(engine)) return FallbackAction.None
        return when (failure.kind) {
            FailureKind.DECODER, FailureKind.FORMAT_UNSUPPORTED, FailureKind.CONTAINER_UNSUPPORTED,
            FailureKind.AUDIO_SINK -> take("decoder or format error")
            FailureKind.CONTAINER_MALFORMED -> if (++malformedInARow >= 2) take("malformed container") else FallbackAction.None
            else -> { malformedInARow = 0; FallbackAction.None }
        }
    }

    /** Called ~2 s after the engine became ready, with the current track support. */
    fun onTracksSettled(engine: EngineKind, groups: List<TrackGroupInfo>): FallbackAction {
        if (!eligible(engine)) return FallbackAction.None
        return if (EngineSelector.hasUnsupportedMainTrack(groups)) take("no supported track") else FallbackAction.None
    }

    private fun eligible(engine: EngineKind) = automatic && !used && engine == EngineKind.MEDIA3

    private fun take(reason: String): FallbackAction {
        used = true
        return FallbackAction.SwitchToVlc(reason)
    }
}
