package rocks.urzahil.iptv.guide

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import rocks.urzahil.iptv.data.db.dao.ProgrammeLite
import rocks.urzahil.iptv.ui.guide.model.CellBuilder
import rocks.urzahil.iptv.ui.guide.model.GapCell
import rocks.urzahil.iptv.ui.guide.model.GuideCell
import rocks.urzahil.iptv.ui.guide.model.GuideClassifier
import rocks.urzahil.iptv.ui.guide.model.ProgrammeAction
import rocks.urzahil.iptv.ui.guide.model.ProgrammeCell
import rocks.urzahil.iptv.ui.guide.model.ProgrammeStatus
import rocks.urzahil.iptv.ui.guide.model.TimeRange
import rocks.urzahil.iptv.ui.guide.model.UnplayableReason

class GuideCellsTest {
    private val min = 60_000L
    private val hour = 60 * min
    private val day = 24 * hour
    private val now = 1_800_000_000_000L

    private fun p(id: Long, startMin: Long, endMin: Long) = ProgrammeLite(id, startMin * min, endMin * min, "P$id", 0)

    private fun spans(cells: List<GuideCell>) = cells.map { it.startMs / min to it.endMs / min }

    // ---- cells ----

    @Test fun `cells tile the window exactly`() {
        val cells = CellBuilder.build(listOf(p(1, 0, 60), p(2, 60, 90), p(3, 90, 180)), TimeRange(0, 180 * min), TimeRange(0, 180 * min))
        assertThat(spans(cells)).containsExactly(0L to 60L, 60L to 90L, 90L to 180L).inOrder()
        assertThat(cells.all { it is ProgrammeCell }).isTrue()
    }

    @Test fun `programmes are clipped at both window edges and keep their real times`() {
        val cells = CellBuilder.build(listOf(p(1, -30, 45), p(2, 45, 300)), TimeRange(0, 120 * min), TimeRange(-60 * min, 400 * min))
        assertThat(spans(cells)).containsExactly(0L to 45L, 45L to 120L).inOrder()
        val first = cells[0] as ProgrammeCell
        assertThat(first.programme.startMs).isEqualTo(-30 * min)
        assertThat((cells[1] as ProgrammeCell).programme.endMs).isEqualTo(300 * min)
    }

    @Test fun `programmes outside the window are skipped`() {
        val cells = CellBuilder.build(listOf(p(1, -120, 0), p(2, 0, 60), p(3, 60, 120)), TimeRange(0, 60 * min), TimeRange(-200 * min, 200 * min))
        assertThat(cells.map { it.key }).containsExactly(2L)
    }

    @Test fun `holes between programmes become known gaps`() {
        val cells = CellBuilder.build(listOf(p(1, 10, 30), p(2, 50, 70)), TimeRange(0, 90 * min), TimeRange(0, 90 * min))
        assertThat(spans(cells)).containsExactly(0L to 10L, 10L to 30L, 30L to 50L, 50L to 70L, 70L to 90L).inOrder()
        assertThat(cells.filterIsInstance<GapCell>().map { it.known }).containsExactly(true, true, true)
    }

    @Test fun `a channel without data is one known gap, unloaded is unknown`() {
        val w = TimeRange(0, 120 * min)
        assertThat(CellBuilder.build(emptyList(), w, w)).containsExactly(GapCell(0, 120 * min, known = true))
        assertThat(CellBuilder.build(emptyList(), w, null)).containsExactly(GapCell(0, 120 * min, known = false))
    }

    @Test fun `gaps are split at the edge of the loaded span`() {
        val cells = CellBuilder.build(listOf(p(1, 60, 90)), TimeRange(0, 240 * min), TimeRange(30 * min, 180 * min))
        assertThat(cells).containsExactly(
            GapCell(0, 30 * min, known = false),
            GapCell(30 * min, 60 * min, known = true),
            ProgrammeCell(p(1, 60, 90), 60 * min, 90 * min),
            GapCell(90 * min, 180 * min, known = true),
            GapCell(180 * min, 240 * min, known = false),
        ).inOrder()
    }

    @Test fun `overlapping feed entries are trimmed and covered ones dropped`() {
        val cells = CellBuilder.build(listOf(p(1, 0, 60), p(2, 30, 90), p(3, 40, 50), p(4, 90, 120)), TimeRange(0, 120 * min), TimeRange(0, 120 * min))
        assertThat(cells.map { it.key }).containsExactly(1L, 2L, 4L).inOrder()
        assertThat(spans(cells)).containsExactly(0L to 60L, 60L to 90L, 90L to 120L).inOrder()
    }

