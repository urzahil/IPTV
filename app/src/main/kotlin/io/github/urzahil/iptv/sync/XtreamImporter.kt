package io.github.urzahil.iptv.sync

import io.github.urzahil.iptv.core.epg.NameNormalizer
import io.github.urzahil.iptv.core.model.CatchupType
import io.github.urzahil.iptv.core.xtream.XLiveStream
import io.github.urzahil.iptv.core.xtream.XtreamAuth
import io.github.urzahil.iptv.core.xtream.XtreamException
import io.github.urzahil.iptv.core.xtream.XtreamParser
import io.github.urzahil.iptv.core.xtream.XtreamTime
import io.github.urzahil.iptv.core.xtream.XtreamUrls
import io.github.urzahil.iptv.data.db.AppDatabase
import io.github.urzahil.iptv.data.db.entities.ChannelEntity
import io.github.urzahil.iptv.data.db.entities.SourceEntity
import io.github.urzahil.iptv.data.security.XtreamCredentials
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request

/** Imports an Xtream account: login check, live categories, then the streamed live stream list. Blocking. */
class XtreamImporter(private val db: AppDatabase, private val client: OkHttpClient) {

    fun import(
        source: SourceEntity,
        creds: XtreamCredentials,
        userAgent: String,
        job: Job? = null,
        onProgress: (Int) -> Unit = {},
    ): PlaylistImportResult {
        val gen = source.syncGen + 1
        val headers = headersFor(source, userAgent)

        val auth = login(creds, headers)
        db.sourceDao().setAccountInfo(
            source.id,
            auth.account.expiresAtSec?.takeIf { it > 0 }?.times(1000),
            auth.account.maxConnections,
            XtreamTime.resolve(auth.server.timezone, auth.server.timeNow, auth.server.timestampNow).id,
            auth.account.allowedFormats.takeIf { it.isNotEmpty() }?.joinToString(","),
        )
        job?.ensureActive()

        val categories = getString(XtreamUrls.liveCategories(creds.base, creds.username, creds.password), headers)
            .let { XtreamParser.parseCategories(it) }
        job?.ensureActive()

        val writer = PlaylistWriter(db, source.id, gen)
        for (c in categories) writer.registerCategory(c.id, c.name)
        val known = categories.mapTo(HashSet()) { it.id }

        val url = XtreamUrls.liveStreams(creds.base, creds.username, creds.password)
        executeStream(url, headers) { input ->
            XtreamParser.streamLiveStreams(input) { s ->
                job?.ensureActive()
                val keys = categoryKeys(s, known)
                if (UNCATEGORIZED_KEY in keys) writer.registerCategory(UNCATEGORIZED_KEY, UNCATEGORIZED_NAME)
                writer.add(toEntity(s, source.id), keys)
                if (writer.channelCount % PROGRESS_EVERY == 0) onProgress(writer.channelCount)
            }
        }
        job?.ensureActive()
        val result = PlaylistImportResult(writer.channelCount, writer.categoryCount, 0, 0, emptyList())
        db.runInTransaction {
            writer.finish()
            db.sourceDao().setPlaylistOk(source.id, gen, System.currentTimeMillis(), result.summary, writer.channelCount, null, null)
        }
        return result
    }

    /** Verifies the credentials. Throws [XtreamException] with a user-facing message when the login is not usable. */
    fun login(creds: XtreamCredentials, headers: Map<String, String>): XtreamAuth {
        val auth = XtreamParser.parseAuth(getString(XtreamUrls.account(creds.base, creds.username, creds.password), headers))
        if (!auth.account.auth) throw XtreamException("Login rejected: wrong username or password")
        if (!auth.account.isActive) throw XtreamException("Account is not active (${auth.account.status})")
        return auth
    }

    private fun getString(url: String, headers: Map<String, String>): String {
        var out = ""
        executeStream(url, headers) { out = it.readBytes().toString(Charsets.UTF_8) }
        return out
    }

    private fun executeStream(url: String, headers: Map<String, String>, consume: (java.io.InputStream) -> Unit) {
        val req = try {
            Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        } catch (e: IllegalArgumentException) {
            throw XtreamException("Invalid server address")
        }
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            consume(resp.body.byteStream())
        }
    }

    companion object {
        const val UNCATEGORIZED_KEY = "__uncategorized"
        const val UNCATEGORIZED_NAME = "Uncategorized"
        private const val PROGRESS_EVERY = 500

        fun headersFor(source: SourceEntity, defaultUserAgent: String): Map<String, String> {
            val m = LinkedHashMap<String, String>()
            m["User-Agent"] = source.userAgent?.takeIf { it.isNotBlank() } ?: defaultUserAgent
            source.referrer?.takeIf { it.isNotBlank() }?.let { m["Referer"] = it }
            return m
        }

        /** Category keys of a stream: its category ids that exist, else Uncategorized. */
        fun categoryKeys(s: XLiveStream, known: Set<String>): List<String> {
            val keys = s.groups.filter { it in known }.distinct()
            return keys.ifEmpty { listOf(UNCATEGORIZED_KEY) }
        }

        fun toEntity(s: XLiveStream, sourceId: Long): ChannelEntity {
            val name = s.name.ifBlank { "Channel ${s.streamId}" }
            val catchup = s.tvArchive && s.tvArchiveDuration > 0
            return ChannelEntity(
                sourceId = sourceId,
                providerKey = s.streamId.toString(),
                name = name,
                nameNorm = NameNormalizer.normalize(name),
                number = s.num,
                sortIndex = 0,
                logoUrl = s.icon,
                xtreamStreamId = s.streamId,
                tvgId = s.epgChannelId,
                catchupType = if (catchup) CatchupType.XTREAM_API.ordinal else CatchupType.NONE.ordinal,
                catchupDays = if (catchup) s.tvArchiveDuration.coerceAtMost(30) else 0,
                isRadio = s.streamType == "radio_streams",
            )
        }
    }
}
