package rocks.urzahil.iptv.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import rocks.urzahil.iptv.data.db.entities.ProgrammeDetailsEntity
import rocks.urzahil.iptv.data.db.entities.ProgrammeDetailsStagingEntity
import rocks.urzahil.iptv.data.db.entities.ProgrammeStagingEntity

@Dao
interface ProgrammeDao {
    /** Rowid range scan; see ProgrammeId.range. */
    @Query("SELECT id, startMs, endMs, title, flags FROM programmes WHERE id BETWEEN :lo AND :hi AND endMs > :t0 ORDER BY id")
    fun range(lo: Long, hi: Long, t0: Long): List<ProgrammeLite>

    @Query("SELECT id, startMs, endMs, title, flags FROM programmes WHERE id = :id")
    fun byId(id: Long): ProgrammeLite?

    @Query("SELECT * FROM programme_details WHERE programmeId = :id")
    fun details(id: Long): ProgrammeDetailsEntity?

    @Query("SELECT COUNT(*) FROM programmes")
    fun count(): Int

    // ---- pruning ----
    @Query("DELETE FROM programmes WHERE id BETWEEN :lo AND :hi AND endMs < :cutoffMs")
    fun deleteEndedBefore(lo: Long, hi: Long, cutoffMs: Long): Int

    @Query("DELETE FROM programmes WHERE id BETWEEN :lo AND :hi")
    fun deleteRange(lo: Long, hi: Long): Int

    // ---- staging ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertStaging(list: List<ProgrammeStagingEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertDetailsStaging(list: List<ProgrammeDetailsStagingEntity>)

    @Query("DELETE FROM programmes_staging")
    fun clearStaging()

    @Query("DELETE FROM programme_details_staging")
    fun clearDetailsStaging()

    @Query("SELECT epgChannelId, MIN(startMs) AS minStartMs FROM programmes_staging GROUP BY epgChannelId")
    fun stagedMinStarts(): List<StagedChannelMin>

    @Query(
        "INSERT OR REPLACE INTO programmes (id, epgChannelId, startMs, endMs, title, flags) " +
            "SELECT id, epgChannelId, startMs, endMs, title, flags FROM programmes_staging",
    )
    fun copyStagingProgrammes()

    @Query(
        "INSERT OR REPLACE INTO programme_details (programmeId, subTitle, description, categories, episode, iconUrl, catchupId) " +
            "SELECT programmeId, subTitle, description, categories, episode, iconUrl, catchupId FROM programme_details_staging " +
            "WHERE programmeId IN (SELECT id FROM programmes)",
    )
    fun copyStagingDetails()
}
