package rocks.urzahil.iptv.core.xtream

import com.google.common.truth.Truth.assertThat
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Test

class XtreamTimeTest {
    @Test fun iana_zone() {
        assertThat(XtreamTime.resolve("Europe/Berlin", null, null)).isEqualTo(ZoneId.of("Europe/Berlin"))
    }

    @Test fun invalid_zone_falls_back_to_offset() {
        // local 13:00:40 vs real 12:00:00Z -> +1h (rounded to 15 min)
        val z = XtreamTime.resolve("Not/AZone", "2026-11-11 13:00:40", 1794398400L)
        assertThat(z).isEqualTo(ZoneOffset.ofHours(1))
        val z2 = XtreamTime.resolve(null, "2026-11-11 17:30:10", 1794398400L)
        assertThat(z2).isEqualTo(ZoneOffset.ofHoursMinutes(5, 30))
        val z3 = XtreamTime.resolve("", "2026-11-11 07:00:00", 1794398400L)
        assertThat(z3).isEqualTo(ZoneOffset.ofHours(-5))
    }

    @Test fun utc_fallback() {
        assertThat(XtreamTime.resolve("bogus", null, null)).isEqualTo(ZoneOffset.UTC)
        assertThat(XtreamTime.resolve(null, "garbage", 5L)).isEqualTo(ZoneOffset.UTC)
        assertThat(XtreamTime.resolve(null, "2026-11-11 12:00:00", null)).isEqualTo(ZoneOffset.UTC)
    }

    @Test fun parse_override() {
        assertThat(XtreamTime.parseZone("+02:00")).isEqualTo(ZoneOffset.ofHours(2))
        assertThat(XtreamTime.parseZone("x")).isNull()
    }
}
