package io.github.urzahil.iptv.core.xmltv

import java.time.LocalDateTime
import java.time.ZoneOffset

object XmltvTime {
    private val PATTERN = Regex("^(\\d{4})(\\d{2})(\\d{2})(\\d{2})?(\\d{2})?(\\d{2})?\\s*([+-]\\d{2}:?\\d{2}|Z|UTC|GMT)?$")

    /** Parses an XMLTV timestamp to epoch milliseconds, or null if malformed. */
    fun parse(text: String?): Long? {
        val m = PATTERN.matchEntire(text?.trim() ?: return null) ?: return null
        val g = m.groupValues
        return try {
            val ldt = LocalDateTime.of(
                g[1].toInt(), g[2].toInt(), g[3].toInt(),
                g[4].ifEmpty { "0" }.toInt(), g[5].ifEmpty { "0" }.toInt(), g[6].ifEmpty { "0" }.toInt(),
            )
            var offsetSec = 0
            val off = g[7]
            if (off.isNotEmpty() && (off[0] == '+' || off[0] == '-')) {
                val digits = off.substring(1).replace(":", "")
                offsetSec = (digits.substring(0, 2).toInt() * 3600 + digits.substring(2).toInt() * 60) *
                    (if (off[0] == '-') -1 else 1)
            }
            (ldt.toEpochSecond(ZoneOffset.UTC) - offsetSec) * 1000
        } catch (e: java.time.DateTimeException) {
            null
        }
    }
}
