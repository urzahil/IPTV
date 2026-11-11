package io.github.urzahil.iptv.data.repo

import io.github.urzahil.iptv.core.epg.NameNormalizer

/** Builds FTS4 MATCH strings from user input. */
object SearchQuery {
    private val TOKEN = Regex("[\\p{L}\\p{N}]+")

    /** "bbc one" -> "bbc* one*". Operators and punctuation are stripped. Empty when nothing searchable remains. */
    fun toFts(input: String): String =
        TOKEN.findAll(input).joinToString(" ") { it.value.lowercase() + "*" }

    /** Fallback for LIKE matching on channels.nameNorm. */
    fun toNorm(input: String): String = NameNormalizer.normalize(input)
}
