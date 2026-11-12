package rocks.urzahil.iptv.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PrunerWindowTest {
    private val now = 1_794_398_400_000L
    private val day = PrunerWindow.DAY_MS

    @Test fun channelWithoutCatchupKeepsNothingInThePast() {
        assertThat(PrunerWindow.keepPastDays(7, 0)).isEqualTo(0)
        assertThat(PrunerWindow.pastCutoffMs(now, 0)).isEqualTo(now)
    }

    @Test fun catchupChannelKeepsSmallerOfSettingAndArchive() {
        assertThat(PrunerWindow.keepPastDays(7, 3)).isEqualTo(3)
        assertThat(PrunerWindow.keepPastDays(2, 14)).isEqualTo(2)
        assertThat(PrunerWindow.pastCutoffMs(now, 3)).isEqualTo(now - 3 * day)
    }

    @Test fun pastSettingZeroKeepsNothingEvenWithCatchup() {
        assertThat(PrunerWindow.keepPastDays(0, 7)).isEqualTo(0)
    }

    @Test fun futureCutoff() {
        assertThat(PrunerWindow.futureCutoffMs(now, 3)).isEqualTo(now + 3 * day)
    }

    @Test fun staleness() {
        val h = 3_600_000L
        assertThat(StalenessPolicy.due(0, 0, now)).isTrue() // never synced
        assertThat(StalenessPolicy.due(now - 25 * h, 24, now)).isTrue()
        assertThat(StalenessPolicy.due(now - 23 * h, 24, now)).isFalse()
        assertThat(StalenessPolicy.due(now - 1000 * h, 0, now)).isFalse() // manual only
    }
}
