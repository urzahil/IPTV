package rocks.urzahil.iptv.sync.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import rocks.urzahil.iptv.App
import rocks.urzahil.iptv.data.db.entities.SourceEntity
import rocks.urzahil.iptv.di.AppContainer
import rocks.urzahil.iptv.sync.StaleFiles
import rocks.urzahil.iptv.sync.EpgImportException
import rocks.urzahil.iptv.sync.EpgImportResult
import rocks.urzahil.iptv.sync.StalenessPolicy
import rocks.urzahil.iptv.sync.SyncException
import rocks.urzahil.iptv.sync.SyncNotifications
import rocks.urzahil.iptv.sync.SyncScheduler
import rocks.urzahil.iptv.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

private const val MAX_ATTEMPTS = 3

abstract class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    protected val container: AppContainer get() = (applicationContext as App).container

    /**
     * Runs [sync]. Anything thrown that the worker did not handle itself, including an [Error] such as
     * OutOfMemoryError from a hostile file, ends the run with a failure: WorkManager re-runs interrupted work at
     * the next start, so an escaping throwable would crash the app on every launch.
     */
    final override suspend fun doWork(): Result = try {
        sync()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        AppLog.w(TAG, "${javaClass.simpleName} failed: ${t.javaClass.simpleName}")
        try {
            onUnexpectedFailure()
        } catch (_: Throwable) {
        }
        Result.failure()
    }

    protected abstract suspend fun sync(): Result

    /** Records the failure where the user can see it. Must not throw (it is guarded anyway). */
    protected open suspend fun onUnexpectedFailure() {}

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
    override suspend fun onUnexpectedFailure() {
        val sourceId = inputData.getLong(SyncScheduler.KEY_SOURCE_ID, -1)
        if (sourceId >= 0) withContext(Dispatchers.IO) { container.db.sourceDao().setPlaylistError(sourceId, SourceEntity.STATUS_ERROR, "Import failed") }
    }

    override suspend fun sync(): Result {
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
    override suspend fun onUnexpectedFailure() {
        val epgId = inputData.getLong(SyncScheduler.KEY_EPG_ID, -1)
        if (epgId >= 0) withContext(Dispatchers.IO) { container.db.epgDao().markError(epgId, "Import failed") }
    }

    override suspend fun sync(): Result {
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
    override suspend fun sync(): Result {
        val c = container
        val settings = c.settings.current()
        withContext(Dispatchers.IO) { c.pruner.prune(settings) }
        return Result.success()
    }
}

/** Runs every 6 hours and once at app start: enqueues the syncs that are due, then a prune. */
class PeriodicRefreshWorker(context: Context, params: WorkerParameters) : SyncWorker(context, params) {
    override suspend fun sync(): Result {
        val c = container
        // Temp files of a sync that was cut short by a process kill still hold the playlist, URLs included.
        withContext(Dispatchers.IO) { StaleFiles.deleteOlderThan(c.syncCacheDir, StaleFiles.MAX_AGE_MS) }
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
