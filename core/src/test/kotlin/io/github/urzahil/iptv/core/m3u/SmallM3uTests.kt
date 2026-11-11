package io.github.urzahil.iptv.core.m3u

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VodDetectorTest {
    @Test fun segments_and_extensions() {
        assertThat(VodDetector.isVod("http://h/movie/u/p/1.ts")).isTrue()
        assertThat(VodDetector.isVod("http://h/series/u/p/1")).isTrue()
        assertThat(VodDetector.isVod("http://h/a/b.MP4?x=1")).isTrue()
        for (e in listOf("mkv", "avi", "mov", "wmv", "m4v", "webm", "mpg", "mpeg", "vob", "3gp"))
            assertThat(VodDetector.isVod("http://h/a.$e")).isTrue()
        assertThat(VodDetector.isVod("http://h/a.ts")).isFalse()
        assertThat(VodDetector.isVod("http://h/a.m3u8?f=x.mp4")).isFalse()
        assertThat(VodDetector.isVod("http://h/a.mpd")).isFalse()
        assertThat(VodDetector.isVod("http://h/live/u/p/1")).isFalse()
        assertThat(VodDetector.isVod("http://movie.example.com/live/1.ts")).isFalse()
    }

    @Test fun positive_duration_with_xtream_pattern() {
        assertThat(VodDetector.isVod("http://h/u/p/12", 5400.0)).isTrue()
        assertThat(VodDetector.isVod("http://h/u/p/12", -1.0)).isFalse()
        assertThat(VodDetector.isVod("http://h/u/p/12", 0.0)).isFalse()
        assertThat(VodDetector.isVod("http://h/live/u/p/12.ts", 10.0)).isFalse()
        assertThat(VodDetector.isVod("http://h/ch/index.m3u8", 10.0)).isFalse()
    }
}

class GroupResolverTest {
    @Test fun split_and_resolve() {
        assertThat(GroupResolver.split("A;B; ;A")).containsExactly("A", "B").inOrder()
        assertThat(GroupResolver.split(null)).isEmpty()
        assertThat(GroupResolver.resolve("", "X;Y")).containsExactly("X", "Y").inOrder()
        assertThat(GroupResolver.resolve(" ; ", null)).containsExactly("Uncategorized")
        assertThat(GroupResolver.resolve("G", "X")).containsExactly("G")
    }
}

class UrlPipeHeadersTest {
    @Test fun splits_suffix() {
        val (u, h) = UrlPipeHeaders.split("http://h/a.m3u8|User-Agent=A%20B&Referrer=http%3A%2F%2Fr&Cookie=x%3Dy&X-Custom=1")
        assertThat(u).isEqualTo("http://h/a.m3u8")
        assertThat(h!!.userAgent).isEqualTo("A B")
        assertThat(h.referrer).isEqualTo("http://r")
        assertThat(h.extra).containsExactly("Cookie", "x=y", "X-Custom", "1")
    }

    @Test fun no_suffix() {
        assertThat(UrlPipeHeaders.split("http://h/a.m3u8").second).isNull()
        assertThat(UrlPipeHeaders.split("http://h/a|notheaders").second).isNull()
    }

    @Test fun uses_last_bar_and_case_insensitive_keys() {
        val (u, h) = UrlPipeHeaders.split("http://h/a|b?x=1|USER-AGENT=ua&referer=r")
        assertThat(u).isEqualTo("http://h/a|b?x=1")
        assertThat(h!!.userAgent).isEqualTo("ua")
        assertThat(h.referrer).isEqualTo("r")
    }
}
