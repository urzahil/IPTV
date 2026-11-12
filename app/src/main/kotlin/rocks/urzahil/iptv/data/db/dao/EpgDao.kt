package rocks.urzahil.iptv.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import rocks.urzahil.iptv.data.db.entities.EpgChannelEntity
import rocks.urzahil.iptv.data.db.entities.EpgSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EpgDao {
    // ---- epg sources ----
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertSource(s: EpgSourceEntity): Long

    @Update
    fun updateSource(s: EpgSourceEntity)

    @Query("SELECT * FROM epg_sources WHERE id = :id")
    fun getSource(id: Long): EpgSourceEntity?

    @Query("SELECT * FROM epg_sources WHERE ownerSourceId = :ownerId ORDER BY priority, id")
    fun sourcesOfBlocking(ownerId: Long): List<EpgSourceEntity>

    @Query("SELECT * FROM epg_sources WHERE ownerSourceId = :ownerId ORDER BY priority, id")
    fun observeSourcesOf(ownerId: Long): Flow<List<EpgSourceEntity>>

    @Query("SELECT * FROM epg_sources WHERE ownerSourceId = :ownerId AND urlHash = :hash")
    fun findByHash(ownerId: Long, hash: String): EpgSourceEntity?

    @Query("DELETE FROM epg_sources WHERE id = :id")
    suspend fun deleteSource(id: Long)

    /** Header-derived (non user-added) sources that are no longer listed in the playlist header. */
    @Query("DELETE FROM epg_sources WHERE ownerSourceId = :ownerId AND userAdded = 0 AND kind = 'URL' AND urlHash NOT IN (:keepHashes)")
    fun deleteHeaderSourcesExcept(ownerId: Long, keepHashes: List<String>): Int

    @Query("UPDATE epg_sources SET etag = :etag, lastModified = :lastModified, lastSyncMs = :now, status = 'OK', lastError = :warning WHERE id = :id")
    fun markSynced(id: Long, etag: String?, lastModified: String?, now: Long, warning: String?)

    @Query("UPDATE epg_sources SET status = 'ERROR', lastError = :error WHERE id = :id")
    fun markError(id: Long, error: String?)

    @Query("UPDATE epg_sources SET etag = NULL, lastModified = NULL WHERE ownerSourceId = :ownerId")
    fun clearValidators(ownerId: Long)

    // ---- epg channels ----
    @Upsert
    fun upsertChannels(list: List<EpgChannelEntity>): List<Long>

    @Query("SELECT id, xmltvIdLower FROM epg_channels WHERE epgSourceId = :epgSourceId")
    fun idMap(epgSourceId: Long): List<EpgIdRow>

    @Query(
        "SELECT ec.id AS id, ec.xmltvId AS xmltvId, ec.nameNorm AS nameNorm FROM epg_channels ec " +
            "JOIN epg_sources es ON es.id = ec.epgSourceId WHERE es.ownerSourceId = :ownerId ORDER BY es.priority, es.id, ec.id",
    )
    fun matchRows(ownerId: Long): List<EpgChannelMatchRow>

    @Query("SELECT ec.id FROM epg_channels ec JOIN epg_sources es ON es.id = ec.epgSourceId WHERE es.ownerSourceId = :ownerId")
    fun channelIdsOf(ownerId: Long): List<Long>

    @Query("SELECT id FROM epg_channels")
    fun allChannelIds(): List<Long>

    /** Channels no playlist channel is mapped to, last seen before [seenBeforeMs]. */
    @Query(
        "SELECT id FROM epg_channels WHERE lastSeenMs < :seenBeforeMs AND id NOT IN " +
            "(SELECT epgChannelId FROM channels WHERE epgChannelId IS NOT NULL)",
    )
    fun orphanIds(seenBeforeMs: Long): List<Long>

    @Query("DELETE FROM epg_channels WHERE id = :id")
    fun deleteChannel(id: Long)
}

data class EpgIdRow(val id: Long, val xmltvIdLower: String)
