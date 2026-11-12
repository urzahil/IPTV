package rocks.urzahil.iptv.ui.channels

import androidx.paging.PagingData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import rocks.urzahil.iptv.data.db.dao.ChannelListItem
import rocks.urzahil.iptv.data.db.entities.CategoryEntity
import rocks.urzahil.iptv.data.db.entities.SourceEntity
import rocks.urzahil.iptv.di.AppContainer

/** Selected source/category. A category belongs to a source, so a source switch resets it. */
data class Selection(val sourceId: Long? = null, val categoryId: Long? = null)

/**
 * Source/category filter and the channel list it selects. The selection itself is app-scoped
 * ([AppContainer.browseSelection]) so the Channels and Guide tabs always show the same source and category.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseState(private val c: AppContainer, scope: CoroutineScope) {
    private val repo = c.channelRepository
    private val selection = c.browseSelection

    /** Null while loading. Only enabled sources are browsable. */
    val sources: StateFlow<List<SourceEntity>?> = c.sourceRepository.sources
        .map { list -> list.filter { it.enabled } }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    val currentSource: StateFlow<SourceEntity?> = combine(sources, selection) { list, sel ->
        list?.firstOrNull { it.id == sel.sourceId } ?: list?.firstOrNull()
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    val categories: StateFlow<List<CategoryEntity>> = currentSource
        .map { it?.id }.distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repo.categories(id) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Selected category id, or null for "All". Falls back to null when the category no longer exists. */
    val selectedCategoryId: StateFlow<Long?> = combine(currentSource, selection, categories) { src, sel, cats ->
        sel.categoryId?.takeIf { sel.sourceId == src?.id && cats.any { c -> c.id == it } }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** Paged channels of the selection; callers cache it in their own scope. */
    val channels: Flow<PagingData<ChannelListItem>> = combine(
        currentSource.map { it?.id }.distinctUntilChanged(),
        selectedCategoryId,
        c.settings.settings.map { it.sortByNumber to it.showRadio }.distinctUntilChanged(),
    ) { sourceId, categoryId, (byNumber, radio) -> Triple(sourceId, categoryId, byNumber to radio) }
        .flatMapLatest { (sourceId, categoryId, opts) ->
            if (sourceId == null) flowOf(PagingData.empty()) else repo.paged(sourceId, categoryId, opts.first, opts.second)
        }

    fun selectSource(id: Long) { selection.value = Selection(id, null) }

    fun selectCategory(id: Long?) { selection.value = Selection(currentSource.value?.id, id) }
}
