package rocks.urzahil.iptv.ui.guide.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rocks.urzahil.iptv.data.db.dao.ProgrammeLite
import rocks.urzahil.iptv.data.repo.EpgRequest

/** What a guide row needs to load its programmes. A channel without EPG mapping has [epgChannelId] = null. */
data class GuideRowKey(val channelId: Long, val epgChannelId: Long?, val shiftMin: Int)

/**
 * Programmes of one row, sorted by start. [span] is the loaded time span (null = nothing loaded yet).
 * [fallback] is true when the programmes come from the provider's short EPG instead of the database.
 */
data class RowData(val span: TimeRange?, val programmes: List<ProgrammeLite>, val fallback: Boolean = false) {
    companion object {
        val EMPTY = RowData(null, emptyList())
    }
}

data class LoadPlan(val ranges: List<TimeRange>, val reset: Boolean, val span: TimeRange)

object LoadPlanner {
    const val MAX_SPAN_MS = 48 * GuideBounds.HOUR_MS

    /**
     * What to query so that [wanted] is covered, given the already [loaded] span. Adjacent or overlapping requests
     * extend the span (trimmed to [maxSpanMs] on the far side); a disjoint request (a jump) replaces it.
     */
    fun plan(loaded: TimeRange?, wanted: TimeRange, maxSpanMs: Long = MAX_SPAN_MS): LoadPlan {
        if (loaded == null || wanted.startMs > loaded.endMs || wanted.endMs < loaded.startMs) {
            return LoadPlan(listOf(wanted), reset = true, span = wanted)
        }
        if (loaded.covers(wanted)) return LoadPlan(emptyList(), reset = false, span = loaded)
        val ranges = ArrayList<TimeRange>(2)
        if (wanted.startMs < loaded.startMs) ranges += TimeRange(wanted.startMs, loaded.startMs)
        if (wanted.endMs > loaded.endMs) ranges += TimeRange(loaded.endMs, wanted.endMs)
        var start = minOf(loaded.startMs, wanted.startMs)
        var end = maxOf(loaded.endMs, wanted.endMs)
        if (end - start > maxSpanMs && wanted.durationMs <= maxSpanMs) {
            // Keep the side that was just asked for.
            if (wanted.endMs > loaded.endMs) start = end - maxSpanMs else end = start + maxSpanMs
            start = minOf(start, wanted.startMs)
            end = maxOf(end, wanted.endMs)
        }
        return LoadPlan(ranges, reset = false, span = TimeRange(start, end))
    }

    /** Union of two sorted programme lists without duplicates, limited to programmes overlapping [span]. */
    fun merge(a: List<ProgrammeLite>, b: List<ProgrammeLite>, span: TimeRange): List<ProgrammeLite> {
        val byId = LinkedHashMap<Long, ProgrammeLite>(a.size + b.size)
        for (p in a) if (span.overlaps(p.startMs, p.endMs)) byId[p.id] = p
        for (p in b) if (span.overlaps(p.startMs, p.endMs)) byId[p.id] = p
        return byId.values.sortedBy { it.startMs }
    }
}

/**
 * Programme rows for the guide, loaded per visible channel window with one batched query per missing range.
 * Each channel has its own observable row, so new data re-renders only that row. Least recently used channels
 * are dropped beyond [maxChannels]. Not thread-safe: call from one thread (the main dispatcher).
 */
class ProgrammeCache(
    private val loader: suspend (requests: List<EpgRequest>, fromMs: Long, toMs: Long) -> Map<Long, List<ProgrammeLite>>,
    private val maxChannels: Int = 400,
) {
    private class Entry(var key: GuideRowKey?) {
        val flow = MutableStateFlow(RowData.EMPTY)
        var db: RowData = RowData.EMPTY
        var fallback: List<ProgrammeLite>? = null
    }

    private val rows = LinkedHashMap<Long, Entry>(64, 0.75f, true)
    private var generation = 0

    val size: Int get() = rows.size

    /** Observable row of a channel; starts empty and fills in once [ensure] has loaded it. */
    fun row(channelId: Long): StateFlow<RowData> = rows.getOrPut(channelId) { Entry(null) }.flow

    /**
     * Makes sure [wanted] is loaded for [keys]. Returns the channel ids whose database result has no programme
     * overlapping [wanted] (candidates for the provider fallback).
     */
    suspend fun ensure(keys: List<GuideRowKey>, wanted: TimeRange): List<Long> {
        if (wanted.isEmpty || keys.isEmpty()) return emptyList()
        val gen = generation
        // Group by the ranges to fetch, so the usual case (all rows miss the same range) is one batched query.
        val groups = LinkedHashMap<TimeRange, MutableList<Pair<GuideRowKey, LoadPlan>>>()
        val plans = HashMap<Long, LoadPlan>()
        for (k in keys) {
            val e = rows.getOrPut(k.channelId) { Entry(k) }
            if (e.key != k) { e.key = k; e.db = RowData.EMPTY; e.fallback = null }
            val plan = LoadPlanner.plan(e.db.span, wanted)
            plans[k.channelId] = plan
            for (r in plan.ranges) groups.getOrPut(r) { ArrayList() } += k to plan
        }
        val loaded = HashMap<Long, MutableList<ProgrammeLite>>()
        for ((range, members) in groups) {
            val requests = members.mapNotNull { (k, _) -> k.epgChannelId?.let { EpgRequest(k.channelId, it, k.shiftMin) } }
            val result = if (requests.isEmpty()) emptyMap() else loader(requests, range.startMs, range.endMs)
            for ((id, list) in result) loaded.getOrPut(id) { ArrayList() } += list
        }
        if (gen != generation) return emptyList() // invalidated while loading: the caller asks again
        val empty = ArrayList<Long>()
        for (k in keys) {
            val e = rows[k.channelId] ?: continue
            if (e.key != k) continue
            val plan = plans.getValue(k.channelId)
            if (plan.ranges.isNotEmpty()) {
                val base = if (plan.reset) emptyList() else e.db.programmes
                e.db = RowData(plan.span, LoadPlanner.merge(base, loaded[k.channelId].orEmpty(), plan.span))
                publish(e)
            }
            if (e.db.programmes.none { wanted.overlaps(it.startMs, it.endMs) }) empty += k.channelId
        }
        evict(keys)
        return empty
    }

    /** Programmes from the provider's short EPG, shown while the database has nothing for the row. */
    fun setFallback(channelId: Long, programmes: List<ProgrammeLite>) {
        val e = rows[channelId] ?: return
        e.fallback = programmes.sortedBy { it.startMs }
        publish(e)
    }

    /** Forgets what was loaded (after an EPG import); rows keep showing their old data until reloaded. */
    fun invalidate() {
        generation++
        for (e in rows.values) e.db = e.db.copy(span = null)
    }

    private fun publish(e: Entry) {
        val fb = e.fallback
        e.flow.value = if (e.db.programmes.isEmpty() && !fb.isNullOrEmpty()) RowData(e.db.span, fb, fallback = true) else e.db
    }

    private fun evict(keep: List<GuideRowKey>) {
        if (rows.size <= maxChannels) return
        val keepIds = keep.mapTo(HashSet()) { it.channelId }
        val it = rows.entries.iterator()
        while (rows.size > maxChannels && it.hasNext()) {
            if (it.next().key !in keepIds) it.remove()
        }
    }
}
