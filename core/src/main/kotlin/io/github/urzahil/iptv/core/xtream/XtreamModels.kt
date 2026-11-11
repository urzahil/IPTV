package io.github.urzahil.iptv.core.xtream

data class XtreamAccountInfo(
    val auth: Boolean,
    val status: String?,
    val username: String?,
    val expiresAtSec: Long?,
    val maxConnections: Int?,
    val activeConnections: Int?,
    val allowedFormats: List<String>,
) {
    /** Login accepted and account reported as active (or status absent). */
    val isActive: Boolean get() = auth && (status == null || status.equals("Active", ignoreCase = true))
}

data class XtreamServerInfo(
    val url: String?,
    val port: String?,
    val timezone: String?,
    val timestampNow: Long?,
    val timeNow: String?,
)

data class XtreamAuth(val account: XtreamAccountInfo, val server: XtreamServerInfo)

data class XCategory(val id: String, val name: String)

data class XLiveStream(
    val num: Int?,
    val name: String,
    val streamType: String?,
    val streamId: Long,
    val icon: String?,
    val epgChannelId: String?,
    val categoryId: String?,
    val categoryIds: List<String>,
    val tvArchive: Boolean,
    /** Days; 0 when unknown. */
    val tvArchiveDuration: Int,
    val isAdult: Boolean,
    val directSource: String?,
) {
    /** Category keys for the channel: category_ids if present, else [category_id]; empty means uncategorised. */
    val groups: List<String> get() = categoryIds.ifEmpty { listOfNotNull(categoryId) }
}

data class XEpgListing(
    val id: String?,
    val title: String,
    val description: String,
    val startSec: Long,
    val endSec: Long,
    val hasArchive: Boolean,
    val nowPlaying: Boolean,
)

class XtreamException(message: String) : Exception(message)
