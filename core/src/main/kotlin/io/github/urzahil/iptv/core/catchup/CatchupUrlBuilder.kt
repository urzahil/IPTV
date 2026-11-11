package io.github.urzahil.iptv.core.catchup

import io.github.urzahil.iptv.core.model.CatchupSpec
import io.github.urzahil.iptv.core.model.CatchupType
import io.github.urzahil.iptv.core.util.Urls
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class TimeshiftStyle { PATH, PHP }

/** Account data for native Xtream catchup (credentials are raw; they are encoded when the URL is built). */
data class XtreamTarget(
    val base: String,
    val user: String,
    val pass: String,
    val streamId: String,
    val style: TimeshiftStyle = TimeshiftStyle.PATH,
)

object CatchupUrlBuilder {
    private val XTREAM_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm")
    const val DEFAULT_APPEND = "?utc={utc}&lutc={lutc}"

    /**
     * @param xtream required for [CatchupType.XTREAM_API].
     * @throws UnsupportedCatchup when the URL/type combination cannot be rewritten.
     */
    fun build(channelUrl: String, spec: CatchupSpec, req: CatchupRequest, xtream: XtreamTarget? = null): CatchupUrl {
        val s = req.startSec + spec.correctionSec
        var e = req.endSec + spec.correctionSec
        if (e - s < MIN_DURATION_SEC) e = s + MIN_DURATION_SEC
        val n = req.nowSec
        val r = CatchupRequest(s, e, n, req.catchupId)

        val type = effectiveType(channelUrl, spec)
        return when (type) {
            CatchupType.DEFAULT -> CatchupUrl(CatchupTemplate.expand(spec.source!!, r, spec.tz), s)
            CatchupType.APPEND -> {
                val tpl = spec.source?.takeIf { it.isNotBlank() } ?: DEFAULT_APPEND
                CatchupUrl(Urls.appendQuery(channelUrl, CatchupTemplate.expand(tpl, r, spec.tz)), s)
            }
            CatchupType.SHIFT -> CatchupUrl(Urls.appendQuery(channelUrl, "utc=$s&lutc=$n"), s)
            CatchupType.FLUSSONIC -> CatchupUrl(FlussonicRewriter.rewrite(channelUrl, s, e - s), s)
            CatchupType.XTREAM -> {
                val m = XtreamUrlPattern.match(channelUrl)
                    ?: throw UnsupportedCatchup("URL is not an Xtream stream URL")
                xtreamPath(XtreamTarget(m.base, m.user, m.pass, m.id), s, e, spec.tz, encode = false)
            }
            CatchupType.XTREAM_API -> {
                val t = xtream ?: throw UnsupportedCatchup("Missing Xtream account for catchup")
                xtreamPath(t, s, e, spec.tz, encode = true)
            }
            CatchupType.NONE -> throw UnsupportedCatchup("Catchup is not available for this channel")
        }
    }

    /** DEFAULT without a source resolves like the parser does: Xtream URL pattern, else SHIFT. */
    private fun effectiveType(url: String, spec: CatchupSpec): CatchupType {
        if (spec.type == CatchupType.DEFAULT && spec.source.isNullOrBlank()) {
            return if (XtreamUrlPattern.match(url) != null) CatchupType.XTREAM else CatchupType.SHIFT
        }
        return spec.type
    }

    private fun xtreamPath(t: XtreamTarget, s: Long, e: Long, tz: ZoneId, encode: Boolean): CatchupUrl {
        val sMin = Math.floorDiv(s, 60L) * 60
        val initialSeekMs = (s - sMin) * 1000
        val durMin = maxOf(1L, Math.floorDiv(e - sMin + 59, 60L))
        val stamp = XTREAM_TIME.format(Instant.ofEpochSecond(sMin).atZone(tz))
        val url = when (t.style) {
            TimeshiftStyle.PATH ->
                if (encode) XtreamUrlsBridge.path(t, durMin, stamp)
                else "${t.base}/timeshift/${t.user}/${t.pass}/$durMin/$stamp/${t.streamId}.ts"
            TimeshiftStyle.PHP -> XtreamUrlsBridge.php(t, durMin, stamp)
        }
        return CatchupUrl(url, sMin, initialSeekMs)
    }

    const val MIN_DURATION_SEC = 60L
}

private object XtreamUrlsBridge {
    fun path(t: XtreamTarget, durMin: Long, stamp: String) =
        io.github.urzahil.iptv.core.xtream.XtreamUrls.timeshiftPath(t.base, t.user, t.pass, durMin, stamp, t.streamId)

    fun php(t: XtreamTarget, durMin: Long, stamp: String) =
        io.github.urzahil.iptv.core.xtream.XtreamUrls.timeshiftPhp(t.base, t.user, t.pass, durMin, stamp, t.streamId)
}
