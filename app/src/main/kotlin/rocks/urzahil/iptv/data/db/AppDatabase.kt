package rocks.urzahil.iptv.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import rocks.urzahil.iptv.data.db.dao.CategoryDao
import rocks.urzahil.iptv.data.db.dao.ChannelDao
import rocks.urzahil.iptv.data.db.dao.ChannelPrefDao
import rocks.urzahil.iptv.data.db.dao.EpgDao
import rocks.urzahil.iptv.data.db.dao.FavouriteDao
import rocks.urzahil.iptv.data.db.dao.ProgrammeDao
import rocks.urzahil.iptv.data.db.dao.RecentDao
import rocks.urzahil.iptv.data.db.dao.SourceDao
import rocks.urzahil.iptv.data.db.entities.CategoryEntity
import rocks.urzahil.iptv.data.db.entities.ChannelCategoryEntity
import rocks.urzahil.iptv.data.db.entities.ChannelEntity
import rocks.urzahil.iptv.data.db.entities.ChannelFts
import rocks.urzahil.iptv.data.db.entities.ChannelPrefEntity
import rocks.urzahil.iptv.data.db.entities.EpgChannelEntity
import rocks.urzahil.iptv.data.db.entities.EpgSourceEntity
import rocks.urzahil.iptv.data.db.entities.FavouriteEntity
import rocks.urzahil.iptv.data.db.entities.ProgrammeDetailsEntity
import rocks.urzahil.iptv.data.db.entities.ProgrammeDetailsStagingEntity
import rocks.urzahil.iptv.data.db.entities.ProgrammeEntity
import rocks.urzahil.iptv.data.db.entities.ProgrammeStagingEntity
import rocks.urzahil.iptv.data.db.entities.RecentEntity
import rocks.urzahil.iptv.data.db.entities.SourceEntity

@Database(
    entities = [
        SourceEntity::class,
        CategoryEntity::class,
        ChannelEntity::class,
        ChannelFts::class,
        ChannelCategoryEntity::class,
        FavouriteEntity::class,
        RecentEntity::class,
        ChannelPrefEntity::class,
        EpgSourceEntity::class,
        EpgChannelEntity::class,
        ProgrammeEntity::class,
        ProgrammeDetailsEntity::class,
        ProgrammeStagingEntity::class,
        ProgrammeDetailsStagingEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun categoryDao(): CategoryDao
    abstract fun channelDao(): ChannelDao
    abstract fun favouriteDao(): FavouriteDao
    abstract fun recentDao(): RecentDao
    abstract fun channelPrefDao(): ChannelPrefDao
    abstract fun epgDao(): EpgDao
    abstract fun programmeDao(): ProgrammeDao

    /** Flushes the WAL into the main file (after large deletes). */
    fun checkpoint() {
        openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
    }

    companion object {
        const val FILE_NAME = "iptv.db"

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, FILE_NAME).build()
    }
}
