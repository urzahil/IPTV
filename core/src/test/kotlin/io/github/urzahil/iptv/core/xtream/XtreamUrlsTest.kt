package io.github.urzahil.iptv.core.xtream

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class XtreamUrlsTest {
    @Test fun base_normalisation() {
        assertThat(XtreamUrls.normalizeBase("host.tv")).isEqualTo("http://host.tv")
        assertThat(XtreamUrls.normalizeBase("  http://host.tv:8080/ ")).isEqualTo("http://host.tv:8080")
        assertThat(XtreamUrls.normalizeBase("https://host.tv/player_api.php?username=u&password=p")).isEqualTo("https://host.tv")
        assertThat(XtreamUrls.normalizeBase("host.tv:25461/get.php?username=u&password=p&type=m3u")).isEqualTo("http://host.tv:25461")
        assertThat(XtreamUrls.normalizeBase("http://host.tv/xmltv.php")).isEqualTo("http://host.tv")
        assertThat(XtreamUrls.normalizeBase("http://host.tv/panel/")).isEqualTo("http://host.tv/panel")
        assertThat(XtreamUrls.normalizeBase("http://host.tv/panel/player_api.php")).isEqualTo("http://host.tv/panel")
    }

    private val b = "http://h:80"

    @Test fun credentials_are_encoded() {
        assertThat(XtreamUrls.account(b, "us er", "p&s+s#1"))
            .isEqualTo("http://h:80/player_api.php?username=us%20er&password=p%26s%2Bs%231")
        assertThat(XtreamUrls.live(b, "u", "p&s+s#1", 5)).isEqualTo("http://h:80/live/u/p%26s%2Bs%231/5.ts")
        assertThat(XtreamUrls.live(b, "a b", "p", 5)).isEqualTo("http://h:80/live/a%20b/p/5.ts")
    }

    @Test fun every_endpoint() {
        val a = "http://h:80/player_api.php?username=u&password=p"
        assertThat(XtreamUrls.account(b, "u", "p")).isEqualTo(a)
        assertThat(XtreamUrls.liveCategories(b, "u", "p")).isEqualTo("$a&action=get_live_categories")
        assertThat(XtreamUrls.liveStreams(b, "u", "p")).isEqualTo("$a&action=get_live_streams")
        assertThat(XtreamUrls.shortEpg(b, "u", "p", 12, 4)).isEqualTo("$a&action=get_short_epg&stream_id=12&limit=4")
        assertThat(XtreamUrls.simpleDataTable(b, "u", "p", 12)).isEqualTo("$a&action=get_simple_data_table&stream_id=12")
        assertThat(XtreamUrls.xmltv(b, "u", "p")).isEqualTo("http://h:80/xmltv.php?username=u&password=p")
        assertThat(XtreamUrls.timeshiftPath(b, "u", "p", 60, "2026-11-10:20-00", "7")).isEqualTo("http://h:80/timeshift/u/p/60/2026-11-10:20-00/7.ts")
        assertThat(XtreamUrls.timeshiftPhp(b, "u", "p", 60, "2026-11-10:20-00", "7"))
            .isEqualTo("http://h:80/streaming/timeshift.php?username=u&password=p&stream=7&start=2026-11-10:20-00&duration=60")
    }

    @Test fun live_ts_and_m3u8() {
        assertThat(XtreamUrls.live(b, "u", "p", 9)).isEqualTo("http://h:80/live/u/p/9.ts")
        assertThat(XtreamUrls.live(b, "u", "p", 9, "m3u8")).isEqualTo("http://h:80/live/u/p/9.m3u8")
    }
}
