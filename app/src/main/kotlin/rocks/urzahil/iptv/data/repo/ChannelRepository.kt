package rocks.urzahil.iptv.data.repo

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import rocks.urzahil.iptv.data.db.AppDatabase
import rocks.urzahil.iptv.data.db.dao.ChannelListItem
import rocks.urzahil.iptv.data.db.entities.CategoryEntity
import rocks.urzahil.iptv.data.db.entities.ChannelEntity
import rocks.urzahil.iptv.data.db.entities.FavouriteEntity
import rocks.urzahil.iptv.data.db.entities.RecentEntity
import kotlinx.coroutines.flow.Flow

enum class ZapDirection { NEXT, PREVIOUS }

class ChannelRepository(private val db: AppDatabase) {
    private val channels = db.channelDao()

    fun categories(sourceId: Long): Flow<List<CategoryEntity>> = db.categoryDao().observeVisible(sourceId)

    /** Paged list of a category, or of the whole source when [categoryId] is null. */
    fun paged(sourceId: Long, categoryId: Long?, sortByNumber: Boolean, showRadio: Boolean): Flow<PagingData<ChannelListItem>> =
        Pager(PagingConfig(pageSize = PAGE_SIZE, prefetchDistance = PAGE_SIZE * 2, enablePlaceholders = false)) {
            when {
                categoryId != null && sortByNumber -> channels.pagedByCategoryNumber(categoryId, showRadio)
                categoryId != null -> channels.pagedByCategory(categoryId, showRadio)
                sortByNumber -> channels.pagedBySourceNumber(sourceId, showRadio)
                else -> channels.pagedBySource(sourceId, showRadio)
            }
        }.flow

    fun favourites(showRadio: Boolean): Flow<List<ChannelListItem>> = channels.observeFavourites(showRadio)

    fun recents(showRadio: Boolean, limit: Int = 20): Flow<List<ChannelListItem>> = channels.observeRecents(showRadio, limit)

    suspend fun channel(id: Long): ChannelEntity? = channels.get(id)

    suspend fun search(text: String, showRadio: Boolean): List<ChannelListItem> {
        val fts = SearchQuery.toFts(text)
        if (fts.isEmpty()) return emptyList()
        val hits = channels.searchFts(fts, showRadio)
        if (hits.isNotEmpty()) return hits
        val norm = SearchQuery.toNorm(text)
        return if (norm.isEmpty()) emptyList() else channels.searchNorm(norm, showRadio)
    }

    /** Returns the new favourite state. */
    suspend fun toggleFavourite(channelId: Long): Boolean {
        val dao = db.favouriteDao()
        return if (dao.isFavourite(channelId) > 0) {
            dao.delete(channelId); false
        } else {
            dao.insert(FavouriteEntity(channelId, dao.nextPosition())); true
        }
    }

    suspend fun markWatched(channelId: Long, nowMs: Long = System.currentTimeMillis()) {
        val dao = db.recentDao()
        dao.upsert(RecentEntity(channelId, nowMs))
        dao.trim(RECENT_KEEP)
    }

    /**
     * Zapping neighbour inside the category the user launched from (wraps around), or inside the source when
     * [categoryId] is null. Returns null when the list has a single channel.
     */
    suspend fun neighbour(channelId: Long, categoryId: Long?, direction: ZapDirection): Long? {
        val id = if (categoryId != null) {
            val pos = channels.positionIn(categoryId, channelId) ?: return null
            when (direction) {
                ZapDirection.NEXT -> channels.nextAfter(categoryId, pos) ?: channels.firstIn(categoryId)
                ZapDirection.PREVIOUS -> channels.previousBefore(categoryId, pos) ?: channels.lastIn(categoryId)
            }
        } else {
            val c = channels.get(channelId) ?: return null
            when (direction) {
                ZapDirection.NEXT -> channels.nextInSource(c.sourceId, c.sortIndex)
                ZapDirection.PREVIOUS -> channels.previousInSource(c.sourceId, c.sortIndex)
            }
        }
        return id?.takeIf { it != channelId }
    }

    suspend fun findByNumber(sourceId: Long, number: Int): Long? = channels.findByNumber(sourceId, number)

    companion object {
        const val PAGE_SIZE = 60
        const val RECENT_KEEP = 50
    }
}
