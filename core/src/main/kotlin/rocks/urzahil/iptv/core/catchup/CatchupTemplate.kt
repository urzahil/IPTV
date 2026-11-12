package rocks.urzahil.iptv.core.catchup

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Placeholder expansion for catchup templates, supporting both {key} and ${key} with optional :argument. */
object CatchupTemplate {
    private val PLACEHOLDER = Regex("\\$?\\{([A-Za-z-]+)(?::([^}]+))?\\}")

    fun expand(template: String, req: CatchupRequest, tz: ZoneId = ZoneOffset.UTC): String =
        expand(template, req.startSec, req.endSec, req.nowSec, req.catchupId, tz)

    fun expand(template: String, s: Long, e: Long, n: Long, catchupId: String?, tz: ZoneId = ZoneOffset.UTC): String =
        PLACEHOLDER.replace(template) { m ->
            val key = m.groupValues[1]
            val arg = m.groups[2]?.value
            when (key) {
                "utc", "start" -> time(s, arg, tz)
                "utcend", "end" -> time(e, arg, tz)
                "lutc", "now", "timestamp" -> time(n, arg, tz)
                "offset" -> divide(n - s, arg) ?: m.value
                "duration" -> divide(e - s, arg) ?: m.value
                "Y", "m", "d", "H", "M", "S" -> format(s, key, tz)
                "catchup-id" -> catchupId.orEmpty()
                else -> m.value
            }
        }

    private fun time(epochSec: Long, fmt: String?, tz: ZoneId): String =
        if (fmt == null) epochSec.toString() else format(epochSec, fmt, tz)

    private fun divide(v: Long, arg: String?): String? {
        if (arg == null) return v.toString()
        val d = arg.trim().toLongOrNull()?.takeIf { it != 0L } ?: return null
        return (v / d).toString()
    }

    /** Replaces Y m d H M S with zero-padded components; other characters are literal. */
    fun format(epochSec: Long, fmt: String, tz: ZoneId): String {
        val t = Instant.ofEpochSecond(epochSec).atZone(tz)
        val sb = StringBuilder()
        for (c in fmt) {
            when (c) {
                'Y' -> sb.append(t.year.toString().padStart(4, '0'))
                'm' -> sb.append(two(t.monthValue))
                'd' -> sb.append(two(t.dayOfMonth))
                'H' -> sb.append(two(t.hour))
                'M' -> sb.append(two(t.minute))
                'S' -> sb.append(two(t.second))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun two(v: Int) = v.toString().padStart(2, '0')
}
