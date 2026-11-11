package rocks.urzahil.iptv.core.m3u

import rocks.urzahil.iptv.core.io.TextInput
import rocks.urzahil.iptv.core.model.CatchupSpec
import rocks.urzahil.iptv.core.model.CatchupType
import rocks.urzahil.iptv.core.model.ParsedChannel
import rocks.urzahil.iptv.core.model.StreamHeaders
import rocks.urzahil.iptv.core.catchup.XtreamUrlPattern
import rocks.urzahil.iptv.core.util.Urls
import java.io.InputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class M3uParseException(message: String) : Exception(message)

data class M3uStats(
    val header: M3uHeader,
    val channels: Int,
    val skippedVod: Int,
    /** EXTINF entries that never got a URL. */
    val dropped: Int,
)

/**
 * Line-streaming M3U parser. Channels are emitted through a callback; no list is ever built.
 * Throws [M3uParseException] when the input is an HLS media playlist rather than a channel list.
 */
class M3uParser {
    private class Pending {
        var extinf: ExtInf? = null
        var extGrp: String? = null
        var vlcUa: String? = null
        var vlcRef: String? = null
        var vlcOrigin: String? = null
        var httpHeaders: StreamHeaders = StreamHeaders.EMPTY
        var kodiHeaders: StreamHeaders = StreamHeaders.EMPTY
        val kodiProps = LinkedHashMap<String, String>()
        var drm = false
        var mime: String? = null
    }

