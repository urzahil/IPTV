package rocks.urzahil.iptv.guide

import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Test
import rocks.urzahil.iptv.ui.guide.model.GuideAxis
import rocks.urzahil.iptv.ui.guide.model.GuideBounds
import rocks.urzahil.iptv.ui.guide.model.GuideDays
import rocks.urzahil.iptv.ui.guide.model.GuideTicks
import rocks.urzahil.iptv.ui.guide.model.GuideWindows
import rocks.urzahil.iptv.ui.guide.model.TimeLabels
import rocks.urzahil.iptv.ui.guide.model.TimeRange

class GuideTimeTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val berlin = ZoneId.of("Europe/Berlin")
    private val hour = GuideBounds.HOUR_MS
    private val day = GuideBounds.DAY_MS

    private fun at(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0): Long =
        ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), zone).toInstant().toEpochMilli()

    // ---- bounds from the retention settings ----

    @Test fun `bounds span past and future days snapped to half hours`() {
        val now = at(utc, 2026, 11, 11, 20, 17)
        val b = GuideBounds.of(now, pastDays = 7, futureDays = 3, zone = utc)
        assertThat(b.originMs).isEqualTo(at(utc, 2026, 11, 4, 20, 0))
        assertThat(b.endMs).isEqualTo(at(utc, 2026, 11, 14, 20, 30))
    }

    @Test fun `zero past days starts at the current half hour`() {
        val now = at(utc, 2026, 11, 11, 20, 47)
        val b = GuideBounds.of(now, pastDays = 0, futureDays = 1, zone = utc)
        assertThat(b.originMs).isEqualTo(at(utc, 2026, 11, 11, 20, 30))
        assertThat(b.originMs).isAtMost(now)
        assertThat(b.endMs).isEqualTo(at(utc, 2026, 11, 12, 21, 0))
    }

    @Test fun `negative settings are treated as zero and the range is never empty`() {
        val now = at(utc, 2026, 11, 11, 20, 0)
        val b = GuideBounds.of(now, pastDays = -3, futureDays = -1, zone = utc)
        assertThat(b.originMs).isEqualTo(now)
        assertThat(b.endMs).isGreaterThan(b.originMs)
    }

    @Test fun `half hours follow the local clock in zones with a 45 minute offset`() {
        val kathmandu = ZoneId.of("Asia/Kathmandu") // UTC+5:45
        val now = at(kathmandu, 2026, 11, 11, 20, 17)
        val b = GuideBounds.of(now, 0, 1, kathmandu)
        assertThat(b.originMs).isEqualTo(at(kathmandu, 2026, 11, 11, 20, 0))
        assertThat(GuideBounds.ceilLocal(now, GuideBounds.HALF_HOUR_MS, kathmandu)).isEqualTo(at(kathmandu, 2026, 11, 11, 20, 30))
    }

    // ---- time <-> pixel ----

    private val bounds = GuideBounds(at(utc, 2026, 11, 10), at(utc, 2026, 11, 13))
    private val axis = GuideAxis(bounds, pxPerMinute = 9f)

    @Test fun `time maps to pixels and back`() {
        assertThat(axis.xOf(bounds.originMs)).isEqualTo(0f)
        assertThat(axis.xOf(bounds.originMs + hour)).isEqualTo(540f)
        assertThat(axis.widthOf(30 * 60_000L)).isEqualTo(270f)
        assertThat(axis.totalWidthPx).isEqualTo(3 * 24 * 540f)
        assertThat(axis.timeAt(540f)).isEqualTo(bounds.originMs + hour)
        val t = bounds.originMs + 37 * hour + 13 * 60_000L
        assertThat(axis.timeAt(axis.xOf(t))).isEqualTo(t)
    }

    @Test fun `pixel precision holds at the far end of a three week guide`() {
        val wide = GuideAxis(GuideBounds(0, 21 * day), pxPerMinute = 12f)
        val t = 21 * day - 5 * 60_000L
        // Float pixels: the round trip stays within a few seconds, far below one pixel (5 s).
        assertThat(wide.timeAt(wide.xOf(t)) - t).isIn(-5_000L..5_000L)
    }

    @Test fun `scroll is clamped to the bounds`() {
        val viewport = 1080f
        assertThat(axis.maxScroll(viewport)).isEqualTo(axis.totalWidthPx - viewport)
        assertThat(axis.clampScroll(-50f, viewport)).isEqualTo(0f)
        assertThat(axis.clampScroll(1e9f, viewport)).isEqualTo(axis.totalWidthPx - viewport)
        assertThat(axis.clampScroll(1234f, viewport)).isEqualTo(1234f)
        // A viewport wider than the whole guide cannot scroll.
        assertThat(axis.maxScroll(axis.totalWidthPx * 2)).isEqualTo(0f)
        assertThat(axis.clampScroll(10f, axis.totalWidthPx * 2)).isEqualTo(0f)
    }

    @Test fun `scrolling to a time clamps before the start and after the end`() {
        val viewport = 1080f
        assertThat(axis.scrollFor(bounds.originMs - day, viewport)).isEqualTo(0f)
        assertThat(axis.scrollFor(bounds.originMs + 2 * hour, viewport)).isEqualTo(1080f)
        assertThat(axis.scrollFor(bounds.endMs, viewport)).isEqualTo(axis.totalWidthPx - viewport)
    }

    @Test fun `visible span follows scroll and viewport`() {
        val v = axis.visible(540f, 1080f)
        assertThat(v).isEqualTo(TimeRange(bounds.originMs + hour, bounds.originMs + 3 * hour))
        // Before layout (viewport 0) the span is empty but valid.
        assertThat(axis.visible(540f, 0f).isEmpty).isTrue()
    }

    @Test fun `rebase keeps the left edge time when bounds or scale change`() {
        val left = bounds.originMs + 30 * hour
        val scroll = axis.xOf(left)
        val moved = GuideAxis(GuideBounds(bounds.originMs + hour, bounds.endMs + hour), 9f)
        assertThat(moved.timeAt(moved.rebase(scroll, axis, 1080f))).isEqualTo(left)
        val denser = GuideAxis(bounds, 12f)
        assertThat(denser.timeAt(denser.rebase(scroll, axis, 1080f))).isEqualTo(left)
        // A left edge that fell out of the new bounds clamps to their start.
        val later = GuideAxis(GuideBounds(left + day, left + 3 * day), 9f)
        assertThat(later.rebase(scroll, axis, 1080f)).isEqualTo(0f)
    }

    // ---- visible window -> cell window and query range ----

    @Test fun `cell window is whole hours plus a margin`() {
        val vis = TimeRange(bounds.originMs + 10 * hour + 20 * 60_000L, bounds.originMs + 11 * hour + 40 * 60_000L)
        assertThat(GuideWindows.cellWindow(vis, bounds)).isEqualTo(TimeRange(bounds.originMs + 9 * hour, bounds.originMs + 13 * hour))
        // Scrolling inside the same hour does not change it (rows are not recomposed).
        val nudged = TimeRange(vis.startMs + 5 * 60_000L, vis.endMs + 5 * 60_000L)
        assertThat(GuideWindows.cellWindow(nudged, bounds)).isEqualTo(GuideWindows.cellWindow(vis, bounds))
    }

    @Test fun `query range is whole six hour buckets plus one bucket each side`() {
        val vis = TimeRange(bounds.originMs + 25 * hour, bounds.originMs + 26 * hour + 30 * 60_000L)
        assertThat(GuideWindows.queryRange(vis, bounds)).isEqualTo(TimeRange(bounds.originMs + 18 * hour, bounds.originMs + 36 * hour))
        // A span crossing a bucket border covers both buckets.
        val crossing = TimeRange(bounds.originMs + 29 * hour, bounds.originMs + 31 * hour)
        assertThat(GuideWindows.queryRange(crossing, bounds)).isEqualTo(TimeRange(bounds.originMs + 18 * hour, bounds.originMs + 42 * hour))
    }

    @Test fun `windows never leave the bounds`() {
        val start = TimeRange(bounds.originMs, bounds.originMs + hour)
        assertThat(GuideWindows.queryRange(start, bounds).startMs).isEqualTo(bounds.originMs)
        assertThat(GuideWindows.cellWindow(start, bounds).startMs).isEqualTo(bounds.originMs)
        val end = TimeRange(bounds.endMs - hour, bounds.endMs)
        assertThat(GuideWindows.queryRange(end, bounds).endMs).isEqualTo(bounds.endMs)
        assertThat(GuideWindows.cellWindow(end, bounds).endMs).isEqualTo(bounds.endMs)
    }

    // ---- days ----

    @Test fun `day chips cover the bounds with offsets from today`() {
        val now = at(utc, 2026, 11, 11, 20, 17)
        val b = GuideBounds.of(now, 2, 1, utc)
        val days = GuideDays.build(b, now, utc)
        assertThat(days.map { it.offset }).containsExactly(-2, -1, 0, 1).inOrder()
        assertThat(days[2].startMs).isEqualTo(at(utc, 2026, 11, 11))
        assertThat(days[2].endMs).isEqualTo(at(utc, 2026, 11, 12))
        assertThat(GuideDays.dayAt(days, now)?.offset).isEqualTo(0)
        assertThat(GuideDays.dayAt(days, at(utc, 2026, 11, 10, 23, 59))?.offset).isEqualTo(-1)
        // The first day is partial: jumping to it lands on the guide start, other days on local midnight.
        assertThat(GuideDays.jumpTarget(days[0], b)).isEqualTo(b.originMs)
        assertThat(GuideDays.jumpTarget(days[3], b)).isEqualTo(at(utc, 2026, 11, 12))
    }

    @Test fun `a day ending exactly at midnight does not add an empty chip`() {
        val b = GuideBounds(at(utc, 2026, 11, 11), at(utc, 2026, 11, 12))
        assertThat(GuideDays.build(b, b.originMs, utc)).hasSize(1)
    }

    @Test fun `spring forward day is 23 hours long`() {
        val now = at(berlin, 2026, 3, 28, 12, 0)
        val days = GuideDays.build(GuideBounds.of(now, 1, 3, berlin), now, berlin)
        val dst = days.first { it.date.toString() == "2026-03-29" }
        assertThat(dst.endMs - dst.startMs).isEqualTo(23 * hour)
        assertThat(dst.offset).isEqualTo(1)
        assertThat(dst.startMs).isEqualTo(at(berlin, 2026, 3, 29))
        // Days stay contiguous across the change.
        days.zipWithNext().forEach { (a, b) -> assertThat(b.startMs).isEqualTo(a.endMs) }
    }

    @Test fun `fall back day is 25 hours long and day lookup uses local midnight`() {
        val now = at(berlin, 2026, 10, 24, 12, 0)
        val days = GuideDays.build(GuideBounds.of(now, 1, 3, berlin), now, berlin)
        val dst = days.first { it.date.toString() == "2026-10-25" }
        assertThat(dst.endMs - dst.startMs).isEqualTo(25 * hour)
        // 23:30 local on the long day is still that day, 24 h after its start.
        assertThat(GuideDays.dayAt(days, dst.startMs + 24 * hour + 30 * 60_000L)).isEqualTo(dst)
        assertThat(GuideDays.dayAt(days, dst.endMs)?.date.toString()).isEqualTo("2026-10-26")
        days.zipWithNext().forEach { (a, b) -> assertThat(b.startMs).isEqualTo(a.endMs) }
    }

    // ---- ruler ----

    @Test fun `ticks fall on local half hours`() {
        val from = at(berlin, 2026, 11, 11, 20, 10)
        val ticks = GuideTicks.between(TimeRange(from, from + 2 * hour), berlin)
        assertThat(ticks.map { it.timeMs }).containsExactly(
            at(berlin, 2026, 11, 11, 20, 30), at(berlin, 2026, 11, 11, 21, 0),
            at(berlin, 2026, 11, 11, 21, 30), at(berlin, 2026, 11, 11, 22, 0),
        ).inOrder()
        assertThat(ticks.map { it.fullHour }).containsExactly(false, true, false, true).inOrder()
    }

    @Test fun `a tick on the range start is included and the end is excluded`() {
        val from = at(utc, 2026, 11, 11, 20, 0)
        val ticks = GuideTicks.between(TimeRange(from, from + hour), utc)
        assertThat(ticks.map { it.timeMs }).containsExactly(from, from + hour / 2).inOrder()
        assertThat(GuideTicks.between(TimeRange(from, from), utc)).isEmpty()
    }

    @Test fun `ticks stay evenly spaced across a DST change`() {
        val from = at(berlin, 2026, 10, 25, 1, 0)
        val ticks = GuideTicks.between(TimeRange(from, from + 4 * hour), berlin)
        assertThat(ticks).hasSize(8)
        ticks.zipWithNext().forEach { (a, b) -> assertThat(b.timeMs - a.timeMs).isEqualTo(hour / 2) }
        // 02:00 happens twice that night; both are full-hour ticks.
        val labels = TimeLabels(berlin, is24Hour = true, locale = Locale.UK)
        assertThat(ticks.filter { it.fullHour }.map { labels.time(it.timeMs) }).containsExactly("01:00", "02:00", "02:00", "03:00").inOrder()
    }

    // ---- labels ----

    @Test fun `times use the zone and the 12 or 24 hour setting`() {
        val t = at(utc, 2026, 11, 11, 19, 5)
        assertThat(TimeLabels(berlin, true, Locale.UK).time(t)).isEqualTo("20:05")
        assertThat(TimeLabels(berlin, false, Locale.US).time(t)).isEqualTo("8:05 PM")
        assertThat(TimeLabels(utc, true, Locale.UK).range(t, t + hour)).isEqualTo("19:05 – 20:05")
        assertThat(TimeLabels(ZoneId.of("America/New_York"), false, Locale.US).time(t)).isEqualTo("2:05 PM")
    }

    @Test fun `day labels`() {
        val now = at(utc, 2026, 11, 11, 12, 0)
        val days = GuideDays.build(GuideBounds.of(now, 2, 2, utc), now, utc)
        val labels = TimeLabels(utc, true, Locale.UK)
        assertThat(days.map { labels.day(it) }).containsExactly("Mon 9", "Yesterday", "Today", "Tomorrow", "Fri 13").inOrder()
        assertThat(labels.longDay(now)).isEqualTo("Wed 11 Nov")
    }

    @Test fun `durations`() {
        assertThat(TimeLabels.duration(45 * 60_000L)).isEqualTo("45 min")
        assertThat(TimeLabels.duration(2 * hour)).isEqualTo("2 h")
        assertThat(TimeLabels.duration(hour + 30 * 60_000L)).isEqualTo("1 h 30 min")
        assertThat(TimeLabels.duration(10_000L)).isEqualTo("1 min")
    }
}
