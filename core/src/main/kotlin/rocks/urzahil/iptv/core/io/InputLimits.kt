package rocks.urzahil.iptv.core.io

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** Untrusted input exceeded a size limit. Retrying cannot help, so callers treat it as permanent. */
class InputLimitException(message: String) : IOException(message)

/** Fails with [InputLimitException] once more than [maxBytes] have been read. */
class LimitedInputStream(input: InputStream, private val maxBytes: Long, private val message: String) : FilterInputStream(input) {
    private var total = 0L

    private fun count(n: Int) {
        if (n <= 0) return
        total += n
        if (total > maxBytes) throw InputLimitException(message)
    }

    override fun read(): Int = super.read().also { if (it >= 0) count(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { count(it) }

    override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) count(it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }

    override fun markSupported(): Boolean = false
}

/**
 * Guards the decoded bytes of an XML document against nodes the parser would buffer whole (the platform parser
 * keeps a complete text node in memory, even for elements the caller skips).
 *
 * It fails when more than [maxRun] bytes pass without a `<`, or without a `>`, or inside one CDATA section or
 * comment, and when more than [maxTotal] bytes pass in all. Normal documents never get near these limits.
 */
class XmlGuardInputStream(
    input: InputStream,
    private val maxRun: Int = DEFAULT_MAX_RUN,
    maxTotal: Long = DEFAULT_MAX_TOTAL,
    private val message: String = "Guide file is malformed",
) : FilterInputStream(LimitedInputStream(input, maxTotal, message)) {
    private var sinceOpen = 0
    private var sinceClose = 0
    private var matchCdata = 0
    private var matchComment = 0
    /** 0 = normal, 1 = in CDATA, 2 = in comment. */
    private var special = 0
    private var sinceSpecial = 0
    private var endRun = 0

    private fun scan(b: Int) {
        if (special != 0) {
            if (++sinceSpecial > maxRun) throw InputLimitException(message)
            val endChar = if (special == 1) ']'.code else '-'.code
            when {
                b == '>'.code && endRun >= 2 -> { special = 0; endRun = 0; sinceOpen = 0; sinceClose = 0 }
                b == endChar -> endRun++
                else -> endRun = 0
            }
            return
        }
        if (b == '<'.code) {
            sinceOpen = 0
            matchCdata = 1
            matchComment = 1
        } else {
            sinceOpen++
            matchCdata = if (matchCdata in 1 until CDATA.length && b == CDATA[matchCdata].code) matchCdata + 1 else 0
            matchComment = if (matchComment in 1 until COMMENT.length && b == COMMENT[matchComment].code) matchComment + 1 else 0
        }
        if (b == '>'.code) sinceClose = 0 else sinceClose++
        if (sinceOpen > maxRun || sinceClose > maxRun) throw InputLimitException(message)
        if (matchCdata == CDATA.length) { special = 1; sinceSpecial = 0; endRun = 0; matchCdata = 0 }
        else if (matchComment == COMMENT.length) { special = 2; sinceSpecial = 0; endRun = 0; matchComment = 0 }
    }

    override fun read(): Int = super.read().also { if (it >= 0) scan(it) }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        for (i in 0 until n) scan(b[off + i].toInt() and 0xFF)
        return n
    }

    override fun skip(n: Long): Long {
        // Skipped bytes must be scanned too.
        val buf = ByteArray(minOf(n, 8192L).toInt().coerceAtLeast(1))
        var left = n
        while (left > 0) {
            val r = read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (r < 0) break
            left -= r
        }
        return n - left
    }

    override fun markSupported(): Boolean = false

    companion object {
        const val DEFAULT_MAX_RUN = 4 * 1024 * 1024
        const val DEFAULT_MAX_TOTAL = 4L * 1024 * 1024 * 1024
        private const val CDATA = "<![CDATA["
        private const val COMMENT = "<!--"
    }
}
