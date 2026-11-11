package io.github.urzahil.iptv.core.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UrlsTest {
    @Test fun last_segment() {
        assertThat(Urls.lastPathSegment("http://h/a/b/ch1.m3u8?x=1#f")).isEqualTo("ch1.m3u8")
        assertThat(Urls.lastPathSegment("http://h/a/b/")).isEqualTo("b")
        assertThat(Urls.lastPathSegment("http://h")).isEmpty()
    }

    @Test fun scheme_and_encoding() {
        assertThat(Urls.withDefaultScheme("h:8080/x")).isEqualTo("http://h:8080/x")
        assertThat(Urls.withDefaultScheme("https://h")).isEqualTo("https://h")
        assertThat(Urls.encodePath("a b&c+d#e/")).isEqualTo("a%20b%26c%2Bd%23e%2F")
    }
}
