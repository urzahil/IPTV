package rocks.urzahil.iptv.core.util

/** Which URL schemes a playlist entry may use. Local schemes (file, content, data, ...) are never played. */
object StreamSchemes {
    val ALLOWED = setOf(
        "http", "https", "rtsp", "rtmp", "rtmps", "rtmpe", "rtmpt", "udp", "rtp",
        "mms", "mmsh", "mmst", "mmsu", "srt", "rist",
    )

    fun schemeOf(url: String): String? {
        val colon = url.indexOf(':')
        if (colon <= 0) return null
        val s = url.substring(0, colon).trim()
        if (s.isEmpty() || !s[0].isLetter() || !s.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return null
        return s.lowercase()
    }

    fun isAllowed(url: String): Boolean = schemeOf(url) in ALLOWED
}
