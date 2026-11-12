package rocks.urzahil.iptv.core.m3u


data class M3uHeader(
    val epgUrls: List<String> = emptyList(),
    val catchupType: String? = null,
    val catchupSource: String? = null,
    val catchupDays: Int? = null,
    val catchupCorrectionSec: Long? = null,
    val shiftMin: Int? = null,
) {
    companion object {
        fun from(attrs: Map<String, String>): M3uHeader {
            val urls = LinkedHashSet<String>()
            for (k in listOf("url-tvg", "x-tvg-url", "tvg-url")) {
                val v = attrs[k]?.trim().orEmpty()
                if (v.isEmpty()) continue
                val parts = if (',' in v) v.split(',') else v.split(Regex("\\s+"))
                parts.map { it.trim() }.filter { it.isNotEmpty() }.forEach { urls.add(it) }
            }
            return M3uHeader(
                epgUrls = urls.toList(),
                catchupType = (attrs["catchup"] ?: attrs["catchup-type"])?.takeIf { it.isNotBlank() },
                catchupSource = attrs["catchup-source"]?.takeIf { it.isNotBlank() },
                catchupDays = attrs["catchup-days"]?.trim()?.toIntOrNull(),
                catchupCorrectionSec = hoursToSeconds(attrs["catchup-correction"]),
                shiftMin = hoursToMinutes(attrs["tvg-shift"]),
            )
        }

        internal fun hoursToMinutes(v: String?): Int? =
            v?.trim()?.replace(',', '.')?.toDoubleOrNull()?.let { Math.round(it * 60).toInt() }

        internal fun hoursToSeconds(v: String?): Long? =
            v?.trim()?.replace(',', '.')?.toDoubleOrNull()?.let { Math.round(it * 3600) }
    }
}

