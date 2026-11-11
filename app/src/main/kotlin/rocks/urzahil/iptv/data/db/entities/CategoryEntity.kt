package rocks.urzahil.iptv.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "categories",
    foreignKeys = [ForeignKey(SourceEntity::class, ["id"], ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index(value = ["sourceId", "providerKey"], unique = true),
        Index(value = ["sourceId", "sortIndex"]),
    ],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    /** Xtream category_id, or the group name for M3U. */
    val providerKey: String,
    val name: String,
    /** Provider order (first appearance). */
    val sortIndex: Int,
    val userSortIndex: Int? = null,
    val hidden: Boolean = false,
    val channelCount: Int = 0,
    val syncGen: Int = 0,
)
