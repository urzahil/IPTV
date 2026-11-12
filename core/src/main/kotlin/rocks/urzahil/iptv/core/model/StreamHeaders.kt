package rocks.urzahil.iptv.core.model

/** HTTP headers for a stream. [extra] keys are compared case-insensitively. */
data class StreamHeaders(
    val userAgent: String? = null,
    val referrer: String? = null,
    val extra: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = userAgent == null && referrer == null && extra.isEmpty()

    /** Returns headers where non-null values of [over] take precedence over this. */
    fun merge(over: StreamHeaders): StreamHeaders {
        val merged = LinkedHashMap<String, String>()
        val names = HashMap<String, String>()
        for ((k, v) in extra) { names[k.lowercase()] = k; merged[k] = v }
        for ((k, v) in over.extra) {
            val old = names[k.lowercase()]
            if (old != null) merged.remove(old)
            names[k.lowercase()] = k
            merged[k] = v
        }
        return StreamHeaders(over.userAgent ?: userAgent, over.referrer ?: referrer, merged)
    }

    /** Flat header map (User-Agent and Referer included). */
    fun toMap(): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        userAgent?.let { m["User-Agent"] = it }
        referrer?.let { m["Referer"] = it }
        m.putAll(extra)
        return m
    }

    /**
     * Sets a header by name, routing User-Agent and Referer(r) to the dedicated fields. Playlist data is untrusted:
     * the value is reduced to printable ASCII (no CR/LF or other control characters, no non-ASCII) and the header
     * is dropped when the name is not an RFC 7230 token or the cleaned value is empty.
     */
    fun with(name: String, value: String): StreamHeaders {
        val n = name.trim()
        val v = sanitizeValue(value)
        if (v.isEmpty() || !isToken(n)) return this
        return when (n.lowercase()) {
            "user-agent", "http-user-agent" -> copy(userAgent = v)
            "referer", "referrer", "http-referrer", "http-referer" -> copy(referrer = v)
            else -> merge(StreamHeaders(extra = mapOf(n to v)))
        }
    }

    companion object {
        val EMPTY = StreamHeaders()

        /** Keeps tab and printable ASCII only, trimmed. Safe for an HTTP header value or a libVLC option. */
        fun sanitizeValue(value: String): String = value.filter { it == '\t' || it in ' '..'~' }.trim()

        /** RFC 7230 token: one or more of the visible characters except delimiters. */
        fun isToken(name: String): Boolean = name.isNotEmpty() && name.all { it in TOKEN_CHARS }

        private const val TOKEN_CHARS = "!#$%&'*+-.^_`|~0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    }
}
