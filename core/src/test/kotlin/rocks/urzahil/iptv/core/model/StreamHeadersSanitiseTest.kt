package rocks.urzahil.iptv.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import rocks.urzahil.iptv.core.m3u.M3uParser
import rocks.urzahil.iptv.core.m3u.UrlPipeHeaders

class StreamHeadersSanitiseTest {
    @Test fun crlf_in_a_value_cannot_inject_a_header() {
        val h = StreamHeaders.EMPTY.with("X-Token", "abc\r\nX-Evil: 1")
        assertThat(h.extra["X-Token"]).isEqualTo("abcX-Evil: 1")
        assertThat(h.toMap().values.none { '\r' in it || '\n' in it }).isTrue()
    }

    @Test fun pipe_suffix_with_encoded_crlf_is_cleaned() {
        val (_, h) = UrlPipeHeaders.split("http://h/s|User-Agent=a%0d%0aX: y&Referer=http://r/%0a")
        assertThat(h!!.userAgent).isEqualTo("aX: y")
        assertThat(h.referrer).isEqualTo("http://r/")
    }

    @Test fun non_ascii_and_control_characters_are_removed() {
        assertThat(StreamHeaders.sanitizeValue("Müller\u0000 TV\u007fé")).isEqualTo("Mller TV")
        assertThat(StreamHeaders.sanitizeValue("  a\tb  ")).isEqualTo("a\tb")
        assertThat(StreamHeaders.EMPTY.with("User-Agent", "Agent/1.0 (ü)").userAgent).isEqualTo("Agent/1.0 ()")
    }

    @Test fun invalid_header_names_are_dropped() {
        var h = StreamHeaders.EMPTY
        for (bad in listOf("", " ", "X Bad", "X:Bad", "X\r\nBad", "Na(me)", "Käse", "a/b", "a\"b")) h = h.with(bad, "v")
        assertThat(h.isEmpty).isTrue()
        assertThat(StreamHeaders.isToken("X-Custom_1.2")).isTrue()
        assertThat(StreamHeaders.EMPTY.with(" X-Ok ", "v").extra).containsExactly("X-Ok", "v")
    }

    @Test fun empty_or_fully_stripped_values_drop_the_header() {
        assertThat(StreamHeaders.EMPTY.with("X-A", "").isEmpty).isTrue()
        assertThat(StreamHeaders.EMPTY.with("X-A", "\r\n").isEmpty).isTrue()
        assertThat(StreamHeaders.EMPTY.with("X-A", "äöü").isEmpty).isTrue()
        // A cleaned-away override does not erase an existing value.
        assertThat(StreamHeaders(userAgent = "keep").with("User-Agent", "ü").userAgent).isEqualTo("keep")
    }

    @Test fun user_agent_and_referrer_aliases_still_route_to_their_fields() {
        val h = StreamHeaders.EMPTY.with("http-user-agent", "UA").with("HTTP-Referrer", "http://r").with("Cookie", "a=b")
        assertThat(h.userAgent).isEqualTo("UA")
        assertThat(h.referrer).isEqualTo("http://r")
        assertThat(h.extra).containsExactly("Cookie", "a=b")
    }

    @Test fun vlc_options_in_a_playlist_are_sanitised_too() {
        val m3u = "#EXTM3U\n#EXTINF:-1,One\n#EXTVLCOPT:http-user-agent=Zedé/1\n#EXTVLCOPT:http-referrer=http://r/ñ\nhttp://h/1\n"
        var headers: StreamHeaders? = null
        M3uParser().parse(m3u.byteInputStream()) { headers = it.headers }
        assertThat(headers!!.userAgent).isEqualTo("Zed/1")
        assertThat(headers!!.referrer).isEqualTo("http://r/")
    }
}
