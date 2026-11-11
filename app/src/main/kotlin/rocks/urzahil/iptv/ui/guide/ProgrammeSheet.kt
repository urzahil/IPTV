package rocks.urzahil.iptv.ui.guide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import rocks.urzahil.iptv.ui.guide.model.GuideClassifier
import rocks.urzahil.iptv.ui.guide.model.ProgrammeAction
import rocks.urzahil.iptv.ui.guide.model.ProgrammeStatus
import rocks.urzahil.iptv.ui.guide.model.TimeLabels
import rocks.urzahil.iptv.ui.guide.model.UnplayableReason
import rocks.urzahil.iptv.ui.theme.Dimens

/** Programme details with the playback actions that apply to it. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProgrammeSheet(
    state: ProgrammeSheetState,
    nowMs: Long,
    labels: TimeLabels,
    onAction: (ProgrammeAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = state.programme
    val catchupDays = state.channel.catchupDays
    val status = GuideClassifier.classify(p.startMs, p.endMs, nowMs, catchupDays)
    val options = GuideClassifier.options(p.startMs, p.endMs, nowMs, catchupDays)
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = scheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenPadding * 1.5f)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                (state.channel.number?.let { "$it  " } ?: "") + state.channel.name,
                style = MaterialTheme.typography.labelLarge,
                color = scheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(p.title, style = MaterialTheme.typography.titleLarge, color = scheme.onSurface)
            state.subTitle?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.titleSmall, color = scheme.onSurfaceVariant)
            }
            Text(
                "${labels.longDay(p.startMs)} · ${labels.range(p.startMs, p.endMs)} · ${TimeLabels.duration(p.endMs - p.startMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
            val statusText = when (status) {
                ProgrammeStatus.NOW -> "On now · ${TimeLabels.duration(p.endMs - nowMs)} left"
                ProgrammeStatus.PAST_PLAYABLE -> "Available on catchup"
                ProgrammeStatus.PAST -> "Already aired"
                ProgrammeStatus.FUTURE -> "Upcoming"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (status == ProgrammeStatus.PAST_PLAYABLE) {
                    Icon(Icons.Filled.History, contentDescription = null, tint = scheme.tertiary, modifier = Modifier.padding(end = 6.dp).size(16.dp))
                }
                Text(
                    statusText,
                    style = MaterialTheme.typography.labelLarge,
                    color = when (status) {
                        ProgrammeStatus.NOW -> scheme.primary
                        ProgrammeStatus.PAST_PLAYABLE -> scheme.tertiary
                        else -> scheme.onSurfaceVariant
                    },
                )
            }
            val meta = listOfNotNull(state.episode?.takeIf { it.isNotBlank() }, state.categories?.takeIf { it.isNotBlank() })
            if (meta.isNotEmpty()) {
                Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            val description = state.description?.takeIf { it.isNotBlank() }
            when {
                description != null -> Text(description, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                !state.loading -> Text("No description available.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            if (options.actions.isNotEmpty()) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.actions.forEachIndexed { i, action ->
                        val (label, icon) = when (action) {
                            ProgrammeAction.WATCH_LIVE -> "Watch live" to Icons.Filled.LiveTv
                            ProgrammeAction.START_OVER -> "Start over" to Icons.Filled.Replay
                            ProgrammeAction.WATCH_CATCHUP -> "Watch" to Icons.Filled.PlayArrow
                        }
                        ActionButton(label, icon, primary = i == 0) { onAction(action) }
                    }
                }
            }
            options.reason?.let { reason ->
                Text(
                    when (reason) {
                        UnplayableReason.NOT_AIRED_YET -> "This programme has not aired yet. It starts at ${labels.time(p.startMs)}."
                        UnplayableReason.NO_CATCHUP -> "This channel has no catchup, so past programmes cannot be played."
                        UnplayableReason.OUTSIDE_CATCHUP_WINDOW ->
                            "This programme is older than the channel's catchup archive (" +
                                (if (catchupDays == 1) "1 day" else "$catchupDays days") + ")."
                    },
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ActionButton(label: String, icon: ImageVector, primary: Boolean, onClick: () -> Unit) {
    val content: @Composable () -> Unit = {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(ButtonDefaults.IconSize))
        Text(label)
    }
    val modifier = Modifier.heightIn(min = Dimens.MinTouchTarget)
    if (primary) Button(onClick, modifier) { content() } else FilledTonalButton(onClick, modifier) { content() }
}
