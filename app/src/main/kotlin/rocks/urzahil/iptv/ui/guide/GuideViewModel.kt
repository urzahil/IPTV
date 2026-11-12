package rocks.urzahil.iptv.ui.guide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import rocks.urzahil.iptv.data.db.dao.ChannelListItem
import rocks.urzahil.iptv.data.db.dao.ProgrammeLite
import rocks.urzahil.iptv.data.db.entities.CategoryEntity
import rocks.urzahil.iptv.data.db.entities.EpgSourceEntity
import rocks.urzahil.iptv.data.db.entities.SourceEntity
import rocks.urzahil.iptv.data.db.entities.SourceType
import rocks.urzahil.iptv.di.AppContainer
import rocks.urzahil.iptv.ui.channels.BrowseState
import rocks.urzahil.iptv.ui.guide.model.GuideBounds
import rocks.urzahil.iptv.ui.guide.model.GuideRowKey
import rocks.urzahil.iptv.ui.guide.model.ProgrammeCache
import rocks.urzahil.iptv.ui.guide.model.TimeRange
import rocks.urzahil.iptv.util.AppLog

/** Whether the selected source has guide data at all. */
sealed interface EpgAvailability {
    data object Unknown : EpgAvailability
    data object Ready : EpgAvailability
    data object Importing : EpgAvailability
    /** A guide address exists but nothing has been imported yet. [error] is the (redacted) last failure. */
    data class NotLoaded(val error: String?) : EpgAvailability
    /** The source declares no guide address. */
    data object NotConfigured : EpgAvailability
}

