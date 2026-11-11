package io.github.urzahil.iptv.core.m3u

/** Result of tokenizing an #EXTINF line (everything after "#EXTINF:"). */
data class ExtInf(
    val duration: Double?,
    /** Lowercased keys; the first non-empty value wins. */
    val attrs: Map<String, String>,
    val title: String,
)

object M3uAttributeTokenizer {
    private val NUMBER = Regex("[+-]?\\d+(?:\\.\\d+)?")
    private val BROKEN_TITLE = Regex("^\\s*((?:[\\w-]+=\"[^\"]*\"\\s*)+),(.*)$")

    fun parseExtInf(rest: String): ExtInf {
        val s = rest
        var i = 0
        while (i < s.length && s[i] == ' ') i++
        // duration: only if the token looks like a number
        var j = i
        while (j < s.length && s[j] != ' ' && s[j] != ',') j++
        var duration: Double? = null
        val tok = s.substring(i, j)
        if (tok.isNotEmpty() && NUMBER.matches(tok)) {
            duration = tok.toDoubleOrNull()
            i = j
        }
        val attrs = LinkedHashMap<String, String>()
        val title = readAttributes(s, i, attrs, allowTitle = true)
        var finalTitle = title.trim()
        val m = BROKEN_TITLE.find(finalTitle)
        if (m != null) {
            readAttributes(m.groupValues[1], 0, attrs, allowTitle = false)
            finalTitle = m.groupValues[2].trim()
        }
        return ExtInf(duration, attrs, finalTitle)
    }

    /** Parses key=value pairs only (used for the #EXTM3U header line). */
    fun parseAttributes(text: String): Map<String, String> {
        val attrs = LinkedHashMap<String, String>()
        readAttributes(text, 0, attrs, allowTitle = false)
        return attrs
    }

    /** Reads attributes from [start]; returns the title (text after the first unquoted comma) or "". */
    private fun readAttributes(s: String, start: Int, out: MutableMap<String, String>, allowTitle: Boolean): String {
        var i = start
        val n = s.length
        while (i < n) {
            while (i < n && (s[i] == ' ' || s[i] == '\t')) i++
            if (i >= n) break
            if (s[i] == ',') {
                return if (allowTitle) s.substring(i + 1) else ""
            }
            val ks = i
            while (i < n && s[i] != '=' && s[i] != ' ' && s[i] != ',') i++
            val key = s.substring(ks, i).lowercase()
            var value = ""
            if (i < n && s[i] == '=') {
                i++
                if (i < n && (s[i] == '"' || s[i] == '\'')) {
                    val q = s[i]
                    i++
                    val close = s.indexOf(q, i)
                    if (close >= 0) {
                        value = s.substring(i, close)
                        i = close + 1
                    } else {
                        // missing closing quote: read to the next quote+comma, else to the next comma, else the end
                        val qc = s.indexOf("$q,", i)
                        val end = when {
                            qc >= 0 -> qc
                            else -> s.indexOf(',', i).let { if (it >= 0) it else n }
                        }
                        value = s.substring(i, end)
                        i = if (qc >= 0) qc + 1 else end
                    }
                } else {
                    val vs = i
                    while (i < n && s[i] != ' ' && s[i] != ',') i++
                    value = s.substring(vs, i)
                }
            }
            if (key.isNotEmpty() && out[key].isNullOrEmpty()) out[key] = value
        }
        return ""
    }
}
