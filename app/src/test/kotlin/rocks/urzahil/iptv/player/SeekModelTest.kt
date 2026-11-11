package rocks.urzahil.iptv.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import rocks.urzahil.iptv.player.model.EndPolicy
import rocks.urzahil.iptv.player.model.EndedAction
import rocks.urzahil.iptv.player.model.EngineSeekInfo
import rocks.urzahil.iptv.player.model.PauseTracker
import rocks.urzahil.iptv.player.model.PlayMode
import rocks.urzahil.iptv.player.model.ProgrammeGuide
import rocks.urzahil.iptv.player.model.ProgrammeSlot
import rocks.urzahil.iptv.player.model.ResumeAction
import rocks.urzahil.iptv.player.model.SeekAction
import rocks.urzahil.iptv.player.model.SeekPlanner
import rocks.urzahil.iptv.player.model.SkipAccumulator
import rocks.urzahil.iptv.player.model.TimelineState

class SeekModelTest {
    private val min = 60_000L
    private val h = 60 * min
    private val t0 = 1794340800_000L // 20:00Z
    private val now = t0 + 4 * h // 24:00
    private val guide = ProgrammeGuide(
        listOf(
            ProgrammeSlot(1, t0, t0 + h, "A"), ProgrammeSlot(2, t0 + h, t0 + 2 * h, "B"),
            ProgrammeSlot(3, t0 + 2 * h, t0 + 3 * h, "C"), ProgrammeSlot(4, t0 + 3 * h, t0 + 5 * h, "D"),
        ),
    )

    private fun state(mode: PlayMode, wall: Long, days: Int = 1) = TimelineState.of(mode, days, now, wall, guide)

    @Test fun channelsWithoutCatchupRejectSeeks() {
        val s = state(PlayMode.LIVE, now, days = 0)
        assertThat(SeekPlanner.plan(now - h, s, guide, null)).isInstanceOf(SeekAction.Rejected::class.java)
    }

    @Test fun targetsNearLiveEdgeGoLive() {
        val s = state(PlayMode.CATCHUP, now - h)
        assertThat(SeekPlanner.plan(now - 14_000, s, guide, null)).isEqualTo(SeekAction.GoLive)
        assertThat(SeekPlanner.plan(now + h, s, guide, null)).isEqualTo(SeekAction.GoLive)
    }

    @Test fun targetsClampToCatchupWindow() {
        val s = state(PlayMode.LIVE, now, days = 1)
        val a = SeekPlanner.plan(now - 5 * 86_400_000L, s, guide, null) as SeekAction.Retune
        assertThat(a.startMs).isEqualTo(now - 86_400_000L)
        assertThat(SeekPlanner.clamp(now + 1, s)).isEqualTo(now - 10_000)
    }

    @Test fun liveRewindRetunesWithProgrammeEnd() {
        val s = state(PlayMode.LIVE, now)
        val a = SeekPlanner.plan(t0 + 3 * h + 20 * min, s, guide, null) as SeekAction.Retune
        assertThat(a.startMs).isEqualTo(t0 + 3 * h + 20 * min)
        assertThat(a.endMs).isEqualTo(t0 + 5 * h) // in the future: the "rewind current" case
    }

    @Test fun withoutEpgEndIsStartPlusTwoHours() {
        val s = TimelineState.of(PlayMode.LIVE, 1, now, now, ProgrammeGuide.EMPTY)
        val a = SeekPlanner.plan(now - 3 * h, s, ProgrammeGuide.EMPTY, null) as SeekAction.Retune
        assertThat(a.endMs - a.startMs).isEqualTo(2 * h)
    }

    @Test fun nativeSeekInsideSeekableArchive() {
        val s = state(PlayMode.CATCHUP, t0 + 10 * min)
        val info = EngineSeekInfo(true, h, t0)
        assertThat(SeekPlanner.plan(t0 + 30 * min, s, guide, info)).isEqualTo(SeekAction.NativeSeek(30 * min))
        // Beyond the archive stream's end: retune instead.
        assertThat(SeekPlanner.plan(t0 + 2 * h, s, guide, info)).isInstanceOf(SeekAction.Retune::class.java)
        // Before its origin: retune.
        assertThat(SeekPlanner.plan(t0 - h, s, guide, info)).isInstanceOf(SeekAction.Retune::class.java)
    }

    @Test fun nonSeekableOrUnknownDurationRetunes() {
        val s = state(PlayMode.CATCHUP, t0 + 10 * min)
        assertThat(SeekPlanner.plan(t0 + 30 * min, s, guide, EngineSeekInfo(false, h, t0))).isInstanceOf(SeekAction.Retune::class.java)
        assertThat(SeekPlanner.plan(t0 + 30 * min, s, guide, EngineSeekInfo(true, null, t0))).isInstanceOf(SeekAction.Retune::class.java)
    }

