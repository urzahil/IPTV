package rocks.urzahil.iptv.ui.guide.model

import rocks.urzahil.iptv.core.catchup.CatchupWindow
import rocks.urzahil.iptv.data.db.dao.ProgrammeLite

enum class ProgrammeStatus {
    /** Ended, and inside the channel's catchup window: can be watched from the archive. */
    PAST_PLAYABLE,
    /** Ended, no catchup or older than the archive. */
    PAST,
    NOW,
    FUTURE,
}

enum class ProgrammeAction { WATCH_LIVE, START_OVER, WATCH_CATCHUP }

/** Why nothing can be played, shown in the programme sheet. */
enum class UnplayableReason { NOT_AIRED_YET, NO_CATCHUP, OUTSIDE_CATCHUP_WINDOW }

data class ProgrammeOptions(val actions: List<ProgrammeAction>, val reason: UnplayableReason? = null)

/** All times are wall-clock epoch ms with tvg-shift already applied (as the repository returns them). */
object GuideClassifier {
    fun classify(startMs: Long, endMs: Long, nowMs: Long, catchupDays: Int): ProgrammeStatus = when {
        startMs <= nowMs && endMs > nowMs -> ProgrammeStatus.NOW
        startMs > nowMs -> ProgrammeStatus.FUTURE
        archived(startMs, nowMs, catchupDays) -> ProgrammeStatus.PAST_PLAYABLE
        else -> ProgrammeStatus.PAST
    }

    fun options(startMs: Long, endMs: Long, nowMs: Long, catchupDays: Int): ProgrammeOptions =
        when (classify(startMs, endMs, nowMs, catchupDays)) {
            ProgrammeStatus.NOW ->
                if (archived(startMs, nowMs, catchupDays)) ProgrammeOptions(listOf(ProgrammeAction.WATCH_LIVE, ProgrammeAction.START_OVER))
                else ProgrammeOptions(listOf(ProgrammeAction.WATCH_LIVE))
            ProgrammeStatus.PAST_PLAYABLE -> ProgrammeOptions(listOf(ProgrammeAction.WATCH_CATCHUP))
            ProgrammeStatus.PAST -> ProgrammeOptions(
                emptyList(),
                if (catchupDays > 0) UnplayableReason.OUTSIDE_CATCHUP_WINDOW else UnplayableReason.NO_CATCHUP,
            )
            ProgrammeStatus.FUTURE -> ProgrammeOptions(emptyList(), UnplayableReason.NOT_AIRED_YET)
        }

    private fun archived(startMs: Long, nowMs: Long, catchupDays: Int): Boolean =
        CatchupWindow(catchupDays, Math.floorDiv(nowMs, 1000L)).available(Math.floorDiv(startMs, 1000L))
}

sealed interface GuideCell {
    val startMs: Long
    val endMs: Long
    /** Stable within a row. */
    val key: Long
}

/** [startMs]/[endMs] are clipped to the cell window; the real times are in [programme]. */
data class ProgrammeCell(val programme: ProgrammeLite, override val startMs: Long, override val endMs: Long) : GuideCell {
    override val key: Long get() = programme.id
}

/** A stretch without programme data. [known] = the span was loaded and is really empty ("No information"). */
data class GapCell(override val startMs: Long, override val endMs: Long, val known: Boolean) : GuideCell {
    // Programme ids are positive (epg channel id shl 32) or small negatives (fallback); keep gaps apart from both.
    override val key: Long get() = Long.MIN_VALUE + Math.floorDiv(startMs, 1000L)
}

object CellBuilder {
    /**
     * Cells that tile [window] exactly, in time order: programmes clipped to the window (overlapping feed entries
     * are trimmed, fully covered ones dropped) and gaps in between. [loaded] is the span the row has data for.
     */
    fun build(programmes: List<ProgrammeLite>, window: TimeRange, loaded: TimeRange?): List<GuideCell> {
        if (window.isEmpty) return emptyList()
        val out = ArrayList<GuideCell>()
        var cursor = window.startMs
        for (p in programmes) {
            if (p.endMs <= cursor) continue
            if (p.startMs >= window.endMs) break
            val start = maxOf(p.startMs, cursor)
            val end = minOf(p.endMs, window.endMs)
            if (end <= start) continue
            if (start > cursor) addGap(out, cursor, start, loaded)
            out += ProgrammeCell(p, start, end)
            cursor = end
        }
        if (cursor < window.endMs) addGap(out, cursor, window.endMs, loaded)
        return out
    }

    private fun addGap(out: MutableList<GuideCell>, start: Long, end: Long, loaded: TimeRange?) {
        if (loaded == null || !loaded.overlaps(start, end)) {
            out += GapCell(start, end, known = false)
            return
        }
        val ks = maxOf(start, loaded.startMs)
        val ke = minOf(end, loaded.endMs)
        if (ks > start) out += GapCell(start, ks, known = false)
        out += GapCell(ks, ke, known = true)
        if (ke < end) out += GapCell(ke, end, known = false)
    }
}
