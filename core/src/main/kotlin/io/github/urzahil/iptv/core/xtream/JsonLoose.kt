package io.github.urzahil.iptv.core.xtream

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Lenient accessors for sloppy Xtream panels (numbers as strings, "" for null, [] for {}, ...). */

private fun JsonElement?.prim(): JsonPrimitive? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }

fun JsonObject.str(key: String): String? = this[key].prim()?.content

fun JsonObject.strNotBlank(key: String): String? = str(key)?.trim()?.takeIf { it.isNotEmpty() }

fun JsonObject.long(key: String): Long? = this[key].prim().toLongLoose()

fun JsonObject.int(key: String): Int? = long(key)?.let {
    if (it > Int.MAX_VALUE) Int.MAX_VALUE else if (it < Int.MIN_VALUE) Int.MIN_VALUE else it.toInt()
}

/** true/false, 1/0, "1"/"0", "true"/"false". */
fun JsonObject.bool(key: String): Boolean? {
    val p = this[key].prim() ?: return null
    val c = p.content.trim().lowercase()
    return when (c) {
        "true", "1", "yes" -> true
        "false", "0", "no", "" -> if (c.isEmpty()) null else false
        else -> c.toDoubleOrNull()?.let { it != 0.0 }
    }
}

/** An array of ints; also accepts a single value or a comma separated string. */
fun JsonObject.intList(key: String): List<Int> = stringList(key).mapNotNull { it.trim().toIntOrNull() }

fun JsonObject.stringList(key: String): List<String> {
    return when (val e = this[key]) {
        null, JsonNull -> emptyList()
        is JsonArray -> e.mapNotNull { it.prim()?.content?.takeIf { s -> s.isNotBlank() } }
        is JsonObject -> e.values.mapNotNull { it.prim()?.content?.takeIf { s -> s.isNotBlank() } }
        is JsonPrimitive -> e.content.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
}

/** The nested object, or null when missing, null or an empty/other array (panels send [] for {}). */
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** The nested array; an object is treated as a map of items (its values). */
fun JsonObject.arr(key: String): List<JsonElement> = when (val e = this[key]) {
    is JsonArray -> e
    is JsonObject -> e.values.toList()
    else -> emptyList()
}

internal fun JsonPrimitive?.toLongLoose(): Long? {
    if (this == null) return null
    val c = content.trim()
    if (c.isEmpty()) return null
    c.toLongOrNull()?.let { return it }
    c.toDoubleOrNull()?.let { return it.toLong() }
    return when (c.lowercase()) { "true" -> 1; "false" -> 0; else -> null }
}
