package rocks.urzahil.iptv.ui.favourites

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import rocks.urzahil.iptv.data.db.dao.ChannelListItem
import rocks.urzahil.iptv.di.AppContainer
import rocks.urzahil.iptv.ui.appViewModel
import rocks.urzahil.iptv.ui.channels.NowNextCache
import rocks.urzahil.iptv.ui.components.ChannelRow
import rocks.urzahil.iptv.ui.components.EmptyState
import rocks.urzahil.iptv.ui.components.SectionHeader
import rocks.urzahil.iptv.ui.components.TabScaffold
import rocks.urzahil.iptv.ui.components.rememberNowMs
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FavouritesViewModel(c: AppContainer) : ViewModel() {
    private val repo = c.channelRepository
    private val showRadio = c.settings.settings.map { it.showRadio }.distinctUntilChanged()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val favourites: StateFlow<List<ChannelListItem>> =
        showRadio.flatMapLatest { repo.favourites(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val recents: StateFlow<List<ChannelListItem>> =
        showRadio.flatMapLatest { repo.recents(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val nowNext = NowNextCache(c.epgRepository, viewModelScope)

    fun toggleFavourite(id: Long) { viewModelScope.launch { repo.toggleFavourite(id) } }
}

@Composable
fun FavouritesScreen(
    onPlayChannel: (channelId: Long, categoryId: Long?) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = appViewModel { FavouritesViewModel(it) }
    val favourites by vm.favourites.collectAsStateWithLifecycle()
    val recents by vm.recents.collectAsStateWithLifecycle()
    val nowNext by vm.nowNext.state.collectAsState()
    val nowMs = rememberNowMs()
    LaunchedEffect(favourites, recents) { vm.nowNext.onVisible(favourites + recents) }

    TabScaffold(title = { Text("Favourites") }, onOpenSettings = onOpenSettings, modifier = modifier) { padding ->
        if (favourites.isEmpty() && recents.isEmpty()) {
            EmptyState(
                title = "Nothing here yet",
                message = "Tap the star on a channel, or long-press it, to add it to your favourites. Channels you watch show up under Recent.",
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(Modifier.padding(padding)) {
                if (favourites.isNotEmpty()) {
                    // The top bar already says "Favourites": only label the list when a second section follows.
                    if (recents.isNotEmpty()) item(key = "h-fav") { SectionHeader("Favourites") }
                    items(favourites, key = { "f${it.id}" }) { ch ->
                        ChannelRow(ch, nowNext[ch.id], nowMs, { onPlayChannel(ch.id, null) }, { vm.toggleFavourite(ch.id) })
                    }
                }
                if (recents.isNotEmpty()) {
                    item(key = "h-recent") { SectionHeader("Recent") }
                    items(recents, key = { "r${it.id}" }) { ch ->
                        ChannelRow(ch, nowNext[ch.id], nowMs, { onPlayChannel(ch.id, null) }, { vm.toggleFavourite(ch.id) })
                    }
                }
            }
        }
    }
}
