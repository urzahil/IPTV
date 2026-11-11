package io.github.urzahil.iptv.core.catchup

/** Recognises Xtream-style stream URLs: scheme://host[:port]/[live/]user/pass/id[.ext]. */
object XtreamUrlPattern {
    private val PATTERN = Regex("^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\\d+)(?:\\.([A-Za-z0-9]+))?(?:\\?.*)?$")
    private val REJECTED = setOf("movie", "series", "timeshift", "streaming")

    data class Match(val base: String, val user: String, val pass: String, val id: String, val ext: String?)

    fun match(url: String): Match? {
        val m = PATTERN.matchEntire(url.trim()) ?: return null
        val user = m.groupValues[2]
        if (user in REJECTED) return null
        return Match(m.groupValues[1], user, m.groupValues[3], m.groupValues[4], m.groupValues[5].ifEmpty { null })
    }
}
