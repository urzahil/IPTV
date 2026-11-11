package rocks.urzahil.iptv.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "favourites",
    foreignKeys = [ForeignKey(ChannelEntity::class, ["id"], ["channelId"], onDelete = ForeignKey.CASCADE)],
)
data class FavouriteEntity(@PrimaryKey val channelId: Long, val position: Int)

@Entity(
    tableName = "recents",
    foreignKeys = [ForeignKey(ChannelEntity::class, ["id"], ["channelId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("lastWatchedMs")],
)
data class RecentEntity(@PrimaryKey val channelId: Long, val lastWatchedMs: Long)

@Entity(
    tableName = "channel_prefs",
    foreignKeys = [ForeignKey(ChannelEntity::class, ["id"], ["channelId"], onDelete = ForeignKey.CASCADE)],
)
data class ChannelPrefEntity(
    @PrimaryKey val channelId: Long,
    val engine: String? = null,
    val autoEngine: String? = null,
    val autoEngineSetMs: Long? = null,
    val audioLang: String? = null,
    val textLang: String? = null,
    val aspectMode: String? = null,
)
