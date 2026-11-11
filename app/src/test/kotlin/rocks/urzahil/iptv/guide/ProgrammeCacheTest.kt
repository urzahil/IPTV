package rocks.urzahil.iptv.guide

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import rocks.urzahil.iptv.data.db.dao.ProgrammeLite
import rocks.urzahil.iptv.data.repo.EpgRequest
import rocks.urzahil.iptv.ui.guide.model.GuideRowKey
import rocks.urzahil.iptv.ui.guide.model.LoadPlanner
import rocks.urzahil.iptv.ui.guide.model.ProgrammeCache
import rocks.urzahil.iptv.ui.guide.model.TimeRange

class ProgrammeCacheTest {
    private val hour = 3_600_000L

    private fun r(fromH: Long, toH: Long) = TimeRange(fromH * hour, toH * hour)

    /** Fake EPG: every channel has back-to-back one-hour programmes; channel ids >= 100 have none. */
    private class FakeEpg {
        val calls = ArrayList<Triple<List<Long>, Long, Long>>()
        suspend fun load(requests: List<EpgRequest>, from: Long, to: Long): Map<Long, List<ProgrammeLite>> {
            calls += Triple(requests.map { it.channelId }, from / 3_600_000L, to / 3_600_000L)
            return requests.associate { req ->
                req.channelId to if (req.epgChannelId >= 100) emptyList() else
                    (from / 3_600_000L until (to + 3_599_999L) / 3_600_000L).map { h ->
                        ProgrammeLite((req.epgChannelId shl 32) or h, h * 3_600_000L, (h + 1) * 3_600_000L, "c${req.channelId} h$h", 0)
                    }
            }
        }
    }

    private fun key(id: Long, epg: Long? = id) = GuideRowKey(id, epg, 0)

    // ---- planner ----

    @Test fun `first request loads the whole range`() {
        val plan = LoadPlanner.plan(null, r(6, 24))
        assertThat(plan.ranges).containsExactly(r(6, 24))
        assertThat(plan.reset).isTrue()
        assertThat(plan.span).isEqualTo(r(6, 24))
    }

    @Test fun `covered request loads nothing`() {
        val plan = LoadPlanner.plan(r(6, 24), r(12, 18))
        assertThat(plan.ranges).isEmpty()
        assertThat(plan.span).isEqualTo(r(6, 24))
    }

    @Test fun `scrolling on loads only the missing side`() {
        val right = LoadPlanner.plan(r(6, 24), r(12, 30))
        assertThat(right.ranges).containsExactly(r(24, 30))
        assertThat(right.reset).isFalse()
        assertThat(right.span).isEqualTo(r(6, 30))
        val left = LoadPlanner.plan(r(6, 24), r(0, 18))
        assertThat(left.ranges).containsExactly(r(0, 6))
        assertThat(left.span).isEqualTo(r(0, 24))
        val both = LoadPlanner.plan(r(6, 12), r(0, 18))
        assertThat(both.ranges).containsExactly(r(0, 6), r(12, 18)).inOrder()
    }

    @Test fun `a jump replaces the loaded span`() {
        val plan = LoadPlanner.plan(r(6, 24), r(72, 90))
        assertThat(plan.ranges).containsExactly(r(72, 90))
        assertThat(plan.reset).isTrue()
        assertThat(plan.span).isEqualTo(r(72, 90))
    }

    @Test fun `the kept span is capped on the far side`() {
        val right = LoadPlanner.plan(r(0, 48), r(36, 54))
        assertThat(right.ranges).containsExactly(r(48, 54))
        assertThat(right.span).isEqualTo(r(6, 54))
        val left = LoadPlanner.plan(r(6, 54), r(0, 18))
        assertThat(left.span).isEqualTo(r(0, 48))
    }

    @Test fun `merge removes duplicates and programmes outside the span`() {
        val a = listOf(ProgrammeLite(1, 0, hour, "a", 0), ProgrammeLite(2, hour, 2 * hour, "b", 0))
        val b = listOf(ProgrammeLite(2, hour, 2 * hour, "b", 0), ProgrammeLite(3, 2 * hour, 3 * hour, "c", 0))
        assertThat(LoadPlanner.merge(a, b, r(0, 3)).map { it.id }).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(LoadPlanner.merge(a, b, r(1, 3)).map { it.id }).containsExactly(2L, 3L).inOrder()
    }

    // ---- cache ----

    @Test fun `visible channels load in one batched query and rows fill in`() = runTest {
        val epg = FakeEpg()
        val cache = ProgrammeCache(epg::load)
        val row = cache.row(1)
        assertThat(row.value.span).isNull()
        cache.ensure(listOf(key(1), key(2), key(3)), r(6, 12))
        assertThat(epg.calls).containsExactly(Triple(listOf(1L, 2L, 3L), 6L, 12L))
        assertThat(row.value.span).isEqualTo(r(6, 12))
        assertThat(row.value.programmes).hasSize(6)
        assertThat(cache.row(3).value.programmes.first().title).isEqualTo("c3 h6")
    }

