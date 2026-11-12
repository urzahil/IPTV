package rocks.urzahil.iptv.core.epg

/**
 * Matches playlist channels to EPG channels: tvg-id, then tvg-name, then channel name.
 * Entries must be supplied in EPG-source priority order; the first entry wins for each key.
 */
class EpgMatcher(entries: Iterable<Entry>) {
    /** [nameNorm] is the [NameNormalizer] output of one display name (add one entry per display name). */
    data class Entry(val epgChannelId: Long, val xmltvId: String, val nameNorm: String)

    private val byId = HashMap<String, Long>()
    private val byName = HashMap<String, Long>()

    init {
        for (e in entries) {
            val id = e.xmltvId.trim().lowercase()
            if (id.isNotEmpty()) byId.putIfAbsent(id, e.epgChannelId)
            if (e.nameNorm.isNotEmpty()) byName.putIfAbsent(e.nameNorm, e.epgChannelId)
        }
    }

    fun match(tvgId: String?, tvgName: String?, name: String?): Long? {
        tvgId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { byId[it]?.let { r -> return r } }
        NameNormalizer.normalize(tvgName).takeIf { it.isNotEmpty() }?.let { byName[it]?.let { r -> return r } }
        NameNormalizer.normalize(name).takeIf { it.isNotEmpty() }?.let { byName[it]?.let { r -> return r } }
        return null
    }
}
