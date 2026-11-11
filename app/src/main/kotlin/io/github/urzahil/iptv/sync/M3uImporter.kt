package io.github.urzahil.iptv.sync

import io.github.urzahil.iptv.core.epg.NameNormalizer
import io.github.urzahil.iptv.core.io.Compression
import io.github.urzahil.iptv.core.m3u.M3uParser
import io.github.urzahil.iptv.core.model.ParsedChannel
import io.github.urzahil.iptv.data.db.AppDatabase
import io.github.urzahil.iptv.data.db.entities.ChannelEntity
import io.github.urzahil.iptv.data.db.entities.SourceEntity
import io.github.urzahil.iptv.data.repo.StreamHeadersJson
import java.io.InputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive

data class PlaylistImportResult(
    val channels: Int,
    val categories: Int,
    val skippedVod: Int,
    val dropped: Int,
    val epgUrls: List<String>,
) {
    val summary: String
        get() = buildString {
            append("$channels channels, $categories groups")
            if (skippedVod > 0) append(", $skippedVod VOD entries skipped")
        }
}

/** Streams an M3U playlist into the database. Blocking: call from an IO dispatcher. */
class M3uImporter(private val db: AppDatabase) {
    fun import(
        source: SourceEntity,
        input: InputStream,
        job: Job? = null,
        etag: String? = null,
        lastModified: String? = null,
        onProgress: (Int) -> Unit = {},
    ): PlaylistImportResult {
        val gen = source.syncGen + 1
        val writer = PlaylistWriter(db, source.id, gen)
        val stats = Compression.open(input).use { stream ->
            M3uParser().parse(stream) { ch ->
                job?.ensureActive()
                for (g in ch.groups) writer.registerCategory(g, g)
                writer.add(toEntity(ch, source.id), ch.groups)
                if (writer.channelCount % PROGRESS_EVERY == 0) onProgress(writer.channelCount)
            }
        }
        job?.ensureActive()
        db.runInTransaction {
            writer.finish()
            db.sourceDao().setPlaylistOk(
                source.id, gen, System.currentTimeMillis(),
                PlaylistImportResult(writer.channelCount, writer.categoryCount, stats.skippedVod, stats.dropped, emptyList()).summary,
                writer.channelCount, etag, lastModified,
            )
        }
        return PlaylistImportResult(writer.channelCount, writer.categoryCount, stats.skippedVod, stats.dropped, stats.header.epgUrls)
    }

    companion object {
        private const val PROGRESS_EVERY = 500

        fun toEntity(ch: ParsedChannel, sourceId: Long) = ChannelEntity(
            sourceId = sourceId,
            providerKey = ch.key,
            name = ch.name,
            nameNorm = NameNormalizer.normalize(ch.name),
            number = ch.chno,
            sortIndex = 0,
            logoUrl = ch.logo,
            streamUrl = ch.url,
            headersJson = StreamHeadersJson.encode(ch.headers),
            tvgId = ch.tvgId,
            tvgName = ch.tvgName,
            shiftMin = ch.shiftMin,
            catchupType = ch.catchup.type.ordinal,
            catchupSource = ch.catchup.source,
            catchupDays = if (ch.catchup.enabled) ch.catchup.days else 0,
            catchupCorrectionSec = ch.catchup.correctionSec,
            isRadio = ch.isRadio,
            mimeHint = ch.mimeHint,
            drm = ch.drm,
        )
    }
}
