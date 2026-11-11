package io.github.urzahil.iptv.core.m3u

object VodDetector {
    private val VOD_EXT = setOf("mp4", "mkv", "avi", "mov", "wmv", "m4v", "webm", "mpg", "mpeg", "vob", "3gp")
    private val XTREAM_VOD_PATH = Regex("^/(?!live/|timeshift/|streaming/)[^/]+/[^/]+/\\d+(?:\\.[A-Za-z0-9]+)?$")

    /** [duration] is the #EXTINF duration (null if absent). */
    fun isVod(url: String, duration: Double? = null): Boolean {
        val noQuery = url.substringBefore('#').substringBefore('?')
        val schemeEnd = noQuery.indexOf("://")
        val pathStart = if (schemeEnd >= 0) noQuery.indexOf('/', schemeEnd + 3) else 0
        val path = if (pathStart >= 0) noQuery.substring(pathStart) else ""
        val segments = path.split('/')
        if (segments.any { it == "movie" || it == "series" }) return true
        val last = segments.lastOrNull().orEmpty()
        val dot = last.lastIndexOf('.')
        if (dot >= 0 && last.substring(dot + 1).lowercase() in VOD_EXT) return true
        if (duration != null && duration > 0 && XTREAM_VOD_PATH.matches(path)) return true
        return false
    }
}
