package rocks.urzahil.iptv.core.xtream

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object XtreamTime {
    val LOCAL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** Server zone: IANA/offset id, else offset derived from time_now vs timestamp_now (15 min steps), else UTC. */
    fun resolve(timezone: String?, timeNow: String?, timestampNow: Long?): ZoneId {
        parseZone(timezone)?.let { return it }
        if (timeNow != null && timestampNow != null) {
            try {
                val local = LocalDateTime.parse(timeNow.trim(), LOCAL_FORMAT).toEpochSecond(ZoneOffset.UTC)
                val diff = local - timestampNow
                val rounded = Math.round(diff / 900.0) * 900
                if (rounded in -18 * 3600..18 * 3600) return ZoneOffset.ofTotalSeconds(rounded.toInt())
            } catch (e: DateTimeException) {
            }
        }
        return ZoneOffset.UTC
    }

    /** Parses an IANA id or an offset like +01:00; null if invalid. */
    fun parseZone(value: String?): ZoneId? {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return null
        return try { ZoneId.of(v) } catch (e: DateTimeException) { null }
    }
}
