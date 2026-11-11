package rocks.urzahil.iptv.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import rocks.urzahil.iptv.data.repo.ZapDirection
import rocks.urzahil.iptv.player.Phase
import rocks.urzahil.iptv.player.PlaybackController
import java.time.ZoneId
import kotlin.math.abs

private enum class Sheet { NONE, TRACKS, ENGINE, PROGRAMMES }

/** Hosts the engine surface, the touch gestures, the controls overlay and the sheets. */
@Composable
fun PlayerScreen(
    controller: PlaybackController,
    invertSwipe: Boolean,
    inPip: Boolean,
    onBack: () -> Unit,
    onPip: (() -> Unit)?,
) {
    val ui by controller.ui.collectAsStateWithLifecycle()
    val engine by controller.engine.collectAsStateWithLifecycle()
    val guide by controller.guide.collectAsStateWithLifecycle()
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var sheetName by rememberSaveable { mutableStateOf(Sheet.NONE.name) }
    val sheet = Sheet.valueOf(sheetName)
    val zone = remember { ZoneId.systemDefault() }
    val catchup = ui.channel?.catchupEnabled == true

    // Auto-hide while playing; always show while paused, loading or failing.
    LaunchedEffect(controlsVisible, ui.phase, sheet, ui.pendingSeekMs) {
        if (controlsVisible && ui.phase == Phase.PLAYING && sheet == Sheet.NONE && ui.pendingSeekMs == null) {
            delay(4_000)
            controlsVisible = false
        }
    }
    val showControls = controlsVisible || ui.phase == Phase.ERROR || ui.phase == Phase.PAUSED

    val actions = remember(controller, onBack, onPip) {
        PlayerActions(
            onBack = onBack,
            onPlayPause = { controller.togglePlayPause() },
            onPrevChannel = { controller.zap(ZapDirection.PREVIOUS) },
            onNextChannel = { controller.zap(ZapDirection.NEXT) },
            onSkip = { step, dir -> controller.skip(step, dir) },
            onRestart = { controller.restartProgramme() },
            onPrevProgramme = { controller.previousProgramme() },
            onNextProgramme = { controller.nextProgramme() },
            onGoLive = { controller.goLive() },
            onSeek = { controller.seekToWall(it) },
            onTracks = { sheetName = Sheet.TRACKS.name },
            onEngine = { sheetName = Sheet.ENGINE.name },
            onProgrammes = { sheetName = Sheet.PROGRAMMES.name },
            onAspect = { controller.cycleAspect() },
            onPip = onPip,
            onRetry = { controller.retry() },
        )
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim)) {
        engine?.let { e -> key(e) { e.Surface(Modifier.fillMaxSize()) } }

        if (!inPip) {
            // Touch gestures: tap shows/hides the controls, double-tap skips (catchup channels), vertical swipe zaps.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(catchup) {
                        detectTapGestures(
                            onTap = { controlsVisible = !controlsVisible },
                            onDoubleTap = { o ->
                                if (catchup) controller.skip(10_000, if (o.x < size.width / 2f) -1 else 1)
                                else controlsVisible = !controlsVisible
                            },
                        )
                    }
                    .pointerInput(invertSwipe) {
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = {
                                if (abs(total) > 96.dp.toPx()) {
                                    val up = total < 0
                                    controller.zap(if (up != invertSwipe) ZapDirection.NEXT else ZapDirection.PREVIOUS)
                                }
                                total = 0f
                            },
                            onDragCancel = { total = 0f },
                        ) { _, dy -> total += dy }
                    },
            )
            PlayerOverlay(ui, showControls, actions, zone)
        }
    }

    when (sheet) {
        Sheet.NONE -> Unit
        Sheet.TRACKS -> TrackSheet(
            ui.tracks,
            onAudio = { controller.selectAudio(it) }, onText = { controller.selectText(it) }, onVideo = { controller.selectVideo(it) },
            onDismiss = { sheetName = Sheet.NONE.name },
        )
        Sheet.ENGINE -> EngineSheet(
            ui.engine, ui.channelEngine,
            onPick = { controller.setChannelEngine(it) }, onDismiss = { sheetName = Sheet.NONE.name },
        )
        Sheet.PROGRAMMES -> ProgrammeSheet(
            slots = guide.slots,
            nowMs = ui.timeline?.nowMs ?: System.currentTimeMillis(),
            catchupDays = ui.channel?.catchupDays ?: 0,
            wallPosMs = ui.timeline?.wallPosMs,
            zone = zone,
            loadDetails = { controller.programmeDetails(it) },
            onPlay = { slot, fromStart -> controller.playProgramme(slot, fromStart) },
            onDismiss = { sheetName = Sheet.NONE.name },
        )
    }
}
