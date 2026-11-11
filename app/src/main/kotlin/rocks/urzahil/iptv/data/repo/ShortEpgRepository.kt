package rocks.urzahil.iptv.data.repo

import java.io.IOException
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import rocks.urzahil.iptv.core.model.StreamHeaders
import rocks.urzahil.iptv.core.xtream.XEpgListing
import rocks.urzahil.iptv.core.xtream.XtreamException
import rocks.urzahil.iptv.core.xtream.XtreamParser
import rocks.urzahil.iptv.core.xtream.XtreamTime
import rocks.urzahil.iptv.core.xtream.XtreamUrls
import rocks.urzahil.iptv.data.db.AppDatabase
import rocks.urzahil.iptv.data.db.dao.ProgrammeLite
import rocks.urzahil.iptv.data.db.entities.SourceType
import rocks.urzahil.iptv.data.security.SourceSecrets
import rocks.urzahil.iptv.data.settings.SettingsRepository
import rocks.urzahil.iptv.util.AppLog

/**
 * Guide fallback for Xtream channels the XMLTV data does not cover: asks the panel for the channel's own
 * programme table. Results (also empty ones and failures) are kept in memory for 30 minutes and at most four
 * requests run at once. The request URL carries the account credentials, so it is never logged or stored;
 * failures are reported by exception class only (never the message, which could repeat a URL). A response over
 * 2 MB, an error status or an unreadable body counts as a failure and is retried after two minutes.
 */
class ShortEpgRepository(
    private val db: AppDatabase,
    private val secrets: SourceSecrets,
    private val client: OkHttpClient,
    private val settings: SettingsRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Cached(val atMs: Long, val programmes: List<ProgrammeLite>, val descriptions: Map<Long, String>)

    private val cache = ConcurrentHashMap<Long, Cached>()
    private val gate = Semaphore(MAX_PARALLEL)

    /**
     * Programmes of the channel with tvg-shift applied, or an empty list when the channel is not an Xtream
     * channel, the panel has nothing, or the request failed. Rows have negative ids (they are not in the database).
     */
    suspend fun load(channelId: Long): List<ProgrammeLite> {
        fresh(channelId)?.let { return it.programmes }
        return gate.withPermit {
            fresh(channelId)?.programmes ?: withContext(Dispatchers.IO) {
                var failed = false
                val result = try {
                    fetch(channelId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    AppLog.d(TAG, "short EPG failed: ${e.javaClass.simpleName}")
                    failed = true
                    emptyList()
                } catch (e: Exception) {
                    AppLog.d(TAG, "short EPG unreadable: ${e.javaClass.simpleName}")
                    failed = true
                    emptyList()
                }
                // A failure is retried sooner than a real (possibly empty) answer.
                val entry = toCached(result, shiftOf(channelId), if (failed) clock() - TTL_MS + RETRY_MS else clock())
                if (cache.size >= MAX_ENTRIES) cache.clear()
                cache[channelId] = entry
                entry.programmes
            }
        }
    }

    /** Description of a fallback programme returned by [load]. */
    fun description(channelId: Long, programmeId: Long): String? = cache[channelId]?.descriptions?.get(programmeId)

    private fun fresh(channelId: Long): Cached? = cache[channelId]?.takeIf { clock() - it.atMs < TTL_MS }

    private suspend fun shiftOf(channelId: Long): Int = db.channelDao().get(channelId)?.shiftMin ?: 0

    private suspend fun fetch(channelId: Long): List<XEpgListing> {
        val ch = db.channelDao().get(channelId) ?: return emptyList()
        val streamId = ch.xtreamStreamId ?: return emptyList()
        val src = db.sourceDao().get(ch.sourceId) ?: return emptyList()
        if (src.sourceType != SourceType.XTREAM) return emptyList()
        val creds = secrets.xtreamCredentials(src) ?: return emptyList()
        val zone = XtreamTime.parseZone(src.effectiveTz) ?: ZoneOffset.UTC
        val ua = src.userAgent?.takeIf { it.isNotBlank() } ?: settings.current().defaultUserAgent
        // The full table includes past programmes (needed to pick a catchup programme); older panels only
        // implement the short list.
        val table = try {
            XtreamParser.parseShortEpg(get(XtreamUrls.simpleDataTable(creds.base, creds.username, creds.password, streamId), ua), zone)
        } catch (e: IOException) {
            emptyList() // not implemented by every panel: the short list below decides whether this is a failure
        } catch (e: XtreamException) {
            emptyList()
        }
        if (table.isNotEmpty()) return table
        return XtreamParser.parseShortEpg(get(XtreamUrls.shortEpg(creds.base, creds.username, creds.password, streamId, SHORT_LIMIT), ua), zone)
    }

    /**
     * Body of a successful response. Throws [IOException] for an error status or a body over [MAX_BYTES]; the
     * message names neither the URL nor the credentials (the URL carries both).
     */
    private fun get(url: String, userAgent: String): String {
        val req = try {
            Request.Builder().url(url).header("User-Agent", StreamHeaders.sanitizeValue(userAgent)).build()
        } catch (e: IllegalArgumentException) {
            throw IOException("Invalid request")
        }
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val source = resp.body.source()
            if (source.request(MAX_BYTES + 1)) throw IOException("Response too large")
            return source.readUtf8()
        }
    }

    private fun toCached(listings: List<XEpgListing>, shiftMin: Int, atMs: Long): Cached {
        val shiftMs = shiftMin * 60_000L
        val programmes = ArrayList<ProgrammeLite>(listings.size)
        val descriptions = HashMap<Long, String>()
        for (l in listings.sortedBy { it.startSec }) {
            if (l.endSec <= l.startSec || l.title.isBlank()) continue
            val id = -(l.startSec / 60) - 1
            if (programmes.lastOrNull()?.id == id) continue
            programmes += ProgrammeLite(id, l.startSec * 1000 + shiftMs, l.endSec * 1000 + shiftMs, l.title.trim(), 0)
            if (l.description.isNotBlank()) descriptions[id] = l.description.trim().take(MAX_DESCRIPTION)
        }
        return Cached(atMs, programmes, descriptions)
    }

    private companion object {
        const val TAG = "ShortEpg"
        const val TTL_MS = 30 * 60_000L
        const val RETRY_MS = 2 * 60_000L
        const val MAX_PARALLEL = 4
        const val MAX_ENTRIES = 500
        const val SHORT_LIMIT = 20
        const val MAX_BYTES = 2L * 1024 * 1024
        const val MAX_DESCRIPTION = 1000
    }
}
