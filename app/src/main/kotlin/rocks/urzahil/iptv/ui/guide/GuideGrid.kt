package rocks.urzahil.iptv.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow
import rocks.urzahil.iptv.data.db.dao.ChannelListItem
import rocks.urzahil.iptv.data.db.dao.ProgrammeLite
import rocks.urzahil.iptv.ui.guide.model.CellBuilder
import rocks.urzahil.iptv.ui.guide.model.GapCell
import rocks.urzahil.iptv.ui.guide.model.GuideAxis
import rocks.urzahil.iptv.ui.guide.model.GuideClassifier
import rocks.urzahil.iptv.ui.guide.model.GuideTicks
import rocks.urzahil.iptv.ui.guide.model.ProgrammeCache
import rocks.urzahil.iptv.ui.guide.model.ProgrammeCell
import rocks.urzahil.iptv.ui.guide.model.ProgrammeStatus
import rocks.urzahil.iptv.ui.guide.model.RowData
import rocks.urzahil.iptv.ui.guide.model.TimeLabels

/** Grid dimensions. Narrow (portrait) screens use a denser time scale so more than an hour fits. */
@Immutable
data class GuideMetrics(val channelWidth: Dp, val rowHeight: Dp, val rulerHeight: Dp, val dpPerMinute: Float) {
    companion object {
        fun of(screenWidthDp: Int, screenHeightDp: Int): GuideMetrics {
            val wide = screenWidthDp >= 600
            return GuideMetrics(
                channelWidth = if (wide) 112.dp else 92.dp,
                rowHeight = if (screenHeightDp < 480) 60.dp else 68.dp,
                rulerHeight = 32.dp,
                dpPerMinute = if (wide) 4f else 3f,
            )
        }
    }
}

/** Position of a child of [TimeStrip], in pixels from the guide origin. */
private data class StripPos(val leftPx: Int, val widthPx: Int)

private fun stripPos(axis: GuideAxis, startMs: Long, endMs: Long): StripPos {
    val left = axis.xOf(startMs).roundToInt()
    return StripPos(left, (axis.xOf(endMs).roundToInt() - left).coerceAtLeast(0))
}

/**
 * Lays its children out on the time axis. The scroll position is read only while placing, so a horizontal
 * scroll re-places the children without recomposing or re-measuring anything.
 */
@Composable
private fun TimeStrip(scroll: GuideScrollState, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier.clipToBounds()) { measurables, constraints ->
        val height = constraints.maxHeight
        val placeables = measurables.map { m ->
            val pos = m.layoutId as StripPos
            pos to m.measure(Constraints.fixed(pos.widthPx, height))
        }
        layout(constraints.maxWidth, height) {
            val offset = scroll.scrollPx.roundToInt()
            for ((pos, p) in placeables) p.place(pos.leftPx - offset, 0)
        }
    }
}

/**
 * Keeps the content of a cell that starts left of the viewport readable: the content is shifted to the visible
 * part and narrowed accordingly. The shift is stepped and derived, so only the cells crossing the left edge
 * are measured again while scrolling.
 */
private fun Modifier.stickyStart(minWidth: Dp, shiftPx: () -> Int): Modifier = layout { measurable, constraints ->
    val shift = shiftPx().coerceIn(0, constraints.maxWidth)
    val room = constraints.maxWidth - shift
    val p = measurable.measure(constraints.copy(minWidth = 0, maxWidth = room))
    // What is left on screen is too narrow for readable text: show the bare cell instead of clipped letters.
    val show = room >= minWidth.roundToPx()
    layout(constraints.maxWidth, constraints.maxHeight) { if (show) p.place(shift, 0) }
}

