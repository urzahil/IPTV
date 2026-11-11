package io.github.urzahil.iptv.sync.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.github.urzahil.iptv.App
import io.github.urzahil.iptv.di.AppContainer
import io.github.urzahil.iptv.sync.EpgImportException
import io.github.urzahil.iptv.sync.EpgImportResult
import io.github.urzahil.iptv.sync.StalenessPolicy
import io.github.urzahil.iptv.sync.SyncException
import io.github.urzahil.iptv.sync.SyncNotifications
import io.github.urzahil.iptv.sync.SyncScheduler
import io.github.urzahil.iptv.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

private const val MAX_ATTEMPTS = 3

abstract class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    protected val container: AppContainer get() = (applicationContext as App).container

    /** Shows the data-sync foreground notification; carries on silently if the system refuses. */
    protected suspend fun promote(key: Int, text: String) {
        try {
            setForeground(SyncNotifications.foregroundInfo(applicationContext, key, text))
        } catch (e: Exception) {
            AppLog.d(TAG, "Foreground promotion refused: ${e.javaClass.simpleName}")
        }
    }

    protected fun progress(phase: String, count: Int) {
        setProgressAsync(Data.Builder().putString(SyncScheduler.KEY_PHASE, phase).putInt(SyncScheduler.KEY_COUNT, count).build())
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        SyncNotifications.foregroundInfo(applicationContext, id.hashCode(), "Updating")

    protected companion object {
        const val TAG = "SyncWorker"
    }
}

class PlaylistSyncWorker(context: Context, params: WorkerParameters) : SyncWorker(context, params) {
    override suspend fun doWork(): Result {
        val sourceId = inputData.getLong(SyncScheduler.KEY_SOURCE_ID, -1)
        val manual = inputData.getBoolean(SyncScheduler.KEY_MANUAL, false)
        if (sourceId < 0) return Result.failure()
        val c = container
        val source = c.db.sourceDao().get(sourceId) ?: return Result.success()
        if (!source.enabled && !manual) return Result.success()

        promote(sourceId.hashCode(), "Updating channels")
        val settings = c.settings.current()
        return try {
            val outcome = withContext(Dispatchers.IO) {
                val job: Job? = currentCoroutineContext()[Job]
                c.playlistSyncRunner.run(sourceId, settings, manual, job) { progress("playlist", it) }
            }
            val now = System.currentTimeMillis()
            for (es in outcome.epgSources) {
                if (manual || StalenessPolicy.due(es.lastSyncMs, settings.epgRefreshHours, now) || outcome.imported) {
                    c.syncScheduler.syncEpg(es.id, sourceId, force = manual)
                }
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncException) {
            if (e.retryable && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }
}

class EpgSyncWorker(context: Context, params: WorkerParameters) : SyncWorker(context, params) {
    override suspend fun doWork(): Result {
        val epgId = inputData.getLong(SyncScheduler.KEY_EPG_ID, -1)
        val force = inputData.getBoolean(SyncScheduler.KEY_MANUAL, false)
        if (epgId < 0) return Result.failure()
        val c = container
        promote(epgId.hashCode() + 1, "Updating TV guide")
        val settings = c.settings.current()
        return try {
            val r = withContext(Dispatchers.IO) {
                val job: Job? = currentCoroutineContext()[Job]
                c.epgImporter.import(epgId, settings, force, job = job) { progress("epg", it) }
            }
            AppLog.d(TAG, "EPG sync finished: ${if (r is EpgImportResult.Imported) "${r.programmes} programmes" else "not modified"}")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: EpgImportException) {
            if (e.retryable && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }
}

class PruneWorker(context: Context, params: WorkerParameters) : SyncWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = container
        val settings = c.settings.current()
        withContext(Dispatchers.IO) { c.pruner.prune(settings) }
        return Result.success()
    }
}

/** Runs every 6 hours and once at app start: enqueues the syncs that are due, then a prune. */
class PeriodicRefreshWorker(context: Context, params: WorkerParameters) : SyncWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = container
        val settings = c.settings.current()
        val now = System.currentTimeMillis()
        for (s in c.db.sourceDao().getAll()) {
            if (!s.enabled) continue
            if (StalenessPolicy.due(s.lastPlaylistSyncMs, settings.playlistRefreshHours, now)) {
                c.syncScheduler.syncPlaylist(s.id, manual = false, needsNetwork = s.type != "M3U_FILE")
            } else {
                for (es in c.db.epgDao().sourcesOfBlocking(s.id)) {
                    if (StalenessPolicy.due(es.lastSyncMs, settings.epgRefreshHours, now)) {
                        c.syncScheduler.syncEpg(es.id, s.id, force = false)
                    }
                }
            }
        }
        c.syncScheduler.requestPrune()
        return Result.success()
    }
}
