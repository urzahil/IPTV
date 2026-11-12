package rocks.urzahil.iptv.core.epg

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProgrammeIdTest {
    @Test fun compose_and_decompose() {
        val start = 1794340800_000L
        val id = ProgrammeId.compose(42, start)
        assertThat(ProgrammeId.epgChannelOf(id)).isEqualTo(42)
        assertThat(ProgrammeId.startMinuteOf(id)).isEqualTo(start / 60_000)
    }

    @Test fun same_minute_dedupes() {
        assertThat(ProgrammeId.compose(1, 60_000)).isEqualTo(ProgrammeId.compose(1, 119_999))
        assertThat(ProgrammeId.compose(1, 60_000)).isNotEqualTo(ProgrammeId.compose(1, 120_000))
    }

    @Test fun range_bounds() {
        val t0 = 1794340800_000L
        val t1 = t0 + 6 * 3_600_000L
        val r = ProgrammeId.range(7, t0, t1)
        assertThat(r.first).isEqualTo(ProgrammeId.compose(7, t0 - ProgrammeId.MAX_PROG_MS))
        assertThat(r.last).isEqualTo(ProgrammeId.compose(7, t1))
        assertThat(ProgrammeId.compose(7, t0 - 3_600_000L) in r).isTrue()
        assertThat(ProgrammeId.compose(8, t0) in r).isFalse()
        assertThat(ProgrammeId.compose(6, t0) in r).isFalse()
        val all = ProgrammeId.channelRange(7)
        assertThat(ProgrammeId.compose(7, t0) in all).isTrue()
        assertThat(ProgrammeId.compose(8, 0) in all).isFalse()
    }
}
