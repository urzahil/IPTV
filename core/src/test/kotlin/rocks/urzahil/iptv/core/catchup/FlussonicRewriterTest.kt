package rocks.urzahil.iptv.core.catchup

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class FlussonicRewriterTest {
    @Test fun hls_any_name() {
        assertThat(FlussonicRewriter.rewrite("http://h:81/a/b/mono.m3u8?x=1", 100, 200)).isEqualTo("http://h:81/a/b/mono-100-200.m3u8?x=1")
        assertThat(FlussonicRewriter.rewrite("http://h/ch/playlist.m3u8", 5, 60)).isEqualTo("http://h/ch/playlist-5-60.m3u8")
    }

    @Test fun mpegts_and_ts() {
        assertThat(FlussonicRewriter.rewrite("http://h/ch/mpegts", 100, 60)).isEqualTo("http://h/ch/timeshift_abs-100.ts")
        assertThat(FlussonicRewriter.rewrite("http://h/ch/live.ts?t=1", 100, 60)).isEqualTo("http://h/ch/timeshift_abs-100.ts?t=1")
    }

    @Test fun bare_channel_with_and_without_trailing_slash() {
        assertThat(FlussonicRewriter.rewrite("http://h/ch", 100, 60)).isEqualTo("http://h/ch/timeshift_abs-100.ts")
        assertThat(FlussonicRewriter.rewrite("http://h/ch/", 100, 60)).isEqualTo("http://h/ch/timeshift_abs-100.ts")
    }

    @Test fun unsupported() {
        assertThrows(UnsupportedCatchup::class.java) { FlussonicRewriter.rewrite("http://h/ch/video.mp4", 1, 1) }
        assertThrows(UnsupportedCatchup::class.java) { FlussonicRewriter.rewrite("http://h", 1, 1) }
        assertThrows(UnsupportedCatchup::class.java) { FlussonicRewriter.rewrite("ch/x", 1, 1) }
    }
}
