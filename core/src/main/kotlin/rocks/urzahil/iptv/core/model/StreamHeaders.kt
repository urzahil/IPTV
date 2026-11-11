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

    /** Sets a header by name, routing User-Agent and Referer(r) to the dedicated fields. */
    fun with(name: String, value: String): StreamHeaders = when (name.trim().lowercase()) {
        "user-agent", "http-user-agent" -> copy(userAgent = value)
        "referer", "referrer", "http-referrer", "http-referer" -> copy(referrer = value)
        else -> merge(StreamHeaders(extra = mapOf(name.trim() to value)))
    }

    companion object {
        val EMPTY = StreamHeaders()
    }
}
