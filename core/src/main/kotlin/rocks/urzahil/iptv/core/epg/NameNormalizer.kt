package rocks.urzahil.iptv.core.epg

import java.text.Normalizer

object NameNormalizer {
    private val COMBINING = Regex("\\p{M}+")
    private val LEADING_TAG = Regex("^\\s*[\\[(]?[a-z]{2,4}[\\])]?\\s*[:|\\-]\\s*")
    private val BRACKETS = Regex("\\[[^\\]]*\\]|\\([^)]*\\)")
    private val QUALITY = Regex("\\b(uhd|fhd|hd|sd|4k|8k|hevc|h\\.?265|h\\.?264|1080[pi]?|720p|50fps|60fps|backup)\\b")
    private val KEEP = Regex("[^a-z0-9+]")

    fun normalize(name: String?): String {
        if (name.isNullOrBlank()) return ""
        var s = Normalizer.normalize(name, Normalizer.Form.NFKD)
        s = COMBINING.replace(s, "").lowercase()
        s = LEADING_TAG.replaceFirst(s, "")
        s = s.replace("&", " and ")
        s = BRACKETS.replace(s, " ")
        s = QUALITY.replace(s, " ")
        return KEEP.replace(s, "")
    }
}
