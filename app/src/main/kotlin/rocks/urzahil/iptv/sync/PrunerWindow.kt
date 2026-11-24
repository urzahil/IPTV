package rocks.urzahil.iptv.sync

/** Retention cut-off math (pure, unit tested). Times in epoch milliseconds. */
object PrunerWindow {
    const val DAY_MS = 86_400_000L

    /**
     * Days of past EPG to keep for a channel. Channels without catchup keep none: only what is needed for the
     * current programme. Catchup channels keep the smaller of the user setting and their archive length.
     */
    fun keepPastDays(settingsPastDays: Int, channelCatchupDays: Int): Int =
        if (channelCatchupDays <= 0) 0 else minOf(settingsPastDays.coerceAtLeast(0), channelCatchupDays)

    /** Programmes that ended before this instant are dropped. With no past days that is "now". */
    fun pastCutoffMs(nowMs: Long, keepPastDays: Int): Long =
        if (keepPastDays <= 0) nowMs else nowMs - keepPastDays * DAY_MS

    /** Programmes that start after this instant are dropped. */
    fun futureCutoffMs(nowMs: Long, futureDays: Int): Long = nowMs + futureDays * DAY_MS

    /** Largest tvg-shift (hours either way) a playlist can sensibly carry; it is applied at read time. */
    const val SHIFT_PAD_MS = 14L * 3_600_000L

    /**
     * Cut-offs used when importing and pruning: [pastCutoffMs] / [futureCutoffMs] widened by [SHIFT_PAD_MS], because a
     * channel with `tvg-shift="+4"` airs raw programmes 4 hours later than their XMLTV times.
     */
    fun retainFromMs(nowMs: Long, keepPastDays: Int): Long = pastCutoffMs(nowMs, keepPastDays) - SHIFT_PAD_MS

    fun retainUntilMs(nowMs: Long, futureDays: Int): Long = futureCutoffMs(nowMs, futureDays) + SHIFT_PAD_MS
}

/** When periodic refreshes are due (pure, unit tested). An interval of 0 means manual refresh only. */
object StalenessPolicy {
    private const val HOUR_MS = 3_600_000L

    fun due(lastSyncMs: Long, intervalHours: Int, nowMs: Long): Boolean {
        if (lastSyncMs <= 0L) return true
        if (intervalHours <= 0) return false
        return nowMs - lastSyncMs >= intervalHours * HOUR_MS
    }
}
