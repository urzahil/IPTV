package io.github.urzahil.iptv.sync

import io.github.urzahil.iptv.core.epg.EpgMatcher
import io.github.urzahil.iptv.data.db.AppDatabase

/** Resolves channels.epgChannelId with [EpgMatcher] (tvg-id, then tvg-name, then name). Blocking. */
class EpgMapper(private val db: AppDatabase) {
    /** Returns the number of channels whose mapping changed. */
    fun map(ownerSourceId: Long): Int {
        val entries = ArrayList<EpgMatcher.Entry>()
        for (r in db.epgDao().matchRows(ownerSourceId)) {
            val norms = r.nameNorm.split('\n').filter { it.isNotEmpty() }
            if (norms.isEmpty()) entries.add(EpgMatcher.Entry(r.id, r.xmltvId, ""))
            norms.forEach { entries.add(EpgMatcher.Entry(r.id, r.xmltvId, it)) }
        }
        val matcher = EpgMatcher(entries)
        val changes = ArrayList<Pair<Long, Long?>>()
        for (c in db.channelDao().matchRows(ownerSourceId)) {
            val m = matcher.match(c.tvgId, c.tvgName, c.name)
            if (m != c.epgChannelId) changes.add(c.id to m)
        }
        for (chunk in changes.chunked(1000)) {
            db.runInTransaction { chunk.forEach { (id, m) -> db.channelDao().setEpgChannel(id, m) } }
        }
        return changes.size
    }
}
