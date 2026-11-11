package io.github.urzahil.iptv.core.xtream

import com.google.common.truth.Truth.assertThat
import java.util.Base64
import org.junit.Test

class Base64TextTest {
    private fun enc(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    @Test fun decodes_base64_utf8() {
        assertThat(Base64Text.decode(enc("Tagesschau – Nachrichten ü"))).isEqualTo("Tagesschau – Nachrichten ü")
        assertThat(Base64Text.decode(enc("line1\nline2"))).isEqualTo("line1\nline2")
    }

    @Test fun mime_style_with_whitespace() {
        val e = enc("A fairly long programme description that needs wrapping to test the mime decoder")
        val wrapped = e.chunked(20).joinToString("\r\n")
        assertThat(Base64Text.decode(wrapped)).startsWith("A fairly long")
    }

    @Test fun plain_text_fallback() {
        assertThat(Base64Text.decode("Evening News at 9!")).isEqualTo("Evening News at 9!")
        assertThat(Base64Text.decode("News")).isEqualTo("News")
        assertThat(Base64Text.decode("not*base64")).isEqualTo("not*base64")
    }

    @Test fun binary_garbage_falls_back_to_raw() {
        val raw = Base64.getEncoder().encodeToString(byteArrayOf(0, 1, 2, 3, 0xFF.toByte()))
        assertThat(Base64Text.decode(raw)).isEqualTo(raw)
    }

    @Test fun empty_and_null() {
        assertThat(Base64Text.decode(null)).isEmpty()
        assertThat(Base64Text.decode("  ")).isEmpty()
    }
}
