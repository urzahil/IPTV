package rocks.urzahil.iptv.core.catchup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CatchupWindowTest {
    private val now = 1_000_000L
    private val day = 86_400L

    @Test fun availability_boundaries() {
        val w = CatchupWindow(3, now)
        assertThat(w.available(now - 3 * day)).isTrue()
        assertThat(w.available(now - 3 * day - 1)).isFalse()
        assertThat(w.available(now - 1)).isTrue()
        assertThat(w.available(now)).isFalse()
        assertThat(CatchupWindow(0, now).available(now - 10)).isFalse()
    }

    @Test fun clamping() {
        val w = CatchupWindow(2, now)
        assertThat(w.clamp(now + 100)).isEqualTo(now - 10)
        assertThat(w.clamp(0)).isEqualTo(now - 2 * day)
        assertThat(w.clamp(now - 500)).isEqualTo(now - 500)
        assertThat(w.clamp(now - 5 * day, windowStartSec = now - 1000)).isEqualTo(now - 1000)
        assertThat(w.clamp(now - 5, windowStartSec = now + 100)).isEqualTo(now - 10)
    }
}
