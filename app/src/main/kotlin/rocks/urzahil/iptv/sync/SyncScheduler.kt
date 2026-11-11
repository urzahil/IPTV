package rocks.urzahil.iptv.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import rocks.urzahil.iptv.sync.workers.EpgSyncWorker
import rocks.urzahil.iptv.sync.workers.PeriodicRefreshWorker
import rocks.urzahil.iptv.sync.workers.PlaylistSyncWorker
import rocks.urzahil.iptv.sync.workers.PruneWorker
import androidx.work.WorkInfo
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class SyncProgress(val running: Boolean, val phase: String?, val count: Int)

/**
 * Unique-work names and constraints. Workers receive ids only, never URLs or credentials:
 * `playlist-<sourceId>`, `epg-<epgSourceId>`, `prune`, `periodic-refresh`, `refresh-on-start`.
 */
class SyncScheduler(context: Context) {
    private val wm: WorkManager by lazy { WorkManager.getInstance(context.applicationContext) }

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Manual or first sync replaces a running one; periodic checks leave it alone. */
    fun syncPlaylist(sourceId: Long, manual: Boolean, needsNetwork: Boolean = true) {
        val req = OneTimeWorkRequestBuilder<PlaylistSyncWorker>()
            .setInputData(workDataOf(KEY_SOURCE_ID to sourceId, KEY_MANUAL to manual))
            .addTag(sourceTag(sourceId))
            .apply { if (needsNetwork) setConstraints(online) }
            .build()
        wm.enqueueUniqueWork(playlistName(sourceId), if (manual) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req)
    }

    fun syncEpg(epgSourceId: Long, ownerSourceId: Long, force: Boolean) {
        val req = OneTimeWorkRequestBuilder<EpgSyncWorker>()
            .setInputData(workDataOf(KEY_EPG_ID to epgSourceId, KEY_MANUAL to force))
            .addTag(sourceTag(ownerSourceId))
            .setConstraints(online)
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniqueWork(epgName(epgSourceId), if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req)
    }

    /** Runs the cleanup (retention settings, orphans). Called when retention settings change. */
    fun requestPrune() {
        wm.enqueueUniqueWork(PRUNE_NAME, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<PruneWorker>().build())
    }

    /** Re-checks staleness of all sources once (app start, or after the refresh intervals change). */
    fun checkNow() {
        wm.enqueueUniqueWork(
            ON_START_NAME, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PeriodicRefreshWorker>().build(),
        )
    }

    fun schedulePeriodic() {
        val req = PeriodicWorkRequestBuilder<PeriodicRefreshWorker>(6, TimeUnit.HOURS).build()
        wm.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Progress of the running or queued workers of a source; null when idle. */
    fun observeProgress(sourceId: Long): Flow<SyncProgress?> =
        wm.getWorkInfosByTagFlow(sourceTag(sourceId)).map { infos ->
            val active = infos.filter { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
            if (active.isEmpty()) {
                null
            } else {
                val running = active.firstOrNull { it.state == WorkInfo.State.RUNNING }
                SyncProgress(
                    running != null,
                    running?.progress?.getString(KEY_PHASE),
                    running?.progress?.getInt(KEY_COUNT, 0) ?: 0,
                )
            }
        }

    fun cancelSource(sourceId: Long) {
        wm.cancelAllWorkByTag(sourceTag(sourceId))
    }

    companion object {
        const val KEY_SOURCE_ID = "sourceId"
        const val KEY_EPG_ID = "epgSourceId"
        const val KEY_MANUAL = "manual"
        const val KEY_PHASE = "phase"
        const val KEY_COUNT = "count"
        const val PRUNE_NAME = "prune"
        const val PERIODIC_NAME = "periodic-refresh"
        const val ON_START_NAME = "refresh-on-start"

        fun playlistName(id: Long) = "playlist-$id"
        fun epgName(id: Long) = "epg-$id"

        /** Tag shared by every worker of a source, used by the UI to show progress. */
        fun sourceTag(id: Long) = "source-$id"
    }
}
