package rocks.urzahil.iptv.core.catchup

import com.google.common.truth.Truth.assertThat
import rocks.urzahil.iptv.core.model.CatchupSpec
import rocks.urzahil.iptv.core.model.CatchupType
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertThrows
import org.junit.Test

class CatchupUrlBuilderTest {
    private val S = 1794340800L
    private val E = 1794344400L
    private val N = 1794398400L
    private val req = CatchupRequest(S, E, N)
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun spec(t: CatchupType, src: String? = null, tz: ZoneId = ZoneOffset.UTC, corr: Long = 0) =
        CatchupSpec(t, src, 7, corr, tz)

    private fun url(u: String, s: CatchupSpec, r: CatchupRequest = req, x: XtreamTarget? = null) =
        CatchupUrlBuilder.build(u, s, r, x)

    @Test fun ex01_append_with_source() {
        assertThat(url("http://p.tv/live/ch1.m3u8", spec(CatchupType.APPEND, "?utc={utc}&lutc={lutc}")).url)
            .isEqualTo("http://p.tv/live/ch1.m3u8?utc=1794340800&lutc=1794398400")
    }

    @Test fun ex02_append_existing_query() {
        assertThat(url("http://p.tv/ch1.m3u8?token=abc", spec(CatchupType.APPEND, "?utc={utc}&lutc={lutc}")).url)
            .isEqualTo("http://p.tv/ch1.m3u8?token=abc&utc=1794340800&lutc=1794398400")
    }

    @Test fun ex03_append_no_source() {
        assertThat(url("http://p.tv/ch1.ts", spec(CatchupType.APPEND)).url)
            .isEqualTo("http://p.tv/ch1.ts?utc=1794340800&lutc=1794398400")
    }

    @Test fun ex04_shift() {
        assertThat(url("http://p.tv/ch1/index.m3u8?token=abc", spec(CatchupType.SHIFT)).url)
            .isEqualTo("http://p.tv/ch1/index.m3u8?token=abc&utc=1794340800&lutc=1794398400")
    }

    @Test fun ex05_default_dollar_placeholders() {
        val src = "http://arch.p.tv/ch1/\${start}-\${end}/index.m3u8?d=\${duration}&o=\${offset}&n=\${timestamp}"
        assertThat(url("http://p.tv/ch1", spec(CatchupType.DEFAULT, src)).url)
            .isEqualTo("http://arch.p.tv/ch1/1794340800-1794344400/index.m3u8?d=3600&o=57600&n=1794398400")
    }

    @Test fun ex06_default_components_and_divisor() {
        val src = "http://a.tv/ch1/{Y}/{m}/{d}/{H}-{M}-{S}.ts?dur={duration:60}"
        assertThat(url("http://p.tv/ch1", spec(CatchupType.DEFAULT, src)).url)
            .isEqualTo("http://a.tv/ch1/2026/11/10/20-00-00.ts?dur=60")
    }

    @Test fun ex07_default_format_strings() {
        val src = "http://a.tv/ch1?b={utc:Y-m-dTH:M:S}&e={utcend:YmdHMS}"
        assertThat(url("http://p.tv/ch1", spec(CatchupType.DEFAULT, src)).url)
            .isEqualTo("http://a.tv/ch1?b=2026-11-10T20:00:00&e=20261110210000")
    }

    @Test fun ex08_default_timezone() {
        assertThat(url("http://p.tv/ch1", spec(CatchupType.DEFAULT, "http://a.tv/x/{Y}{m}{d}{H}{M}.ts", berlin)).url)
            .isEqualTo("http://a.tv/x/202611102100.ts")
    }

    @Test fun ex09_catchup_id() {
        assertThat(url("http://p.tv/ch1", spec(CatchupType.DEFAULT, "http://a.tv/c?id={catchup-id}"), req.copy(catchupId = "ev42")).url)
            .isEqualTo("http://a.tv/c?id=ev42")
    }

    @Test fun ex10_flussonic_index() {
        assertThat(url("http://fl.tv/ch1/index.m3u8?token=abc", spec(CatchupType.FLUSSONIC)).url)
            .isEqualTo("http://fl.tv/ch1/index-1794340800-3600.m3u8?token=abc")
    }

    @Test fun ex11_flussonic_video() {
        assertThat(url("http://fl.tv/ch1/video.m3u8", spec(CatchupType.FLUSSONIC)).url)
            .isEqualTo("http://fl.tv/ch1/video-1794340800-3600.m3u8")
    }

    @Test fun ex12_flussonic_mpegts_via_fs_attr() {
        assertThat(url("http://fl.tv/ch1/mpegts?token=abc", spec(CatchupType.fromAttr("fs"))).url)
            .isEqualTo("http://fl.tv/ch1/timeshift_abs-1794340800.ts?token=abc")
    }

