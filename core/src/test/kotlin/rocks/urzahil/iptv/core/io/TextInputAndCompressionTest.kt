package rocks.urzahil.iptv.core.io

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Test

class TextInputAndCompressionTest {
    private fun lines(b: ByteArray): List<String> {
        val r = TextInput.lines(ByteArrayInputStream(b))
        return generateSequence { r.readLine() }.toList()
    }

    @Test fun utf8_bom_and_line_endings() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "a\r\nb\rc\nd".toByteArray()
        assertThat(lines(bytes)).containsExactly("a", "b", "c", "d").inOrder()
    }

    @Test fun utf16_bom() {
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "x\ny".toByteArray(Charsets.UTF_16LE)
        assertThat(lines(le)).containsExactly("x", "y").inOrder()
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "é\n".toByteArray(Charsets.UTF_16BE)
        assertThat(lines(be)).containsExactly("é").inOrder()
    }

    @Test fun malformed_bytes_are_replaced() {
        val l = lines(byteArrayOf('a'.code.toByte(), 0xFF.toByte(), 'b'.code.toByte()))
        assertThat(l).containsExactly("a�b")
    }

    @Test fun crlf_split_across_buffer_boundary() {
        val sb = StringBuilder()
        sb.append("x".repeat(8191)).append("\r\n").append("next")
        assertThat(lines(sb.toString().toByteArray())).containsExactly("x".repeat(8191), "next").inOrder()
    }

    @Test fun empty_input() {
        assertThat(lines(ByteArray(0))).isEmpty()
    }

    @Test fun gzip_zip_and_plain_detected_by_magic() {
        val text = "<tv/>".toByteArray()
        val gz = ByteArrayOutputStream().also { GZIPOutputStream(it).use { g -> g.write(text) } }.toByteArray()
        val zip = ByteArrayOutputStream().also {
            ZipOutputStream(it).use { z -> z.putNextEntry(ZipEntry("a.xml")); z.write(text); z.closeEntry() }
        }.toByteArray()
        for (b in listOf(gz, zip, text)) {
            assertThat(Compression.open(ByteArrayInputStream(b)).readBytes()).isEqualTo(text)
        }
    }
}
