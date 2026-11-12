package rocks.urzahil.iptv.ui.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import rocks.urzahil.iptv.data.db.dao.ChannelListItem
import rocks.urzahil.iptv.data.db.entities.CategoryEntity
import rocks.urzahil.iptv.data.db.entities.SourceEntity
import rocks.urzahil.iptv.di.AppContainer

class ChannelsViewModel(private val c: AppContainer) : ViewModel() {
    private val browse = BrowseState(c, viewModelScope)
    val nowNext = NowNextCache(c.epgRepository, viewModelScope)

    val sources: StateFlow<List<SourceEntity>?> = browse.sources
    val currentSource: StateFlow<SourceEntity?> = browse.currentSource
    val categories: StateFlow<List<CategoryEntity>> = browse.categories
    val selectedCategoryId: StateFlow<Long?> = browse.selectedCategoryId
    val channels: Flow<PagingData<ChannelListItem>> = browse.channels.cachedIn(viewModelScope)

    fun selectSource(id: Long) = browse.selectSource(id)

    fun selectCategory(id: Long?) = browse.selectCategory(id)

    fun toggleFavourite(channelId: Long) { viewModelScope.launch { c.channelRepository.toggleFavourite(channelId) } }

    fun retry(sourceId: Long) { c.syncScheduler.syncPlaylist(sourceId, manual = true) }
}
