package rocks.urzahil.iptv.ui.guide

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import rocks.urzahil.iptv.data.db.dao.ChannelListItem
import rocks.urzahil.iptv.data.db.entities.CategoryEntity
import rocks.urzahil.iptv.ui.appViewModel
import rocks.urzahil.iptv.ui.components.CategoryChips
import rocks.urzahil.iptv.ui.components.EmptyState
import rocks.urzahil.iptv.ui.components.LoadingView
import rocks.urzahil.iptv.ui.components.SourceEmptyState
import rocks.urzahil.iptv.ui.components.SourceTitle
import rocks.urzahil.iptv.ui.components.TabScaffold
import rocks.urzahil.iptv.ui.components.rememberNowMs
import rocks.urzahil.iptv.ui.guide.model.GuideAxis
import rocks.urzahil.iptv.ui.guide.model.GuideBounds
import rocks.urzahil.iptv.ui.guide.model.GuideDay
import rocks.urzahil.iptv.ui.guide.model.GuideDays
import rocks.urzahil.iptv.ui.guide.model.GuideRowKey
import rocks.urzahil.iptv.ui.guide.model.ProgrammeAction
import rocks.urzahil.iptv.ui.guide.model.TimeLabels
import rocks.urzahil.iptv.ui.theme.Dimens

/** What the guide asks the player to do. Times are wall-clock epoch ms with tvg-shift applied. */
sealed interface GuidePlayRequest {
    val channelId: Long
    val categoryId: Long?

    data class Live(override val channelId: Long, override val categoryId: Long?) : GuidePlayRequest

    /** A programme from the database, played from its start through catchup. */
    data class Programme(override val channelId: Long, override val categoryId: Long?, val programmeId: Long) : GuidePlayRequest

    /** A programme without a row id (provider short EPG). */
    data class Span(override val channelId: Long, override val categoryId: Long?, val startMs: Long, val endMs: Long) : GuidePlayRequest
}

/** Space before "now" at the left edge when the guide opens or "Now" is tapped. */
private const val NOW_LEAD_MS = 30 * 60_000L

