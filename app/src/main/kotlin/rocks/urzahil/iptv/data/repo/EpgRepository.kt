package rocks.urzahil.iptv.data.repo

import androidx.room.withTransaction
import rocks.urzahil.iptv.core.epg.ProgrammeId
import rocks.urzahil.iptv.data.db.AppDatabase
import rocks.urzahil.iptv.data.db.dao.ProgrammeLite
import rocks.urzahil.iptv.data.db.entities.ProgrammeDetailsEntity

/** What to look up for one channel. [shiftMin] is the channel's tvg-shift (applied at read time). */
data class EpgRequest(val channelId: Long, val epgChannelId: Long, val shiftMin: Int = 0)

data class NowNext(val now: ProgrammeLite?, val next: ProgrammeLite?)

class EpgRepository(private val db: AppDatabase) {
    private val dao = db.programmeDao()

    /**
     * Programmes of one EPG channel overlapping [fromMs, toMs], with tvg-shift applied to the returned times.
     * Runs a rowid range scan (no secondary index needed).
     */
    suspend fun programmes(epgChannelId: Long, shiftMin: Int, fromMs: Long, toMs: Long): List<ProgrammeLite> =
        db.withTransaction { query(epgChannelId, shiftMin, fromMs, toMs) }

    /** Window query for many channels in one transaction (guide rows). Result is keyed by channel id. */
    suspend fun programmesBatch(requests: List<EpgRequest>, fromMs: Long, toMs: Long): Map<Long, List<ProgrammeLite>> =
        db.withTransaction {
            requests.associate { it.channelId to query(it.epgChannelId, it.shiftMin, fromMs, toMs) }
        }

    /** Current and next programme per channel id, in one transaction. */
    suspend fun nowNext(requests: List<EpgRequest>, nowMs: Long): Map<Long, NowNext> =
        db.withTransaction {
            val out = HashMap<Long, NowNext>(requests.size)
            for (r in requests) {
                val list = query(r.epgChannelId, r.shiftMin, nowMs - ProgrammeId.MAX_PROG_MS, nowMs + ProgrammeId.MAX_PROG_MS)
                val now = list.firstOrNull { it.startMs <= nowMs && it.endMs > nowMs }
                val next = list.firstOrNull { it.startMs >= (now?.endMs ?: nowMs) && it.startMs > nowMs }
                out[r.channelId] = NowNext(now, next)
            }
            out
        }

    suspend fun details(programmeId: Long): ProgrammeDetailsEntity? = db.withTransaction { dao.details(programmeId) }

    private fun query(epgChannelId: Long, shiftMin: Int, fromMs: Long, toMs: Long): List<ProgrammeLite> {
        val shiftMs = shiftMin * 60_000L
        val lo = fromMs - shiftMs
        val range = ProgrammeId.range(epgChannelId, lo, toMs - shiftMs)
        val rows = dao.range(range.first, range.last, lo)
        return if (shiftMs == 0L) rows else rows.map { it.copy(startMs = it.startMs + shiftMs, endMs = it.endMs + shiftMs) }
    }
}
