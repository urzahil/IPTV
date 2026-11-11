package rocks.urzahil.iptv.sync

import rocks.urzahil.iptv.core.epg.NameNormalizer
import rocks.urzahil.iptv.core.epg.ProgrammeId
import rocks.urzahil.iptv.core.io.Compression
import rocks.urzahil.iptv.core.model.ParsedEpgChannel
import rocks.urzahil.iptv.core.model.ParsedProgramme
import rocks.urzahil.iptv.core.util.Redactor
import rocks.urzahil.iptv.core.xmltv.XmltvOptions
import rocks.urzahil.iptv.core.xmltv.XmltvParser
import rocks.urzahil.iptv.core.xtream.XtreamUrls
import rocks.urzahil.iptv.data.db.AppDatabase
import rocks.urzahil.iptv.data.db.entities.EpgChannelEntity
import rocks.urzahil.iptv.data.db.entities.EpgKind
import rocks.urzahil.iptv.data.db.entities.ProgrammeDetailsStagingEntity
import rocks.urzahil.iptv.data.db.entities.ProgrammeEntity
import rocks.urzahil.iptv.data.db.entities.ProgrammeStagingEntity
import rocks.urzahil.iptv.data.net.DownloadResult
import rocks.urzahil.iptv.data.net.Downloader
import rocks.urzahil.iptv.data.security.SourceSecrets
import rocks.urzahil.iptv.data.settings.AppSettings
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient

sealed interface EpgImportResult {
    data object NotModified : EpgImportResult
    data class Imported(val channels: Int, val programmes: Int, val skipped: Int, val warnings: List<String>) : EpgImportResult
}

class EpgImportException(message: String, val retryable: Boolean) : Exception(message)

/**
 * Downloads one XMLTV source and imports it: parse into the staging tables (filtered by the wanted channel set and
 * the retention window), then swap into the live tables in a single transaction. Blocking: use an IO dispatcher.
 */
