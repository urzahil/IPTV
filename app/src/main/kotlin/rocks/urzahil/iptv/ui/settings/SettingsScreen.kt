package rocks.urzahil.iptv.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import rocks.urzahil.iptv.data.settings.AppSettings
import rocks.urzahil.iptv.di.AppContainer
import rocks.urzahil.iptv.ui.appViewModel
import rocks.urzahil.iptv.ui.components.DetailScaffold
import rocks.urzahil.iptv.ui.components.SectionHeader
import rocks.urzahil.iptv.ui.theme.Dimens
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    private val repo = c.settings
    val settings: StateFlow<AppSettings> =
        repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /** Applies the retention to existing data now; if more data is wanted than before, fetches the guide again. */
    private fun retentionChanged(wantsMore: Boolean) {
        c.syncScheduler.requestPrune()
        if (wantsMore) refreshGuides()
    }

    private fun refreshGuides() {
        viewModelScope.launch {
            for (s in c.sourceRepository.getAll()) {
                if (!s.enabled) continue
                for (es in c.db.epgDao().sourcesOfBlocking(s.id)) c.syncScheduler.syncEpg(es.id, s.id, force = true)
            }
        }
    }

    fun setFutureDays(v: Int) = viewModelScope.launch {
        val old = repo.current().epgFutureDays
        repo.setEpgFutureDays(v)
        retentionChanged(v > old)
    }

    fun setPastDays(v: Int) = viewModelScope.launch {
        val old = repo.current().epgPastDays
        repo.setEpgPastDays(v)
        retentionChanged(v > old)
    }

    fun setPlaylistHours(v: Int) = viewModelScope.launch { repo.setPlaylistRefreshHours(v); c.syncScheduler.checkNow() }
    fun setEpgHours(v: Int) = viewModelScope.launch { repo.setEpgRefreshHours(v); c.syncScheduler.checkNow() }
    fun setUserAgent(v: String) = viewModelScope.launch { repo.setDefaultUserAgent(v) }
    fun setSortByNumber(v: Boolean) = viewModelScope.launch { repo.setSortByNumber(v) }
    fun setShowRadio(v: Boolean) = viewModelScope.launch { repo.setShowRadio(v) }

    fun refreshAll() {
        viewModelScope.launch {
            for (s in c.sourceRepository.getAll()) if (s.enabled) c.syncScheduler.syncPlaylist(s.id, manual = true, needsNetwork = s.type != "M3U_FILE")
        }
    }

    fun cleanUpNow() = c.syncScheduler.requestPrune()
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val vm = appViewModel { SettingsViewModel(it) }
    val s by vm.settings.collectAsStateWithLifecycle()
    var uaDialog by remember { mutableStateOf(false) }
    var hoursDialog by remember { mutableStateOf<HoursTarget?>(null) }

    DetailScaffold("Settings", onBack = onBack) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            SectionHeader("TV guide retention")
            DaysSlider(
                title = "Days of guide to keep ahead",
                value = s.epgFutureDays,
                range = AppSettings.MIN_FUTURE_DAYS..AppSettings.MAX_FUTURE_DAYS,
                supporting = null,
                onCommit = { vm.setFutureDays(it) },
            )
            DaysSlider(
                title = "Days of guide to keep in the past",
                value = s.epgPastDays,
                range = AppSettings.MIN_PAST_DAYS..AppSettings.MAX_PAST_DAYS,
                supporting = "Only channels with catchup keep past programmes. Other channels keep just the current programme.",
                onCommit = { vm.setPastDays(it) },
            )

            HorizontalDivider()
            SectionHeader("Refresh")
            ListItem(
                modifier = Modifier.clickable { hoursDialog = HoursTarget.PLAYLIST },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("Playlist refresh") },
                supportingContent = { Text(hoursLabel(s.playlistRefreshHours)) },
            )
            ListItem(
                modifier = Modifier.clickable { hoursDialog = HoursTarget.EPG },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("TV guide refresh") },
                supportingContent = { Text(hoursLabel(s.epgRefreshHours)) },
            )
            ListItem(
                modifier = Modifier.clickable { vm.refreshAll() },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("Refresh all sources now") },
            )
            ListItem(
                modifier = Modifier.clickable { vm.cleanUpNow() },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("Clean up guide data now") },
                supportingContent = { Text("Applies the retention settings above to stored programmes.") },
            )

            HorizontalDivider()
            SectionHeader("Channels")
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("Sort by channel number") },
                supportingContent = { Text("Off keeps the provider's order.") },
                trailingContent = { Switch(checked = s.sortByNumber, onCheckedChange = { vm.setSortByNumber(it) }) },
            )
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("Show radio channels") },
                trailingContent = { Switch(checked = s.showRadio, onCheckedChange = { vm.setShowRadio(it) }) },
            )

            HorizontalDivider()
            SectionHeader("Network")
            ListItem(
                modifier = Modifier.clickable { uaDialog = true },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("Default user agent") },
                supportingContent = { Text(s.defaultUserAgent) },
            )
        }
    }

    if (uaDialog) {
        var text by remember { mutableStateOf(s.defaultUserAgent) }
        AlertDialog(
            onDismissRequest = { uaDialog = false },
            title = { Text("Default user agent") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { vm.setUserAgent(text); uaDialog = false }) { Text("Save") } },
            dismissButton = {
                TextButton(onClick = { text = AppSettings.DEFAULT_USER_AGENT }) { Text("Reset") }
            },
        )
    }
    hoursDialog?.let { target ->
        val current = if (target == HoursTarget.PLAYLIST) s.playlistRefreshHours else s.epgRefreshHours
        AlertDialog(
            onDismissRequest = { hoursDialog = null },
            title = { Text(if (target == HoursTarget.PLAYLIST) "Playlist refresh" else "TV guide refresh") },
            text = {
                Column {
                    AppSettings.REFRESH_CHOICES_HOURS.forEach { h ->
                        Row(
                            Modifier.fillMaxWidth().heightMin().selectable(selected = h == current, role = Role.RadioButton) {
                                if (target == HoursTarget.PLAYLIST) vm.setPlaylistHours(h) else vm.setEpgHours(h)
                                hoursDialog = null
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = h == current, onClick = null)
                            Text(hoursLabel(h), Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { hoursDialog = null }) { Text("Close") } },
        )
    }
}

private fun Modifier.heightMin() = this.defaultMinSize(minHeight = Dimens.MinTouchTarget)

private enum class HoursTarget { PLAYLIST, EPG }

private fun hoursLabel(h: Int) = when {
    h <= 0 -> "Manual only"
    h < 24 -> "Every $h hours"
    h == 24 -> "Daily"
    else -> "Every ${h / 24} days"
}

@Composable
private fun DaysSlider(title: String, value: Int, range: IntRange, supporting: String?, onCommit: (Int) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = 4.dp)) {
        Text("$title: ${local.toInt()}", style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(local.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1,
        )
        if (supporting != null) {
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
