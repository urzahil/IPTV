package rocks.urzahil.iptv.ui.guide

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import rocks.urzahil.iptv.ui.guide.model.GuideAxis
import rocks.urzahil.iptv.ui.guide.model.GuideWindows
import rocks.urzahil.iptv.ui.guide.model.TimeRange

/**
 * Horizontal (time) scroll position shared by the ruler, every row and the now line.
 *
 * [scrollPx] changes on every frame of a drag: read it only in layout or draw lambdas. Composition should read
 * the stepped [cellWindow] / [queryRange] / [leftDayStartMs] instead, which change a few times per screen width.
 */
@Stable
class GuideScrollState(axis: GuideAxis, initialLeftMs: Long) {
    var axis by mutableStateOf(axis)
        private set

    var scrollPx by mutableFloatStateOf(axis.scrollFor(initialLeftMs, 0f))
        private set

    /** Width of the programme area. */
    var viewportPx by mutableIntStateOf(0)
        private set

    val scrollable = ScrollableState { delta ->
        val a = this.axis
        val target = a.clampScroll(scrollPx - delta, viewportPx.toFloat())
        val consumed = scrollPx - target
        scrollPx = target
        consumed
    }

    private val visible: TimeRange get() = axis.visible(scrollPx, viewportPx.toFloat())

    /** Span whose cells are composed. */
    val cellWindow: TimeRange by derivedStateOf { GuideWindows.cellWindow(visible, this.axis.bounds) }

    /** Span that must be loaded. */
    val queryRange: TimeRange by derivedStateOf { GuideWindows.queryRange(visible, this.axis.bounds) }

    val leftEdgeMs: Long get() = axis.timeAt(scrollPx)

    fun updateViewport(widthPx: Int) {
        if (widthPx == viewportPx) return
        viewportPx = widthPx
        scrollPx = axis.clampScroll(scrollPx, widthPx.toFloat())
    }

    /** New bounds or scale: keeps the same time at the left edge. */
    fun updateAxis(new: GuideAxis) {
        val old = axis
        if (old.bounds == new.bounds && old.pxPerMinute == new.pxPerMinute) return
        scrollPx = new.rebase(scrollPx, old, viewportPx.toFloat())
        axis = new
    }

    /** Puts [timeMs] at the left edge. Short distances animate; long ones jump so no data is loaded on the way. */
    suspend fun scrollToTime(timeMs: Long) {
        val delta = axis.scrollFor(timeMs, viewportPx.toFloat()) - scrollPx
        if (delta == 0f) return
        if (abs(delta) > viewportPx * JUMP_VIEWPORTS) scrollable.scrollBy(-delta) else scrollable.animateScrollBy(-delta)
    }

    private companion object {
        const val JUMP_VIEWPORTS = 3
    }
}