    @Test fun `asking again for a loaded window does not query`() = runTest {
        val epg = FakeEpg()
        val cache = ProgrammeCache(epg::load)
        cache.ensure(listOf(key(1), key(2)), r(6, 12))
        cache.ensure(listOf(key(1), key(2)), r(6, 12))
        cache.ensure(listOf(key(2)), r(7, 11))
        assertThat(epg.calls).hasSize(1)
    }

    @Test fun `only the missing part and the new channels are queried`() = runTest {
        val epg = FakeEpg()
        val cache = ProgrammeCache(epg::load)
        cache.ensure(listOf(key(1), key(2)), r(6, 12))
        epg.calls.clear()
        cache.ensure(listOf(key(1), key(2), key(3)), r(6, 18))
        assertThat(epg.calls).containsExactly(Triple(listOf(1L, 2L), 12L, 18L), Triple(listOf(3L), 6L, 18L))
        val row = cache.row(1).value
        assertThat(row.span).isEqualTo(r(6, 18))
        assertThat(row.programmes.map { it.startMs / hour }).isEqualTo((6L until 18L).toList())
    }

    @Test fun `channels without a guide mapping are never queried and count as loaded`() = runTest {
        val epg = FakeEpg()
        val cache = ProgrammeCache(epg::load)
        val empty = cache.ensure(listOf(key(1), key(7, epg = null)), r(0, 6))
        assertThat(epg.calls).containsExactly(Triple(listOf(1L), 0L, 6L))
        assertThat(cache.row(7).value.span).isEqualTo(r(0, 6))
        assertThat(cache.row(7).value.programmes).isEmpty()
        assertThat(empty).containsExactly(7L)
    }

    @Test fun `rows without programmes in the window are reported for the fallback`() = runTest {
        val cache = ProgrammeCache(FakeEpg()::load)
        assertThat(cache.ensure(listOf(key(1), key(2, epg = 100)), r(0, 6))).containsExactly(2L)
    }

    @Test fun `fallback programmes show until the database has data`() = runTest {
        val cache = ProgrammeCache(FakeEpg()::load)
        cache.ensure(listOf(key(2, epg = 100)), r(0, 6))
        cache.setFallback(2, listOf(ProgrammeLite(-9, hour, 2 * hour, "short", 0)))
        val row = cache.row(2).value
        assertThat(row.fallback).isTrue()
        assertThat(row.programmes.single().title).isEqualTo("short")
        assertThat(row.span).isEqualTo(r(0, 6))
        // The channel gets mapped to a guide channel with data: the database wins.
        cache.ensure(listOf(key(2, epg = 5)), r(0, 6))
        assertThat(cache.row(2).value.fallback).isFalse()
        assertThat(cache.row(2).value.programmes).hasSize(6)
    }

    @Test fun `a changed mapping or shift reloads the row`() = runTest {
        val epg = FakeEpg()
        val cache = ProgrammeCache(epg::load)
        cache.ensure(listOf(key(1)), r(0, 6))
        cache.ensure(listOf(GuideRowKey(1, 1, shiftMin = 60)), r(0, 6))
        assertThat(epg.calls).hasSize(2)
    }

    @Test fun `invalidate reloads but keeps showing the old rows meanwhile`() = runTest {
        val epg = FakeEpg()
        val cache = ProgrammeCache(epg::load)
        cache.ensure(listOf(key(1)), r(0, 6))
        cache.invalidate()
        assertThat(cache.row(1).value.programmes).hasSize(6)
        cache.ensure(listOf(key(1)), r(0, 6))
        assertThat(epg.calls).hasSize(2)
        assertThat(cache.row(1).value.span).isEqualTo(r(0, 6))
    }

    @Test fun `least recently used channels are dropped, visible ones never`() = runTest {
        val cache = ProgrammeCache(FakeEpg()::load, maxChannels = 4)
        cache.ensure((1L..4L).map { key(it) }, r(0, 6))
        cache.ensure(listOf(key(1)), r(0, 6)) // touch 1
        cache.ensure(listOf(key(5), key(6)), r(0, 6))
        assertThat(cache.size).isEqualTo(4)
        assertThat(cache.row(1).value.span).isNotNull()
        assertThat(cache.row(5).value.span).isNotNull()
        // 2 was evicted: asking for its row starts empty again.
        assertThat(cache.row(2).value.span).isNull()
        // More visible rows than the limit: all of them stay.
        cache.ensure((10L..19L).map { key(it) }, r(0, 6))
        assertThat((10L..19L).all { cache.row(it).value.span != null }).isTrue()
    }

    @Test fun `empty requests do nothing`() = runTest {
        val epg = FakeEpg()
        val cache = ProgrammeCache(epg::load)
        assertThat(cache.ensure(emptyList(), r(0, 6))).isEmpty()
        assertThat(cache.ensure(listOf(key(1)), r(6, 6))).isEmpty()
        assertThat(epg.calls).isEmpty()
    }
}
