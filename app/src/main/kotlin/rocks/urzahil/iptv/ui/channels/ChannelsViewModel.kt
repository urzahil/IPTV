package rocks.urzahil.iptv.ui.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import rocks.urzahil.iptv.data.db.dao.ChannelListItem
import rocks.urzahil.iptv.data.db.entities.CategoryEntity
import rocks.urzahil.iptv.data.db.entities.SourceEntity
import rocks.urzahil.iptv.di.AppContainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Selected source/category. A category belongs to a source, so a source switch resets it. */
data class Selection(val sourceId: Long? = null, val categoryId: Long? = null)

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelsViewModel(private val c: AppContainer) : ViewModel() {
    private val repo = c.channelRepository
    val nowNext = NowNextCache(c.epgRepository, viewModelScope)

    /** Null while loading. Only enabled sources are browsable. */
    val sources: StateFlow<List<SourceEntity>?> = c.sourceRepository.sources
        .map { list -> list.filter { it.enabled } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val selection = MutableStateFlow(Selection())

    val currentSource: StateFlow<SourceEntity?> = combine(sources, selection) { list, sel ->
        list?.firstOrNull { it.id == sel.sourceId } ?: list?.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val categories: StateFlow<List<CategoryEntity>> = currentSource
        .map { it?.id }.distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repo.categories(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Selected category id, or null for "All". Falls back to null when the category no longer exists. */
    val selectedCategoryId: StateFlow<Long?> = combine(currentSource, selection, categories) { src, sel, cats ->
        sel.categoryId?.takeIf { sel.sourceId == src?.id && cats.any { c -> c.id == it } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val channels: Flow<PagingData<ChannelListItem>> = combine(
        currentSource.map { it?.id }.distinctUntilChanged(),
        selectedCategoryId,
        c.settings.settings.map { it.sortByNumber to it.showRadio }.distinctUntilChanged(),
    ) { sourceId, categoryId, (byNumber, radio) -> Triple(sourceId, categoryId, byNumber to radio) }
        .flatMapLatest { (sourceId, categoryId, opts) ->
            if (sourceId == null) flowOf(PagingData.empty()) else repo.paged(sourceId, categoryId, opts.first, opts.second)
        }
        .cachedIn(viewModelScope)

    fun selectSource(id: Long) { selection.value = Selection(id, null) }

    fun selectCategory(id: Long?) { selection.value = Selection(currentSource.value?.id, id) }

    fun toggleFavourite(channelId: Long) { viewModelScope.launch { repo.toggleFavourite(channelId) } }

    fun retry(sourceId: Long) { c.syncScheduler.syncPlaylist(sourceId, manual = true) }
}
