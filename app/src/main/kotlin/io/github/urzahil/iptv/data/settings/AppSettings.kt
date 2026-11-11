package io.github.urzahil.iptv.data.settings

/** User settings. Player-specific options are added by the player milestones. */
data class AppSettings(
    /** Days of EPG to keep in the future (1..14). */
    val epgFutureDays: Int = DEFAULT_FUTURE_DAYS,
    /** Days of EPG to keep in the past, only for channels with catchup (0..30). */
    val epgPastDays: Int = DEFAULT_PAST_DAYS,
    /** 0 = manual only. */
    val playlistRefreshHours: Int = DEFAULT_PLAYLIST_HOURS,
    /** 0 = manual only. */
    val epgRefreshHours: Int = DEFAULT_EPG_HOURS,
    val defaultUserAgent: String = DEFAULT_USER_AGENT,
    /** Order channels by channel number instead of provider order. */
    val sortByNumber: Boolean = false,
    val showRadio: Boolean = true,
) {
    companion object {
        const val MIN_FUTURE_DAYS = 1
        const val MAX_FUTURE_DAYS = 14
        const val MIN_PAST_DAYS = 0
        const val MAX_PAST_DAYS = 30
        const val DEFAULT_FUTURE_DAYS = 3
        const val DEFAULT_PAST_DAYS = 7
        const val DEFAULT_PLAYLIST_HOURS = 24
        const val DEFAULT_EPG_HOURS = 12
        const val DEFAULT_USER_AGENT = "IPTV/0.1 (Android)"
        val REFRESH_CHOICES_HOURS = listOf(0, 6, 12, 24, 48)
    }
}
