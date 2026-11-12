package rocks.urzahil.iptv.player.ui

import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import rocks.urzahil.iptv.data.db.entities.ProgrammeDetailsEntity
import rocks.urzahil.iptv.player.model.EngineKind
import rocks.urzahil.iptv.player.model.EnginePreference
import rocks.urzahil.iptv.player.model.ProgrammeList
import rocks.urzahil.iptv.player.model.ProgrammeRow
import rocks.urzahil.iptv.player.model.ProgrammeSlot
import rocks.urzahil.iptv.player.model.RowState
import rocks.urzahil.iptv.player.model.TimeText
import rocks.urzahil.iptv.player.model.TrackSet
import java.time.ZoneId

/** Keeps the system bars hidden while a sheet (which lives in its own window) is open. */
@Composable
private fun HideBarsInDialog() {
    val view = LocalView.current
    LaunchedEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
        window.setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        WindowCompat.getInsetsController(window, view).hide(WindowInsetsCompat.Type.systemBars())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        HideBarsInDialog()
        content()
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ChoiceRow(label: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        headlineContent = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = detail?.let { { Text(it) } },
    )
}

/** Audio, subtitle and video quality choices. */
@Composable
fun TrackSheet(
    tracks: TrackSet,
    onAudio: (String?) -> Unit,
    onText: (String?) -> Unit,
    onVideo: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    PlayerSheet(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            if (tracks.audio.size > 1) {
                SheetTitle("Audio")
                tracks.audio.forEach { t -> ChoiceRow(t.label, t.detail, t.selected) { onAudio(t.id); onDismiss() } }
            }
            if (tracks.text.isNotEmpty()) {
                SheetTitle("Subtitles")
                ChoiceRow("Off", null, tracks.textOff) { onText(null); onDismiss() }
                tracks.text.forEach { t -> ChoiceRow(t.label, t.detail, !tracks.textOff && t.selected) { onText(t.id); onDismiss() } }
            }
            if (tracks.video.size > 1) {
                SheetTitle("Quality")
                ChoiceRow("Automatic", null, tracks.videoAuto) { onVideo(null); onDismiss() }
                tracks.video.forEach { t -> ChoiceRow(t.label, t.detail, !tracks.videoAuto && t.selected) { onVideo(t.id); onDismiss() } }
            }
            if (tracks.audio.size <= 1 && tracks.text.isEmpty() && tracks.video.size <= 1) {
                Text("No alternative tracks in this stream.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Per-channel engine choice, remembered. */
@Composable
fun EngineSheet(
    current: EngineKind?,
    channelPref: EnginePreference,
    onPick: (EnginePreference) -> Unit,
    onDismiss: () -> Unit,
) {
    PlayerSheet(onDismiss) {
        Column(Modifier.padding(bottom = 16.dp)) {
            SheetTitle("Player for this channel")
            val playingWith = when (current) { EngineKind.MEDIA3 -> "Playing with Media3"; EngineKind.VLC -> "Playing with VLC"; null -> "" }
            ChoiceRow("Automatic", "Media3, switching to VLC when a stream needs it. $playingWith.", channelPref == EnginePreference.AUTO) { onPick(EnginePreference.AUTO); onDismiss() }
            ChoiceRow("Media3", "Hardware video decoding, best for HLS and DASH.", channelPref == EnginePreference.MEDIA3) { onPick(EnginePreference.MEDIA3); onDismiss() }
            ChoiceRow("VLC", "Software decoding for MPEG-2, odd containers and multicast.", channelPref == EnginePreference.VLC) { onPick(EnginePreference.VLC); onDismiss() }
        }
    }
}

/**
 * The channel's programmes: past ones inside the catchup window are playable, the airing one can be watched
 * live or from its start, upcoming ones show their description.
 */
@Composable
fun ProgrammeSheet(
    slots: List<ProgrammeSlot>,
    nowMs: Long,
    catchupDays: Int,
    wallPosMs: Long?,
    zone: ZoneId,
    loadDetails: suspend (ProgrammeSlot) -> ProgrammeDetailsEntity?,
    onPlay: (ProgrammeSlot, fromStart: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val rows = remember(slots, nowMs / 60_000, catchupDays, wallPosMs?.div(60_000)) { ProgrammeList.build(slots, nowMs, catchupDays, wallPosMs) }
    val listState = rememberLazyListState()
    LaunchedEffect(rows.isNotEmpty()) { if (rows.isNotEmpty()) listState.scrollToItem((ProgrammeList.nowIndex(rows) - 1).coerceAtLeast(0)) }
    var expanded by remember { mutableStateOf<Long?>(null) }
    PlayerSheet(onDismiss) {
        SheetTitle("Programmes")
        if (rows.isEmpty()) {
            Text("No guide data for this channel.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(state = listState, modifier = Modifier.padding(bottom = 16.dp)) {
                itemsIndexed(rows, key = { i, r -> r.slot.id ?: -(i + 1L) }) { i, row ->
                    val key = row.slot.id ?: -(i + 1L)
                    ProgrammeItem(
                        row, zone, catchupDays > 0, expanded == key, { expanded = if (expanded == key) null else key },
                        loadDetails, onPlay = { fromStart -> onPlay(row.slot, fromStart); onDismiss() },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun ProgrammeItem(
    row: ProgrammeRow,
    zone: ZoneId,
    catchup: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    loadDetails: suspend (ProgrammeSlot) -> ProgrammeDetailsEntity?,
    onPlay: (fromStart: Boolean) -> Unit,
) {
    val s = row.slot
    val dim = row.state == RowState.PAST_UNAVAILABLE
    Column(Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = if (row.playing) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
            leadingContent = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(TimeText.clock(s.startMs, zone), style = MaterialTheme.typography.titleSmall)
                    Text(TimeText.clock(s.endMs, zone), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            headlineContent = {
                Text(
                    s.title, maxLines = if (expanded) 4 else 1, overflow = TextOverflow.Ellipsis,
                    color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            },
            supportingContent = {
                Text(
                    when (row.state) {
                        RowState.NOW -> "On now · ${TimeText.duration(s.durationMs)}"
                        RowState.PAST_PLAYABLE -> "Catchup · ${TimeText.duration(s.durationMs)}"
                        RowState.PAST_UNAVAILABLE -> "Not available"
                        RowState.UPCOMING -> TimeText.duration(s.durationMs)
                    },
                )
            },
            trailingContent = {
                when {
                    row.playing -> AssistChip(onClick = {}, label = { Text("Playing") })
                    row.state == RowState.PAST_PLAYABLE -> Icon(Icons.Filled.PlayArrow, contentDescription = "Play from the start", tint = MaterialTheme.colorScheme.primary)
                }
            },
        )
        if (expanded) {
            val details by produceState<ProgrammeDetailsEntity?>(null, s.id) { value = loadDetails(s) }
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                details?.let { d ->
                    listOfNotNull(d.subTitle, d.episode, d.categories).filter { it.isNotBlank() }.joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    d.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (row.state) {
                        RowState.NOW -> {
                            Button(onClick = { onPlay(false) }) { Text("Watch live") }
                            if (catchup) OutlinedButton(onClick = { onPlay(true) }) { Text("Start over") }
                        }
                        RowState.PAST_PLAYABLE -> Button(onClick = { onPlay(true) }) { Text("Play from the start") }
                        else -> Unit
                    }
                }
            }
        }
    }
}
