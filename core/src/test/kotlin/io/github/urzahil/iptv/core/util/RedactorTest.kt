package io.github.urzahil.iptv.core.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RedactorTest {
    private val r = Redactor()

    @Test fun query_params() {
        assertThat(r.redact("GET http://h/player_api.php?username=bob&password=s3cr3t&action=x"))
            .isEqualTo("GET http://h/player_api.php?username=***&password=***&action=x")
        assertThat(r.redact("http://h/a?Token=abc123")).isEqualTo("http://h/a?Token=***")
    }

    @Test fun xtream_paths() {
        assertThat(r.redact("http://h:80/live/bob/s3cr3t/12.ts")).isEqualTo("http://h:80/live/***/***/12.ts")
        assertThat(r.redact("http://h/timeshift/bob/pw/60/2026-11-10:20-00/12.ts"))
            .isEqualTo("http://h/timeshift/***/***/60/2026-11-10:20-00/12.ts")
        assertThat(r.redact("http://h/movie/bob/pw/12.mp4")).isEqualTo("http://h/movie/***/***/12.mp4")
    }

    @Test fun userinfo() {
        assertThat(r.redact("failed for http://bob:pw@host/x")).isEqualTo("failed for http://***@host/x")
    }

    @Test fun registered_secrets_in_exception_text() {
        r.register("hunter22")
        r.register("p&ss#1")
        val e = java.io.IOException("Unexpected end for hunter22 at p%26ss%231 and p&ss#1")
        val out = r.redact(e)
        assertThat(out).doesNotContain("hunter22")
        assertThat(out).doesNotContain("p%26ss%231")
        assertThat(out).doesNotContain("p&ss#1")
        assertThat(out).contains("IOException")
        r.clear()
        assertThat(r.redact("hunter22")).isEqualTo("hunter22")
    }

    @Test fun short_secrets_ignored_and_null_safe() {
        r.register("a")
        assertThat(r.redact("a banana")).isEqualTo("a banana")
        assertThat(r.redact(null)).isEmpty()
    }
}