class EpgImporter(
    private val db: AppDatabase,
    private val client: OkHttpClient,
    private val downloader: Downloader,
    private val secrets: SourceSecrets,
    private val redactor: Redactor,
    private val cacheDir: File,
) {
    fun import(
        epgSourceId: Long,
        settings: AppSettings,
        force: Boolean,
        nowMs: Long = System.currentTimeMillis(),
        job: Job? = null,
        onProgress: (Int) -> Unit = {},
    ): EpgImportResult {
        val epgDao = db.epgDao()
        val es = epgDao.getSource(epgSourceId) ?: return EpgImportResult.NotModified
        val owner = db.sourceDao().getBlocking(es.ownerSourceId) ?: return EpgImportResult.NotModified

        val url = when (es.kind) {
            EpgKind.XTREAM -> secrets.xtreamCredentials(owner)?.let { XtreamUrls.xmltv(it.base, it.username, it.password) }
            else -> secrets.epgUrl(es)
        } ?: throw EpgImportException("Stored credentials cannot be read, re-enter them", retryable = false)

        val headers = XtreamImporter.headersFor(owner, settings.defaultUserAgent)
        val file = File(cacheDir, "epg-$epgSourceId.tmp")
        try {
            val dl = downloader.download(
                client, url, headers,
                if (force) null else es.etag, if (force) null else es.lastModified, file,
            )
            when (dl) {
                is DownloadResult.NotModified -> {
                    epgDao.markSynced(es.id, es.etag, es.lastModified, nowMs, null)
                    db.sourceDao().setEpgResult(owner.id, nowMs, null)
                    return EpgImportResult.NotModified
                }
                is DownloadResult.Failed -> throw EpgImportException(dl.message, dl.retryable)
                is DownloadResult.Downloaded -> Unit
            }
            job?.ensureActive()
            val result = importFile(es.id, owner.id, file, settings, nowMs, job, onProgress)
            epgDao.markSynced(
                es.id, (dl as DownloadResult.Downloaded).etag, dl.lastModified, nowMs,
                result.warnings.firstOrNull(),
            )
            db.sourceDao().setEpgResult(owner.id, nowMs, null)
            EpgMapper(db).map(owner.id)
            Pruner(db).prune(settings, nowMs)
            return result
        } catch (e: EpgImportException) {
            fail(es.id, owner.id, e.message)
            throw e
        } catch (e: IOException) {
            val msg = redactor.redact(e)
            fail(es.id, owner.id, msg)
            throw EpgImportException(msg, retryable = true)
        } finally {
            file.delete()
        }
    }

    private fun fail(epgSourceId: Long, ownerId: Long, message: String?) {
        db.epgDao().markError(epgSourceId, message)
        db.sourceDao().setEpgResult(ownerId, System.currentTimeMillis(), message)
    }

    /** Parses [file] into staging and swaps. Exposed for tests. */
    fun importFile(
        epgSourceId: Long,
        ownerId: Long,
        file: File,
        settings: AppSettings,
        nowMs: Long,
        job: Job? = null,
        onProgress: (Int) -> Unit = {},
    ): EpgImportResult.Imported {
        val epgDao = db.epgDao()
        val progDao = db.programmeDao()
        val wanted = WantedSet.build(db.channelDao().matchRows(ownerId))

        progDao.clearStaging()
        progDao.clearDetailsStaging()

        val idMap = HashMap<String, Long>()
        epgDao.idMap(epgSourceId).forEach { idMap[it.xmltvIdLower] = it.id }
        val pendingChannels = ArrayList<EpgChannelEntity>()
        val declared = HashSet<String>()
        val pastByChannel = HashMap<String, Int>()
        val staging = ArrayList<ProgrammeStagingEntity>(BATCH)
        val details = ArrayList<ProgrammeDetailsStagingEntity>(BATCH)
        var written = 0

        fun flushChannels() {
            if (pendingChannels.isEmpty()) return
            db.runInTransaction {
                val ids = epgDao.upsertChannels(pendingChannels)
                pendingChannels.forEachIndexed { i, e -> idMap[e.xmltvIdLower] = if (e.id != 0L) e.id else ids[i] }
            }
            pendingChannels.clear()
        }

        fun flushProgrammes() {
            if (staging.isEmpty()) return
            db.runInTransaction {
                progDao.insertStaging(staging)
                if (details.isNotEmpty()) progDao.insertDetailsStaging(details)
            }
            written += staging.size
            onProgress(written)
            staging.clear()
            details.clear()
        }

        fun channelEntity(id: String, names: List<String>, icon: String?): EpgChannelEntity {
            val lower = id.lowercase()
            return EpgChannelEntity(
                id = idMap[lower] ?: 0,
                epgSourceId = epgSourceId,
                xmltvId = id,
                xmltvIdLower = lower,
                displayName = names.firstOrNull() ?: id,
                nameNorm = names.map { NameNormalizer.normalize(it) }.filter { it.isNotEmpty() }.distinct().joinToString("\n"),
                iconUrl = icon,
                lastSeenMs = nowMs,
            )
        }

        val parser = XmltvParser(
            XmltvOptions(
                nowMs = nowMs,
                pastDays = settings.epgPastDays,
                futureDays = settings.epgFutureDays,
                wantedIds = wanted.ids,
                wantedNames = wanted.names,
            ),
        )
        val parsed = Compression.open(file.inputStream().buffered(BUFFER)).use { input ->
            parser.parse(
                input,
                onChannel = { c: ParsedEpgChannel ->
                    job?.ensureActive()
                    val lower = c.id.lowercase()
                    if (declared.add(lower)) {
                        pastByChannel[lower] = wanted.catchupDays(lower, c.displayNames)
                        pendingChannels.add(channelEntity(c.id, c.displayNames, c.icon))
                        if (pendingChannels.size >= CHANNEL_BATCH) flushChannels()
                    }
                },
                onProgramme = { p: ParsedProgramme ->
                    job?.ensureActive()
                    val lower = p.xmltvChannelId.lowercase()
                    if (pendingChannels.isNotEmpty()) flushChannels()
                    val chId = idMap[lower] ?: run {
                        pastByChannel[lower] = wanted.catchupDays(lower, emptyList())
                        pendingChannels.add(channelEntity(p.xmltvChannelId, emptyList(), null))
                        flushChannels()
                        idMap.getValue(lower)
                    }
                    val keep = PrunerWindow.keepPastDays(settings.epgPastDays, pastByChannel[lower] ?: 0)
                    if (p.endMs >= PrunerWindow.pastCutoffMs(nowMs, keep)) {
                        val id = ProgrammeId.compose(chId, p.startMs)
                        var flags = 0
                        val hasDetails = p.subTitle != null || p.desc != null || p.categories != null ||
                            p.episode != null || p.icon != null || p.catchupId != null
                        if (hasDetails) flags = flags or ProgrammeEntity.FLAG_HAS_DETAILS
                        if (p.catchupId != null) flags = flags or ProgrammeEntity.FLAG_CATCHUP_ID
                        staging.add(ProgrammeStagingEntity(id, chId, p.startMs, p.endMs, p.title, flags))
                        if (hasDetails) {
                            details.add(
                                ProgrammeDetailsStagingEntity(
                                    id, p.subTitle, p.desc?.take(MAX_DESC), p.categories, p.episode, p.icon, p.catchupId,
                                ),
                            )
                        }
                        if (staging.size >= BATCH) flushProgrammes()
                    }
                },
            )
        }
        flushChannels()
        flushProgrammes()
        job?.ensureActive()

        db.runInTransaction {
            for (m in progDao.stagedMinStarts()) {
                val all = ProgrammeId.channelRange(m.epgChannelId)
                progDao.deleteRange(ProgrammeId.compose(m.epgChannelId, m.minStartMs), all.last)
            }
            progDao.copyStagingProgrammes()
            progDao.copyStagingDetails()
            progDao.clearStaging()
            progDao.clearDetailsStaging()
        }
        return EpgImportResult.Imported(parsed.channels, written, parsed.skipped, parsed.warnings)
    }

    /** Wanted channel ids/names of a playlist and the largest catchup window per key. */
    class WantedSet private constructor(
        val ids: Set<String>,
        val names: Set<String>,
        private val daysById: Map<String, Int>,
        private val daysByName: Map<String, Int>,
    ) {
        fun catchupDays(idLower: String, displayNames: List<String>): Int {
            var d = daysById[idLower] ?: 0
            for (n in displayNames) d = maxOf(d, daysByName[NameNormalizer.normalize(n)] ?: 0)
            return d
        }

        companion object {
            fun build(rows: List<rocks.urzahil.iptv.data.db.dao.ChannelMatchRow>): WantedSet {
                val ids = HashSet<String>()
                val names = HashSet<String>()
                val byId = HashMap<String, Int>()
                val byName = HashMap<String, Int>()
                for (r in rows) {
                    r.tvgId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let {
                        ids.add(it)
                        byId.merge(it, r.catchupDays, ::maxOf)
                    }
                    for (n in listOf(NameNormalizer.normalize(r.tvgName), r.nameNorm)) {
                        if (n.isEmpty()) continue
                        names.add(n)
                        byName.merge(n, r.catchupDays, ::maxOf)
                    }
                }
                return WantedSet(ids, names, byId, byName)
            }
        }
    }

    private companion object {
        const val BATCH = 2000
        const val CHANNEL_BATCH = 500
        const val BUFFER = 64 * 1024
        const val MAX_DESC = 1000
    }
}