@Composable
fun GuideScreen(
    onPlay: (GuidePlayRequest) -> Unit,
    onOpenSettings: () -> Unit,
    onAddSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = appViewModel { GuideViewModel(it) }
    val sources by vm.sources.collectAsStateWithLifecycle()
    val source by vm.currentSource.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val selectedCategory by vm.selectedCategoryId.collectAsStateWithLifecycle()
    val availability by vm.availability.collectAsStateWithLifecycle()
    val bounds by vm.bounds.collectAsStateWithLifecycle()
    val sheet by vm.sheet.collectAsStateWithLifecycle()
    val items = vm.channels.collectAsLazyPagingItems()
    val nowMs = rememberNowMs()
    val context = LocalContext.current
    val is24Hour = DateFormat.is24HourFormat(context)
    val labels = remember(vm.zone, is24Hour) { TimeLabels(vm.zone, is24Hour) }
    val config = LocalConfiguration.current
    val compactHeight = config.screenHeightDp < 480

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshClock() }

    TabScaffold(
        modifier = modifier,
        onOpenSettings = onOpenSettings,
        title = { SourceTitle(sources.orEmpty(), source, vm::selectSource, fallback = "Guide") },
    ) { padding ->
        val list = sources
        val src = source
        val b = bounds
        when {
            list == null || b == null -> LoadingView(Modifier.padding(padding))
            list.isEmpty() || src == null -> EmptyState(
                title = "No sources yet",
                message = "Add an Xtream account or an M3U playlist to see the TV guide.",
                actionLabel = "Add source",
                onAction = onAddSource,
                modifier = Modifier.padding(padding),
            )
            else -> Column(Modifier.padding(padding).fillMaxSize()) {
                val metrics = remember(config.screenWidthDp, config.screenHeightDp) {
                    GuideMetrics.of(config.screenWidthDp, config.screenHeightDp)
                }
                val pxPerMinute = with(LocalDensity.current) { metrics.dpPerMinute.dp.toPx() }
                val axis = remember(b, pxPerMinute) { GuideAxis(b, pxPerMinute) }
                val scroll = remember {
                    GuideScrollState(axis, vm.savedLeftEdgeMs ?: (System.currentTimeMillis() - NOW_LEAD_MS))
                }
                scroll.updateAxis(axis)
                DisposableEffect(scroll) { onDispose { vm.savedLeftEdgeMs = scroll.leftEdgeMs } }
                val days = remember(b, nowMs / GuideBounds.HOUR_MS) { GuideDays.build(b, nowMs, vm.zone) }
                val scope = rememberCoroutineScope()
                val showGrid = availability == EpgAvailability.Ready && items.itemCount > 0

                GuideFilterBar(
                    compact = compactHeight,
                    categories = categories,
                    selectedCategory = selectedCategory,
                    onSelectCategory = vm::selectCategory,
                    days = days,
                    scroll = scroll,
                    labels = labels,
                    showTime = showGrid,
                    onNow = { scope.launch { scroll.scrollToTime(System.currentTimeMillis() - NOW_LEAD_MS) } },
                    onDay = { day -> scope.launch { scroll.scrollToTime(GuideDays.jumpTarget(day, b)) } },
                )
                when {
                    items.itemCount == 0 -> SourceEmptyState(src, onRetry = { vm.retryPlaylist(src.id) })
                    showGrid -> {
                        val listState = rememberLazyListState()
                        ReportViewport(vm, items, listState, scroll)
                        GuideGrid(
                            items = items, listState = listState, scroll = scroll, cache = vm.cache, nowMs = nowMs,
                            labels = labels, metrics = metrics,
                            onChannel = { onPlay(GuidePlayRequest.Live(it.id, selectedCategory)) },
                            onProgramme = vm::openProgramme,
                        )
                    }
                    else -> EpgEmptyState(availability, onRefresh = vm::refreshGuide)
                }
            }
        }
    }

    sheet?.let { s ->
        ProgrammeSheet(
            state = s, nowMs = nowMs, labels = labels,
            onAction = { action ->
                vm.closeProgramme()
                val p = s.programme
                onPlay(
                    when {
                        action == ProgrammeAction.WATCH_LIVE -> GuidePlayRequest.Live(s.channel.id, selectedCategory)
                        // "Start over" and "Watch" both play the programme from its start through the archive.
                        p.id >= 0 -> GuidePlayRequest.Programme(s.channel.id, selectedCategory, p.id)
                        else -> GuidePlayRequest.Span(s.channel.id, selectedCategory, p.startMs, p.endMs)
                    },
                )
            },
            onDismiss = vm::closeProgramme,
        )
    }
}

/** Tells the view model which rows and which time span are on screen (debounced), so it loads just those. */
@OptIn(FlowPreview::class)
@Composable
private fun ReportViewport(
    vm: GuideViewModel,
    items: LazyPagingItems<ChannelListItem>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    scroll: GuideScrollState,
) {
    val version by vm.dataVersion.collectAsStateWithLifecycle()
    // Paging replaces the snapshot list on every page load and refresh (e.g. new channel-to-guide mappings).
    val snapshot = items.itemSnapshotList
    LaunchedEffect(listState, scroll, snapshot, version) {
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            val first = (visible.firstOrNull()?.index ?: 0) - ROW_MARGIN
            val last = (visible.lastOrNull()?.index ?: -1) + ROW_MARGIN
            Triple(first, last, scroll.queryRange)
        }.debounce(80).collect { (first, last, range) ->
            val keys = ArrayList<GuideRowKey>()
            for (i in first.coerceAtLeast(0)..last.coerceAtMost(snapshot.size - 1)) {
                val item = snapshot[i] ?: continue
                keys += GuideRowKey(item.id, item.epgChannelId, item.shiftMin)
            }
            if (keys.isNotEmpty() && !range.isEmpty) vm.onViewport(keys, range)
        }
    }
}

