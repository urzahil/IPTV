package io.github.urzahil.iptv.core.io

import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** Detects gzip / zip by magic bytes (never by extension or content type). */
object Compression {
    private const val BUFFER = 64 * 1024

    fun open(input: InputStream): InputStream {
        val s = if (input.markSupported()) input else BufferedInputStream(input, BUFFER)
        s.mark(8)
        val b0 = s.read()
        val b1 = s.read()
        val b2 = s.read()
        val b3 = s.read()
        s.reset()
        return when {
            b0 == 0x1F && b1 == 0x8B -> BufferedInputStream(GZIPInputStream(s, BUFFER), BUFFER)
            b0 == 0x50 && b1 == 0x4B && b2 == 0x03 && b3 == 0x04 -> {
                val zip = ZipInputStream(s)
                // first non-directory entry
                var e = zip.nextEntry
                while (e != null && e.isDirectory) e = zip.nextEntry
                if (e == null) java.io.ByteArrayInputStream(ByteArray(0)) else BufferedInputStream(zip, BUFFER)
            }
            else -> s
        }
    }
}
