package io.github.urzahil.iptv.ui.channels

import io.github.urzahil.iptv.data.db.dao.ChannelListItem
import io.github.urzahil.iptv.data.repo.EpgRepository
import io.github.urzahil.iptv.data.repo.EpgRequest
import io.github.urzahil.iptv.data.repo.NowNext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Batched now/next for the channels currently on screen. Refreshes every minute and when a programme ends. */
class NowNextCache(private val epg: EpgRepository, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<Map<Long, NowNext>>(emptyMap())
    val state: StateFlow<Map<Long, NowNext>> = _state.asStateFlow()

    private var visible: List<ChannelListItem> = emptyList()
    private var loading: Job? = null

    init {
        scope.launch {
            while (true) {
                delay(TICK_MS)
                load(force = true)
            }
        }
    }

    fun onVisible(items: List<ChannelListItem>) {
        visible = items
        load(force = false)
    }

    private fun load(force: Boolean) {
        val now = System.currentTimeMillis()
        val current = _state.value
        val wanted = visible.filter { it.epgChannelId != null }.filter {
            force || current[it.id].let { nn -> nn == null || (nn.now != null && nn.now.endMs <= now) }
        }
        if (wanted.isEmpty()) return
        loading?.cancel()
        loading = scope.launch {
            val result = epg.nowNext(wanted.map { EpgRequest(it.id, it.epgChannelId!!, it.shiftMin) }, now)
            _state.value = _state.value + result
        }
    }

    private companion object {
        const val TICK_MS = 60_000L
    }
}
