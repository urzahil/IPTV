package rocks.urzahil.iptv.data.repo

import rocks.urzahil.iptv.core.catchup.TimeshiftStyle
import rocks.urzahil.iptv.core.catchup.XtreamTarget
import rocks.urzahil.iptv.core.model.CatchupSpec
import rocks.urzahil.iptv.core.model.CatchupType
import rocks.urzahil.iptv.core.model.StreamHeaders
import rocks.urzahil.iptv.core.xtream.XtreamTime
import rocks.urzahil.iptv.core.xtream.XtreamUrls
import rocks.urzahil.iptv.data.db.AppDatabase
import rocks.urzahil.iptv.data.db.entities.ChannelEntity
import rocks.urzahil.iptv.data.db.entities.SourceEntity
import rocks.urzahil.iptv.data.db.entities.SourceType
import rocks.urzahil.iptv.data.security.SourceSecrets
import rocks.urzahil.iptv.data.settings.SettingsRepository
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Everything the player needs to start a channel. [liveUrl] and [xtreamTarget] contain credentials for Xtream
 * sources: never log, persist or put them in intents.
 *
 * Catchup: `CatchupUrlBuilder.build(liveUrl, catchup, request, xtreamTarget)`.
 */
data class ChannelStreamInputs(
    val channel: ChannelEntity,
    val source: SourceEntity,
    val liveUrl: String,
    /** App default UA, then source UA/referrer, then channel-level headers. */
    val headers: StreamHeaders,
    val catchup: CatchupSpec,
    val xtreamTarget: XtreamTarget?,
    /** "hls" or "dash" when the playlist declared it. */
    val mimeHint: String?,
)

class StreamInputsRepository(
    private val db: AppDatabase,
    private val secrets: SourceSecrets,
    private val settings: SettingsRepository,
) {
    suspend fun resolve(channelId: Long): ChannelStreamInputs? = withContext(Dispatchers.IO) {
        val ch = db.channelDao().get(channelId) ?: return@withContext null
        val src = db.sourceDao().get(ch.sourceId) ?: return@withContext null
        val app = settings.current()
        var headers = StreamHeaders(userAgent = app.defaultUserAgent)
        headers = headers.merge(StreamHeaders(userAgent = src.userAgent, referrer = src.referrer))
        headers = headers.merge(StreamHeadersJson.decode(ch.headersJson))

        val type = CatchupType.entries.getOrElse(ch.catchupType) { CatchupType.NONE }
        var tz: java.time.ZoneId = ZoneOffset.UTC
        var target: XtreamTarget? = null
        val url: String
        if (src.sourceType == SourceType.XTREAM) {
            val creds = secrets.xtreamCredentials(src) ?: return@withContext null
            val id = ch.xtreamStreamId ?: return@withContext null
            url = XtreamUrls.live(creds.base, creds.username, creds.password, id, liveExtension(src, ch))
            tz = XtreamTime.parseZone(src.effectiveTz) ?: ZoneOffset.UTC
            target = XtreamTarget(
                creds.base, creds.username, creds.password, id.toString(),
                if (src.timeshiftStyle == "php") TimeshiftStyle.PHP else TimeshiftStyle.PATH,
            )
        } else {
            url = ch.streamUrl ?: return@withContext null
            secrets.registerUrlSecrets(url)
        }
        val spec = CatchupSpec(type, ch.catchupSource, ch.catchupDays, ch.catchupCorrectionSec, tz)
        ChannelStreamInputs(ch, src, url, headers, spec, target, ch.mimeHint)
    }

    private fun liveExtension(src: SourceEntity, ch: ChannelEntity): String {
        ch.containerExt?.takeIf { it.isNotBlank() }?.let { return it }
        val allowed = src.allowedFormats?.split(',')?.map { it.trim().lowercase() }.orEmpty()
        return when {
            src.streamFormat == "m3u8" -> "m3u8"
            allowed.isNotEmpty() && "ts" !in allowed && "m3u8" in allowed -> "m3u8"
            else -> "ts"
        }
    }
}
