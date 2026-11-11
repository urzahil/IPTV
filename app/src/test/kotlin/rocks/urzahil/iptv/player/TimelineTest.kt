package rocks.urzahil.iptv.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import rocks.urzahil.iptv.player.model.PlayMode
import rocks.urzahil.iptv.player.model.ProgrammeGuide
import rocks.urzahil.iptv.player.model.ProgrammeSlot
import rocks.urzahil.iptv.player.model.TimeText
import rocks.urzahil.iptv.player.model.TimelineState
import java.time.ZoneOffset

class TimelineTest {
    private val h = 3_600_000L
    private val base = 1794340800_000L // 2026-11-10 20:00Z
    private fun p(startH: Double, endH: Double, t: String) = ProgrammeSlot(null, base + (startH * h).toLong(), base + (endH * h).toLong(), t)
    private val guide = ProgrammeGuide(listOf(p(0.0, 1.0, "A"), p(1.0, 1.5, "B"), p(2.0, 3.0, "C")))

    @Test fun windowIsTheProgrammeContainingTheWallPosition() {
        val w = TimelineState.windowAt(guide, base + h / 2)
        assertThat(w.programme!!.title).isEqualTo("A")
        assertThat(w.startMs).isEqualTo(base)
        assertThat(w.endMs).isEqualTo(base + h)
    }

    @Test fun windowRollsOverAtProgrammeEnd() {
        assertThat(TimelineState.windowAt(guide, base + h - 1).programme!!.title).isEqualTo("A")
        assertThat(TimelineState.windowAt(guide, base + h).programme!!.title).isEqualTo("B")
    }

    @Test fun gapBetweenProgrammesGetsPseudoSlotClippedToNeighbours() {
        val w = TimelineState.windowAt(guide, base + (1.75 * h).toLong())
        assertThat(w.programme).isNull()
        assertThat(w.startMs).isEqualTo(base + (1.5 * h).toLong())
        assertThat(w.endMs).isEqualTo(base + 2 * h)
    }

    @Test fun withoutEpgPseudoSlotIsTheClockHour() {
        val w = TimelineState.windowAt(ProgrammeGuide.EMPTY, base + 5 * h + 20 * 60_000)
        assertThat(w.startMs).isEqualTo(base + 5 * h)
        assertThat(w.endMs).isEqualTo(base + 6 * h)
    }

    @Test fun behindLiveAndFractions() {
        val now = base + (1.0 * h).toLong() + 30 * 60_000 // 21:30
        val st = TimelineState.of(PlayMode.CATCHUP, 3, now, base + 15 * 60_000, guide) // watching 20:15
        assertThat(st.behindLiveMs).isEqualTo(75 * 60_000L)
        assertThat(st.isBehindLive).isTrue()
        assertThat(st.positionFraction).isWithin(0.001f).of(0.25f)
        assertThat(st.airedFraction).isEqualTo(1f)
        assertThat(st.seekFloorMs).isEqualTo(now - 3 * 86_400_000L)
    }

    @Test fun liveModeIsNeverBehind() {
        val st = TimelineState.of(PlayMode.LIVE, 3, base + 10 * 60_000, base + 10 * 60_000, guide)
        assertThat(st.behindLiveMs).isEqualTo(0)
        assertThat(st.airedFraction).isWithin(0.001f).of(1f / 6)
    }

    @Test fun noCatchupMeansNoFloor() {
        assertThat(TimelineState.of(PlayMode.LIVE, 0, base, base, guide).seekFloorMs).isNull()
    }

    @Test fun textFormats() {
        assertThat(TimeText.duration(65_000)).isEqualTo("1:05")
        assertThat(TimeText.duration(3_723_000)).isEqualTo("1:02:03")
        assertThat(TimeText.duration(-5)).isEqualTo("0:00")
        assertThat(TimeText.clock(base, ZoneOffset.UTC)).isEqualTo("20:00")
        assertThat(TimeText.behindLive(754_000)).isEqualTo("−12:34 behind live")
    }
}
