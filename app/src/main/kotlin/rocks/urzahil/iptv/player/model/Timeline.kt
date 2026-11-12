package rocks.urzahil.iptv.player.model

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.Instant

/** A programme as the player sees it. Times are wall-clock epoch ms with tvg-shift already applied. */
data class ProgrammeSlot(
    val id: Long?,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val hasCatchupId: Boolean = false,
) {
    val durationMs: Long get() = endMs - startMs
}

/** Sorted programmes of one channel. */
class ProgrammeGuide(programmes: List<ProgrammeSlot>) {
    val slots: List<ProgrammeSlot> = programmes.sortedBy { it.startMs }

    /** The programme airing at [ms]. */
    fun at(ms: Long): ProgrammeSlot? = slots.lastOrNull { it.startMs <= ms }?.takeIf { ms < it.endMs }

    /** The latest programme that starts before [startMs]. */
    fun before(startMs: Long): ProgrammeSlot? = slots.lastOrNull { it.startMs < startMs }

    /** The first programme that starts at or after [endMs]. */
    fun after(endMs: Long): ProgrammeSlot? = slots.firstOrNull { it.startMs >= endMs }

    fun isEmpty() = slots.isEmpty()

    companion object {
        val EMPTY = ProgrammeGuide(emptyList())
    }
}

enum class PlayMode { LIVE, CATCHUP }

/** The span the seek bar draws: the programme containing the playback position, or a pseudo slot. */
data class TimelineWindow(val startMs: Long, val endMs: Long, val programme: ProgrammeSlot?) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * Programme-relative view of the playback position. All times are wall-clock epoch ms.
 * [wallPosMs] is the real-world time of the picture on screen.
 */
data class TimelineState(
    val mode: PlayMode,
    val catchupDays: Int,
    val nowMs: Long,
    val wallPosMs: Long,
    val window: TimelineWindow,
) {
    val catchupEnabled: Boolean get() = catchupDays > 0

    /** Oldest wall time that can be requested, or null when the channel has no catchup. */
    val seekFloorMs: Long? get() = if (catchupDays > 0) nowMs - catchupDays * DAY_MS else null

    /** How far the picture on screen is behind the live edge (also grows while live is paused). */
    val behindLiveMs: Long get() = (nowMs - wallPosMs).coerceAtLeast(0)

    val isBehindLive: Boolean get() = behindLiveMs >= BEHIND_LIVE_THRESHOLD_MS

    /** Position inside the window, 0..duration. */
    val offsetInWindowMs: Long get() = (wallPosMs - window.startMs).coerceIn(0, window.durationMs)

    /** Fraction of the window that has already aired (the rest is drawn hatched). */
    val airedFraction: Float
        get() = if (window.durationMs <= 0) 0f else ((nowMs - window.startMs).toFloat() / window.durationMs).coerceIn(0f, 1f)

    val positionFraction: Float
        get() = if (window.durationMs <= 0) 0f else (offsetInWindowMs.toFloat() / window.durationMs).coerceIn(0f, 1f)

    companion object {
        const val DAY_MS = 86_400_000L
        const val HOUR_MS = 3_600_000L
        const val BEHIND_LIVE_THRESHOLD_MS = 15_000L

        /**
         * The window containing [wallMs]: the programme, or without EPG an hour slot, clipped to its
         * neighbours so slots never overlap a known programme.
         */
        fun windowAt(guide: ProgrammeGuide, wallMs: Long): TimelineWindow {
            guide.at(wallMs)?.let { return TimelineWindow(it.startMs, it.endMs, it) }
            var start = Math.floorDiv(wallMs, HOUR_MS) * HOUR_MS
            var end = start + HOUR_MS
            guide.before(wallMs + 1)?.let { if (it.endMs in (start + 1)..wallMs) start = it.endMs }
            guide.after(wallMs)?.let { if (it.startMs < end) end = it.startMs }
            return TimelineWindow(start, end, null)
        }

        fun of(mode: PlayMode, catchupDays: Int, nowMs: Long, wallPosMs: Long, guide: ProgrammeGuide): TimelineState =
            TimelineState(mode, catchupDays, nowMs, wallPosMs, windowAt(guide, wallPosMs))
    }
}

/** Human readable time strings for the controls. */
object TimeText {
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
    private val CLOCK_SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun clock(ms: Long, zone: ZoneId): String = CLOCK.format(Instant.ofEpochMilli(ms).atZone(zone))

    fun clockSeconds(ms: Long, zone: ZoneId): String = CLOCK_SECONDS.format(Instant.ofEpochMilli(ms).atZone(zone))

    /** "4:05" or "1:02:03". */
    fun duration(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** "-12:34 behind live" (uses a real minus sign). */
    fun behindLive(ms: Long): String = "−${duration(ms)} behind live"
}