    @Test fun `keys are unique within a row`() {
        val cells = CellBuilder.build(listOf(p(1, 10, 30), p(-5, 50, 70)), TimeRange(0, 90 * min), TimeRange(20 * min, 60 * min))
        assertThat(cells.map { it.key }.toSet()).hasSize(cells.size)
    }

    @Test fun `empty window gives no cells`() {
        assertThat(CellBuilder.build(listOf(p(1, 0, 60)), TimeRange(10, 10), null)).isEmpty()
    }

    // ---- playable or not ----

    @Test fun `programme states around now`() {
        assertThat(GuideClassifier.classify(now - hour, now + hour, now, 0)).isEqualTo(ProgrammeStatus.NOW)
        assertThat(GuideClassifier.classify(now, now + hour, now, 0)).isEqualTo(ProgrammeStatus.NOW)
        assertThat(GuideClassifier.classify(now - hour, now, now, 0)).isEqualTo(ProgrammeStatus.PAST)
        assertThat(GuideClassifier.classify(now + 1, now + hour, now, 3)).isEqualTo(ProgrammeStatus.FUTURE)
    }

    @Test fun `past programmes are playable only inside the catchup window`() {
        assertThat(GuideClassifier.classify(now - 3 * hour, now - 2 * hour, now, 0)).isEqualTo(ProgrammeStatus.PAST)
        assertThat(GuideClassifier.classify(now - 3 * hour, now - 2 * hour, now, 2)).isEqualTo(ProgrammeStatus.PAST_PLAYABLE)
        // The window is measured from the programme start.
        assertThat(GuideClassifier.classify(now - 2 * day, now - 2 * day + hour, now, 2)).isEqualTo(ProgrammeStatus.PAST_PLAYABLE)
        assertThat(GuideClassifier.classify(now - 2 * day - min, now - 2 * day + hour, now, 2)).isEqualTo(ProgrammeStatus.PAST)
    }

    @Test fun `tvg-shift moves a programme between states`() {
        // Feed time: ended 20 minutes ago. With tvg-shift +1 h the same programme is still on air.
        val start = now - 80 * min
        val end = now - 20 * min
        assertThat(GuideClassifier.classify(start, end, now, 1)).isEqualTo(ProgrammeStatus.PAST_PLAYABLE)
        val shift = 60 * min
        assertThat(GuideClassifier.classify(start + shift, end + shift, now, 1)).isEqualTo(ProgrammeStatus.NOW)
        // With -1 day it falls out of a one-day archive.
        assertThat(GuideClassifier.classify(start - day, end - day, now, 1)).isEqualTo(ProgrammeStatus.PAST)
    }

    @Test fun `actions for the current programme`() {
        assertThat(GuideClassifier.options(now - hour, now + hour, now, 0).actions).containsExactly(ProgrammeAction.WATCH_LIVE)
        assertThat(GuideClassifier.options(now - hour, now + hour, now, 3).actions)
            .containsExactly(ProgrammeAction.WATCH_LIVE, ProgrammeAction.START_OVER).inOrder()
        // Started this very second: nothing to start over yet.
        assertThat(GuideClassifier.options(now, now + hour, now, 3).actions).containsExactly(ProgrammeAction.WATCH_LIVE)
        // A long programme that began before the archive does: only live.
        assertThat(GuideClassifier.options(now - 2 * day, now + hour, now, 1).actions).containsExactly(ProgrammeAction.WATCH_LIVE)
    }

    @Test fun `actions and reasons for past and future programmes`() {
        val playable = GuideClassifier.options(now - 3 * hour, now - 2 * hour, now, 2)
        assertThat(playable.actions).containsExactly(ProgrammeAction.WATCH_CATCHUP)
        assertThat(playable.reason).isNull()

        val noCatchup = GuideClassifier.options(now - 3 * hour, now - 2 * hour, now, 0)
        assertThat(noCatchup.actions).isEmpty()
        assertThat(noCatchup.reason).isEqualTo(UnplayableReason.NO_CATCHUP)

        val tooOld = GuideClassifier.options(now - 5 * day, now - 5 * day + hour, now, 2)
        assertThat(tooOld.actions).isEmpty()
        assertThat(tooOld.reason).isEqualTo(UnplayableReason.OUTSIDE_CATCHUP_WINDOW)

        val future = GuideClassifier.options(now + hour, now + 2 * hour, now, 2)
        assertThat(future.actions).isEmpty()
        assertThat(future.reason).isEqualTo(UnplayableReason.NOT_AIRED_YET)
    }
}
