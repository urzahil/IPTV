package io.github.urzahil.iptv.data.repo

import io.github.urzahil.iptv.core.model.StreamHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Channel-level headers <-> the `headersJson` column. */
object StreamHeadersJson {
    fun encode(h: StreamHeaders): String? {
        if (h.isEmpty) return null
        return buildJsonObject {
            for ((k, v) in h.toMap()) put(k, JsonPrimitive(v))
        }.toString()
    }

    fun decode(json: String?): StreamHeaders {
        if (json.isNullOrBlank()) return StreamHeaders.EMPTY
        val obj = try {
            Json.parseToJsonElement(json) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return StreamHeaders.EMPTY
        var h = StreamHeaders.EMPTY
        for ((k, v) in obj) {
            val s = (v as? JsonPrimitive)?.content ?: continue
            h = h.with(k, s)
        }
        return h
    }
}
