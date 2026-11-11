package rocks.urzahil.iptv.ui.guide.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Half-open time interval [startMs, endMs) in epoch milliseconds. */
data class TimeRange(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
    val isEmpty: Boolean get() = endMs <= startMs

    fun contains(ms: Long): Boolean = ms in startMs until endMs
    fun covers(other: TimeRange): Boolean = other.startMs >= startMs && other.endMs <= endMs
    fun overlaps(start: Long, end: Long): Boolean = start < endMs && end > startMs

    /** The part of this range inside [bounds]; may be empty. */
    fun clampTo(bounds: TimeRange): TimeRange {
        val s = startMs.coerceIn(bounds.startMs, bounds.endMs)
        return TimeRange(s, endMs.coerceIn(s, bounds.endMs))
    }
}

/**
 * The scrollable extent of the guide: from now minus the "days in the past" setting to now plus the
 * "days ahead" setting, both snapped outwards to local half hours so the ruler starts on a tick.
 */
data class GuideBounds(val originMs: Long, val endMs: Long) {
    val range: TimeRange get() = TimeRange(originMs, endMs)

    companion object {
        const val MINUTE_MS = 60_000L
        const val HALF_HOUR_MS = 30 * MINUTE_MS
        const val HOUR_MS = 60 * MINUTE_MS
        const val DAY_MS = 24 * HOUR_MS

        fun of(nowMs: Long, pastDays: Int, futureDays: Int, zone: ZoneId): GuideBounds {
            val origin = floorLocal(nowMs - pastDays.coerceAtLeast(0) * DAY_MS, HALF_HOUR_MS, zone)
            val end = ceilLocal(nowMs + futureDays.coerceAtLeast(0) * DAY_MS, HALF_HOUR_MS, zone)
            return GuideBounds(origin, maxOf(end, origin + HALF_HOUR_MS))
        }

        /** Rounds down to a multiple of [stepMs] of the local wall clock (zones may be offset by 15/30/45 min). */
        fun floorLocal(ms: Long, stepMs: Long, zone: ZoneId): Long {
            val offset = zone.rules.getOffset(Instant.ofEpochMilli(ms)).totalSeconds * 1000L
            return ms - Math.floorMod(ms + offset, stepMs)
        }

        fun ceilLocal(ms: Long, stepMs: Long, zone: ZoneId): Long {
            val f = floorLocal(ms, stepMs, zone)
            return if (f == ms) ms else f + stepMs
        }
    }
}

/** Time <-> pixel mapping. Pixels are measured from [GuideBounds.originMs]; scroll is the pixel at the left edge. */
class GuideAxis(val bounds: GuideBounds, val pxPerMinute: Float) {
    // Double arithmetic: a three-week guide is hundreds of thousands of pixels wide.
    private val pxPerMs: Double = pxPerMinute.toDouble() / GuideBounds.MINUTE_MS

    val totalWidthPx: Float get() = widthOf(bounds.endMs - bounds.originMs)

    fun xOf(timeMs: Long): Float = ((timeMs - bounds.originMs) * pxPerMs).toFloat()

    fun timeAt(xPx: Float): Long = bounds.originMs + Math.round(xPx / pxPerMs)

    fun widthOf(durationMs: Long): Float = (durationMs * pxPerMs).toFloat()

    fun maxScroll(viewportPx: Float): Float = (totalWidthPx - viewportPx).coerceAtLeast(0f)

    fun clampScroll(scrollPx: Float, viewportPx: Float): Float = scrollPx.coerceIn(0f, maxScroll(viewportPx))

    /** Scroll position that puts [timeMs] at the left edge, clamped to the bounds. */
    fun scrollFor(timeMs: Long, viewportPx: Float): Float = clampScroll(xOf(timeMs), viewportPx)

    /** The time span on screen. */
    fun visible(scrollPx: Float, viewportPx: Float): TimeRange =
        TimeRange(timeAt(scrollPx), timeAt(scrollPx + viewportPx.coerceAtLeast(0f))).clampTo(bounds.range)

    /** Scroll position showing the same left-edge time after the bounds or the scale changed. */
    fun rebase(scrollPx: Float, old: GuideAxis, viewportPx: Float): Float = scrollFor(old.timeAt(scrollPx), viewportPx)
}

/** Ranges derived from the visible span. Both are stepped so they change rarely while scrolling. */
object GuideWindows {
    const val CELL_STEP_MS = GuideBounds.HOUR_MS
    const val CELL_MARGIN_MS = GuideBounds.HOUR_MS
    const val QUERY_BUCKET_MS = 6 * GuideBounds.HOUR_MS

    /** Span whose cells are composed: the visible span widened to whole hours plus a margin. */
    fun cellWindow(visible: TimeRange, bounds: GuideBounds): TimeRange = stepped(visible, CELL_STEP_MS, CELL_MARGIN_MS, bounds)

    /** Span to have loaded from the database: whole 6-hour buckets around the visible span plus one bucket each side. */
    fun queryRange(visible: TimeRange, bounds: GuideBounds): TimeRange = stepped(visible, QUERY_BUCKET_MS, QUERY_BUCKET_MS, bounds)

    private fun stepped(visible: TimeRange, step: Long, margin: Long, bounds: GuideBounds): TimeRange {
        val start = Math.floorDiv(visible.startMs, step) * step - margin
        val end = -Math.floorDiv(-visible.endMs, step) * step + margin
        return TimeRange(start, end).clampTo(bounds.range)
    }
}

/** One local calendar day of the guide. [offset] is relative to today (-1 = yesterday). */
data class GuideDay(val date: LocalDate, val startMs: Long, val endMs: Long, val offset: Int) {
    fun contains(ms: Long): Boolean = ms in startMs until endMs
}

object GuideDays {
    /** Local days touched by the bounds. Start and end follow the zone rules, so DST days are 23 or 25 hours long. */
    fun build(bounds: GuideBounds, nowMs: Long, zone: ZoneId): List<GuideDay> {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val first = Instant.ofEpochMilli(bounds.originMs).atZone(zone).toLocalDate()
        val last = Instant.ofEpochMilli(bounds.endMs - 1).atZone(zone).toLocalDate()
        val out = ArrayList<GuideDay>()
        var d = first
        while (!d.isAfter(last)) {
            val start = d.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            out += GuideDay(d, start, end, (d.toEpochDay() - today.toEpochDay()).toInt())
            d = d.plusDays(1)
        }
        return out
    }

    fun dayAt(days: List<GuideDay>, ms: Long): GuideDay? = days.firstOrNull { it.contains(ms) }

    /** Left-edge time when jumping to [day]: its local midnight, or the guide start for the first (partial) day. */
    fun jumpTarget(day: GuideDay, bounds: GuideBounds): Long = day.startMs.coerceIn(bounds.originMs, bounds.endMs)
}

data class GuideTick(val timeMs: Long, val fullHour: Boolean)

object GuideTicks {
    /** Ruler ticks on local half hours inside [range] (start inclusive). */
    fun between(range: TimeRange, zone: ZoneId, stepMs: Long = GuideBounds.HALF_HOUR_MS): List<GuideTick> {
        if (range.isEmpty) return emptyList()
        val out = ArrayList<GuideTick>()
        var t = GuideBounds.ceilLocal(range.startMs, stepMs, zone)
        while (t < range.endMs) {
            out += GuideTick(t, GuideBounds.floorLocal(t, GuideBounds.HOUR_MS, zone) == t)
            t += stepMs
        }
        return out
    }
}
