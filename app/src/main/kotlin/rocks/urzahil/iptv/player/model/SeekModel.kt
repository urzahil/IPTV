package rocks.urzahil.iptv.player.model

sealed interface SeekAction {
    data object GoLive : SeekAction
    /** Seek inside the already playing archive stream. */
    data class NativeSeek(val positionMs: Long) : SeekAction
    /** Open a new archive stream starting at [startMs] and ending at [endMs] (wall clock; may be in the future). */
    data class Retune(val startMs: Long, val endMs: Long) : SeekAction
    data class Rejected(val reason: String) : SeekAction
}

/** What the engine knows about the current stream. [originMs] is the wall time of position 0. */
data class EngineSeekInfo(val seekable: Boolean, val durationMs: Long?, val originMs: Long)

/** Turns a requested wall-clock target into an action. Pure. */
object SeekPlanner {
    /** Targets this close to now are treated as "go live". */
    const val LIVE_SNAP_MS = 15_000L
    /** The newest position the archive can be asked for. */
    const val EDGE_MARGIN_MS = 10_000L
    const val NATIVE_END_MARGIN_MS = 5_000L
    const val DEFAULT_SLOT_MS = 2 * 3_600_000L

    fun clamp(targetMs: Long, state: TimelineState): Long {
        val floor = state.seekFloorMs ?: state.window.startMs
        val ceiling = state.nowMs - EDGE_MARGIN_MS
        return if (floor > ceiling) ceiling else targetMs.coerceIn(floor, ceiling)
    }

    fun plan(targetMs: Long, state: TimelineState, guide: ProgrammeGuide, engine: EngineSeekInfo?): SeekAction {
        if (!state.catchupEnabled) return SeekAction.Rejected("No catchup on this channel")
        val target = clamp(targetMs, state)
        if (target >= state.nowMs - LIVE_SNAP_MS) return SeekAction.GoLive
        val dur = engine?.durationMs
        if (state.mode == PlayMode.CATCHUP && engine != null && engine.seekable && dur != null && dur > 0 &&
            target >= engine.originMs && target <= engine.originMs + dur - NATIVE_END_MARGIN_MS
        ) {
            return SeekAction.NativeSeek(target - engine.originMs)
        }
        return SeekAction.Retune(target, guide.at(target)?.endMs ?: (target + DEFAULT_SLOT_MS))
    }

    /** Target for "restart this programme". */
    fun restartTarget(state: TimelineState): Long = state.window.startMs

    /** Start of the previous programme (or the hour slot before), relative to the current window. */
    fun previousProgrammeStart(state: TimelineState, guide: ProgrammeGuide): Long =
        guide.before(state.window.startMs)?.startMs ?: (state.window.startMs - TimelineState.HOUR_MS)

    /** Start of the following programme (which [plan] turns into go-live when it is airing now). */
    fun nextProgrammeStart(state: TimelineState): Long = state.window.endMs
}

/**
 * Collects rapid skip taps into one pending target committed after a quiet period. Repeated taps grow the step:
 * x1 for the first three, x3 up to six, x12 beyond.
 */
class SkipAccumulator(private val quietMs: Long = QUIET_MS) {
    var pendingMs: Long? = null
        private set
    private var streak = 0
    private var lastTapMs = Long.MIN_VALUE

    /** [direction] is +1 or -1. [baseMs] is the position to start from when nothing is pending. */
    fun skip(baseMs: Long, stepMs: Long, direction: Int, realNowMs: Long): Long {
        streak = if (pendingMs != null && realNowMs - lastTapMs <= quietMs) streak + 1 else 0
        lastTapMs = realNowMs
        val scaled = stepMs * multiplier(streak)
        val next = (pendingMs ?: baseMs) + direction * scaled
        pendingMs = next
        return next
    }

    /** Takes the pending target and resets. */
    fun take(): Long? = pendingMs.also { pendingMs = null; streak = 0; lastTapMs = Long.MIN_VALUE }

    fun cancel() { take() }

    companion object {
        const val QUIET_MS = 700L

        fun multiplier(streak: Int): Int = when {
            streak < 3 -> 1
            streak < 6 -> 3
            else -> 12
        }
    }
}

/** Decisions about pause/resume and stream end. */
sealed interface ResumeAction {
    data object Continue : ResumeAction
    /** Re-open the archive at this wall time. */
    data class RetuneCatchup(val wallMs: Long) : ResumeAction
    data object ReprepareLive : ResumeAction
}

class PauseTracker {
    private var pausedAtRealMs: Long? = null
    private var wallAtPauseMs: Long = 0
    private var mode: PlayMode = PlayMode.LIVE

    fun onPause(realNowMs: Long, mode: PlayMode, wallPosMs: Long) {
        if (pausedAtRealMs != null) return
        pausedAtRealMs = realNowMs
        wallAtPauseMs = wallPosMs
        this.mode = mode
    }

    fun onResume(realNowMs: Long, catchupEnabled: Boolean): ResumeAction {
        val at = pausedAtRealMs ?: return ResumeAction.Continue
        pausedAtRealMs = null
        val paused = realNowMs - at
        return when (mode) {
            PlayMode.LIVE -> when {
                catchupEnabled && paused > LIVE_PAUSE_LIMIT_MS -> ResumeAction.RetuneCatchup(wallAtPauseMs)
                !catchupEnabled && paused > LIVE_NO_CATCHUP_LIMIT_MS -> ResumeAction.ReprepareLive
                else -> ResumeAction.Continue
            }
            PlayMode.CATCHUP -> if (paused > ARCHIVE_PAUSE_LIMIT_MS) ResumeAction.RetuneCatchup(wallAtPauseMs) else ResumeAction.Continue
        }
    }

    fun clear() { pausedAtRealMs = null }

    companion object {
        const val LIVE_PAUSE_LIMIT_MS = 10_000L
        const val LIVE_NO_CATCHUP_LIMIT_MS = 30_000L
        const val ARCHIVE_PAUSE_LIMIT_MS = 120_000L
    }
}

sealed interface EndedAction {
    data object Reconnect : EndedAction
    data object GoLive : EndedAction
    data class Retune(val startMs: Long, val endMs: Long) : EndedAction
}

object EndPolicy {
    const val LIVE_SNAP_MS = 30_000L

    /** [wallMs] is where the stream ended. */
    fun onEnded(mode: PlayMode, wallMs: Long, nowMs: Long, guide: ProgrammeGuide): EndedAction = when (mode) {
        PlayMode.LIVE -> EndedAction.Reconnect
        PlayMode.CATCHUP ->
            if (wallMs >= nowMs - LIVE_SNAP_MS) EndedAction.GoLive
            else EndedAction.Retune(wallMs, guide.at(wallMs)?.endMs ?: (wallMs + SeekPlanner.DEFAULT_SLOT_MS))
    }
}