    @Test fun restartPreviousAndNextProgramme() {
        val s = state(PlayMode.CATCHUP, t0 + h + 20 * min) // inside B
        assertThat(SeekPlanner.restartTarget(s)).isEqualTo(t0 + h)
        assertThat(SeekPlanner.previousProgrammeStart(s, guide)).isEqualTo(t0)
        assertThat(SeekPlanner.nextProgrammeStart(s)).isEqualTo(t0 + 2 * h)
        val first = state(PlayMode.CATCHUP, t0 + 5 * min)
        assertThat(SeekPlanner.previousProgrammeStart(first, guide)).isEqualTo(t0 - h)
    }

    @Test fun nextProgrammeThatJustStartedBecomesLive() {
        val s = state(PlayMode.CATCHUP, t0 + 2 * h + 10 * min) // inside C; next is D, started an hour ago
        assertThat(SeekPlanner.plan(SeekPlanner.nextProgrammeStart(s), s, guide, null)).isInstanceOf(SeekAction.Retune::class.java)
        val s2 = TimelineState.of(PlayMode.CATCHUP, 1, t0 + 3 * h + 5_000, t0 + 2 * h, guide) // D started 5 s ago
        assertThat(SeekPlanner.plan(SeekPlanner.nextProgrammeStart(s2), s2, guide, null)).isEqualTo(SeekAction.GoLive)
    }

    @Test fun skipAccumulatesAndAccelerates() {
        val a = SkipAccumulator(quietMs = 700)
        var t = 0L
        val base = 1_000_000L
        var last = base
        repeat(3) { last = a.skip(base, 10_000, -1, t); t += 300 }
        assertThat(last).isEqualTo(base - 30_000)
        last = a.skip(base, 10_000, -1, t); t += 300 // 4th tap: x3
        assertThat(last).isEqualTo(base - 60_000)
        assertThat(a.take()).isEqualTo(base - 60_000)
        assertThat(a.pendingMs).isNull()
    }

    @Test fun skipStreakResetsAfterQuietPeriod() {
        val a = SkipAccumulator(quietMs = 700)
        repeat(5) { a.skip(0, 10_000, 1, it * 100L) }
        val before = a.pendingMs!!
        val next = a.skip(0, 10_000, 1, 10_000)
        assertThat(next - before).isEqualTo(10_000)
        assertThat(SkipAccumulator.multiplier(0)).isEqualTo(1)
        assertThat(SkipAccumulator.multiplier(3)).isEqualTo(3)
        assertThat(SkipAccumulator.multiplier(6)).isEqualTo(12)
    }

    @Test fun pauseOnLiveCatchupChannelResumesBehindLive() {
        val p = PauseTracker()
        p.onPause(0, PlayMode.LIVE, now)
        assertThat(p.onResume(5_000, true)).isEqualTo(ResumeAction.Continue)
        p.onPause(0, PlayMode.LIVE, now)
        assertThat(p.onResume(11_000, true)).isEqualTo(ResumeAction.RetuneCatchup(now))
    }

    @Test fun pauseOnLiveWithoutCatchupReprepares() {
        val p = PauseTracker()
        p.onPause(0, PlayMode.LIVE, now)
        assertThat(p.onResume(20_000, false)).isEqualTo(ResumeAction.Continue)
        p.onPause(0, PlayMode.LIVE, now)
        assertThat(p.onResume(40_000, false)).isEqualTo(ResumeAction.ReprepareLive)
    }

    @Test fun longPauseInArchiveRetunesAtPausedPosition() {
        val p = PauseTracker()
        p.onPause(0, PlayMode.CATCHUP, t0)
        assertThat(p.onResume(10_000, true)).isEqualTo(ResumeAction.Continue)
        p.onPause(0, PlayMode.CATCHUP, t0 + 5)
        assertThat(p.onResume(200_000, true)).isEqualTo(ResumeAction.RetuneCatchup(t0 + 5))
        assertThat(p.onResume(300_000, true)).isEqualTo(ResumeAction.Continue) // not paused any more
    }

    @Test fun endOfLiveStreamReconnects() {
        assertThat(EndPolicy.onEnded(PlayMode.LIVE, now, now, guide)).isEqualTo(EndedAction.Reconnect)
    }

    @Test fun endOfArchiveContinuesIntoNextProgrammeOrGoesLive() {
        val a = EndPolicy.onEnded(PlayMode.CATCHUP, t0 + h, now, guide) as EndedAction.Retune
        assertThat(a.startMs).isEqualTo(t0 + h)
        assertThat(a.endMs).isEqualTo(t0 + 2 * h)
        assertThat(EndPolicy.onEnded(PlayMode.CATCHUP, now - 20_000, now, guide)).isEqualTo(EndedAction.GoLive)
    }
}
