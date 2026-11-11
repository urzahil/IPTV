package io.github.urzahil.iptv.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import io.github.urzahil.iptv.data.db.entities.SourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    @Insert
    suspend fun insert(source: SourceEntity): Long

    @Update
    suspend fun update(source: SourceEntity)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM sources ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE id = :id")
    fun observe(id: Long): Flow<SourceEntity?>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun get(id: Long): SourceEntity?

    @Query("SELECT * FROM sources WHERE id = :id")
    fun getBlocking(id: Long): SourceEntity?

    @Query("SELECT * FROM sources ORDER BY sortOrder, id")
    suspend fun getAll(): List<SourceEntity>

    @Query("SELECT COALESCE(MAX(sortOrder), 0) + 1 FROM sources")
    suspend fun nextSortOrder(): Int

    @Query("UPDATE sources SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query(
        "UPDATE sources SET syncGen = :gen, lastPlaylistSyncMs = :now, status = 'OK', lastError = NULL, " +
            "lastSummary = :summary, channelCount = :channels, playlistEtag = :etag, playlistLastModified = :lastModified " +
            "WHERE id = :id",
    )
    fun setPlaylistOk(id: Long, gen: Int, now: Long, summary: String?, channels: Int, etag: String?, lastModified: String?)

    @Query("UPDATE sources SET lastPlaylistSyncMs = :now, status = 'OK', lastError = NULL, lastSummary = :summary WHERE id = :id")
    fun setPlaylistUnchanged(id: Long, now: Long, summary: String?)

    @Query("UPDATE sources SET status = :status, lastError = :error WHERE id = :id")
    fun setPlaylistError(id: Long, status: String, error: String?)

    @Query(
        "UPDATE sources SET expiresAtMs = :expiresAtMs, maxConnections = :maxConnections, serverTz = :serverTz, " +
            "allowedFormats = :allowedFormats WHERE id = :id",
    )
    fun setAccountInfo(id: Long, expiresAtMs: Long?, maxConnections: Int?, serverTz: String?, allowedFormats: String?)

    @Query("UPDATE sources SET lastEpgSyncMs = :now, epgError = :error WHERE id = :id")
    fun setEpgResult(id: Long, now: Long, error: String?)

    @Query("UPDATE sources SET playlistEtag = NULL, playlistLastModified = NULL WHERE id = :id")
    suspend fun clearPlaylistValidators(id: Long)

    @Query("UPDATE sources SET timeshiftStyle = :style WHERE id = :id")
    suspend fun setTimeshiftStyle(id: Long, style: String)
}
