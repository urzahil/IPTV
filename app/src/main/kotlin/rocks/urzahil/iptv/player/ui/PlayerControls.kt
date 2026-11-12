package rocks.urzahil.iptv.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import rocks.urzahil.iptv.player.ErrorKind
import rocks.urzahil.iptv.player.Phase
import rocks.urzahil.iptv.player.PlayerUiState
import rocks.urzahil.iptv.player.model.EngineKind
import rocks.urzahil.iptv.player.model.PlayMode
import rocks.urzahil.iptv.player.model.TimeText
import rocks.urzahil.iptv.player.model.TimelineState
import java.time.ZoneId

/** Everything the controls can ask for. Default no-ops make previews and screenshots trivial. */
class PlayerActions(
    val onBack: () -> Unit = {},
    val onPlayPause: () -> Unit = {},
    val onPrevChannel: () -> Unit = {},
    val onNextChannel: () -> Unit = {},
    val onSkip: (stepMs: Long, direction: Int) -> Unit = { _, _ -> },
    val onRestart: () -> Unit = {},
    val onPrevProgramme: () -> Unit = {},
    val onNextProgramme: () -> Unit = {},
    val onGoLive: () -> Unit = {},
    val onSeek: (Long) -> Unit = {},
    val onTracks: () -> Unit = {},
    val onEngine: () -> Unit = {},
    val onProgrammes: () -> Unit = {},
    val onAspect: () -> Unit = {},
    val onPip: (() -> Unit)? = null,
    val onRetry: () -> Unit = {},
)

