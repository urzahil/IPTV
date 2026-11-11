package io.github.urzahil.iptv.core.m3u

/** Generates stable keys "tvgId|name|#dupIndex". Create one instance per parse. */
class ChannelKeys {
    private val seen = HashMap<String, Int>()

    fun next(tvgId: String?, name: String): String {
        val base = "${tvgId.orEmpty()}|$name"
        val idx = seen.merge(base, 1, Int::plus)!! - 1
        return "$base|#$idx"
    }
}
