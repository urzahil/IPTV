package rocks.urzahil.iptv.core.xtream

import java.io.InputStream
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeToSequence

object XtreamParser {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    private fun parseRoot(body: String): JsonElement {
        val t = body.trim().removePrefix("﻿")
        if (t.isEmpty()) throw XtreamException("Empty response from server")
        return try {
            json.parseToJsonElement(t)
        } catch (e: SerializationException) {
            throw XtreamException("Server did not return JSON")
        }
    }

    fun parseAuth(body: String): XtreamAuth {
        val root = parseRoot(body) as? JsonObject
            ?: throw XtreamException("Unexpected response from server")
        val ui = root.obj("user_info") ?: throw XtreamException("Unexpected response from server (no user_info)")
        val si = root.obj("server_info")
        val account = XtreamAccountInfo(
            auth = ui.bool("auth") ?: false,
            status = ui.strNotBlank("status"),
            username = ui.strNotBlank("username"),
            expiresAtSec = ui.long("exp_date"),
            maxConnections = ui.int("max_connections"),
            activeConnections = ui.int("active_cons"),
            allowedFormats = ui.stringList("allowed_output_formats"),
        )
        val server = XtreamServerInfo(
            url = si?.strNotBlank("url"),
            port = si?.strNotBlank("port"),
            timezone = si?.strNotBlank("timezone"),
            timestampNow = si?.long("timestamp_now"),
            timeNow = si?.strNotBlank("time_now"),
        )
        return XtreamAuth(account, server)
    }

    fun parseCategories(body: String): List<XCategory> {
        val root = parseRoot(body)
        val items: Collection<JsonElement> = when (root) {
            is JsonArray -> root
            is JsonObject -> root.values
            else -> throw XtreamException("Unexpected categories response")
        }
        return items.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.strNotBlank("category_id") ?: return@mapNotNull null
            XCategory(id, o.str("category_name")?.trim().orEmpty().ifEmpty { id })
        }
    }

    /** Streams a get_live_streams response element by element; returns the number of emitted streams. */
    @OptIn(ExperimentalSerializationApi::class)
    fun streamLiveStreams(input: InputStream, onStream: (XLiveStream) -> Unit): Int {
        var n = 0
        try {
            val seq = json.decodeToSequence(input, JsonElement.serializer(), DecodeSequenceMode.ARRAY_WRAPPED)
            for (e in seq) {
                val s = parseLiveStream(e as? JsonObject ?: continue) ?: continue
                n++
                onStream(s)
            }
        } catch (e: SerializationException) {
            throw XtreamException("Unexpected live streams response")
        } catch (e: IllegalArgumentException) {
            throw XtreamException("Unexpected live streams response")
        }
        return n
    }

    fun parseLiveStreams(body: String): List<XLiveStream> {
        val out = ArrayList<XLiveStream>()
        streamLiveStreams(body.byteInputStream(Charsets.UTF_8)) { out.add(it) }
        return out
    }

    internal fun parseLiveStream(o: JsonObject): XLiveStream? {
        val type = o.strNotBlank("stream_type")?.lowercase()
        if (type == "movie" || type == "series") return null
        val id = o.long("stream_id") ?: return null
        val categoryIds = o.stringList("category_ids")
        return XLiveStream(
            num = o.int("num"),
            name = o.str("name")?.trim().orEmpty(),
            streamType = type,
            streamId = id,
            icon = o.strNotBlank("stream_icon"),
            epgChannelId = o.strNotBlank("epg_channel_id"),
            categoryId = o.strNotBlank("category_id"),
            categoryIds = categoryIds,
            tvArchive = o.bool("tv_archive") ?: false,
            tvArchiveDuration = o.int("tv_archive_duration") ?: 0,
            isAdult = o.bool("is_adult") ?: false,
            directSource = o.strNotBlank("direct_source"),
        )
    }

    /** Parses get_short_epg / get_simple_data_table. Local time strings are interpreted in [serverZone]. */
    fun parseShortEpg(body: String, serverZone: ZoneId): List<XEpgListing> {
        val root = parseRoot(body)
        val items: List<JsonElement> = when (root) {
            is JsonObject -> root.arr("epg_listings")
            is JsonArray -> root
            else -> emptyList()
        }
        return items.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val start = o.long("start_timestamp") ?: localToEpoch(o.str("start"), serverZone)
            val end = o.long("stop_timestamp") ?: o.long("end_timestamp") ?: localToEpoch(o.str("end"), serverZone)
            if (start == null || end == null) return@mapNotNull null
            XEpgListing(
                id = o.strNotBlank("id") ?: o.strNotBlank("epg_id"),
                title = Base64Text.decode(o.str("title")),
                description = Base64Text.decode(o.str("description")),
                startSec = start,
                endSec = end,
                hasArchive = o.bool("has_archive") ?: false,
                nowPlaying = o.bool("now_playing") ?: false,
            )
        }
    }

    private fun localToEpoch(s: String?, zone: ZoneId): Long? {
        if (s.isNullOrBlank()) return null
        return try {
            LocalDateTime.parse(s.trim(), XtreamTime.LOCAL_FORMAT).atZone(zone).toEpochSecond()
        } catch (e: DateTimeException) {
            null
        }
    }
}
