package rocks.urzahil.iptv.core.util

import java.net.URLDecoder
import java.net.URLEncoder

object Urls {
    /** Appends [query] (with or without leading ? or &) to [url], keeping any #fragment at the end. */
    fun appendQuery(url: String, query: String): String {
        val q = query.trimStart('?', '&')
        if (q.isEmpty()) return url
        val hash = url.indexOf('#')
        val base = if (hash >= 0) url.substring(0, hash) else url
        val frag = if (hash >= 0) url.substring(hash) else ""
        val sep = when {
            '?' !in base -> "?"
            base.endsWith("?") || base.endsWith("&") -> ""
            else -> "&"
        }
        return base + sep + q + frag
    }

    /** Prepends [scheme]:// when the value has no scheme. */
    fun withDefaultScheme(url: String, scheme: String = "http"): String {
        val t = url.trim()
        return if (t.contains("://")) t else "$scheme://${t.trimStart('/')}"
    }

    /** Last non-empty path segment, ignoring query and fragment. Empty string if none. */
    fun lastPathSegment(url: String): String {
        var u = url.substringBefore('#').substringBefore('?')
        val schemeEnd = u.indexOf("://")
        val pathStart = if (schemeEnd >= 0) u.indexOf('/', schemeEnd + 3) else 0
        if (pathStart < 0) return ""
        u = u.substring(pathStart).trimEnd('/')
        return u.substringAfterLast('/')
    }

    /** Form-style encoding for query values: space becomes %20, not +. */
    fun encodeQuery(v: String): String = URLEncoder.encode(v, Charsets.UTF_8).replace("+", "%20")

    /** Path-segment encoding (spaces as %20, / encoded). */
    fun encodePath(v: String): String = encodeQuery(v)

    fun decode(v: String): String = try {
        URLDecoder.decode(v, Charsets.UTF_8)
    } catch (e: IllegalArgumentException) {
        v
    }
}
