package rocks.urzahil.iptv.sync

import rocks.urzahil.iptv.data.db.AppDatabase
import rocks.urzahil.iptv.data.db.dao.CategoryKeyRow
import rocks.urzahil.iptv.data.db.dao.ChannelKeyRow
import rocks.urzahil.iptv.data.db.entities.CategoryEntity
import rocks.urzahil.iptv.data.db.entities.ChannelCategoryEntity
import rocks.urzahil.iptv.data.db.entities.ChannelEntity

/**
 * Batched writer shared by the M3U and Xtream importers. Rows are upserted by provider key so ids (and with them
 * favourites, recents and hidden flags) survive refreshes; rows not seen in this generation are deleted by [finish].
 * All calls are blocking and must run off the main thread.
 */
class PlaylistWriter(
    private val db: AppDatabase,
    private val sourceId: Long,
    private val gen: Int,
    private val batchSize: Int = BATCH,
) {
    private class Pending(val entity: ChannelEntity, val categoryKeys: List<String>)

    private val channelDao = db.channelDao()
    private val categoryDao = db.categoryDao()
    private val existingChannels: Map<String, ChannelKeyRow> = channelDao.keyRows(sourceId).associateBy { it.providerKey }
    private val existingCategories: Map<String, CategoryKeyRow> = categoryDao.keyRows(sourceId).associateBy { it.providerKey }

    private val seenChannelKeys = HashSet<String>()
    private val categoryIds = HashMap<String, Long>()
    private val categoryPositions = HashMap<String, Int>()
    private val registeredCategories = HashSet<String>()
    private val pendingCategories = ArrayList<CategoryEntity>()
    private val batch = ArrayList<Pending>()
    private var nextCategorySort = 0
    private var nextChannelSort = 0

    var channelCount = 0
        private set
    val categoryCount: Int get() = registeredCategories.size

    /** Registers a category in provider order. Idempotent. */
    fun registerCategory(providerKey: String, name: String) {
        if (!registeredCategories.add(providerKey)) return
        val old = existingCategories[providerKey]
        pendingCategories.add(
            CategoryEntity(
                id = old?.id ?: 0,
                sourceId = sourceId,
                providerKey = providerKey,
                name = name,
                sortIndex = nextCategorySort++,
                userSortIndex = old?.userSortIndex,
                hidden = old?.hidden ?: false,
                syncGen = gen,
            ),
        )
    }

    fun isCategoryRegistered(providerKey: String) = providerKey in registeredCategories

    /**
     * Adds a channel in file/response order. [categoryKeys] must already be registered. [entity] must have id 0;
     * its sortIndex and syncGen are assigned here. Duplicate provider keys are ignored.
     */
    fun add(entity: ChannelEntity, categoryKeys: List<String>) {
        if (!seenChannelKeys.add(entity.providerKey)) return
        val old = existingChannels[entity.providerKey]
        batch.add(
            Pending(
                entity.copy(
                    id = old?.id ?: 0,
                    sourceId = sourceId,
                    sortIndex = nextChannelSort++,
                    hidden = old?.hidden ?: false,
                    epgChannelId = old?.epgChannelId,
                    syncGen = gen,
                ),
                categoryKeys.distinct(),
            ),
        )
        channelCount++
        if (batch.size >= batchSize) flush()
    }

    private fun flush() {
        if (batch.isEmpty() && pendingCategories.isEmpty()) return
        db.runInTransaction {
            if (pendingCategories.isNotEmpty()) {
                val ids = categoryDao.upsertAll(pendingCategories)
                pendingCategories.forEachIndexed { i, c ->
                    categoryIds[c.providerKey] = if (c.id != 0L) c.id else ids[i]
                }
                pendingCategories.clear()
            }
            if (batch.isNotEmpty()) {
                val ids = channelDao.upsertAll(batch.map { it.entity })
                val memberships = ArrayList<ChannelCategoryEntity>(batch.size + 16)
                batch.forEachIndexed { i, p ->
                    val channelId = if (p.entity.id != 0L) p.entity.id else ids[i]
                    for (key in p.categoryKeys) {
                        val catId = categoryIds[key] ?: continue
                        val pos = categoryPositions.merge(key, 1, Int::plus)!! - 1
                        memberships.add(ChannelCategoryEntity(catId, channelId, pos, gen))
                    }
                }
                categoryDao.insertMemberships(memberships)
                batch.clear()
            }
        }
    }

    /** Flushes, deletes everything this generation did not see and recounts categories. One transaction. */
    fun finish() {
        flush()
        db.runInTransaction {
            channelDao.deleteStale(sourceId, gen)
            categoryDao.deleteStale(sourceId, gen)
            categoryDao.deleteStaleMemberships(sourceId, gen)
            categoryDao.recountChannels(sourceId)
        }
    }

    companion object {
        const val BATCH = 1000
    }
}
