package io.github.urzahil.iptv.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.urzahil.iptv.data.db.dao.CategoryDao
import io.github.urzahil.iptv.data.db.dao.ChannelDao
import io.github.urzahil.iptv.data.db.dao.ChannelPrefDao
import io.github.urzahil.iptv.data.db.dao.EpgDao
import io.github.urzahil.iptv.data.db.dao.FavouriteDao
import io.github.urzahil.iptv.data.db.dao.ProgrammeDao
import io.github.urzahil.iptv.data.db.dao.RecentDao
import io.github.urzahil.iptv.data.db.dao.SourceDao
import io.github.urzahil.iptv.data.db.entities.CategoryEntity
import io.github.urzahil.iptv.data.db.entities.ChannelCategoryEntity
import io.github.urzahil.iptv.data.db.entities.ChannelEntity
import io.github.urzahil.iptv.data.db.entities.ChannelFts
import io.github.urzahil.iptv.data.db.entities.ChannelPrefEntity
import io.github.urzahil.iptv.data.db.entities.EpgChannelEntity
import io.github.urzahil.iptv.data.db.entities.EpgSourceEntity
import io.github.urzahil.iptv.data.db.entities.FavouriteEntity
import io.github.urzahil.iptv.data.db.entities.ProgrammeDetailsEntity
import io.github.urzahil.iptv.data.db.entities.ProgrammeDetailsStagingEntity
import io.github.urzahil.iptv.data.db.entities.ProgrammeEntity
import io.github.urzahil.iptv.data.db.entities.ProgrammeStagingEntity
import io.github.urzahil.iptv.data.db.entities.RecentEntity
import io.github.urzahil.iptv.data.db.entities.SourceEntity

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
