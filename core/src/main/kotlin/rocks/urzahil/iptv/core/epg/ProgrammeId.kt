package rocks.urzahil.iptv.core.epg

/** id = (epgChannelRowId shl 32) or (startMs / 60000). Range scans run on the rowid. */
object ProgrammeId {
    const val MAX_PROG_MS = 12 * 3_600_000L
    private const val MINUTE_MASK = 0xFFFFFFFFL

    fun compose(epgChannelId: Long, startMs: Long): Long =
        (epgChannelId shl 32) or ((startMs / 60_000L) and MINUTE_MASK)

    fun epgChannelOf(id: Long): Long = id ushr 32

    fun startMinuteOf(id: Long): Long = id and MINUTE_MASK

    /** Inclusive rowid range that contains every programme of the channel that may overlap [fromMs, toMs]. */
    fun range(epgChannelId: Long, fromMs: Long, toMs: Long): LongRange =
        compose(epgChannelId, maxOf(0L, fromMs - MAX_PROG_MS))..compose(epgChannelId, maxOf(0L, toMs))

    /** Whole-channel range. */
    fun channelRange(epgChannelId: Long): LongRange =
        (epgChannelId shl 32)..((epgChannelId shl 32) or MINUTE_MASK)
}
