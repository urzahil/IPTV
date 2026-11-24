package rocks.urzahil.iptv.core.model

/**
 * [code] is what the database stores. It is fixed per constant (the values equal the old ordinals, so existing rows
 * stay valid) and must never be changed or reused, whatever happens to the declaration order.
 */
enum class CatchupType(val code: Int) {
    NONE(0), DEFAULT(1), APPEND(2), SHIFT(3), FLUSSONIC(4),

    /** The `xc` M3U type: Xtream-style URL found in the playlist. */
    XTREAM(5),

    /** Native Xtream API source. */
    XTREAM_API(6);

    companion object {
        /** Unknown codes map to [NONE]. */
        fun fromCode(code: Int): CatchupType = entries.firstOrNull { it.code == code } ?: NONE

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
