package rocks.urzahil.iptv.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import rocks.urzahil.iptv.data.db.entities.ChannelPrefEntity
import rocks.urzahil.iptv.data.db.entities.FavouriteEntity
import rocks.urzahil.iptv.data.db.entities.RecentEntity

@Dao
interface FavouriteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(f: FavouriteEntity)

    @Query("DELETE FROM favourites WHERE channelId = :channelId")
    suspend fun delete(channelId: Long)

    @Query("SELECT COUNT(*) FROM favourites WHERE channelId = :channelId")
    suspend fun isFavourite(channelId: Long): Int

    @Query("SELECT COALESCE(MAX(position), 0) + 1 FROM favourites")
    suspend fun nextPosition(): Int
}

@Dao
interface RecentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(r: RecentEntity)

    /** Keeps only the newest [keep] rows. */
    @Query("DELETE FROM recents WHERE channelId NOT IN (SELECT channelId FROM recents ORDER BY lastWatchedMs DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Query("DELETE FROM recents")
    suspend fun clear()

    @Query("SELECT channelId FROM recents ORDER BY lastWatchedMs DESC LIMIT 2")
    suspend fun lastTwo(): List<Long>
}

@Dao
interface ChannelPrefDao {
    @Query("SELECT * FROM channel_prefs WHERE channelId = :channelId")
    suspend fun get(channelId: Long): ChannelPrefEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: ChannelPrefEntity)
}
