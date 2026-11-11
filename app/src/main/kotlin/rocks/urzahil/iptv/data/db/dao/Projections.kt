package rocks.urzahil.iptv.data.db.dao

/** Light channel row for lists. */
data class ChannelListItem(
    val id: Long,
    val sourceId: Long,
    val name: String,
    val number: Int?,
    val logoUrl: String?,
    val epgChannelId: Long?,
    val shiftMin: Int,
    val catchupDays: Int,
    val isRadio: Boolean,
    val fav: Boolean,
)

data class ChannelKeyRow(val id: Long, val providerKey: String, val hidden: Boolean, val epgChannelId: Long?)

data class CategoryKeyRow(val id: Long, val providerKey: String, val hidden: Boolean, val userSortIndex: Int?)

/** Channel columns the EPG mapper and the wanted-set builder need. */
data class ChannelMatchRow(
    val id: Long,
    val tvgId: String?,
    val tvgName: String?,
    val name: String,
    val nameNorm: String,
    val catchupDays: Int,
    val epgChannelId: Long?,
)

data class EpgChannelMatchRow(val id: Long, val xmltvId: String, val nameNorm: String)

data class EpgRef(val epgChannelId: Long, val maxCatchupDays: Int)

data class ProgrammeLite(
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val flags: Int,
)

data class StagedChannelMin(val epgChannelId: Long, val minStartMs: Long)