/** Transparent overlay with the controls, the state overlays (buffering, reconnecting, error) and notices. */
@Composable
fun PlayerOverlay(
    state: PlayerUiState,
    controlsVisible: Boolean,
    actions: PlayerActions,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    // The overlay sits on video, not on a Surface: set the content colour explicitly.
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    Box(modifier.fillMaxSize()) {
        if (controlsVisible) PlayerControls(state, actions, zone)
        StatusOverlay(state, actions)
        state.notice?.let {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp).widthIn(max = 420.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            ) { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium) }
        }
        state.pendingSeekMs?.let { ms ->
            val tl = state.timeline
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
            ) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(TimeText.clockSeconds(ms, zone), style = MaterialTheme.typography.headlineSmall)
                    if (tl != null) {
                        val behind = (tl.nowMs - ms).coerceAtLeast(0)
                        Text(
                            if (behind >= TimelineState.BEHIND_LIVE_THRESHOLD_MS) TimeText.behindLive(behind) else "Live",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun StatusOverlay(state: PlayerUiState, a: PlayerActions) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (state.phase) {
            Phase.LOADING, Phase.BUFFERING -> CircularProgressIndicator()
            Phase.RECONNECTING -> Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(state.reconnect?.let { "Reconnecting ${it.first}/${it.second}" } ?: "Reconnecting")
                }
            }
            Phase.ERROR -> state.error?.let { e ->
                Surface(
                    modifier = Modifier.padding(24.dp).widthIn(max = 480.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(e.title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                        e.detail?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (e.kind == ErrorKind.CATCHUP_UNAVAILABLE) {
                                Button(onClick = a.onGoLive) { Text("Watch live") }
                            } else {
                                Button(onClick = a.onRetry) { Text("Retry") }
                                OutlinedButton(onClick = a.onEngine) { Text("Switch player") }
                            }
                            TextButton(onClick = a.onNextChannel) { Text("Next channel") }
                        }
                    }
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun PlayerControls(state: PlayerUiState, a: PlayerActions, zone: ZoneId) {
    val scheme = MaterialTheme.colorScheme
    val ch = state.channel
    val tl = state.timeline
    val catchup = ch?.catchupEnabled == true
    Box(Modifier.fillMaxSize()) {
        // Scrims keep the controls readable over bright video.
        Box(Modifier.fillMaxWidth().height(96.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(scheme.scrim.copy(alpha = 0.75f), scheme.scrim.copy(alpha = 0f)))))
        Box(Modifier.fillMaxWidth().height(160.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(scheme.scrim.copy(alpha = 0f), scheme.scrim.copy(alpha = 0.8f)))))

        // ---- top bar
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = a.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Column(Modifier.weight(1f)) {
                Text(
                    listOfNotNull(ch?.number?.toString(), ch?.name).joinToString("  "),
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val prog = tl?.window?.programme?.title
                if (prog != null) {
                    Text(prog, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = a.onProgrammes) { Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = "Programmes") }
            if (state.tracks.audio.size > 1 || state.tracks.text.isNotEmpty() || state.tracks.video.size > 1) {
                IconButton(onClick = a.onTracks) { Icon(Icons.Filled.Subtitles, contentDescription = "Audio and subtitles") }
            }
            IconButton(onClick = a.onAspect) { Icon(Icons.Filled.AspectRatio, contentDescription = "Picture size") }
            a.onPip?.let { pip -> IconButton(onClick = pip) { Icon(Icons.Filled.PictureInPictureAlt, contentDescription = "Picture in picture") } }
            IconButton(onClick = a.onEngine) { Icon(Icons.Filled.Tune, contentDescription = "Player") }
        }

        // ---- centre transport
        Row(
            Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            RoundButton(Icons.Filled.SkipPrevious, "Previous channel", a.onPrevChannel)
            if (catchup) RoundButton(Icons.Filled.Replay10, "Back 10 seconds") { a.onSkip(10_000, -1) }
            FilledIconButton(onClick = a.onPlayPause, modifier = Modifier.size(64.dp)) {
                Icon(
                    if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.playing) "Pause" else "Play", modifier = Modifier.size(36.dp),
                )
            }
            if (catchup) RoundButton(Icons.Filled.Forward10, "Forward 10 seconds") { a.onSkip(10_000, 1) }
            RoundButton(Icons.Filled.SkipNext, "Next channel", a.onNextChannel)
        }

        // ---- bottom: seek bar, labels, live badge
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            if (tl != null) {
                val win = tl.window
                CatchupSeekBar(tl, state.pendingSeekMs, interactive = catchup, onSeek = { a.onSeek(it) }, onScrub = {})
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${TimeText.duration(tl.offsetInWindowMs)} / ${TimeText.duration(win.durationMs)}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${TimeText.clock(win.startMs, zone)} – ${TimeText.clock(win.endMs, zone)}",
                        style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LiveBadge(state)
                GoLiveButton(state, a.onGoLive)
                Spacer(Modifier.weight(1f))
                if (catchup && tl != null) {
                    SmallAction(Icons.Filled.Replay, "Restart programme", a.onRestart)
                    SmallAction(Icons.Filled.SkipPrevious, "Previous programme", a.onPrevProgramme)
                    SmallAction(Icons.Filled.SkipNext, "Next programme", a.onNextProgramme)
                }
                state.engine?.let {
                    Text(if (it == EngineKind.VLC) "VLC" else "Media3", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun LiveBadge(state: PlayerUiState) {
    val tl = state.timeline
    val behind = tl?.isBehindLive == true
    if (behind && tl != null) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
            Text(
                TimeText.behindLive(tl.behindLiveMs), Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    } else {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Filled.FiberManualRecord, null, Modifier.size(10.dp))
                Text(if (state.mode == PlayMode.LIVE) "LIVE" else "REPLAY", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** "Go live" lives next to the badge so it is obvious how to get back. */
@Composable
fun GoLiveButton(state: PlayerUiState, onGoLive: () -> Unit, modifier: Modifier = Modifier) {
    if (state.timeline?.isBehindLive == true) {
        FilledTonalButton(onClick = onGoLive, modifier = modifier) { Text("Go live") }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f)),
    ) { Icon(icon, contentDescription = description) }
}

@Composable
private fun SmallAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) { Icon(icon, contentDescription = description) }
}
