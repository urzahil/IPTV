package io.github.urzahil.iptv.sync

import io.github.urzahil.iptv.core.epg.ProgrammeId
import io.github.urzahil.iptv.data.db.AppDatabase
import io.github.urzahil.iptv.data.settings.AppSettings

/**
 * Applies the EPG retention settings with rowid range deletes (see [ProgrammeId]):
 *  - channels without catchup keep only the current programme and the future;
 *  - catchup channels keep min(past days setting, their archive length);
 *  - everything beyond the future-days setting is dropped;
 *  - EPG channels nothing maps to and no import has seen for [ORPHAN_AGE_DAYS] days are removed.
 * Blocking.
 */
class Pruner(private val db: AppDatabase) {
    fun prune(settings: AppSettings, nowMs: Long = System.currentTimeMillis()) {
        val refs = db.channelDao().epgReferences().associate { it.epgChannelId to it.maxCatchupDays }
        val programmes = db.programmeDao()
        val futureCut = PrunerWindow.futureCutoffMs(nowMs, settings.epgFutureDays)

        for (chunk in db.epgDao().allChannelIds().chunked(200)) {
            db.runInTransaction {
                for (id in chunk) {
                    val keep = PrunerWindow.keepPastDays(settings.epgPastDays, refs[id] ?: 0)
                    val cut = PrunerWindow.pastCutoffMs(nowMs, keep)
                    val all = ProgrammeId.channelRange(id)
                    programmes.deleteEndedBefore(all.first, ProgrammeId.compose(id, cut), cut)
                    programmes.deleteRange(ProgrammeId.compose(id, futureCut) + 1, all.last)
                }
            }
        }

        val orphans = db.epgDao().orphanIds(nowMs - ORPHAN_AGE_DAYS * PrunerWindow.DAY_MS)
        for (chunk in orphans.chunked(100)) {
            db.runInTransaction {
                for (id in chunk) {
                    val r = ProgrammeId.channelRange(id)
                    programmes.deleteRange(r.first, r.last)
                    db.epgDao().deleteChannel(id)
                }
            }
        }
        db.checkpoint()
    }

    companion object {
        const val ORPHAN_AGE_DAYS = 3L
    }
}