@Composable
fun GuideGrid(
    items: LazyPagingItems<ChannelListItem>,
    listState: LazyListState,
    scroll: GuideScrollState,
    cache: ProgrammeCache,
    nowMs: Long,
    labels: TimeLabels,
    metrics: GuideMetrics,
    onChannel: (ChannelListItem) -> Unit,
    onProgramme: (ChannelListItem, ProgrammeLite) -> Unit,
    modifier: Modifier = Modifier,
) {
    val nowColor = MaterialTheme.colorScheme.error
    val density = LocalDensity.current
    val channelPx = with(density) { metrics.channelWidth.toPx() }
    val linePx = with(density) { 2.dp.toPx() }
    Column(
        modifier
            .fillMaxSize()
            // One horizontal scrollable around the whole grid; the list inside only takes vertical drags, and
            // each gesture is locked to the axis that first passes the touch slop.
            .scrollable(scroll.scrollable, Orientation.Horizontal)
            .drawWithContent {
                drawContent()
                // "Now" line over the ruler and all rows; drawn here so it costs no layout or composition.
                val x = channelPx + scroll.axis.xOf(nowMs) - scroll.scrollPx
                if (x >= channelPx && x <= size.width) {
                    drawLine(nowColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = linePx)
                    drawCircle(nowColor, radius = linePx * 2.5f, center = Offset(x, linePx * 2.5f))
                }
            },
    ) {
        Row(Modifier.fillMaxWidth().height(metrics.rulerHeight).background(MaterialTheme.colorScheme.surfaceContainer)) {
            RulerCorner(scroll, labels, Modifier.width(metrics.channelWidth).fillMaxHeight())
            TimeRuler(
                scroll, labels,
                Modifier.weight(1f).fillMaxHeight().onSizeChanged { scroll.updateViewport(it.width) },
            )
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
            items(count = items.itemCount, key = items.itemKey { it.id }, contentType = { "row" }) { index ->
                val item = items[index]
                if (item != null) {
                    val row = remember(item.id) { cache.row(item.id) }
                    GuideRow(item, row, scroll, nowMs, labels, metrics, onChannel, onProgramme)
                } else {
                    Spacer(Modifier.height(metrics.rowHeight))
                }
            }
        }
    }
}

