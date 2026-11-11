package rocks.urzahil.iptv.player

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import rocks.urzahil.iptv.core.catchup.TimeshiftStyle
import rocks.urzahil.iptv.core.catchup.UnsupportedCatchup
import rocks.urzahil.iptv.core.epg.ProgrammeId
import rocks.urzahil.iptv.core.model.CatchupType
import rocks.urzahil.iptv.core.xtream.XtreamParser
import rocks.urzahil.iptv.core.xtream.XtreamUrls
import rocks.urzahil.iptv.data.db.dao.SourceDao
import rocks.urzahil.iptv.data.db.entities.ChannelEntity
import rocks.urzahil.iptv.data.db.entities.ProgrammeDetailsEntity
import rocks.urzahil.iptv.data.db.entities.ProgrammeEntity
import rocks.urzahil.iptv.data.repo.ChannelPrefsRepository
import rocks.urzahil.iptv.data.repo.ChannelRepository
import rocks.urzahil.iptv.data.repo.ChannelStreamInputs
import rocks.urzahil.iptv.data.repo.EpgRepository
import rocks.urzahil.iptv.data.repo.StreamInputsRepository
import rocks.urzahil.iptv.data.repo.ZapDirection
import rocks.urzahil.iptv.data.settings.AppSettings
import rocks.urzahil.iptv.data.settings.SettingsRepository
import rocks.urzahil.iptv.player.engine.EngineConfig
import rocks.urzahil.iptv.player.engine.EngineFactory
import rocks.urzahil.iptv.player.engine.PlayerEngine
import rocks.urzahil.iptv.player.engine.StreamTypeProbe
import rocks.urzahil.iptv.player.model.AspectMode
import rocks.urzahil.iptv.player.model.CatchupAttemptPlanner
import rocks.urzahil.iptv.player.model.CatchupRequests
import rocks.urzahil.iptv.player.model.EndPolicy
import rocks.urzahil.iptv.player.model.EndedAction
import rocks.urzahil.iptv.player.model.EngineKind
import rocks.urzahil.iptv.player.model.EnginePreference
import rocks.urzahil.iptv.player.model.EngineSeekInfo
import rocks.urzahil.iptv.player.model.EngineSelector
import rocks.urzahil.iptv.player.model.EngineState
import rocks.urzahil.iptv.player.model.FailureKind
import rocks.urzahil.iptv.player.model.FallbackAction
import rocks.urzahil.iptv.player.model.FallbackPolicy
import rocks.urzahil.iptv.player.model.PauseTracker
import rocks.urzahil.iptv.player.model.PlayMode
import rocks.urzahil.iptv.player.model.PlayerFailure
import rocks.urzahil.iptv.player.model.ProgrammeGuide
import rocks.urzahil.iptv.player.model.ProgrammeSlot
import rocks.urzahil.iptv.player.model.ResumeAction
import rocks.urzahil.iptv.player.model.RetryPolicy
import rocks.urzahil.iptv.player.model.SeekAction
import rocks.urzahil.iptv.player.model.SeekPlanner
import rocks.urzahil.iptv.player.model.SkipAccumulator
import rocks.urzahil.iptv.player.model.StreamRequest
import rocks.urzahil.iptv.player.model.TimelineState
import rocks.urzahil.iptv.core.util.Redactor
import rocks.urzahil.iptv.util.AppLog

/** What to play. Ids and times only: never URLs. */
data class PlayRequest(
    val channelId: Long,
    val categoryId: Long? = null,
    /** Wall-clock start (epoch ms, tvg-shift applied) of a past programme to play. */
    val catchupStartMs: Long? = null,
    val catchupEndMs: Long? = null,
    /** Programme row id (see ProgrammeId); resolved to start/end by the controller. */
    val catchupProgrammeId: Long? = null,
)

/**
 * App-scoped playback state machine. It owns the engine, so the player screen can be recreated, go to PiP, or
 * hand over to the background-audio service without interrupting playback. All entry points are main-thread.
 *
 * The decision logic lives in [rocks.urzahil.iptv.player.model]; this class wires it to the engines, the
 * database and the clock.
 */
