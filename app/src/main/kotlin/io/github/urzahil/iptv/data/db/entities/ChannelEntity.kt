package io.github.urzahil.iptv.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "channels",
    foreignKeys = [ForeignKey(SourceEntity::class, ["id"], ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index(value = ["sourceId", "providerKey"], unique = true),
        Index(value = ["sourceId", "sortIndex"]),
        Index(value = ["epgChannelId"]),
    ],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    /** M3U: "tvgId|name|#dup"; Xtream: stream_id. Stable across refreshes. */
    val providerKey: String,
    val name: String,
    val nameNorm: String,
    val number: Int? = null,
    val sortIndex: Int,
    val logoUrl: String? = null,
    /** M3U only. Xtream URLs are built at play time from the source credentials. */
    val streamUrl: String? = null,
    val xtreamStreamId: Long? = null,
    val containerExt: String? = null,
    /** Channel-level headers as JSON object (M3U only). */
    val headersJson: String? = null,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val shiftMin: Int = 0,
    /** Ordinal of [io.github.urzahil.iptv.core.model.CatchupType]. */
    val catchupType: Int = 0,
    val catchupSource: String? = null,
    val catchupDays: Int = 0,
    val catchupCorrectionSec: Long = 0,
    val isRadio: Boolean = false,
    /** "hls" or "dash" when the playlist declared it. */
    val mimeHint: String? = null,
    val drm: Boolean = false,
    /** Row id in epg_channels, set by EpgMapper. */
    val epgChannelId: Long? = null,
    val hidden: Boolean = false,
    val syncGen: Int = 0,
)

@Fts4(
    contentEntity = ChannelEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics=1"],
)
@Entity(tableName = "channels_fts")
data class ChannelFts(val name: String)

@Entity(
    tableName = "channel_category",
    primaryKeys = ["categoryId", "channelId"],
    foreignKeys = [
        ForeignKey(CategoryEntity::class, ["id"], ["categoryId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ChannelEntity::class, ["id"], ["channelId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["categoryId", "position"]), Index(value = ["channelId"])],
)
data class ChannelCategoryEntity(
    val categoryId: Long,
    val channelId: Long,
    val position: Int,
    /** Generation of the import that wrote this row; older rows are deleted at the end of a sync. */
    val syncGen: Int = 0,
)
