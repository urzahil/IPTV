package rocks.urzahil.iptv.core.io

import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackInputStream
import java.io.Reader
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object TextInput {
    /** BOM-aware reader. UTF-8 with replacement of malformed bytes is the default. */
    fun reader(input: InputStream): Reader {
        val pb = PushbackInputStream(input, 4)
        val head = ByteArray(3)
        var n = 0
        while (n < 3) {
            val r = pb.read(head, n, 3 - n)
            if (r < 0) break
            n += r
        }
        var charset: Charset = Charsets.UTF_8
        var skip = 0
        if (n >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) {
            skip = 3
        } else if (n >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()) {
            charset = Charsets.UTF_16LE; skip = 2
        } else if (n >= 2 && head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte()) {
            charset = Charsets.UTF_16BE; skip = 2
        }
        if (n > skip) pb.unread(head, skip, n - skip)
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        return InputStreamReader(pb, decoder)
    }

    /** Longest accepted line. M3U lines are a few hundred bytes; a file without line breaks must not fill the memory. */
    const val MAX_LINE_LENGTH = 1024 * 1024

    /** Reads lines terminated by LF, CRLF or lone CR. Throws [InputLimitException] for a line over [maxLineLength]. */
    class LineReader(private val reader: Reader, private val maxLineLength: Int = MAX_LINE_LENGTH) {
        private val buf = CharArray(8192)
        private var len = 0
        private var pos = 0
        private var skipLf = false

        fun readLine(): String? {
            var sb: StringBuilder? = null
            while (true) {
                if (pos >= len) {
                    len = reader.read(buf, 0, buf.size)
                    pos = 0
                    if (len < 0) {
                        len = 0
                        return sb?.toString()
                    }
                    if (len == 0) continue
                }
                if (skipLf) {
                    skipLf = false
                    if (buf[pos] == '\n') { pos++; continue }
                }
                val start = pos
                while (pos < len && buf[pos] != '\n' && buf[pos] != '\r') pos++
                if (pos < len) {
                    if ((sb?.length ?: 0) + (pos - start) > maxLineLength) throw InputLimitException("Line too long")
                    val line = if (sb == null) String(buf, start, pos - start)
                    else sb.append(buf, start, pos - start).toString()
                    if (buf[pos] == '\r') skipLf = true
                    pos++
                    return line
                }
                if (sb == null) sb = StringBuilder()
                if (sb.length + (pos - start) > maxLineLength) throw InputLimitException("Line too long")
                sb.append(buf, start, pos - start)
            }
        }
    }

    fun lines(input: InputStream): LineReader = LineReader(reader(input))
}