/** The programme shown in the bottom sheet. */
data class ProgrammeSheetState(
    val channel: ChannelListItem,
    val programme: ProgrammeLite,
    val subTitle: String? = null,
    val description: String? = null,
    val categories: String? = null,
    val episode: String? = null,
    val loading: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class GuideViewModel(private val c: AppContainer) : ViewModel() {
    // A failing database read or network call in a launched task must not end the process: log the class only
    // (messages can repeat URLs) and let the screen keep what it has.
    private val handler = CoroutineExceptionHandler { _, t -> AppLog.w(TAG, "guide task failed: ${t.javaClass.simpleName}") }
    private val scope: CoroutineScope = viewModelScope + handler

    private val browse = BrowseState(c, scope)

    val sources: StateFlow<List<SourceEntity>?> = browse.sources
    val currentSource: StateFlow<SourceEntity?> = browse.currentSource
    val categories: StateFlow<List<CategoryEntity>> = browse.categories
    val selectedCategoryId: StateFlow<Long?> = browse.selectedCategoryId
    val channels: Flow<PagingData<ChannelListItem>> = browse.channels.cachedIn(viewModelScope)

    val zone: ZoneId = ZoneId.systemDefault()
    val cache = ProgrammeCache(c.epgRepository::programmesBatch)

    private val retention = c.settings.settings.map { it.epgPastDays to it.epgFutureDays }.distinctUntilChanged()
    private val boundsClock = MutableStateFlow(System.currentTimeMillis())

    /** Scrollable time extent, from the retention settings. Null until the settings are read. */
    val bounds: StateFlow<GuideBounds?> = combine(retention, boundsClock) { (past, future), now ->
        GuideBounds.of(now, past, future, zone)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private var savedLeftEdgeMs: Long? = null
    private var savedAtMs = 0L

    /** Remembers the time at the left edge across a rotation or a short visit to another tab. */
    fun saveLeftEdge(timeMs: Long, nowMs: Long = System.currentTimeMillis()) {
        savedLeftEdgeMs = timeMs
        savedAtMs = nowMs
    }

    /** The remembered position, or null when it is stale (the guide then opens at the current time). */
    fun restoredLeftEdge(nowMs: Long = System.currentTimeMillis()): Long? =
        savedLeftEdgeMs?.takeIf { nowMs - savedAtMs in 0 until RESTORE_WITHIN_MS }

    private val epgSources: Flow<List<EpgSourceEntity>> = currentSource.map { it?.id }.distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else c.sourceRepository.observeEpgSources(id) }

    private val syncing: Flow<Boolean> = currentSource.map { it?.id }.distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(false) else c.syncScheduler.observeProgress(id).map { it != null } }

    val availability: StateFlow<EpgAvailability> = combine(currentSource, epgSources, syncing) { src, list, busy ->
        when {
            src == null -> EpgAvailability.Unknown
            list.any { it.lastSyncMs > 0 } -> EpgAvailability.Ready
            busy -> EpgAvailability.Importing
            list.isEmpty() -> EpgAvailability.NotConfigured
            else -> EpgAvailability.NotLoaded(list.firstNotNullOfOrNull { it.lastError })
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), EpgAvailability.Unknown)

    /** Bumped when guide data changed underneath the cache; the screen then reports its viewport again. */
    private val _dataVersion = MutableStateFlow(0)
    val dataVersion: StateFlow<Int> = _dataVersion.asStateFlow()

    private val _sheet = MutableStateFlow<ProgrammeSheetState?>(null)
    val sheet: StateFlow<ProgrammeSheetState?> = _sheet.asStateFlow()

    private var ensureJob: Job? = null
    private var fallbackJob: Job? = null

    init {
        // A finished import (or a new channel-to-guide mapping) makes the loaded rows stale.
        scope.launch {
            epgSources.map { list -> list.maxOfOrNull { it.lastSyncMs } ?: 0L }.distinctUntilChanged().drop(1).collect {
                cache.invalidate()
                _dataVersion.value++
            }
        }
    }

    fun selectSource(id: Long) = browse.selectSource(id)

    fun selectCategory(id: Long?) = browse.selectCategory(id)

    /** Recomputes the bounds when the guide is opened again much later (the window moves with the clock). */
    fun refreshClock(nowMs: Long = System.currentTimeMillis()) {
        if (nowMs - boundsClock.value >= GuideBounds.HALF_HOUR_MS || nowMs < boundsClock.value) boundsClock.value = nowMs
    }

    /** Called (debounced) with the rows on screen and the time span to have loaded. */
    fun onViewport(keys: List<GuideRowKey>, range: TimeRange) {
        ensureJob?.cancel()
        fallbackJob?.cancel()
        ensureJob = scope.launch {
            val empty = cache.ensure(keys, range)
            if (empty.isNotEmpty() && currentSource.value?.sourceType == SourceType.XTREAM) loadFallback(empty)
        }
    }

    /** Provider short EPG for Xtream rows without guide data, once the viewport has settled. */
    private fun loadFallback(channelIds: List<Long>) {
        fallbackJob = scope.launch {
            delay(FALLBACK_SETTLE_MS)
            channelIds.take(FALLBACK_MAX_ROWS).map { id ->
                async {
                    val list = c.shortEpg.load(id)
                    if (list.isNotEmpty()) cache.setFallback(id, list)
                }
            }.awaitAll()
        }
    }

    fun openProgramme(channel: ChannelListItem, programme: ProgrammeLite) {
        if (programme.id < 0) {
            _sheet.value = ProgrammeSheetState(channel, programme, description = c.shortEpg.description(channel.id, programme.id))
            return
        }
        _sheet.value = ProgrammeSheetState(channel, programme, loading = true)
        scope.launch {
            val d = try {
                c.epgRepository.details(programme.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _sheet.value = _sheet.value?.takeIf { it.programme.id == programme.id && it.channel.id == channel.id }?.copy(
                subTitle = d?.subTitle, description = d?.description, categories = d?.categories, episode = d?.episode,
                loading = false,
            ) ?: _sheet.value
        }
    }

    fun closeProgramme() { _sheet.value = null }

    /** Manual guide refresh for the selected source. */
    fun refreshGuide() {
        val src = currentSource.value ?: return
        scope.launch {
            val list = c.sourceRepository.epgSourcesOf(src.id)
            if (list.isEmpty()) {
                c.syncScheduler.syncPlaylist(src.id, manual = true, needsNetwork = src.sourceType != SourceType.M3U_FILE)
            } else {
                list.forEach { c.syncScheduler.syncEpg(it.id, src.id, force = true) }
            }
        }
    }

    fun retryPlaylist(sourceId: Long) { c.syncScheduler.syncPlaylist(sourceId, manual = true) }

    private companion object {
        const val TAG = "GuideVM"
        const val RESTORE_WITHIN_MS = 10 * 60_000L
        const val FALLBACK_SETTLE_MS = 600L
        const val FALLBACK_MAX_ROWS = 16
    }
}