    fun parse(
        input: InputStream,
        onHeader: (M3uHeader) -> Unit = {},
        onChannel: (ParsedChannel) -> Unit,
    ): M3uStats {
        val lines = TextInput.lines(input)
        var header = M3uHeader()
        val keys = ChannelKeys()
        var pending = Pending()
        var count = 0
        var skipped = 0
        var dropped = 0
        var headerEmitted = false

        fun emitHeaderOnce() {
            if (!headerEmitted) { headerEmitted = true; onHeader(header) }
        }

        while (true) {
            val raw = lines.readLine() ?: break
            var line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("﻿")) line = line.substring(1).trim()
            if (line.startsWith("#")) {
                when {
                    line.startsWith("#EXTM3U", ignoreCase = true) -> {
                        header = M3uHeader.from(M3uAttributeTokenizer.parseAttributes(line.substring(7)))
                    }
                    line.startsWith("#EXTINF:", ignoreCase = true) -> {
                        emitHeaderOnce()
                        if (pending.extinf != null) { dropped++; pending = Pending() }
                        pending.extinf = M3uAttributeTokenizer.parseExtInf(line.substring(8))
                    }
                    line.startsWith("#EXTGRP:", ignoreCase = true) -> pending.extGrp = line.substring(8).trim()
                    line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> vlcOpt(pending, line.substring(11))
                    line.startsWith("#EXTHTTP:", ignoreCase = true) -> extHttp(pending, line.substring(9))
                    line.startsWith("#KODIPROP:", ignoreCase = true) -> kodiProp(pending, line.substring(10))
                    line.startsWith("#EXT-X-", ignoreCase = true) ->
                        throw M3uParseException("URL is a stream, not a playlist")
                }
                continue
            }
            // URL line
            emitHeaderOnce()
            val ext = pending.extinf
            val (url, pipe) = UrlPipeHeaders.split(line)
            if (VodDetector.isVod(url, ext?.duration)) {
                skipped++
                pending = Pending()
                continue
            }
            val ch = buildChannel(url, pipe, ext, pending, header, keys)
            count++
            onChannel(ch)
            pending = Pending()
        }
        emitHeaderOnce()
        if (pending.extinf != null) dropped++
        return M3uStats(header, count, skipped, dropped)
    }

    private fun vlcOpt(p: Pending, body: String) {
        val eq = body.indexOf('=')
        if (eq <= 0) return
        val k = body.substring(0, eq).trim().lowercase()
        val v = body.substring(eq + 1).trim()
        when (k) {
            "http-user-agent" -> p.vlcUa = v
            "http-referrer", "http-referer" -> p.vlcRef = v
            "http-origin" -> p.vlcOrigin = v
        }
    }

    private fun extHttp(p: Pending, body: String) {
        val obj = try {
            Json.parseToJsonElement(body.trim()) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return
        var h = p.httpHeaders
        for ((k, v) in obj) {
            val s = (v as? JsonPrimitive)?.content ?: continue
            h = h.with(k, s)
        }
        p.httpHeaders = h
    }

    private fun kodiProp(p: Pending, body: String) {
        val eq = body.indexOf('=')
        if (eq <= 0) return
        val k = body.substring(0, eq).trim()
        val v = body.substring(eq + 1).trim()
        p.kodiProps[k] = v
        when (k.lowercase()) {
            "inputstream.adaptive.stream_headers", "inputstream.adaptive.common_headers" ->
                p.kodiHeaders = p.kodiHeaders.merge(UrlPipeHeaders.parseHeaderString(v))
            "inputstream.adaptive.manifest_type" -> p.mime = v.lowercase().takeIf { it == "mpd" || it == "hls" }
                ?.let { if (it == "mpd") "dash" else "hls" }
            "inputstream.adaptive.license_type", "inputstream.adaptive.license_key" -> p.drm = true
        }
    }

    private fun buildChannel(
        url: String,
        pipe: StreamHeaders?,
        ext: ExtInf?,
        p: Pending,
        header: M3uHeader,
        keys: ChannelKeys,
    ): ParsedChannel {
        val a = ext?.attrs ?: emptyMap()
        fun attr(vararg names: String): String? =
            names.firstNotNullOfOrNull { a[it]?.trim()?.takeIf { v -> v.isNotEmpty() } }

        val tvgId = attr("tvg-id")
        val tvgName = attr("tvg-name")
        val name = ext?.title.orEmpty().ifBlank { tvgName.orEmpty() }.ifBlank { Urls.lastPathSegment(url) }
        val groups = GroupResolver.resolve(a["group-title"], p.extGrp)
        val chno = attr("tvg-chno", "channel-number", "tvg-num")?.toIntOrNull()
        val shift = M3uHeader.hoursToMinutes(attr("tvg-shift")) ?: header.shiftMin ?: 0

        // Header precedence (low to high): EXTHTTP/KODIPROP, EXTVLCOPT, pipe suffix.
        var headers = p.kodiHeaders.merge(p.httpHeaders)
        var vlc = StreamHeaders(userAgent = p.vlcUa, referrer = p.vlcRef)
        p.vlcOrigin?.let { vlc = vlc.with("Origin", it) }
        headers = headers.merge(vlc)
        if (pipe != null) headers = headers.merge(pipe)

        return ParsedChannel(
            key = keys.next(tvgId, name),
            name = name,
            url = url,
            groups = groups,
            tvgId = tvgId,
            tvgName = tvgName,
            logo = attr("tvg-logo", "logo"),
            chno = chno,
            shiftMin = shift,
            catchup = resolveCatchup(url, a, header),
            headers = headers,
            isRadio = a["radio"]?.trim().equals("true", ignoreCase = true),
            kodiProps = p.kodiProps,
            drm = p.drm,
            mimeHint = p.mime,
        )
    }

    private fun resolveCatchup(url: String, a: Map<String, String>, header: M3uHeader): CatchupSpec {
        fun attr(vararg names: String): String? =
            names.firstNotNullOfOrNull { a[it]?.trim()?.takeIf { v -> v.isNotEmpty() } }

        val typeRaw = attr("catchup", "catchup-type") ?: header.catchupType
        val source = attr("catchup-source") ?: header.catchupSource
        val days = attr("catchup-days", "timeshift", "tvg-rec")?.toIntOrNull() ?: header.catchupDays
        val correction = M3uHeader.hoursToSeconds(attr("catchup-correction")) ?: header.catchupCorrectionSec ?: 0L
        val xtreamUrl = XtreamUrlPattern.match(url) != null

        var type = when {
            typeRaw != null -> CatchupType.fromAttr(typeRaw)
            source != null -> CatchupType.DEFAULT
            (days ?: 0) > 0 && xtreamUrl -> CatchupType.XTREAM
            else -> CatchupType.NONE
        }
        if (type == CatchupType.DEFAULT && source == null) {
            type = if (xtreamUrl) CatchupType.XTREAM else CatchupType.SHIFT
        }
        val d = (days ?: if (type != CatchupType.NONE) DEFAULT_DAYS else 0).coerceIn(0, MAX_DAYS)
        return CatchupSpec(type, source, d, correction)
    }

    companion object {
        const val DEFAULT_DAYS = 7
        const val MAX_DAYS = 30
    }
}