/** Day shown above the channel column: the day at the left edge of the grid. */
@Composable
private fun RulerCorner(scroll: GuideScrollState, labels: TimeLabels, modifier: Modifier) {
    val day by remember(scroll, labels) { derivedStateOf { labels.shortDay(scroll.leftEdgeMs) } }
    Box(modifier.padding(start = 8.dp), contentAlignment = Alignment.CenterStart) {
        Text(day, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun TimeRuler(scroll: GuideScrollState, labels: TimeLabels, modifier: Modifier) {
    val axis = scroll.axis
    val window = scroll.cellWindow
    val ticks = remember(window, labels) { GuideTicks.between(window, labels.zone) }
    val tickColor = MaterialTheme.colorScheme.outlineVariant
    TimeStrip(scroll, modifier) {
        for (t in ticks) {
            key(t.timeMs) {
                Box(
                    Modifier
                        .layoutId(stripPos(axis, t.timeMs, t.timeMs + TICK_STEP_MS))
                        .drawBehind {
                            val h = if (t.fullHour) size.height * 0.5f else size.height * 0.3f
                            drawLine(tickColor, Offset(0f, size.height - h), Offset(0f, size.height), strokeWidth = 1.dp.toPx())
                        }
                        .padding(start = 6.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        labels.time(t.timeMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (t.fullHour) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

private const val TICK_STEP_MS = 30 * 60_000L

@Composable
private fun GuideRow(
    item: ChannelListItem,
    row: StateFlow<RowData>,
    scroll: GuideScrollState,
    nowMs: Long,
    labels: TimeLabels,
    metrics: GuideMetrics,
    onChannel: (ChannelListItem) -> Unit,
    onProgramme: (ChannelListItem, ProgrammeLite) -> Unit,
) {
    val data by row.collectAsState()
    val axis = scroll.axis
    val window = scroll.cellWindow
    val cells = remember(data, window) { CellBuilder.build(data.programmes, window, data.span) }
    Row(Modifier.fillMaxWidth().height(metrics.rowHeight)) {
        ChannelCell(item, Modifier.width(metrics.channelWidth).fillMaxHeight(), onClick = { onChannel(item) })
        TimeStrip(scroll, Modifier.weight(1f).fillMaxHeight()) {
            for (cell in cells) {
                key(cell.key) {
                    val pos = stripPos(axis, cell.startMs, cell.endMs)
                    when (cell) {
                        is ProgrammeCell -> ProgrammeCellView(
                            programme = cell.programme,
                            status = GuideClassifier.classify(cell.programme.startMs, cell.programme.endMs, nowMs, item.catchupDays),
                            pos = pos, scroll = scroll, labels = labels,
                            onClick = { onProgramme(item, cell.programme) },
                        )
                        is GapCell -> GapCellView(cell.known, pos, scroll)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelCell(item: ChannelListItem, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .padding(end = 1.dp, bottom = 1.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClickLabel = "Watch live", onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                if (item.logoUrl.isNullOrBlank()) {
                    Text(
                        item.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    AsyncImage(model = item.logoUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(2.dp))
                }
            }
            if (item.number != null) {
                Text(
                    item.number.toString(),
                    modifier = Modifier.padding(start = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
            if (item.catchupDays > 0) {
                Icon(
                    Icons.Filled.History,
                    contentDescription = "Catchup available",
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(start = 4.dp).size(14.dp),
                )
            }
        }
        Text(
            item.name,
            modifier = Modifier.padding(top = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Pixels by which a cell's content must move right to stay on screen, in 8 dp steps. */
@Composable
private fun rememberStickyShift(scroll: GuideScrollState, pos: StripPos): () -> Int {
    val step = with(LocalDensity.current) { 8.dp.roundToPx() }.coerceAtLeast(1)
    val state = remember(scroll, pos, step) {
        derivedStateOf {
            val hidden = scroll.scrollPx.roundToInt() - pos.leftPx
            if (hidden <= 0) 0 else (hidden + step - 1) / step * step
        }
    }
    return { state.value }
}

@Composable
private fun ProgrammeCellView(
    programme: ProgrammeLite,
    status: ProgrammeStatus,
    pos: StripPos,
    scroll: GuideScrollState,
    labels: TimeLabels,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val container: Color
    val content: Color
    val secondary: Color
    when (status) {
        ProgrammeStatus.NOW -> { container = scheme.primaryContainer; content = scheme.onPrimaryContainer; secondary = scheme.onPrimaryContainer }
        ProgrammeStatus.FUTURE -> { container = scheme.surfaceContainerHigh; content = scheme.onSurface; secondary = scheme.onSurfaceVariant }
        ProgrammeStatus.PAST_PLAYABLE -> { container = scheme.surfaceContainer; content = scheme.onSurfaceVariant; secondary = scheme.onSurfaceVariant }
        ProgrammeStatus.PAST -> { container = scheme.surfaceContainerLow; content = scheme.outline; secondary = scheme.outline }
    }
    val shift = rememberStickyShift(scroll, pos)
    val time = labels.range(programme.startMs, programme.endMs)
    Box(
        Modifier
            .layoutId(pos)
            .padding(end = 1.dp, bottom = 1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(container)
            .clickable(onClick = onClick),
    ) {
        // Too narrow for text: the colour block alone still shows the slot and stays tappable.
        if (pos.widthPx >= with(LocalDensity.current) { MIN_TEXT_WIDTH.roundToPx() }) {
            Column(Modifier.stickyStart(MIN_TEXT_WIDTH, shift).padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(
                    programme.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = content,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (status == ProgrammeStatus.PAST_PLAYABLE) {
                        Icon(
                            Icons.Filled.History,
                            contentDescription = "Catchup available",
                            tint = scheme.tertiary,
                            modifier = Modifier.padding(end = 4.dp).size(14.dp),
                        )
                    }
                    Text(time, style = MaterialTheme.typography.labelSmall, color = secondary, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                }
            }
        }
    }
}

@Composable
private fun GapCellView(known: Boolean, pos: StripPos, scroll: GuideScrollState) {
    val shift = rememberStickyShift(scroll, pos)
    Box(
        Modifier
            .layoutId(pos)
            .padding(end = 1.dp, bottom = 1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        if (known && pos.widthPx >= with(LocalDensity.current) { MIN_GAP_TEXT_WIDTH.roundToPx() }) {
            Box(Modifier.stickyStart(MIN_GAP_TEXT_WIDTH, shift).fillMaxHeight().padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                Text(
                    "No information",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

private val MIN_TEXT_WIDTH = 44.dp
private val MIN_GAP_TEXT_WIDTH = 96.dp