    @Test fun ex13_flussonic_bare_channel() {
        assertThat(url("http://fl.tv/ch1", spec(CatchupType.FLUSSONIC)).url)
            .isEqualTo("http://fl.tv/ch1/timeshift_abs-1794340800.ts")
    }

    @Test fun ex14_xtream_m3u_live_ts() {
        val r = url("http://xc.tv:8080/live/user/pass/123.ts", spec(CatchupType.XTREAM))
        assertThat(r.url).isEqualTo("http://xc.tv:8080/timeshift/user/pass/60/2026-11-10:20-00/123.ts")
        assertThat(r.streamOriginSec).isEqualTo(S)
        assertThat(r.initialSeekMs).isEqualTo(0)
    }

    @Test fun ex15_xtream_m3u_no_live_no_ext() {
        assertThat(url("http://xc.tv:8080/user/pass/123", spec(CatchupType.XTREAM)).url)
            .isEqualTo("http://xc.tv:8080/timeshift/user/pass/60/2026-11-10:20-00/123.ts")
    }

    private val target = XtreamTarget("http://xc.tv:8080", "user", "pass", "123")

    @Test fun ex16_xtream_api_berlin() {
        assertThat(url("", spec(CatchupType.XTREAM_API, tz = berlin), x = target).url)
            .isEqualTo("http://xc.tv:8080/timeshift/user/pass/60/2026-11-10:21-00/123.ts")
    }

    @Test fun ex17_xtream_api_php() {
        assertThat(url("", spec(CatchupType.XTREAM_API, tz = berlin), x = target.copy(style = TimeshiftStyle.PHP)).url)
            .isEqualTo("http://xc.tv:8080/streaming/timeshift.php?username=user&password=pass&stream=123&start=2026-11-10:21-00&duration=60")
    }

    // programme 11:30-12:30Z, rewind to 11:45
    private val progEnd = 1794400200L

    @Test fun ex18_rewind_current() {
        val r = url("", spec(CatchupType.XTREAM_API), CatchupRequest(1794397500L, progEnd, N), target)
        assertThat(r.url).isEqualTo("http://xc.tv:8080/timeshift/user/pass/45/2026-11-11:11-45/123.ts")
        assertThat(r.streamOriginSec).isEqualTo(1794397500L)
        assertThat(r.initialSeekMs).isEqualTo(0)
    }

    @Test fun ex19_seconds_become_initial_seek() {
        val r = url("", spec(CatchupType.XTREAM_API), CatchupRequest(1794397530L, progEnd, N), target)
        assertThat(r.url).isEqualTo("http://xc.tv:8080/timeshift/user/pass/45/2026-11-11:11-45/123.ts")
        assertThat(r.streamOriginSec).isEqualTo(1794397500L)
        assertThat(r.initialSeekMs).isEqualTo(30_000)
    }

    @Test fun ex20_password_is_encoded() {
        val t = target.copy(pass = "p&ss#1")
        assertThat(url("", spec(CatchupType.XTREAM_API), x = t).url).contains("/user/p%26ss%231/")
        assertThat(url("", spec(CatchupType.XTREAM_API), x = t.copy(style = TimeshiftStyle.PHP)).url)
            .contains("password=p%26ss%231&")
    }

    @Test fun ex21_correction_applies_to_start() {
        assertThat(url("http://p.tv/ch1/index.m3u8?token=abc", spec(CatchupType.SHIFT, corr = 3600)).url)
            .contains("utc=1794344400")
    }

    @Test fun ex22_movie_url_unsupported() {
        assertThrows(UnsupportedCatchup::class.java) {
            url("http://xc.tv:8080/movie/user/pass/123.mp4", spec(CatchupType.XTREAM))
        }
    }

    @Test fun default_without_source_falls_back() {
        assertThat(url("http://xc.tv:8080/live/user/pass/123.ts", spec(CatchupType.DEFAULT)).url)
            .contains("/timeshift/user/pass/60/")
        assertThat(url("http://p.tv/ch1.m3u8", spec(CatchupType.DEFAULT)).url).contains("utc=$S")
    }

    @Test fun minimum_duration_is_one_minute() {
        val r = url("", spec(CatchupType.XTREAM_API), CatchupRequest(S, S, N), target)
        assertThat(r.url).contains("/60/".replace("60", "1"))
    }

    @Test fun none_throws() {
        assertThrows(UnsupportedCatchup::class.java) { url("http://p.tv/a", spec(CatchupType.NONE)) }
    }

    @Test fun append_query_keeps_fragment() {
        assertThat(rocks.urzahil.iptv.core.util.Urls.appendQuery("http://a/b?x=1#f", "&y=2")).isEqualTo("http://a/b?x=1&y=2#f")
        assertThat(rocks.urzahil.iptv.core.util.Urls.appendQuery("http://a/b?", "y=2")).isEqualTo("http://a/b?y=2")
    }
}
