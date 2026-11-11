package rocks.urzahil.iptv.core.catchup

import com.google.common.truth.Truth.assertThat
import java.time.ZoneId
import org.junit.Test

class CatchupTemplateTest {
    private val r = CatchupRequest(1794340800L, 1794344400L, 1794398400L, "id7")
    private fun x(t: String, tz: String = "UTC") = CatchupTemplate.expand(t, r, ZoneId.of(tz))

    @Test fun epoch_placeholders_both_syntaxes() {
        for ((k, v) in listOf("utc" to "1794340800", "start" to "1794340800", "utcend" to "1794344400", "end" to "1794344400",
            "lutc" to "1794398400", "now" to "1794398400", "timestamp" to "1794398400",
            "offset" to "57600", "duration" to "3600")) {
            assertThat(x("{$k}")).isEqualTo(v)
            assertThat(x("\${$k}")).isEqualTo(v)
        }
    }

    @Test fun component_placeholders() {
        assertThat(x("{Y}-{m}-{d} {H}:{M}:{S}")).isEqualTo("2026-11-10 20:00:00")
        assertThat(x("{Y}{m}{d}{H}{M}{S}", "Asia/Tokyo")).isEqualTo("20261111050000")
    }

    @Test fun divisors() {
        assertThat(x("{duration:60}")).isEqualTo("60")
        assertThat(x("{offset:3600}")).isEqualTo("16")
        assertThat(x("{utc:1000}".replace("utc", "duration"))).isEqualTo("3")
    }

    @Test fun format_strings() {
        assertThat(x("{utc:Y-m-d H:M:S}")).isEqualTo("2026-11-10 20:00:00")
        assertThat(x("{lutc:Ymd}")).isEqualTo("20261111")
        assertThat(x("{utcend:H}", "Europe/Berlin")).isEqualTo("22")
    }

    @Test fun catchup_id_and_unknown() {
        assertThat(x("a{catchup-id}b")).isEqualTo("aid7b")
        assertThat(x("{unknown}\${nope:x}")).isEqualTo("{unknown}\${nope:x}")
        assertThat(CatchupTemplate.expand("{catchup-id}", r.copy(catchupId = null))).isEqualTo("")
    }
}
