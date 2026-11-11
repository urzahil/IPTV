package rocks.urzahil.iptv.core.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StreamSchemesTest {
    @Test fun stream_schemes_are_allowed_in_any_case() {
        for (u in listOf(
            "http://h/a", "HTTPS://h/a", "rtsp://h/a", "rtmp://h/a", "rtmps://h/a", "udp://@239.1.1.1:1234", "rtp://h:1",
            "mms://h/a", "srt://h:1", "rist://h:1", "rtmpe://h/a", "rtmpt://h/a", "mmsh://h/a", "mmst://h/a", "mmsu://h/a",
        )) assertThat(StreamSchemes.isAllowed(u)).isTrue()
    }

    @Test fun local_and_odd_schemes_are_rejected() {
        for (u in listOf(
            "file:///etc/passwd", "content://media/external/1", "android.resource://pkg/1", "data:text/plain;base64,AAAA",
            "javascript:alert(1)", "ftp://h/a", "/sdcard/a.ts", "stream.ts", "://h/a", "", "ht tp://h", "1http://h",
        )) assertThat(StreamSchemes.isAllowed(u)).isFalse()
    }
}
