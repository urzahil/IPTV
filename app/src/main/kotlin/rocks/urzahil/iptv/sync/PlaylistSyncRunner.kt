package rocks.urzahil.iptv.sync

import rocks.urzahil.iptv.core.m3u.M3uParseException
import rocks.urzahil.iptv.core.util.Redactor
import rocks.urzahil.iptv.core.xtream.XtreamException
import rocks.urzahil.iptv.data.db.AppDatabase
import rocks.urzahil.iptv.data.db.entities.EpgSourceEntity
import rocks.urzahil.iptv.data.db.entities.SourceEntity
import rocks.urzahil.iptv.data.db.entities.SourceType
import rocks.urzahil.iptv.data.net.ContentSource
import rocks.urzahil.iptv.data.net.DownloadResult
import rocks.urzahil.iptv.data.net.Downloader
import rocks.urzahil.iptv.data.security.SourceSecrets
import rocks.urzahil.iptv.data.settings.AppSettings
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import okhttp3.OkHttpClient

class SyncException(message: String, val retryable: Boolean) : Exception(message)

data class PlaylistSyncOutcome(
    /** False when the server reported "not modified" and nothing was imported. */
    val imported: Boolean,
    val epgSources: List<EpgSourceEntity>,
    val summary: String?,
)

/** Imports one playlist source, records the outcome on the source row and maintains its EPG sources. Blocking. */
class PlaylistSyncRunner(
    private val db: AppDatabase,
    private val client: OkHttpClient,
    private val downloader: Downloader,
    private val secrets: SourceSecrets,
    private val redactor: Redactor,
    private val content: ContentSource,
    private val cacheDir: File,
) {
    private val sourceDao = db.sourceDao()
    private val epgSources = EpgSourceManager(db, secrets)

    fun run(
        sourceId: Long,
        settings: AppSettings,
        manual: Boolean,
        job: Job? = null,
        onProgress: (Int) -> Unit = {},
    ): PlaylistSyncOutcome {
        val source = sourceDao.getBlocking(sourceId) ?: return PlaylistSyncOutcome(false, emptyList(), null)
        try {
            val outcome = when (source.sourceType) {
                SourceType.XTREAM -> importXtream(source, settings, job, onProgress)
                SourceType.M3U_URL -> importM3uUrl(source, settings, manual, job, onProgress)
                SourceType.M3U_FILE -> importM3uFile(source, job, onProgress)
            }
            db.channelDao().optimizeFts()
            return outcome
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncException) {
            sourceDao.setPlaylistError(source.id, e.status(), e.message)
            throw e
        } catch (e: Exception) {
            val ex = classify(e)
            sourceDao.setPlaylistError(source.id, SourceEntity.STATUS_ERROR, ex.message)
            throw ex
        }
    }

    private fun SyncException.status() =
        if (message == NEEDS_CREDENTIALS_MESSAGE) SourceEntity.STATUS_NEEDS_CREDENTIALS else SourceEntity.STATUS_ERROR

    private fun classify(e: Exception): SyncException = when (e) {
        is XtreamException, is M3uParseException -> SyncException(redactor.redact(e.message), retryable = false)
        is SecurityException -> SyncException("The file is no longer accessible. Pick it again.", retryable = false)
        is IOException -> SyncException(redactor.redact(e), retryable = true)
        else -> SyncException(redactor.redact(e), retryable = false)
    }

    private fun importXtream(source: SourceEntity, settings: AppSettings, job: Job?, onProgress: (Int) -> Unit): PlaylistSyncOutcome {
        val creds = secrets.xtreamCredentials(source) ?: throw SyncException(NEEDS_CREDENTIALS_MESSAGE, retryable = false)
        val result = XtreamImporter(db, client).import(source, creds, settings.defaultUserAgent, job, onProgress)
        epgSources.syncFromPlaylist(source, emptyList())
        db.epgDao().clearValidators(source.id)
        return finish(source, result, imported = true)
    }

    private fun importM3uUrl(source: SourceEntity, settings: AppSettings, manual: Boolean, job: Job?, onProgress: (Int) -> Unit): PlaylistSyncOutcome {
        val url = secrets.m3uUrl(source) ?: throw SyncException(NEEDS_CREDENTIALS_MESSAGE, retryable = false)
        val headers = XtreamImporter.headersFor(source, settings.defaultUserAgent)
        val file = File(cacheDir, "playlist-${source.id}.m3u")
        try {
            val dl = downloader.download(
                client, url, headers,
                if (manual) null else source.playlistEtag, if (manual) null else source.playlistLastModified, file,
            )
            return when (dl) {
                is DownloadResult.Failed -> throw SyncException(dl.message, dl.retryable)
                is DownloadResult.NotModified -> {
                    sourceDao.setPlaylistUnchanged(source.id, System.currentTimeMillis(), source.lastSummary)
                    PlaylistSyncOutcome(false, db.epgDao().sourcesOfBlocking(source.id), source.lastSummary)
                }
                is DownloadResult.Downloaded -> {
                    val result = file.inputStream().use {
                        M3uImporter(db).import(source, it, job, dl.etag, dl.lastModified, onProgress)
                    }
                    finish(source, result, imported = true)
                }
            }
        } finally {
            file.delete()
        }
    }

    private fun importM3uFile(source: SourceEntity, job: Job?, onProgress: (Int) -> Unit): PlaylistSyncOutcome {
        val uri = source.fileUri ?: throw SyncException("No file selected", retryable = false)
        val result = content.open(uri).use { M3uImporter(db).import(source, it, job, onProgress = onProgress) }
        return finish(source, result, imported = true)
    }

    private fun finish(source: SourceEntity, result: PlaylistImportResult, imported: Boolean): PlaylistSyncOutcome {
        val ids = if (source.sourceType == SourceType.XTREAM) {
            db.epgDao().sourcesOfBlocking(source.id)
        } else {
            epgSources.syncFromPlaylist(source, result.epgUrls)
            db.epgDao().clearValidators(source.id)
            db.epgDao().sourcesOfBlocking(source.id)
        }
        EpgMapper(db).map(source.id)
        return PlaylistSyncOutcome(imported, ids, result.summary)
    }

    companion object {
        const val NEEDS_CREDENTIALS_MESSAGE = "Stored credentials cannot be read. Edit the source and enter them again."
    }
}
