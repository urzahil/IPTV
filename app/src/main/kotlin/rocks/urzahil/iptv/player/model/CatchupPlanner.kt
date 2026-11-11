package rocks.urzahil.iptv.player.model

import rocks.urzahil.iptv.core.catchup.CatchupRequest
import rocks.urzahil.iptv.core.catchup.CatchupUrl
import rocks.urzahil.iptv.core.catchup.CatchupUrlBuilder
import rocks.urzahil.iptv.core.catchup.TimeshiftStyle
import rocks.urzahil.iptv.core.catchup.XtreamTarget
import rocks.urzahil.iptv.core.model.CatchupSpec
import rocks.urzahil.iptv.core.model.CatchupType

data class CatchupAttempt(val endSec: Long, val style: TimeshiftStyle?)

/**
 * The sequence of requests tried for one catchup play request when the server answers 4xx:
 * 1. as asked (end = programme end, possibly in the future),
 * 2. end = now (servers that reject a future end),
 * 3. for native Xtream sources, the other timeshift URL style (path <-> php), again with both ends.
 */
class CatchupAttemptPlanner(
    endSec: Long,
    nowSec: Long,
    private val xtreamApi: Boolean,
    private val savedStyle: TimeshiftStyle,
) {
    private val attempts: List<CatchupAttempt>
    private var index = 0

    init {
        val ends = if (endSec > nowSec) listOf(endSec, nowSec) else listOf(endSec)
        val styles: List<TimeshiftStyle?> =
            if (xtreamApi) listOf(savedStyle, if (savedStyle == TimeshiftStyle.PATH) TimeshiftStyle.PHP else TimeshiftStyle.PATH)
            else listOf(null)
        attempts = styles.flatMap { s -> ends.map { e -> CatchupAttempt(e, s) } }
    }

    val current: CatchupAttempt get() = attempts[index]

    val attemptNumber: Int get() = index + 1
    val attemptCount: Int get() = attempts.size

    /** Moves on after a rejection. Null when every variant has been tried. */
    fun advance(): CatchupAttempt? {
        if (index + 1 >= attempts.size) return null
        index++
        return attempts[index]
    }

    /** The style to store on the source after the current attempt worked, or null when nothing changes. */
    fun styleToRemember(): TimeshiftStyle? = current.style?.takeIf { xtreamApi && it != savedStyle }
}

object CatchupRequests {
    /** Builds the archive URL for [attempt]. Throws [rocks.urzahil.iptv.core.catchup.UnsupportedCatchup]. */
    fun build(
        liveUrl: String,
        spec: CatchupSpec,
        xtream: XtreamTarget?,
        startMs: Long,
        nowMs: Long,
        attempt: CatchupAttempt,
        catchupId: String? = null,
    ): CatchupUrl {
        val target = if (spec.type == CatchupType.XTREAM_API && attempt.style != null) xtream?.copy(style = attempt.style) else xtream
        val req = CatchupRequest(Math.floorDiv(startMs, 1000L), attempt.endSec, Math.floorDiv(nowMs, 1000L), catchupId)
        return CatchupUrlBuilder.build(liveUrl, spec, req, target)
    }
}

enum class RowState { PAST_PLAYABLE, PAST_UNAVAILABLE, NOW, UPCOMING }

data class ProgrammeRow(val slot: ProgrammeSlot, val state: RowState, val playing: Boolean)

/** Rows for the programme list sheet. */
object ProgrammeList {
    fun build(slots: List<ProgrammeSlot>, nowMs: Long, catchupDays: Int, wallPosMs: Long?): List<ProgrammeRow> {
        val window = rocks.urzahil.iptv.core.catchup.CatchupWindow(catchupDays, Math.floorDiv(nowMs, 1000L))
        return slots.sortedBy { it.startMs }.map { s ->
            val state = when {
                s.startMs <= nowMs && s.endMs > nowMs -> RowState.NOW
                s.endMs <= nowMs -> if (window.available(Math.floorDiv(s.startMs, 1000L))) RowState.PAST_PLAYABLE else RowState.PAST_UNAVAILABLE
                else -> RowState.UPCOMING
            }
            ProgrammeRow(s, state, playing = wallPosMs != null && wallPosMs >= s.startMs && wallPosMs < s.endMs)
        }
    }

    /** Index of the row to scroll to (the airing programme), or 0. */
    fun nowIndex(rows: List<ProgrammeRow>): Int = rows.indexOfFirst { it.state == RowState.NOW }.coerceAtLeast(0)
}
