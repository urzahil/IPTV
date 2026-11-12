package rocks.urzahil.iptv.core.catchup

/** Catchup availability. All times are epoch seconds. */
class CatchupWindow(private val days: Int, private val nowSec: Long) {
    private val floor: Long get() = nowSec - days * 86_400L

    fun available(programmeStartSec: Long): Boolean =
        days > 0 && programmeStartSec >= floor && programmeStartSec < nowSec

    /** Clamps a seek target between the archive floor (or [windowStartSec], if later) and now - 10 s. */
    fun clamp(targetSec: Long, windowStartSec: Long = Long.MIN_VALUE): Long {
        val lo = maxOf(floor, windowStartSec)
        val hi = nowSec - 10
        return if (lo > hi) hi else targetSec.coerceIn(lo, hi)
    }
}
