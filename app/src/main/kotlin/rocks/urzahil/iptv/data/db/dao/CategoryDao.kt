package rocks.urzahil.iptv.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import rocks.urzahil.iptv.data.db.entities.CategoryEntity
import rocks.urzahil.iptv.data.db.entities.ChannelCategoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Upsert
    fun upsertAll(list: List<CategoryEntity>): List<Long>

    @Query("SELECT id, providerKey, hidden, userSortIndex FROM categories WHERE sourceId = :sourceId")
    fun keyRows(sourceId: Long): List<CategoryKeyRow>

    @Query("SELECT * FROM categories WHERE sourceId = :sourceId AND hidden = 0 AND channelCount > 0 ORDER BY COALESCE(userSortIndex, sortIndex), id")
    fun observeVisible(sourceId: Long): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE sourceId = :sourceId ORDER BY COALESCE(userSortIndex, sortIndex), id")
    fun getAllBlocking(sourceId: Long): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun get(id: Long): CategoryEntity?

    @Query("DELETE FROM categories WHERE sourceId = :sourceId AND syncGen < :gen")
    fun deleteStale(sourceId: Long, gen: Int): Int

    @Query(
        "UPDATE categories SET channelCount = " +
            "(SELECT COUNT(*) FROM channel_category cc WHERE cc.categoryId = categories.id) WHERE sourceId = :sourceId",
    )
    fun recountChannels(sourceId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMemberships(list: List<ChannelCategoryEntity>)

    @Query(
        "DELETE FROM channel_category WHERE syncGen < :gen AND categoryId IN " +
            "(SELECT id FROM categories WHERE sourceId = :sourceId)",
    )
    fun deleteStaleMemberships(sourceId: Long, gen: Int): Int
}
