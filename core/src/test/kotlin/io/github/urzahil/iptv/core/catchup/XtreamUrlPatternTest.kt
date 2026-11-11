package io.github.urzahil.iptv.core.catchup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class XtreamUrlPatternTest {
    @Test fun live_forms() {
        val m = XtreamUrlPattern.match("http://xc.tv:8080/live/user/pass/123.ts")!!
        assertThat(m.base).isEqualTo("http://xc.tv:8080")
        assertThat(m.user).isEqualTo("user")
        assertThat(m.pass).isEqualTo("pass")
        assertThat(m.id).isEqualTo("123")
        assertThat(m.ext).isEqualTo("ts")
        assertThat(XtreamUrlPattern.match("https://h/user/pass/9")!!.ext).isNull()
        assertThat(XtreamUrlPattern.match("http://h/user/pass/9.m3u8?x=1")!!.id).isEqualTo("9")
    }

    @Test fun rejections() {
        assertThat(XtreamUrlPattern.match("http://h/movie/user/pass/9.mp4")).isNull()
        assertThat(XtreamUrlPattern.match("http://h/series/user/pass/9.mkv")).isNull()
        assertThat(XtreamUrlPattern.match("http://h/timeshift/user/pass/9")).isNull()
        assertThat(XtreamUrlPattern.match("http://h/streaming/a/b/9")).isNull()
        assertThat(XtreamUrlPattern.match("http://h/ch1/index.m3u8")).isNull()
        assertThat(XtreamUrlPattern.match("http://h/a/b/notnumeric.ts")).isNull()
        assertThat(XtreamUrlPattern.match("rtmp://h/user/pass/9")).isNull()
    }
}
