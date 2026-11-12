package rocks.urzahil.iptv.core.m3u

import rocks.urzahil.iptv.core.model.StreamHeaders
import rocks.urzahil.iptv.core.util.Urls

object UrlPipeHeaders {
    /** Splits "url|User-Agent=..&Referer=.." into the clean URL and its headers (null if no suffix). */
    fun split(raw: String): Pair<String, StreamHeaders?> {
        val bar = raw.lastIndexOf('|')
        if (bar < 0) return raw to null
        val suffix = raw.substring(bar + 1)
        if ('=' !in suffix) return raw to null
        return raw.substring(0, bar).trim() to parseHeaderString(suffix)
    }

    /** Parses "K=V&K2=V2" (URL-decoded) into headers. */
    fun parseHeaderString(s: String): StreamHeaders {
        var h = StreamHeaders.EMPTY
        for (part in s.split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val k = Urls.decode(part.substring(0, eq)).trim()
            val v = Urls.decode(part.substring(eq + 1))
            if (k.isNotEmpty()) h = h.with(k, v)
        }
        return h
    }
}
