package rocks.urzahil.iptv.data.settings

import rocks.urzahil.iptv.player.model.BufferProfile
import rocks.urzahil.iptv.player.model.DeinterlaceMode
import rocks.urzahil.iptv.player.model.EnginePreference

/** User settings. Player-specific options are added by the player milestones. */
data class AppSettings(
    /** Days of EPG to keep in the future (1..14). */
    val epgFutureDays: Int = DEFAULT_FUTURE_DAYS,
    /** Days of EPG to keep in the past, only for channels with catchup (0..7). */
    val epgPastDays: Int = DEFAULT_PAST_DAYS,
    /** 0 = manual only. */
    val playlistRefreshHours: Int = DEFAULT_PLAYLIST_HOURS,
    /** 0 = manual only. */
    val epgRefreshHours: Int = DEFAULT_EPG_HOURS,
    val defaultUserAgent: String = DEFAULT_USER_AGENT,
    /** Order channels by channel number instead of provider order. */
    val sortByNumber: Boolean = false,
    val showRadio: Boolean = true,
    // ---- player ----
    /** Default playback engine; a channel can override it. */
    val engine: EnginePreference = EnginePreference.AUTO,
    val bufferProfile: BufferProfile = BufferProfile.BALANCED,
    /** Reconnect attempts after a network error (0 disables). */
    val retryAttempts: Int = DEFAULT_RETRIES,
    /** Let the system pass AC3/DTS to an HDMI/USB receiver instead of decoding to PCM. */
    val audioPassthrough: Boolean = false,
    val deinterlace: DeinterlaceMode = DeinterlaceMode.AUTO,
    /** Keep audio playing (Media3 engine only) when the app is left. */
    val backgroundAudio: Boolean = false,
    /** BCP-47 / ISO language code; blank = device language. */
    val preferredAudioLanguage: String = "",
    /** Blank = subtitles off by default. */
    val preferredTextLanguage: String = "",
    /** Swap the meaning of swipe up/down for channel zapping. */
    val invertSwipeZap: Boolean = false,
) {
    companion object {
        const val MIN_FUTURE_DAYS = 1
        const val MAX_FUTURE_DAYS = 14
        const val MIN_PAST_DAYS = 0
        const val MAX_PAST_DAYS = 7
        const val DEFAULT_FUTURE_DAYS = 3
        const val DEFAULT_PAST_DAYS = 7
        const val DEFAULT_PLAYLIST_HOURS = 24
        const val DEFAULT_EPG_HOURS = 12
        const val MAX_RETRIES = 10
        const val DEFAULT_RETRIES = 5
        const val DEFAULT_USER_AGENT = "IPTV/0.1 (Android)"
        val REFRESH_CHOICES_HOURS = listOf(0, 6, 12, 24, 48)
    }
}
