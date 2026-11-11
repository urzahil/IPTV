package io.github.urzahil.iptv.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object EpgKind {
    /** XMLTV of an Xtream panel; the URL is built from the source credentials and never stored. */
    const val XTREAM = "XTREAM"

    /** Any other XMLTV URL (playlist header or user supplied); stored encrypted. */
    const val URL = "URL"
}

@Entity(
    tableName = "epg_sources",
    foreignKeys = [ForeignKey(SourceEntity::class, ["id"], ["ownerSourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["ownerSourceId", "urlHash"], unique = true)],
)
data class EpgSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ownerSourceId: Long,
    val kind: String,
    val urlEnc: String? = null,
    /** SHA-256 of the URL (or "xtream"): identity for dedupe without decrypting. */
    val urlHash: String,
    /** Host and path without query, for display. */
    val displayName: String,
    val priority: Int = 0,
    /** True when the user added it (kept when the playlist header changes). */
    val userAdded: Boolean = false,
    val etag: String? = null,
    val lastModified: String? = null,
    val lastSyncMs: Long = 0,
    val status: String = "NEW",
    val lastError: String? = null,
)

@Entity(
    tableName = "epg_channels",
    foreignKeys = [ForeignKey(EpgSourceEntity::class, ["id"], ["epgSourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index(value = ["epgSourceId", "xmltvIdLower"], unique = true),
        Index(value = ["nameNorm"]),
    ],
)
data class EpgChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epgSourceId: Long,
    val xmltvId: String,
    val xmltvIdLower: String,
    val displayName: String,
    /** Normalised display names, newline separated (a channel may declare several). */
    val nameNorm: String,
    val iconUrl: String? = null,
    /** Last time an import saw this channel (orphans are pruned after a few days). */
    val lastSeenMs: Long = 0,
)

/** Id is ProgrammeId.compose(epgChannelId, startMs). No secondary index: range scans use the rowid. */
@Entity(tableName = "programmes")
data class ProgrammeEntity(
    @PrimaryKey(autoGenerate = false) val id: Long,
    val epgChannelId: Long,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val flags: Int,
) {
    companion object {
        const val FLAG_HAS_DETAILS = 1
        const val FLAG_CATCHUP_ID = 2
    }
}

@Entity(
    tableName = "programme_details",
    foreignKeys = [ForeignKey(ProgrammeEntity::class, ["id"], ["programmeId"], onDelete = ForeignKey.CASCADE)],
)
data class ProgrammeDetailsEntity(
    @PrimaryKey val programmeId: Long,
    val subTitle: String? = null,
    val description: String? = null,
    val categories: String? = null,
    val episode: String? = null,
    val iconUrl: String? = null,
    val catchupId: String? = null,
)

@Entity(tableName = "programmes_staging")
data class ProgrammeStagingEntity(
    @PrimaryKey(autoGenerate = false) val id: Long,
    val epgChannelId: Long,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val flags: Int,
)

@Entity(tableName = "programme_details_staging")
data class ProgrammeDetailsStagingEntity(
    @PrimaryKey val programmeId: Long,
    val subTitle: String? = null,
    val description: String? = null,
    val categories: String? = null,
    val episode: String? = null,
    val iconUrl: String? = null,
    val catchupId: String? = null,
)
