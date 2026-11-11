package io.github.urzahil.iptv.core.catchup

/** Times in epoch seconds. [endSec] is the programme end (may be in the future for "rewind current"). */
data class CatchupRequest(
    val startSec: Long,
    val endSec: Long,
    val nowSec: Long,
    val catchupId: String? = null,
)

/**
 * @property streamOriginSec wall-clock second that corresponds to position 0 of the returned stream.
 * @property initialSeekMs seek to apply once playing (Xtream starts on a minute boundary).
 */
data class CatchupUrl(val url: String, val streamOriginSec: Long, val initialSeekMs: Long = 0)

class UnsupportedCatchup(message: String) : Exception(message)
