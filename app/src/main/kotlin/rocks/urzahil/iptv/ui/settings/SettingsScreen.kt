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
import kotlin.math.roundToInt
import rocks.urzahil.iptv.player.model.BufferProfile
import rocks.urzahil.iptv.player.model.DeinterlaceMode
import rocks.urzahil.iptv.player.model.EnginePreference
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
    fun setEngine(v: EnginePreference) = viewModelScope.launch { repo.setEngine(v) }
    fun setBuffer(v: BufferProfile) = viewModelScope.launch { repo.setBufferProfile(v) }
    fun setRetries(v: Int) = viewModelScope.launch { repo.setRetryAttempts(v) }
    fun setPassthrough(v: Boolean) = viewModelScope.launch { repo.setAudioPassthrough(v) }
    fun setDeinterlace(v: DeinterlaceMode) = viewModelScope.launch { repo.setDeinterlace(v) }
    fun setBackgroundAudio(v: Boolean) = viewModelScope.launch { repo.setBackgroundAudio(v) }
    fun setAudioLang(v: String) = viewModelScope.launch { repo.setPreferredAudioLanguage(v) }
    fun setTextLang(v: String) = viewModelScope.launch { repo.setPreferredTextLanguage(v) }
    fun setInvertSwipe(v: Boolean) = viewModelScope.launch { repo.setInvertSwipeZap(v) }

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
    var choice by remember { mutableStateOf<PlayerChoice?>(null) }
    var langDialog by remember { mutableStateOf<LangTarget?>(null) }

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
            SectionHeader("Playback")
            ListItem(
                modifier = Modifier.clickable { choice = PlayerChoice.ENGINE },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("Default player") },
                supportingContent = { Text(engineLabel(s.engine) + ". A channel can override this from the player menu.") },
            )
            ListItem(
                modifier = Modifier.clickable { choice = PlayerChoice.BUFFER },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("Buffer") },
                supportingContent = { Text(bufferLabel(s.bufferProfile)) },
            )
            IntSlider(
                title = "Reconnect attempts",
                value = s.retryAttempts,
                range = 0..AppSettings.MAX_RETRIES,
                supporting = "Tries again with a growing delay after a network error. 0 turns it off.",
                onCommit = { vm.setRetries(it) },
            )
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("Play audio in the background") },
                supportingContent = { Text("Keeps sound going when you leave the app (not with the VLC player).") },
                trailingContent = { Switch(checked = s.backgroundAudio, onCheckedChange = { vm.setBackgroundAudio(it) }) },
            )
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("Pass audio through (AC3, DTS)") },
                supportingContent = { Text("For receivers over HDMI or USB. Off decodes everything to stereo/PCM.") },
                trailingContent = { Switch(checked = s.audioPassthrough, onCheckedChange = { vm.setPassthrough(it) }) },
            )
            ListItem(
                modifier = Modifier.clickable { choice = PlayerChoice.DEINTERLACE },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("Deinterlace (VLC player)") },
                supportingContent = { Text(deinterlaceLabel(s.deinterlace)) },
            )
            ListItem(
                modifier = Modifier.clickable { langDialog = LangTarget.AUDIO },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("Preferred audio language") },
                supportingContent = { Text(s.preferredAudioLanguage.ifBlank { "Device language" }) },
            )
            ListItem(
                modifier = Modifier.clickable { langDialog = LangTarget.TEXT },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("Preferred subtitle language") },
                supportingContent = { Text(s.preferredTextLanguage.ifBlank { "Off" }) },
            )
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("Invert channel swipe") },
                supportingContent = { Text("Off: swipe up for the next channel.") },
                trailingContent = { Switch(checked = s.invertSwipeZap, onCheckedChange = { vm.setInvertSwipe(it) }) },
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

    choice?.let { which ->
        when (which) {
            PlayerChoice.ENGINE -> ChoiceDialog(
                "Default player", EnginePreference.entries, s.engine, ::engineLabel,
                { vm.setEngine(it); choice = null }, { choice = null },
            )
            PlayerChoice.BUFFER -> ChoiceDialog(
                "Buffer", BufferProfile.entries, s.bufferProfile, ::bufferLabel,
                { vm.setBuffer(it); choice = null }, { choice = null },
            )
            PlayerChoice.DEINTERLACE -> ChoiceDialog(
                "Deinterlace", DeinterlaceMode.entries, s.deinterlace, ::deinterlaceLabel,
                { vm.setDeinterlace(it); choice = null }, { choice = null },
            )
        }
    }
    langDialog?.let { target ->
        var text by remember(target) { mutableStateOf(if (target == LangTarget.AUDIO) s.preferredAudioLanguage else s.preferredTextLanguage) }
        AlertDialog(
            onDismissRequest = { langDialog = null },
            title = { Text(if (target == LangTarget.AUDIO) "Preferred audio language" else "Preferred subtitle language") },
            text = {
                OutlinedTextField(
                    text, { text = it.take(8) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Language code, e.g. en, de, fr") },
                    supportingText = { Text("Leave empty for " + if (target == LangTarget.AUDIO) "the device language." else "no subtitles.") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (target == LangTarget.AUDIO) vm.setAudioLang(text) else vm.setTextLang(text)
                    langDialog = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { langDialog = null }) { Text("Cancel") } },
        )
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

private enum class PlayerChoice { ENGINE, BUFFER, DEINTERLACE }

private enum class LangTarget { AUDIO, TEXT }

private fun engineLabel(e: EnginePreference) = when (e) {
    EnginePreference.AUTO -> "Automatic (Media3, VLC when needed)"
    EnginePreference.MEDIA3 -> "Media3 only"
    EnginePreference.VLC -> "VLC only"
}

private fun bufferLabel(b: BufferProfile) = when (b) {
    BufferProfile.LOW_LATENCY -> "Small (fastest start)"
    BufferProfile.BALANCED -> "Balanced"
    BufferProfile.LARGE -> "Large (best for weak connections)"
}

private fun deinterlaceLabel(d: DeinterlaceMode) = when (d) {
    DeinterlaceMode.AUTO -> "Automatic"
    DeinterlaceMode.ON -> "Always on"
    DeinterlaceMode.OFF -> "Off"
}

@Composable
private fun <T> ChoiceDialog(title: String, options: List<T>, current: T, label: (T) -> String, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { o ->
                    Row(
                        Modifier.fillMaxWidth().heightMin().selectable(selected = o == current, role = Role.RadioButton) { onPick(o) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = o == current, onClick = null)
                        Text(label(o), Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun DaysSlider(title: String, value: Int, range: IntRange, supporting: String?, onCommit: (Int) -> Unit) =
    IntSlider(title, value, range, supporting, onCommit)

/**
 * A whole-number slider that looks continuous: no tick marks (steps = 0); the value is rounded as you drag,
 * so the thumb snaps to whole numbers.
 */
@Composable
private fun IntSlider(title: String, value: Int, range: IntRange, supporting: String?, onCommit: (Int) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = 4.dp)) {
        Text("$title: ${local.roundToInt()}", style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = local,
            onValueChange = { local = it.roundToInt().toFloat() },
            onValueChangeFinished = { onCommit(local.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = 0,
        )
        if (supporting != null) {
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
