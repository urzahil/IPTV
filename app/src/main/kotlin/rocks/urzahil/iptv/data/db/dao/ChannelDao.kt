package rocks.urzahil.iptv.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import rocks.urzahil.iptv.data.db.entities.ChannelEntity
import kotlinx.coroutines.flow.Flow

private const val COLS =
    "c.id, c.sourceId, c.name, c.number, c.logoUrl, c.epgChannelId, c.shiftMin, c.catchupDays, c.isRadio, " +
        "(f.channelId IS NOT NULL) AS fav"
private const val FAV_JOIN = "LEFT JOIN favourites f ON f.channelId = c.id"

@Dao
interface ChannelDao {
    // ---- import (blocking; called from IO threads inside transactions) ----

    @Upsert
    fun upsertAll(list: List<ChannelEntity>): List<Long>

    @Query("SELECT id, providerKey, hidden, epgChannelId FROM channels WHERE sourceId = :sourceId")
    fun keyRows(sourceId: Long): List<ChannelKeyRow>

    @Query("DELETE FROM channels WHERE sourceId = :sourceId AND syncGen < :gen")
    fun deleteStale(sourceId: Long, gen: Int): Int

    @Query("SELECT COUNT(*) FROM channels WHERE sourceId = :sourceId")
    fun count(sourceId: Long): Int

    @Query("SELECT id, tvgId, tvgName, name, nameNorm, catchupDays, epgChannelId FROM channels WHERE sourceId = :sourceId")
    fun matchRows(sourceId: Long): List<ChannelMatchRow>

    @Query("UPDATE channels SET epgChannelId = :epgChannelId WHERE id = :id")
    fun setEpgChannel(id: Long, epgChannelId: Long?)

    @Query("SELECT epgChannelId, MAX(catchupDays) AS maxCatchupDays FROM channels WHERE epgChannelId IS NOT NULL GROUP BY epgChannelId")
    fun epgReferences(): List<EpgRef>

    @Query("INSERT INTO channels_fts(channels_fts) VALUES('optimize')")
    fun optimizeFts()

    // ---- browsing ----

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun get(id: Long): ChannelEntity?

    @Query(
        "SELECT $COLS FROM channel_category cc JOIN channels c ON c.id = cc.channelId $FAV_JOIN " +
            "WHERE cc.categoryId = :categoryId AND c.hidden = 0 AND (:showRadio = 1 OR c.isRadio = 0) ORDER BY cc.position",
    )
    fun pagedByCategory(categoryId: Long, showRadio: Boolean): PagingSource<Int, ChannelListItem>

    @Query(
        "SELECT $COLS FROM channel_category cc JOIN channels c ON c.id = cc.channelId $FAV_JOIN " +
            "WHERE cc.categoryId = :categoryId AND c.hidden = 0 AND (:showRadio = 1 OR c.isRadio = 0) " +
            "ORDER BY (c.number IS NULL), c.number, cc.position",
    )
    fun pagedByCategoryNumber(categoryId: Long, showRadio: Boolean): PagingSource<Int, ChannelListItem>

    @Query(
        "SELECT $COLS FROM channels c $FAV_JOIN " +
            "WHERE c.sourceId = :sourceId AND c.hidden = 0 AND (:showRadio = 1 OR c.isRadio = 0) ORDER BY c.sortIndex",
    )
    fun pagedBySource(sourceId: Long, showRadio: Boolean): PagingSource<Int, ChannelListItem>

    @Query(
        "SELECT $COLS FROM channels c $FAV_JOIN " +
            "WHERE c.sourceId = :sourceId AND c.hidden = 0 AND (:showRadio = 1 OR c.isRadio = 0) " +
            "ORDER BY (c.number IS NULL), c.number, c.sortIndex",
    )
    fun pagedBySourceNumber(sourceId: Long, showRadio: Boolean): PagingSource<Int, ChannelListItem>

    @Query(
        "SELECT $COLS FROM favourites fv JOIN channels c ON c.id = fv.channelId $FAV_JOIN " +
            "WHERE c.hidden = 0 AND (:showRadio = 1 OR c.isRadio = 0) ORDER BY fv.position",
    )
    fun observeFavourites(showRadio: Boolean): Flow<List<ChannelListItem>>

    @Query(
        "SELECT $COLS FROM recents r JOIN channels c ON c.id = r.channelId $FAV_JOIN " +
            "WHERE c.hidden = 0 AND (:showRadio = 1 OR c.isRadio = 0) ORDER BY r.lastWatchedMs DESC LIMIT :limit",
    )
    fun observeRecents(showRadio: Boolean, limit: Int): Flow<List<ChannelListItem>>

    @Query(
        "SELECT $COLS FROM channels c $FAV_JOIN WHERE c.hidden = 0 AND (:showRadio = 1 OR c.isRadio = 0) AND c.id IN " +
            "(SELECT rowid FROM channels_fts WHERE channels_fts MATCH :ftsQuery) ORDER BY c.sourceId, c.sortIndex LIMIT 300",
    )
    suspend fun searchFts(ftsQuery: String, showRadio: Boolean): List<ChannelListItem>

    @Query(
        "SELECT $COLS FROM channels c $FAV_JOIN WHERE c.hidden = 0 AND (:showRadio = 1 OR c.isRadio = 0) " +
            "AND c.nameNorm LIKE '%' || :norm || '%' ORDER BY c.sourceId, c.sortIndex LIMIT 300",
    )
    suspend fun searchNorm(norm: String, showRadio: Boolean): List<ChannelListItem>

    // ---- zapping ----

    @Query("SELECT position FROM channel_category WHERE categoryId = :categoryId AND channelId = :channelId")
    suspend fun positionIn(categoryId: Long, channelId: Long): Int?

    @Query("SELECT channelId FROM channel_category WHERE categoryId = :categoryId AND position > :position ORDER BY position LIMIT 1")
    suspend fun nextAfter(categoryId: Long, position: Int): Long?

    @Query("SELECT channelId FROM channel_category WHERE categoryId = :categoryId AND position < :position ORDER BY position DESC LIMIT 1")
    suspend fun previousBefore(categoryId: Long, position: Int): Long?

    @Query("SELECT channelId FROM channel_category WHERE categoryId = :categoryId ORDER BY position LIMIT 1")
    suspend fun firstIn(categoryId: Long): Long?

    @Query("SELECT channelId FROM channel_category WHERE categoryId = :categoryId ORDER BY position DESC LIMIT 1")
    suspend fun lastIn(categoryId: Long): Long?

    @Query("SELECT id FROM channels WHERE sourceId = :sourceId AND sortIndex > :sortIndex AND hidden = 0 ORDER BY sortIndex LIMIT 1")
    suspend fun nextInSource(sourceId: Long, sortIndex: Int): Long?

    @Query("SELECT id FROM channels WHERE sourceId = :sourceId AND sortIndex < :sortIndex AND hidden = 0 ORDER BY sortIndex DESC LIMIT 1")
    suspend fun previousInSource(sourceId: Long, sortIndex: Int): Long?

    @Query("SELECT id FROM channels WHERE sourceId = :sourceId AND number = :number AND hidden = 0 ORDER BY sortIndex LIMIT 1")
    suspend fun findByNumber(sourceId: Long, number: Int): Long?
}
