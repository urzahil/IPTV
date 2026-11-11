package rocks.urzahil.iptv.ui.channels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import rocks.urzahil.iptv.data.db.entities.SourceEntity
import rocks.urzahil.iptv.ui.appViewModel
import rocks.urzahil.iptv.ui.components.CategoryChips
import rocks.urzahil.iptv.ui.components.ChannelRow
import rocks.urzahil.iptv.ui.components.SourceEmptyState
import rocks.urzahil.iptv.ui.components.SourceTitle
import rocks.urzahil.iptv.ui.components.EmptyState
import rocks.urzahil.iptv.ui.components.LoadingView
import rocks.urzahil.iptv.ui.components.TabScaffold
import rocks.urzahil.iptv.ui.components.rememberNowMs
import rocks.urzahil.iptv.ui.theme.Dimens
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce

@OptIn(FlowPreview::class)
@Composable
fun ChannelsScreen(
    onPlayChannel: (channelId: Long, categoryId: Long?) -> Unit,
    onOpenSettings: () -> Unit,
    onAddSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = appViewModel { ChannelsViewModel(it) }
    val sources by vm.sources.collectAsStateWithLifecycle()
    val source by vm.currentSource.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val selectedCategory by vm.selectedCategoryId.collectAsStateWithLifecycle()
    val items = vm.channels.collectAsLazyPagingItems()
    val nowNext by vm.nowNext.state.collectAsState()
    val nowMs = rememberNowMs()
    val listState = rememberLazyListState()

    // Report the visible rows to the now/next cache (debounced).
    LaunchedEffect(listState, items) {
        androidx.compose.runtime.snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .debounce(80)
            .collect { idx -> vm.nowNext.onVisible(idx.mapNotNull { if (it < items.itemCount) items.peek(it) else null }) }
    }
    // First page arrives after the first emission: refresh visible items when the list size changes.
    LaunchedEffect(items.itemCount) {
        val idx = listState.layoutInfo.visibleItemsInfo.map { it.index }
        vm.nowNext.onVisible(idx.mapNotNull { if (it < items.itemCount) items.peek(it) else null })
    }

    TabScaffold(
        modifier = modifier,
        onOpenSettings = onOpenSettings,
        title = { SourceTitle(sources.orEmpty(), source, vm::selectSource) },
    ) { padding ->
        val list = sources
        when {
            list == null -> LoadingView(Modifier.padding(padding))
            list.isEmpty() || source == null -> EmptyState(
                title = "No sources yet",
                message = "Add an Xtream account or an M3U playlist to see channels.",
                actionLabel = "Add source",
                onAction = onAddSource,
                modifier = Modifier.padding(padding),
            )
            else -> Column(Modifier.padding(padding).fillMaxSize()) {
                CategoryChips(
                    categories = categories,
                    selected = selectedCategory,
                    onSelect = vm::selectCategory,
                )
                val src = source!!
                if (items.itemCount == 0) {
                    SourceEmptyState(src, onRetry = { vm.retry(src.id) })
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
                        items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
                            val item = items[index]
                            if (item != null) {
                                ChannelRow(
                                    item = item,
                                    nowNext = nowNext[item.id],
                                    nowMs = nowMs,
                                    onClick = { onPlayChannel(item.id, selectedCategory) },
                                    onToggleFavourite = { vm.toggleFavourite(item.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
