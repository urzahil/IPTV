package rocks.urzahil.iptv.ui.search

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import rocks.urzahil.iptv.data.db.dao.ChannelListItem
import rocks.urzahil.iptv.di.AppContainer
import rocks.urzahil.iptv.ui.appViewModel
import rocks.urzahil.iptv.ui.channels.NowNextCache
import rocks.urzahil.iptv.ui.components.ChannelRow
import rocks.urzahil.iptv.ui.components.EmptyState
import rocks.urzahil.iptv.ui.components.TabScaffold
import rocks.urzahil.iptv.ui.components.rememberNowMs
import rocks.urzahil.iptv.ui.theme.Dimens
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(c: AppContainer) : ViewModel() {
    private val repo = c.channelRepository
    val query = MutableStateFlow("")
    val nowNext = NowNextCache(c.epgRepository, viewModelScope)

    /** Null until a search ran. */
    val results: StateFlow<List<ChannelListItem>?> = combine(query.debounce(250), c.settings.settings) { q, s -> q to s.showRadio }
        .mapLatest { (q, radio) -> if (q.isBlank()) null else repo.search(q, radio) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun toggleFavourite(id: Long) { viewModelScope.launch { repo.toggleFavourite(id) } }
}

@Composable
fun SearchScreen(
    onPlayChannel: (channelId: Long, categoryId: Long?) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = appViewModel { SearchViewModel(it) }
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val nowNext by vm.nowNext.state.collectAsState()
    val nowMs = rememberNowMs()
    LaunchedEffect(results) { vm.nowNext.onVisible(results.orEmpty()) }

    TabScaffold(title = { Text("Search") }, onOpenSettings = onOpenSettings, modifier = modifier) { padding ->
        Column(Modifier.padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { vm.query.value = it },
                modifier = Modifier.fillMaxWidth().padding(Dimens.ScreenPadding),
                singleLine = true,
                placeholder = { Text("Search channels") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Clear") }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            val list = results
            when {
                list == null -> EmptyState("Search", "Type a channel name.")
                list.isEmpty() -> EmptyState("No matches", "Nothing found for \"$query\".")
                else -> LazyColumn {
                    items(list, key = { it.id }) { ch ->
                        ChannelRow(ch, nowNext[ch.id], nowMs, { onPlayChannel(ch.id, null) }, { vm.toggleFavourite(ch.id) })
                    }
                }
            }
        }
    }
}