private const val ROW_MARGIN = 4

@Composable
private fun EpgEmptyState(availability: EpgAvailability, onRefresh: () -> Unit) {
    when (availability) {
        EpgAvailability.Unknown, EpgAvailability.Ready -> LoadingView()
        EpgAvailability.Importing -> EmptyState(
            title = "Importing the TV guide",
            message = "Programme data is being downloaded. This can take a few minutes for large guides.",
        )
        EpgAvailability.NotConfigured -> EmptyState(
            title = "No TV guide for this source",
            message = "The playlist does not name a guide. Add an XMLTV address to the source on the Sources tab, or refresh to check again.",
            actionLabel = "Refresh guide",
            onAction = onRefresh,
        )
        is EpgAvailability.NotLoaded -> EmptyState(
            title = if (availability.error != null) "Could not load the TV guide" else "No TV guide yet",
            message = availability.error ?: "The guide for this source has not been downloaded yet.",
            actionLabel = "Refresh guide",
            onAction = onRefresh,
            isError = availability.error != null,
        )
    }
}

/**
 * Category chips (as on the Channels tab) and time navigation. Tall screens get two rows: categories, then
 * "Now" and one chip per day. Short (landscape) screens get a single row with the days folded into a menu.
 */
@Composable
private fun GuideFilterBar(
    compact: Boolean,
    categories: List<CategoryEntity>,
    selectedCategory: Long?,
    onSelectCategory: (Long?) -> Unit,
    days: List<GuideDay>,
    scroll: GuideScrollState,
    labels: TimeLabels,
    showTime: Boolean,
    onNow: () -> Unit,
    onDay: (GuideDay) -> Unit,
) {
    // The day at the left edge: derived, so the bar recomposes when the day changes, not on every scroll frame.
    val currentDay by remember(days, scroll) { derivedStateOf { GuideDays.dayAt(days, scroll.leftEdgeMs) ?: days.firstOrNull() } }
    if (!compact) {
        CategoryChips(categories, selectedCategory, onSelectCategory)
        if (showTime) {
            Row(Modifier.fillMaxWidth().padding(start = Dimens.ScreenPadding, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                NowChip(onNow)
                val dayRow = rememberLazyListState()
                val index = days.indexOf(currentDay)
                LaunchedEffect(index) { if (index >= 0) dayRow.animateScrollToItem(index) }
                LazyRow(
                    state = dayRow,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 8.dp, end = Dimens.ScreenPadding),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(days, key = { it.startMs }) { day ->
                        FilterChip(selected = day == currentDay, onClick = { onDay(day) }, label = { Text(labels.day(day), maxLines = 1) })
                    }
                }
            }
        }
    } else {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = Dimens.ScreenPadding, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showTime) {
                item(key = "now") { NowChip(onNow) }
                item(key = "day") { DayMenuChip(days, currentDay, labels, onDay) }
            }
            item(key = "all") {
                FilterChip(selected = selectedCategory == null, onClick = { onSelectCategory(null) }, label = { Text("All") })
            }
            items(categories, key = { it.id }) { c ->
                FilterChip(selected = selectedCategory == c.id, onClick = { onSelectCategory(c.id) }, label = { Text(c.name, maxLines = 1) })
            }
        }
    }
}

@Composable
private fun NowChip(onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text("Now") },
        leadingIcon = { Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
    )
}

@Composable
private fun DayMenuChip(days: List<GuideDay>, current: GuideDay?, labels: TimeLabels, onDay: (GuideDay) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = true,
            onClick = { open = true },
            label = { Text(current?.let { labels.day(it) } ?: "Day", maxLines = 1) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose day", modifier = Modifier.size(FilterChipDefaults.IconSize)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            days.forEach { day ->
                DropdownMenuItem(
                    text = { Text(if (day.offset in -1..1) "${labels.day(day)} · ${labels.shortDay(day.startMs)}" else labels.longDay(day.startMs)) },
                    onClick = { open = false; onDay(day) },
                )
            }
        }
    }
}
