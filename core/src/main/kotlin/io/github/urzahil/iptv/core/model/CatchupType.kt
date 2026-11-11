package io.github.urzahil.iptv.core.model

enum class CatchupType {
    NONE, DEFAULT, APPEND, SHIFT, FLUSSONIC,

    /** The `xc` M3U type: Xtream-style URL found in the playlist. */
    XTREAM,

    /** Native Xtream API source. */
    XTREAM_API;

    companion object {
        /** Maps the value of an M3U `catchup` / `catchup-type` attribute. Unknown values map to [NONE]. */
        fun fromAttr(value: String?): CatchupType {
            val v = value?.trim()?.lowercase().orEmpty()
            return when {
                v.isEmpty() -> NONE
                v == "default" -> DEFAULT
                v == "append" -> APPEND
                v == "shift" -> SHIFT
                v == "fs" || v.startsWith("flussonic") -> FLUSSONIC
                v == "xc" -> XTREAM
                v == "vod" -> NONE
                else -> NONE
            }
        }
    }
}
