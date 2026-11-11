package rocks.urzahil.iptv.ui.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rocks.urzahil.iptv.data.db.entities.SourceEntity
import rocks.urzahil.iptv.data.db.entities.SourceType
import rocks.urzahil.iptv.ui.appViewModel
import rocks.urzahil.iptv.ui.components.EmptyState
import rocks.urzahil.iptv.ui.components.LoadingView
import rocks.urzahil.iptv.ui.components.TabScaffold
import rocks.urzahil.iptv.ui.components.formatAgo
import rocks.urzahil.iptv.ui.theme.Dimens
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    onAddXtream: () -> Unit,
    onAddM3u: () -> Unit,
    onEdit: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = appViewModel { SourcesViewModel(it) }
    val sources by vm.sources.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<SourceEntity?>(null) }

    TabScaffold(
        title = { Text("Sources") },
        onOpenSettings = onOpenSettings,
        modifier = modifier,
        floatingActionButton = {
            if (!sources.isNullOrEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { showAdd = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add source") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            val list = sources
            when {
                list == null -> LoadingView()
                list.isEmpty() -> EmptyState(
                    title = "No sources",
                    message = "Add your IPTV provider as an Xtream account or an M3U playlist.",
                    actionLabel = "Add source",
                    onAction = { showAdd = true },
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(Dimens.ScreenPadding, 8.dp, Dimens.ScreenPadding, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(list, key = { it.id }) { s ->
                        val progressFlow = remember(s.id) { vm.progress(s.id) }
                        val progress by progressFlow.collectAsStateWithLifecycle(initialValue = null)
                        SourceCard(
                            source = s,
                            progressText = progress?.let {
                                if (!it.running) "Waiting for network"
                                else when (it.phase) {
                                    "epg" -> "Updating TV guide: ${it.count}"
                                    "playlist" -> "Importing channels: ${it.count}"
                                    else -> "Working"
                                }
                            },
                            busy = progress != null,
                            onRefresh = { vm.refresh(s) },
                            onEdit = { onEdit(s.id) },
                            onToggleEnabled = { vm.setEnabled(s, !s.enabled) },
                            onDelete = { toDelete = s },
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        ModalBottomSheet(onDismissRequest = { showAdd = false }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text("Add a source", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = 8.dp))
                ListItem(
                    modifier = Modifier.clickable { showAdd = false; onAddXtream() },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("Xtream account") },
                    supportingContent = { Text("Server, username and password. Best catchup and TV guide support.") },
                )
                ListItem(
                    modifier = Modifier.clickable { showAdd = false; onAddM3u() },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("M3U playlist") },
                    supportingContent = { Text("From a web address or a file on this device.") },
                )
            }
        }
    }

    toDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete \"${s.name}\"?") },
            text = { Text("Its channels, guide data and favourites from this source will be removed.") },
            confirmButton = { TextButton(onClick = { vm.delete(s); toDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SourceCard(
    source: SourceEntity,
    progressText: String?,
    busy: Boolean,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    onToggleEnabled: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(source.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        typeLabel(source) + if (!source.enabled) " · disabled" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRefresh, enabled = !busy && source.enabled) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text(if (source.enabled) "Disable" else "Enable") }, onClick = { menu = false; onToggleEnabled() })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                    Text(progressText.orEmpty(), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(statusLine(source), style = MaterialTheme.typography.bodyMedium)
                }
                source.lastError?.takeIf { source.status != SourceEntity.STATUS_OK }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                source.epgError?.let {
                    Text("TV guide: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                source.expiresAtMs?.let {
                    Text(
                        "Expires ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))}" +
                            (source.maxConnections?.let { m -> " · $m connection(s)" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (source.insecureHttp) {
                    AssistChip(onClick = {}, label = { Text("Insecure (HTTP)") })
                }
            }
        }
    }
}

private fun typeLabel(s: SourceEntity) = when (s.sourceType) {
    SourceType.XTREAM -> "Xtream account"
    SourceType.M3U_URL -> "M3U playlist (web)"
    SourceType.M3U_FILE -> "M3U playlist (file)"
}

private fun statusLine(s: SourceEntity): String = when (s.status) {
    SourceEntity.STATUS_NEW -> "Waiting for first import"
    SourceEntity.STATUS_OK -> "${s.lastSummary ?: "${s.channelCount} channels"} · updated ${formatAgo(s.lastPlaylistSyncMs)}"
    SourceEntity.STATUS_NEEDS_CREDENTIALS -> "Credentials need to be re-entered"
    else -> "Last update failed" + if (s.lastPlaylistSyncMs > 0) " (last success ${formatAgo(s.lastPlaylistSyncMs)})" else ""
}
