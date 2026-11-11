package rocks.urzahil.iptv.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import rocks.urzahil.iptv.player.model.TimelineState

/**
 * Draws the programme containing the playback position:
 * - the part already aired (lighter), the played part (primary) and a hatched part that has not aired yet,
 * - a tick at the live edge when the programme is still running,
 * - a thumb at the position (or at the finger while dragging).
 *
 * When [interactive] the bar can be tapped or dragged; the target is committed through [onSeek] with a wall
 * clock time (epoch ms). Without catchup the bar is display-only.
 */
@Composable
fun CatchupSeekBar(
    timeline: TimelineState,
    pendingMs: Long?,
    interactive: Boolean,
    onSeek: (Long) -> Unit,
    onScrub: (Float?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val win = timeline.window
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val shownFraction = dragFraction
        ?: pendingMs?.let { ((it - win.startMs).toFloat() / win.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f) }
        ?: timeline.positionFraction
    val aired = timeline.airedFraction

    fun toWall(f: Float) = win.startMs + (f * win.durationMs).toLong()

    Box(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .semantics { contentDescription = "Programme position" }
            .then(
                if (!interactive) Modifier else Modifier
                    .pointerInput(timeline.window) {
                        detectTapGestures { o -> onSeek(toWall((o.x / size.width).coerceIn(0f, 1f))) }
                    }
                    .pointerInput(timeline.window) {
                        detectDragGestures(
                            onDragStart = { o -> dragFraction = (o.x / size.width).coerceIn(0f, 1f); onScrub(dragFraction) },
                            onDragEnd = { dragFraction?.let { onSeek(toWall(it)) }; dragFraction = null; onScrub(null) },
                            onDragCancel = { dragFraction = null; onScrub(null) },
                        ) { change, _ ->
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                            onScrub(dragFraction)
                        }
                    },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(36.dp)) {
            val h = 4.dp.toPx()
            val y = (size.height - h) / 2
            val r = CornerRadius(h / 2)
            val w = size.width
            // Track: everything the programme spans.
            drawRoundRect(scheme.surfaceVariant, Offset(0f, y), Size(w, h), r)
            // Aired part.
            drawRoundRect(scheme.onSurfaceVariant.copy(alpha = 0.5f), Offset(0f, y), Size(w * aired, h), r)
            // Not yet aired: hatched.
            if (aired < 1f) {
                clipRect(left = w * aired, top = y, right = w, bottom = y + h) {
                    val step = 6.dp.toPx()
                    var x = w * aired - h
                    while (x < w + h) {
                        drawLine(scheme.onSurfaceVariant.copy(alpha = 0.55f), Offset(x, y + h), Offset(x + h, y), strokeWidth = 1.5.dp.toPx())
                        x += step
                    }
                }
            }
            // Played part.
            drawRoundRect(scheme.primary, Offset(0f, y), Size(w * shownFraction, h), r)
            // Live edge.
            if (aired in 0.001f..0.999f) {
                drawRect(scheme.tertiary, Offset(w * aired - 1.dp.toPx(), y - 5.dp.toPx()), Size(2.dp.toPx(), h + 10.dp.toPx()))
            }
            // Thumb.
            val thumbR = if (dragFraction != null) 10.dp.toPx() else 7.dp.toPx()
            drawCircle(scheme.primary, thumbR, Offset(w * shownFraction, size.height / 2))
        }
    }
}
