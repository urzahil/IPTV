package rocks.urzahil.iptv.core.io

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertThrows
import org.junit.Test

class InputLimitsTest {
    private fun guard(text: String, run: Int = 100, total: Long = 1_000_000L) =
        XmlGuardInputStream(ByteArrayInputStream(text.toByteArray()), run, total)

    private fun drain(s: java.io.InputStream) = s.readBytes().size

    // ---- lines

    @Test fun line_at_the_limit_is_accepted_and_one_over_is_rejected() {
        val ok = TextInput.LineReader(java.io.StringReader("a".repeat(100) + "\nnext"), maxLineLength = 100)
        assertThat(ok.readLine()).hasLength(100)
        assertThat(ok.readLine()).isEqualTo("next")
        val bad = TextInput.LineReader(java.io.StringReader("a".repeat(101) + "\n"), maxLineLength = 100)
        assertThrows(InputLimitException::class.java) { bad.readLine() }
    }

    @Test fun file_without_line_breaks_is_rejected_while_reading() {
        // Longer than several 8 KB buffer refills, never ends a line.
        val r = TextInput.LineReader(java.io.StringReader("x".repeat(50_000)), maxLineLength = 20_000)
        val e = assertThrows(IOException::class.java) { r.readLine() }
        assertThat(e).isInstanceOf(InputLimitException::class.java)
        assertThat(e).hasMessageThat().isEqualTo("Line too long")
    }

    @Test fun default_line_limit_is_one_megabyte() {
        assertThat(TextInput.MAX_LINE_LENGTH).isEqualTo(1024 * 1024)
        val r = TextInput.lines(ByteArrayInputStream("y".repeat(1024 * 1024 + 10).toByteArray()))
        assertThrows(InputLimitException::class.java) { r.readLine() }
    }

    // ---- total size

    @Test fun limited_stream_fails_past_the_limit() {
        val s = LimitedInputStream(ByteArrayInputStream(ByteArray(1000)), 999, "too big")
        val e = assertThrows(InputLimitException::class.java) { s.readBytes() }
        assertThat(e).hasMessageThat().isEqualTo("too big")
        assertThat(drain(LimitedInputStream(ByteArrayInputStream(ByteArray(1000)), 1000, "x"))).isEqualTo(1000)
    }

    // ---- XML nodes

    @Test fun normal_document_passes_unchanged() {
        val xml = """<?xml version="1.0"?><tv><channel id="a"><display-name>A</display-name></channel>
            <!-- a short comment --><programme channel="a"><title>T</title><desc><![CDATA[ x < y > z ]]></desc></programme></tv>"""
        assertThat(guard(xml, run = 200).readBytes().toString(Charsets.UTF_8)).isEqualTo(xml)
    }

    @Test fun huge_text_node_is_rejected() {
        val e = assertThrows(IOException::class.java) { drain(guard("<tv><desc>" + "a".repeat(500) + "</desc></tv>")) }
        assertThat(e).isInstanceOf(InputLimitException::class.java)
        assertThat(e).hasMessageThat().isEqualTo("Guide file is malformed")
    }

    @Test fun text_node_just_under_the_limit_is_accepted() {
        drain(guard("<tv><desc>" + "a".repeat(90) + "</desc></tv>"))
    }

    @Test fun huge_cdata_section_with_angle_brackets_is_rejected() {
        // Every '<' would reset a naive "bytes without <" counter.
        val bad = "<tv><desc><![CDATA[" + "<a".repeat(300) + "]]></desc></tv>"
        assertThrows(InputLimitException::class.java) { drain(guard(bad)) }
    }

    @Test fun huge_comment_is_rejected() {
        val bad = "<tv><!--" + "<-".repeat(300) + "--></tv>"
        assertThrows(InputLimitException::class.java) { drain(guard(bad)) }
    }

    @Test fun cdata_ends_at_its_terminator() {
        // Two sections that are each under the limit but together over it: the counter restarts after "]]>".
        val xml = "<tv><d><![CDATA[" + "a".repeat(60) + "]]><![CDATA[" + "b".repeat(60) + "]]></d></tv>"
        drain(guard(xml))
    }

    @Test fun run_of_open_brackets_without_a_close_is_rejected() {
        assertThrows(InputLimitException::class.java) { drain(guard("<tv>" + "<".repeat(500))) }
    }

    @Test fun huge_attribute_value_is_rejected() {
        assertThrows(InputLimitException::class.java) { drain(guard("""<tv a="${"v".repeat(500)}"/>""")) }
    }

    @Test fun total_decoded_size_is_limited() {
        val xml = "<a>t</a>".repeat(200)
        assertThrows(InputLimitException::class.java) { drain(guard(xml, run = 100, total = 500)) }
    }

    @Test fun single_byte_reads_and_skips_are_checked_too() {
        val s = guard("<tv><desc>" + "a".repeat(500) + "</desc></tv>")
        assertThrows(InputLimitException::class.java) { while (s.read() >= 0) Unit }
        val t = guard("<tv><desc>" + "a".repeat(500) + "</desc></tv>")
        assertThrows(InputLimitException::class.java) { t.skip(10_000) }
    }
}