class PlaybackController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val streamInputs: StreamInputsRepository,
    private val channels: ChannelRepository,
    private val epg: EpgRepository,
    private val prefs: ChannelPrefsRepository,
    private val sources: SourceDao,
    private val settings: SettingsRepository,
    private val http: OkHttpClient,
    private val redactor: Redactor,
    private val engineFactory: EngineFactory,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _ui = MutableStateFlow(PlayerUiState())
    val ui: StateFlow<PlayerUiState> = _ui.asStateFlow()

    private val _engine = MutableStateFlow<PlayerEngine?>(null)
    val engine: StateFlow<PlayerEngine?> = _engine.asStateFlow()

    private val _guide = MutableStateFlow(ProgrammeGuide.EMPTY)
    /** Programmes of the current channel around the catchup window; for the programme sheet. */
    val guide: StateFlow<ProgrammeGuide> = _guide.asStateFlow()

    private val _backgroundHold = MutableStateFlow(false)
    /** True while the app is in the background and audio keeps playing (the service shows a notification). */
    val backgroundHold: StateFlow<Boolean> = _backgroundHold.asStateFlow()

    /** Everything about the channel being played. Contains the live URL (credentials): memory only. */
    private class Session(
        val inputs: ChannelStreamInputs,
        val categoryId: Long?,
        val info: ChannelInfo,
        var kind: EngineKind,
        var channelPref: EnginePreference,
        var globalPref: EnginePreference,
        var fallback: FallbackPolicy,
        val retry: RetryPolicy,
    ) {
        var mode = PlayMode.LIVE
        var originMs = 0L
        var pendingInitialSeekMs = 0L
        var planner: CatchupAttemptPlanner? = null
        var lastWallMs = 0L
        var pausedWallMs: Long? = null
        var readyHandled = false
        var readySinceRealMs = 0L
        var guideLoadedMs = 0L
        var pendingLanguages: Pair<String?, String?> = Pair(null, null)
        var endedWallMs = Long.MIN_VALUE
        var endedRepeats = 0
        val catchupEnabled: Boolean get() = inputs.catchup.enabled
    }

    private var session: Session? = null
    private var opJob: Job? = null
    private var engineJob: Job? = null
    private var tickerJob: Job? = null
    private var commitJob: Job? = null
    private var trackCheckJob: Job? = null
    private var noticeJob: Job? = null
    private val _uiVisible = MutableStateFlow(false)
    /** True between onUiStarted and onUiStopped (PiP counts as visible). */
    val uiVisible: StateFlow<Boolean> = _uiVisible.asStateFlow()
    private var multicastLock: WifiManager.MulticastLock? = null
    private val pauseTracker = PauseTracker()
    private val skips = SkipAccumulator()
    private var lastPlayedChannelId: Long? = null

    val currentChannelId: Long? get() = session?.info?.id
    val currentCategoryId: Long? get() = session?.categoryId

    // ------------------------------------------------------------------ entry points

    /** Starts (or restarts) playback. Cheap to call again for the channel that is already playing. */
    fun play(request: PlayRequest) {
        launchOp { start(request) }
    }

    /** True when [channelId] is the channel being played and nothing else was requested. */
    fun isPlaying(channelId: Long): Boolean = session?.info?.id == channelId && _ui.value.phase != Phase.IDLE

    fun zap(direction: ZapDirection) {
        val s = session ?: return
        launchOp {
            val next = channels.neighbour(s.info.id, s.categoryId, direction) ?: return@launchOp
            channels.markWatched(next)
            start(PlayRequest(next, s.categoryId))
        }
    }

    fun togglePlayPause() {
        val e = _engine.value ?: return
        if (e.playing.value) e.pause() else e.play()
    }

    fun goLive() {
        val s = session ?: return
        skips.cancel(); setPending(null)
        launchOp { openLive(s) }
    }

    /** Seeks to a wall-clock time (epoch ms). Chooses between a native seek, a new archive stream and live. */
    fun seekToWall(targetMs: Long) {
        val s = session ?: return
        skips.cancel(); setPending(null)
        launchOp { performSeek(s, targetMs) }
    }

    /** Relative skip with accumulation: repeated taps add up and commit after a quiet moment. */
    fun skip(stepMs: Long, direction: Int) {
        val s = session ?: return
        if (!s.catchupEnabled) return
        val base = currentWall(s)
        val target = skips.skip(base, stepMs, direction, clock())
        setPending(target)
        commitJob?.cancel()
        commitJob = scope.launch {
            delay(SkipAccumulator.QUIET_MS)
            val t = skips.take() ?: return@launch
            setPending(null)
            launchOp { performSeek(s, t) }
        }
    }

    fun restartProgramme() {
        val s = session ?: return
        val tl = timeline(s)
        seekToWall(SeekPlanner.restartTarget(tl))
    }

    fun previousProgramme() {
        val s = session ?: return
        seekToWall(SeekPlanner.previousProgrammeStart(timeline(s), _guide.value))
    }

    fun nextProgramme() {
        val s = session ?: return
        seekToWall(SeekPlanner.nextProgrammeStart(timeline(s)))
    }

    /** Plays a programme from the list: live when it is airing now (or [fromStart] = false), else the archive. */
    fun playProgramme(slot: ProgrammeSlot, fromStart: Boolean) {
        val s = session ?: return
        val now = clock()
        if (!fromStart && slot.startMs <= now && slot.endMs > now) { goLive(); return }
        skips.cancel(); setPending(null)
        launchOp {
            if (!s.catchupEnabled) { notice("Catchup is not available for this channel"); return@launchOp }
            val floor = now - s.inputs.catchup.days * TimelineState.DAY_MS
            if (slot.startMs < floor) { notice("That programme is older than the catchup window"); return@launchOp }
            openCatchup(s, slot.startMs, slot.endMs)
        }
    }

    suspend fun programmeDetails(slot: ProgrammeSlot): ProgrammeDetailsEntity? = slot.id?.let { epg.details(it) }

    fun retry() {
        val s = session ?: return
        s.retry.reset()
        launchOp { reopenAtCurrent(s) }
    }

    /** Sets the channel's engine (remembered). AUTO clears the override and the remembered automatic fallback. */
    fun setChannelEngine(pref: EnginePreference) {
        val s = session ?: return
        launchOp {
            prefs.setEngine(s.info.id, pref)
            s.channelPref = pref
            val choice = EngineSelector.choose(
                s.inputs.liveUrl.substringBefore("://", "http"), s.inputs.headers.extra.isNotEmpty(),
                s.globalPref, pref, null,
            )
            s.fallback = FallbackPolicy(automatic = !choice.forced)
            s.retry.reset()
            s.kind = choice.kind
            _ui.update { it.copy(channelEngine = pref) }
            reopenAtCurrent(s)
        }
    }

    fun cycleAspect() {
        val e = _engine.value ?: return
        val next = _ui.value.aspect.next()
        e.setAspect(next)
        _ui.update { it.copy(aspect = next) }
        session?.let { s -> scope.launch { prefs.setAspect(s.info.id, next) } }
    }

    fun selectAudio(id: String?) {
        val e = _engine.value ?: return
        e.selectAudio(id)
        val lang = _ui.value.tracks.audio.firstOrNull { it.id == id }?.language
        session?.let { s -> scope.launch { prefs.setAudioLang(s.info.id, if (id == null) null else lang) } }
    }

    fun selectText(id: String?) {
        val e = _engine.value ?: return
        e.selectText(id)
        val lang = _ui.value.tracks.text.firstOrNull { it.id == id }?.language
        session?.let { s -> scope.launch { prefs.setTextLang(s.info.id, if (id == null) null else lang) } }
    }

    fun selectVideo(id: String?) { _engine.value?.selectVideo(id) }

    fun dismissNotice() { _ui.update { it.copy(notice = null) } }

    // ------------------------------------------------------------------ lifecycle (called by the activity)

    /** The player screen is visible. Resumes a suspended session. */
    fun onUiStarted() {
        _uiVisible.value = true
        _backgroundHold.value = false
        val s = session ?: return
        if (_ui.value.phase == Phase.SUSPENDED) {
            s.retry.reset()
            launchOp { reopenAtCurrent(s) }
        }
    }

    /**
     * The player screen left the foreground (not for PiP). With [keepAudio] and a playing Media3 engine the audio
     * continues (the service shows the notification); otherwise playback is released and resumes on return.
     */
    fun onUiStopped(keepAudio: Boolean) {
        _uiVisible.value = false
        val s = session ?: return
        val e = _engine.value
        if (keepAudio && e != null && e.kind == EngineKind.MEDIA3 && e.playing.value && _ui.value.phase.let { it == Phase.PLAYING || it == Phase.BUFFERING }) {
            _backgroundHold.value = true
            return
        }
        opJob?.cancel()
        s.lastWallMs = currentWall(s)
        releaseEngine()
        _ui.update { it.copy(phase = Phase.SUSPENDED, playing = false, pendingSeekMs = null) }
    }

    /** The player screen was closed by the user: stop everything. */
    fun stopAll() {
        opJob?.cancel(); commitJob?.cancel(); trackCheckJob?.cancel(); tickerJob?.cancel(); tickerJob = null
        skips.cancel()
        releaseEngine()
        session = null
        _backgroundHold.value = false
        _guide.value = ProgrammeGuide.EMPTY
        _ui.value = PlayerUiState()
    }

    /** Current wall-clock position, for saving across process death (the activity stores it as a number). */
    fun wallPositionMs(): Long? = session?.let { if (it.mode == PlayMode.CATCHUP) currentWall(it) else null }

    // ------------------------------------------------------------------ operations

    private fun launchOp(block: suspend () -> Unit) {
        opJob?.cancel()
        opJob = scope.launch { block() }
    }

    private suspend fun start(r: PlayRequest) {
        commitJob?.cancel(); skips.cancel(); trackCheckJob?.cancel()
        _ui.update {
            PlayerUiState(
                channel = it.channel?.takeIf { c -> c.id == r.channelId }, phase = Phase.LOADING, aspect = it.aspect,
            )
        }
        val inputs = streamInputs.resolve(r.channelId)
        if (inputs == null) {
            fail(null, ErrorKind.NOT_FOUND, "Channel not available", "It may have been removed by the last playlist update.")
            return
        }
        val app = settings.current()
        val pref = prefs.get(r.channelId)
        val channelPref = EnginePreference.parse(pref?.engine)
        val remembered = pref?.autoEngine?.let { n -> EngineKind.entries.firstOrNull { it.name == n } }
        val now = clock()
        val choice = EngineSelector.choose(
            inputs.liveUrl.substringBefore("://", "http"), inputs.headers.extra.isNotEmpty(), app.engine, channelPref,
            remembered, now - (pref?.autoEngineSetMs ?: 0),
        )
        val ch = inputs.channel
        val info = ChannelInfo(ch.id, ch.name, ch.number, ch.logoUrl, ch.isRadio, if (inputs.catchup.enabled) inputs.catchup.days else 0)
        val s = Session(inputs, r.categoryId, info, choice.kind, channelPref, app.engine, FallbackPolicy(!choice.forced), RetryPolicy(app.retryAttempts))
        session = s
        lastPlayedChannelId = ch.id
        loadGuide(s)
        val aspect = AspectMode.parse(pref?.aspectMode)
        _ui.update { it.copy(channel = info, channelEngine = channelPref, aspect = aspect, engine = s.kind) }
        s.pendingLanguages = Pair(pref?.audioLang, pref?.textLang)
        applyLanguages(s, app)

        // Resolve what to open.
        var startMs = r.catchupStartMs
        var endMs = r.catchupEndMs
        if (startMs == null && r.catchupProgrammeId != null) {
            val row = r.catchupProgrammeId.takeIf { ProgrammeId.epgChannelOf(it) == ch.epgChannelId }
                ?.let { epg.programme(it, ch.shiftMin) }
            if (row != null) { startMs = row.startMs; endMs = row.endMs }
        }
        ensureTicker()
        if (startMs != null && s.catchupEnabled && startMs < now - SeekPlanner.LIVE_SNAP_MS) {
            val floor = now - s.inputs.catchup.days * TimelineState.DAY_MS
            openCatchup(s, startMs.coerceAtLeast(floor), endMs ?: (startMs + SeekPlanner.DEFAULT_SLOT_MS))
        } else {
            if (startMs != null && !s.catchupEnabled) notice("Catchup is not available for this channel")
            openLive(s)
        }
    }

    private fun applyLanguages(s: Session, app: AppSettings) {
        val (a, t) = s.pendingLanguages
        _engine.value?.setLanguages(a ?: app.preferredAudioLanguage, t ?: app.preferredTextLanguage)
    }

    private suspend fun loadGuide(s: Session) {
        val ch = s.inputs.channel
        val epgId = ch.epgChannelId
        val now = clock()
        s.guideLoadedMs = now
        if (epgId == null) { _guide.value = ProgrammeGuide.EMPTY; return }
        val from = now - (if (s.catchupEnabled) s.inputs.catchup.days * TimelineState.DAY_MS else ProgrammeId.MAX_PROG_MS)
        val rows = try {
            epg.programmes(epgId, ch.shiftMin, from, now + TimelineState.DAY_MS)
        } catch (e: Exception) {
            AppLog.w(TAG, "guide load failed", e); emptyList()
        }
        _guide.value = ProgrammeGuide(rows.map { ProgrammeSlot(it.id, it.startMs, it.endMs, it.title, it.flags and ProgrammeEntity.FLAG_CATCHUP_ID != 0) })
    }

    private suspend fun openLive(s: Session) {
        s.mode = PlayMode.LIVE
        s.planner = null
        s.originMs = 0
        s.pendingInitialSeekMs = 0
        s.pausedWallMs = null
        val p = _guide.value.at(clock())
        val request = streamRequest(s, s.inputs.liveUrl, live = true, subtitle = p?.title)
        _ui.update { it.copy(mode = PlayMode.LIVE, error = null, reconnect = null) }
        prepareOn(s, request)
    }

    private suspend fun openCatchup(s: Session, startMs: Long, endMs: Long) {
        if (!s.catchupEnabled) { notice("Catchup is not available for this channel"); if (_engine.value == null) openLive(s); return }
        val now = clock()
        val planner = CatchupAttemptPlanner(
            endMs / 1000, now / 1000, s.inputs.catchup.type == CatchupType.XTREAM_API,
            s.inputs.xtreamTarget?.style ?: TimeshiftStyle.PATH,
        )
        s.planner = planner
        s.lastWallMs = startMs
        buildAndPrepareCatchup(s, startMs)
    }

    private suspend fun buildAndPrepareCatchup(s: Session, startMs: Long) {
        val planner = s.planner ?: return
        val now = clock()
        val slot = _guide.value.at(startMs)
        val catchupId = if (slot?.hasCatchupId == true) slot.id?.let { epg.details(it)?.catchupId } else null
        val built = try {
            CatchupRequests.build(s.inputs.liveUrl, s.inputs.catchup, s.inputs.xtreamTarget, startMs, now, planner.current, catchupId)
        } catch (e: UnsupportedCatchup) {
            notice("Catchup is not available for this channel")
            if (s.mode == PlayMode.CATCHUP || _engine.value == null) openLive(s)
            return
        }
        s.mode = PlayMode.CATCHUP
        s.originMs = built.streamOriginSec * 1000
        s.pendingInitialSeekMs = built.initialSeekMs
        s.pausedWallMs = null
        val request = streamRequest(s, built.url, live = false, subtitle = slot?.title)
        _ui.update { it.copy(mode = PlayMode.CATCHUP, error = null, reconnect = null) }
        prepareOn(s, request)
    }

    private fun streamRequest(s: Session, url: String, live: Boolean, subtitle: String?): StreamRequest {
        val h = s.inputs.headers
        return StreamRequest(
            url = url, userAgent = h.userAgent, referrer = h.referrer, headers = h.toMap(),
            mime = s.inputs.mimeHint, live = live, title = s.info.name, subtitle = subtitle,
        )
    }

    private suspend fun prepareOn(s: Session, request: StreamRequest) {
        val e = ensureEngine(s)
        var r = request
        if (s.kind == EngineKind.MEDIA3 && r.mime == null && StreamTypeProbe.shouldProbe(r.url)) {
            StreamTypeProbe.probe(http, r.url, r.headers, redactor)?.let { kind ->
                StreamTypeProbe.mimeOf(kind)?.let { m ->
                    r = StreamRequest(r.url, r.userAgent, r.referrer, r.headers, m, r.live, r.title, r.subtitle)
                }
            }
        }
        setMulticast(r.scheme == "udp" || r.scheme == "rtp")
        pauseTracker.clear()
        s.readyHandled = false
        trackCheckJob?.cancel()
        _ui.update { it.copy(phase = if (it.phase == Phase.RECONNECTING) Phase.RECONNECTING else Phase.LOADING, engine = e.kind, error = null) }
        AppLog.d(TAG, "prepare ${e.kind} live=${r.live} scheme=${r.scheme}")
        e.prepare(r, 0)
    }

    private suspend fun ensureEngine(s: Session): PlayerEngine {
        _engine.value?.let { if (it.kind == s.kind) return it }
        releaseEngine()
        val app = settings.current()
        val config = EngineConfig(
            app.bufferProfile, app.audioPassthrough, app.deinterlace,
            s.pendingLanguages.first ?: app.preferredAudioLanguage, s.pendingLanguages.second ?: app.preferredTextLanguage,
        )
        val e = try {
            engineFactory.create(s.kind, config)
        } catch (t: Throwable) {
            // e.g. libVLC native libraries missing for this ABI.
            AppLog.w(TAG, "engine ${s.kind} unavailable", t)
            if (s.kind == EngineKind.VLC) {
                s.kind = EngineKind.MEDIA3
                notice("The VLC player is not available on this device")
                engineFactory.create(EngineKind.MEDIA3, config)
            } else throw t
        }
        s.kind = e.kind
        e.setAspect(_ui.value.aspect)
        _engine.value = e
        attach(e)
        return e
    }

    private fun attach(e: PlayerEngine) {
        engineJob?.cancel()
        engineJob = scope.launch {
            launch { e.state.collect { st -> if (_engine.value === e) onEngineState(e, st) } }
            launch { e.playing.collect { p -> if (_engine.value === e) onPlaying(p) } }
            launch { e.tracks.collect { t -> if (_engine.value === e) _ui.update { it.copy(tracks = t) } } }
            launch { e.videoSize.collect { v -> if (_engine.value === e) _ui.update { it.copy(videoAspect = v?.aspect) } } }
        }
    }

    private fun releaseEngine() {
        engineJob?.cancel(); engineJob = null
        trackCheckJob?.cancel()
        val e = _engine.value
        _engine.value = null
        pauseTracker.clear()
        setMulticast(false)
        if (e != null) try { e.release() } catch (t: Throwable) { AppLog.w(TAG, "release failed", t) }
    }

    // ------------------------------------------------------------------ engine events

    private fun onEngineState(e: PlayerEngine, st: EngineState) {
        val s = session ?: return
        when (st) {
            EngineState.Idle -> Unit
            EngineState.Buffering -> _ui.update {
                if (it.phase == Phase.RECONNECTING || it.phase == Phase.ERROR || it.phase == Phase.SUSPENDED) it else it.copy(phase = Phase.BUFFERING)
            }
            EngineState.Ready -> onReady(e, s)
            EngineState.Ended -> launchOp { handleEnded(s) }
            is EngineState.Error -> launchOp { handleFailure(s, e.kind, st.failure) }
        }
    }

    private fun onReady(e: PlayerEngine, s: Session) {
        _ui.update { it.copy(phase = if (e.playing.value) Phase.PLAYING else Phase.PAUSED, reconnect = null, playing = e.playing.value) }
        if (s.readyHandled) return
        s.readyHandled = true
        s.readySinceRealMs = clock()
        if (s.pendingInitialSeekMs > 0) {
            if (e.isSeekable()) e.seekTo(s.pendingInitialSeekMs)
            s.pendingInitialSeekMs = 0
        }
        s.planner?.styleToRemember()?.let { style ->
            scope.launch(Dispatchers.IO) { sources.setTimeshiftStyle(s.inputs.source.id, style.name.lowercase()) }
        }
        trackCheckJob?.cancel()
        trackCheckJob = scope.launch {
            delay(TRACK_CHECK_DELAY_MS)
            val cur = _engine.value ?: return@launch
            val action = s.fallback.onTracksSettled(cur.kind, cur.tracks.value.groups)
            if (action is FallbackAction.SwitchToVlc) launchOp { switchToVlc(s, action.reason) }
        }
    }

    private fun onPlaying(p: Boolean) {
        val s = session ?: return
        _ui.update { it.copy(playing = p, phase = if (it.phase == Phase.PLAYING && !p) Phase.PAUSED else if (it.phase == Phase.PAUSED && p) Phase.PLAYING else it.phase) }
        val now = clock()
        if (!p) {
            val wall = currentWall(s)
            if (s.mode == PlayMode.LIVE) s.pausedWallMs = now
            pauseTracker.onPause(now, s.mode, wall)
        } else {
            s.pausedWallMs = null
            when (val a = pauseTracker.onResume(now, s.catchupEnabled)) {
                ResumeAction.Continue -> Unit
                is ResumeAction.RetuneCatchup -> launchOp { openCatchup(s, a.wallMs, endAt(s, a.wallMs)) }
                ResumeAction.ReprepareLive -> launchOp { openLive(s) }
            }
        }
    }

    private suspend fun handleEnded(s: Session) {
        val wall = currentWall(s)
        // Guard against a server that ends every stream immediately.
        if (kotlin.math.abs(wall - s.endedWallMs) < 2_000) s.endedRepeats++ else s.endedRepeats = 0
        s.endedWallMs = wall
        if (s.endedRepeats >= 3) {
            fail(s, ErrorKind.GENERIC, "The stream keeps ending", "The provider closes it right away.")
            return
        }
        when (val a = EndPolicy.onEnded(s.mode, wall, clock(), _guide.value)) {
            EndedAction.Reconnect -> retryOrFail(s, PlayerFailure(FailureKind.NETWORK, message = "Stream ended"), cap = null)
            EndedAction.GoLive -> openLive(s)
            is EndedAction.Retune -> openCatchup(s, a.startMs, a.endMs)
        }
    }

    private suspend fun handleFailure(s: Session, kind: EngineKind, f: PlayerFailure) {
        AppLog.d(TAG, "failure ${f.kind} code=${f.code} http=${f.httpStatus}")
        when (val fb = s.fallback.onFailure(kind, f)) {
            is FallbackAction.SwitchToVlc -> { switchToVlc(s, fb.reason); return }
            FallbackAction.None -> Unit
        }
        val retryable = f.kind == FailureKind.HTTP_CLIENT_ERROR || f.kind == FailureKind.OPEN_FAILED
        // Archive requests: try the next variant (end = now, other URL style) before giving up.
        if (s.mode == PlayMode.CATCHUP && retryable) {
            val planner = s.planner
            if (planner != null && planner.advance() != null) {
                buildAndPrepareCatchup(s, s.lastWallMs.takeIf { it > 0 } ?: currentWall(s))
                return
            }
            fail(s, ErrorKind.CATCHUP_UNAVAILABLE, "Catchup unavailable", "The provider refused this programme. It may be too old or not recorded.", f)
            return
        }
        when {
            f.kind == FailureKind.HTTP_CLIENT_ERROR && s.mode == PlayMode.LIVE -> {
                val note = diagnose(s)
                _ui.update { it.copy(phase = Phase.ERROR, error = ErrorMessages.describe(f, s.mode, kind, note), reconnect = null) }
            }
            f.kind == FailureKind.OPEN_FAILED -> retryOrFail(s, f, cap = 2)
            RetryPolicy.isRetryable(f) -> retryOrFail(s, f, cap = null)
            else -> _ui.update { it.copy(phase = Phase.ERROR, error = ErrorMessages.describe(f, s.mode, kind), reconnect = null) }
        }
    }

    private suspend fun retryOrFail(s: Session, f: PlayerFailure, cap: Int?) {
        val kind = s.kind
        if (cap != null && s.retry.attempt >= cap) {
            _ui.update { it.copy(phase = Phase.ERROR, error = ErrorMessages.describe(f, s.mode, kind), reconnect = null) }
            return
        }
        val delayMs = s.retry.nextDelayMs()
        if (delayMs == null) {
            _ui.update { it.copy(phase = Phase.ERROR, error = ErrorMessages.describe(f, s.mode, kind), reconnect = null) }
            return
        }
        s.lastWallMs = currentWall(s)
        _ui.update { it.copy(phase = Phase.RECONNECTING, reconnect = s.retry.attempt to s.retry.maxAttempts) }
        delay(if (f.kind == FailureKind.BEHIND_LIVE_WINDOW) 0 else delayMs)
        reopenAtCurrent(s)
    }

    private suspend fun switchToVlc(s: Session, reason: String) {
        AppLog.d(TAG, "falling back to VLC: $reason")
        s.kind = EngineKind.VLC
        s.lastWallMs = currentWall(s)
        scope.launch { prefs.rememberAutoEngine(s.info.id, EngineKind.VLC, clock()) }
        notice("Switched to the VLC player for this channel")
        reopenAtCurrent(s)
    }

    private suspend fun reopenAtCurrent(s: Session) {
        if (s.mode == PlayMode.LIVE) openLive(s)
        else {
            val wall = currentWall(s)
            openCatchup(s, wall, endAt(s, wall))
        }
    }

    private suspend fun performSeek(s: Session, targetMs: Long) {
        val e = _engine.value
        val tl = timeline(s)
        val info = e?.let { EngineSeekInfo(it.isSeekable(), it.durationMs(), s.originMs) }
        when (val a = SeekPlanner.plan(targetMs, tl, _guide.value, info)) {
            SeekAction.GoLive -> openLive(s)
            is SeekAction.NativeSeek -> {
                e?.seekTo(a.positionMs)
                s.lastWallMs = targetMs
            }
            is SeekAction.Retune -> { s.retry.reset(); openCatchup(s, a.startMs, a.endMs) }
            is SeekAction.Rejected -> notice(a.reason)
        }
    }

    private suspend fun diagnose(s: Session): String? {
        val t = s.inputs.xtreamTarget ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(XtreamUrls.account(t.base, t.user, t.pass)).build()
                val body = http.newCall(req).execute().use { it.body.string() }
                AccountDiagnosis.explain(XtreamParser.parseAuth(body).account, clock() / 1000)
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun fail(s: Session?, kind: ErrorKind, title: String, detail: String?, f: PlayerFailure? = null) {
        _ui.update { it.copy(phase = Phase.ERROR, error = ErrorInfo(kind, title, detail), reconnect = null) }
    }

    // ------------------------------------------------------------------ time

    private fun endAt(s: Session, wallMs: Long): Long = _guide.value.at(wallMs)?.endMs ?: (wallMs + SeekPlanner.DEFAULT_SLOT_MS)

    /** The real-world time of the picture on screen. */
    private fun currentWall(s: Session): Long {
        val now = clock()
        return when (s.mode) {
            PlayMode.LIVE -> s.pausedWallMs ?: now
            PlayMode.CATCHUP -> {
                val e = _engine.value
                val phase = _ui.value.phase
                if (e != null && (phase == Phase.PLAYING || phase == Phase.PAUSED)) s.originMs + e.positionMs() else s.lastWallMs.takeIf { it > 0 } ?: s.originMs
            }
        }
    }

    private fun timeline(s: Session): TimelineState {
        val now = clock()
        return TimelineState.of(s.mode, s.info.catchupDays, now, currentWall(s).coerceAtMost(now), _guide.value)
    }

    private fun ensureTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (true) {
                tick()
                delay(if (_uiVisible.value) 1_000 else 5_000)
            }
        }
    }

    private fun tick() {
        val s = session ?: return
        val phase = _ui.value.phase
        if (phase == Phase.SUSPENDED || phase == Phase.IDLE) return
        val tl = timeline(s)
        if (phase == Phase.PLAYING || phase == Phase.PAUSED) s.lastWallMs = tl.wallPosMs
        if (phase == Phase.PLAYING && s.readySinceRealMs > 0 && clock() - s.readySinceRealMs >= RetryPolicy.STABLE_MS) s.retry.reset()
        val next = _guide.value.after(tl.window.endMs)?.title
        _ui.update { it.copy(timeline = tl, nextProgrammeTitle = next) }
        if (clock() - s.guideLoadedMs > GUIDE_REFRESH_MS) scope.launch { loadGuide(s) }
    }

    private fun setPending(ms: Long?) { _ui.update { it.copy(pendingSeekMs = ms) } }

    private fun notice(text: String) {
        _ui.update { it.copy(notice = text) }
        noticeJob?.cancel()
        noticeJob = scope.launch { delay(4_000); _ui.update { it.copy(notice = null) } }
    }

    @Suppress("DEPRECATION")
    private fun setMulticast(on: Boolean) {
        try {
            if (on) {
                if (multicastLock == null) {
                    val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    multicastLock = wm?.createMulticastLock("iptv-multicast")?.apply { setReferenceCounted(false) }
                }
                multicastLock?.takeIf { !it.isHeld }?.acquire()
            } else {
                multicastLock?.takeIf { it.isHeld }?.release()
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "multicast lock failed", e)
        }
    }

    private companion object {
        const val TAG = "Playback"
        const val TRACK_CHECK_DELAY_MS = 2_000L
        const val GUIDE_REFRESH_MS = 5 * 60_000L
    }
}
