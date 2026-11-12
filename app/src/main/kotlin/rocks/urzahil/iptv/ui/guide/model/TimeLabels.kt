package rocks.urzahil.iptv.ui.guide.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Text for times and days in the device zone, honouring the device's 12/24-hour setting. */
class TimeLabels(val zone: ZoneId, val is24Hour: Boolean, locale: Locale = Locale.getDefault()) {
    private val timeFormat = DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a", locale).withZone(zone)
    private val shortDay = DateTimeFormatter.ofPattern("EEE d", locale).withZone(zone)
    private val longDay = DateTimeFormatter.ofPattern("EEE d MMM", locale).withZone(zone)

    fun time(ms: Long): String = timeFormat.format(Instant.ofEpochMilli(ms))

    fun range(startMs: Long, endMs: Long): String = "${time(startMs)} – ${time(endMs)}"

    /** "Wed 11". */
    fun shortDay(ms: Long): String = shortDay.format(Instant.ofEpochMilli(ms))

    /** "Wed 11 Nov". */
    fun longDay(ms: Long): String = longDay.format(Instant.ofEpochMilli(ms))

    fun day(day: GuideDay): String = when (day.offset) {
        0 -> "Today"
        1 -> "Tomorrow"
        -1 -> "Yesterday"
        else -> shortDay(day.startMs)
    }

    companion object {
        fun duration(ms: Long): String {
            val min = ((ms + 30_000) / 60_000).coerceAtLeast(1)
            val h = min / 60
            val m = min % 60
            return when {
                h == 0L -> "$m min"
                m == 0L -> "$h h"
                else -> "$h h $m min"
            }
        }
    }
}
