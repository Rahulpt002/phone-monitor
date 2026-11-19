package com.hotspotmonitor.agent.finder

import android.content.Context
import android.os.PowerManager
import com.hotspotmonitor.agent.core.AgentGraph
import com.hotspotmonitor.agent.core.Clock
import com.hotspotmonitor.agent.core.FinderEngine
import com.hotspotmonitor.agent.core.Ids
import com.hotspotmonitor.agent.db.FinderSpotEntity
import com.hotspotmonitor.agent.protocol.AgentJson
import com.hotspotmonitor.agent.protocol.EventKind
import com.hotspotmonitor.agent.protocol.FinderLive
import com.hotspotmonitor.agent.protocol.FinderLiveMsg
import com.hotspotmonitor.agent.protocol.FinderMode
import com.hotspotmonitor.agent.protocol.FinderProgress
import com.hotspotmonitor.agent.protocol.FinderSettings
import com.hotspotmonitor.agent.protocol.FinderSpot
import com.hotspotmonitor.agent.protocol.FinderSpotDeletedMsg
import com.hotspotmonitor.agent.protocol.FinderSpotMsg
import com.hotspotmonitor.agent.protocol.NetType
import com.hotspotmonitor.agent.protocol.RadioInfo
import com.hotspotmonitor.agent.protocol.ServingCell
import com.hotspotmonitor.agent.protocol.Severity
import com.hotspotmonitor.agent.protocol.Smoother
import com.hotspotmonitor.agent.protocol.SpeedRun
import com.hotspotmonitor.agent.protocol.SpeedSession
import com.hotspotmonitor.agent.protocol.jsonObjectOf
import com.hotspotmonitor.agent.protocol.rankSpots
import com.hotspotmonitor.agent.protocol.rawScore
import com.hotspotmonitor.agent.protocol.round1
import com.hotspotmonitor.agent.protocol.scoreInputs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.EnumSet
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Signal Finder (plan section 7).
 *
 * - Live values: while anyone needs them (Finder screen, walk mode, a test, a headless session or
 *   the Dashboard mirror) the radio runs at 1 Hz (TelemetryHub.setHighRateRadio) and [live] is
 *   rebuilt every second: smoothed score (Smoother over finder.smooth_s), raw score, measurements
 *   (SS-* for NR_SA), serving cell, cell switches in the last 5 minutes, test progress. With the
 *   mirror on, each value also goes to the Server as `finder_live`.
 * - Walk mode: metal-detector tone + spoken score every finder.speak_every_s, wake lock.
 * - Spot test, speed test, ranking, CSV export, baseline, delete, photo: see the methods.
 * - Headless (plan 7.3): long volume-key presses read through root (HeadlessKeyListener).
 *
 * Tests run one at a time in the service scope, so a test started from the screen survives
 * navigation and [stopFinder] can cancel a test started by a Dashboard command.
 */
class FinderEngineImpl(private val graph: AgentGraph) : FinderEngine {
    private enum class LiveConsumer { SCREEN, WALK, TEST, HEADLESS, MIRROR }

    private data class ProgressState(val label: String, val startedAtMs: Long, val totalS: Int, val estimated: Boolean)

    private val lock = Any()

    private val _mode = MutableStateFlow(FinderMode.IDLE)
    override val mode: StateFlow<FinderMode> = _mode.asStateFlow()

    private val _live = MutableStateFlow<FinderLive?>(null)
    override val live: StateFlow<FinderLive?> = _live.asStateFlow()

    private val _ui = MutableStateFlow(FinderUiState())

    /** Local screen state (walk, mute, headless, last result). */
    val ui: StateFlow<FinderUiState> = _ui.asStateFlow()

    override val spots: Flow<List<FinderSpot>> by lazy {
        graph.db.finderSpotDao().allFlow().map { rows -> rankSpots(rows.mapNotNull { decodeSpot(it.json) }) }
    }

    // Radio state, fed by the radioFlow collector.
    private val smoother = Smoother(DEFAULT_SMOOTH_MS)
    private val cellSwitches = CellSwitchCounter()

    @Volatile
    private var latestReading: RadioInfo? = null

    @Volatile
    private var latestReadingAtMs: Long = 0

    @Volatile
    private var progressState: ProgressState? = null

    // Lifecycle and activities; guarded by lock.
    private var serviceScope: CoroutineScope? = null
    private var radioJob: Job? = null
    private val consumers: EnumSet<LiveConsumer> = EnumSet.noneOf(LiveConsumer::class.java)
    private var liveJob: Job? = null
    private var liveGeneration = 0L
    private var highRateOn = false
    private var walking = false
    private var walkJob: Job? = null
    private var testJob: Deferred<*>? = null
    private var testKind: FinderMode? = null
    private var headlessSession = false
    private var headlessSessionJob: Job? = null

    @Volatile
    private var mirrorOn = false

    @Volatile
    private var muted = false

    private val testMutex = Mutex()
    private val spotMutex = Mutex()

    private val audioLazy = lazy { FinderAudio(graph.app, graph.log) }
    private val audio: FinderAudio by audioLazy
    private val speed: SpeedTester by lazy { SpeedTester(graph) }
    private val headless: HeadlessKeyListener by lazy {
        HeadlessKeyListener(graph, ::onHeadlessGesture, ::onHeadlessHold, ::onHeadlessStatus)
    }
    private val wakeLock: PowerManager.WakeLock? by lazy {
        runCatching {
            (graph.app.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply { setReferenceCounted(false) }
        }.getOrNull()
    }

    // --- lifecycle ----------------------------------------------------------------------------

    override fun start(scope: CoroutineScope) {
        refreshPrefs()
        synchronized(lock) {
            serviceScope = scope
            radioJob?.cancel()
            radioJob = scope.launch { collectRadio() }
            // The Finder screen may already be open: move its live loop into the service scope and
            // ask the (re)started telemetry hub for the high radio rate again.
            if (consumers.isNotEmpty()) {
                liveJob?.cancel()
                liveJob = null
                highRateOn = false
                ensureLiveLocked()
            }
        }
        if (headlessEnabled()) headless.start(scope)
    }

    override fun stop() {
        headless.stop()
        val test = synchronized(lock) {
            serviceScope = null
            radioJob?.cancel()
            radioJob = null
            stopWalkLocked()
            endHeadlessSessionLocked()
            mirrorOn = false
            consumers.retainAll(setOf(LiveConsumer.SCREEN))
            liveJob?.cancel()
            liveJob = null
            ensureLiveLocked()
            recomputeLocked()
            testJob
        }
        test?.cancel()
        if (audioLazy.isInitialized()) audio.shutdown()
        _ui.update { it.copy(mirror = false, headlessListening = false) }
    }

    // --- live values --------------------------------------------------------------------------

    private suspend fun collectRadio() {
        graph.telemetry.radioFlow.collect { reading ->
            try {
                onReading(reading)
            } catch (e: Exception) {
                graph.log.w(TAG, "Could not process a radio reading", e)
            }
        }
    }

    private fun onReading(reading: RadioInfo) {
        val now = graph.clock.elapsedMs()
        latestReading = reading
        latestReadingAtMs = now
        val finder = graph.settings.current.finder
        smoother.windowMs = smoothWindowMs(finder)
        reading.rawScore(finder.weights)?.let { smoother.add(it, now) }
        cellSwitches.observe(CellIdentity.of(reading), now)
    }

    /** The latest reading if it is younger than [maxAgeMs], else null. */
    private fun freshReading(nowMs: Long, maxAgeMs: Long = FRESH_READING_MS): RadioInfo? {
        val reading = latestReading ?: return null
        if (latestReadingAtMs == 0L) return null
        return if (nowMs - latestReadingAtMs <= maxAgeMs) reading else null
    }

    private fun setConsumer(consumer: LiveConsumer, on: Boolean) {
        synchronized(lock) {
            val changed = if (on) consumers.add(consumer) else consumers.remove(consumer)
            if (changed) ensureLiveLocked()
        }
    }

    // Caller holds lock.
    private fun ensureLiveLocked() {
        val wanted = consumers.isNotEmpty()
        if (wanted != highRateOn) {
            highRateOn = wanted
            try {
                graph.telemetry.setHighRateRadio(wanted)
            } catch (e: Exception) {
                graph.log.w(TAG, "Could not switch the radio rate", e)
            }
        }
        if (wanted) {
            if (liveJob?.isActive != true) {
                val generation = ++liveGeneration
                liveJob = workScopeLocked().launch { liveLoop(generation) }
            }
        } else {
            liveJob?.cancel()
            liveJob = null
            liveGeneration++
            _live.value = null
        }
    }

    private suspend fun liveLoop(generation: Long) {
        var lastMirrorAtMs = Long.MIN_VALUE / 2
        while (currentCoroutineContext().isActive) {
            val tickStart = graph.clock.elapsedMs()
            try {
                val value = buildLive(tickStart)
                val current = synchronized(lock) {
                    (generation == liveGeneration).also { if (it) _live.value = value }
                }
                if (!current) return
                if (mirrorOn) {
                    val everyMs = graph.settings.current.telemetry.mirrorS.coerceAtLeast(1) * 1000L
                    if (tickStart - lastMirrorAtMs >= everyMs - MIRROR_SLACK_MS) {
                        graph.sync.sendLive(FinderLiveMsg(value))
                        lastMirrorAtMs = tickStart
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                graph.log.w(TAG, "Live update failed", e)
            }
            val spent = graph.clock.elapsedMs() - tickStart
            delay((LIVE_TICK_MS - spent).coerceAtLeast(MIN_TICK_DELAY_MS))
        }
    }

    private fun buildLive(now: Long): FinderLive {
        val finder = graph.settings.current.finder
        smoother.windowMs = smoothWindowMs(finder)
        // Before the first fresh reading show the hub's last one, so the screen is not blank.
        val reading = freshReading(now) ?: if (latestReadingAtMs == 0L) graph.telemetry.radio.value else null
        val inputs = reading?.scoreInputs()
        val cell = reading?.let { SpotAccumulator.servingCellOf(it) }
        val progress = progressState?.let { p ->
            val elapsed = ((now - p.startedAtMs) / 1000).toInt().coerceAtLeast(0)
            if (p.estimated) {
                FinderProgress(p.label, elapsed, maxOf(p.totalS, elapsed + 1))
            } else {
                FinderProgress(p.label, elapsed.coerceAtMost(p.totalS), p.totalS)
            }
        }
        return FinderLive(
            ts = graph.clock.isoNow(),
            mode = _mode.value,
            score = smoother.mean(now)?.let(::round1),
            scoreRaw = reading?.rawScore(finder.weights),
            rsrp = inputs?.rsrp,
            sinr = inputs?.sinr?.let(::round1),
            rsrq = inputs?.rsrq,
            netType = reading?.netType,
            band = cell?.band,
            pci = cell?.pci,
            cellId = cell?.cellId,
            ta = cell?.ta,
            cellSwitches5min = cellSwitches.countRecent(now),
            progress = progress,
        )
    }

    override fun setMirror(on: Boolean) {
        synchronized(lock) {
            if (mirrorOn == on) return
            mirrorOn = on
            if (on) consumers.add(LiveConsumer.MIRROR) else consumers.remove(LiveConsumer.MIRROR)
            ensureLiveLocked()
        }
        _ui.update { it.copy(mirror = on) }
        graph.log.i(TAG, "Dashboard mirror ${if (on) "on" else "off"}")
    }

    /** The local Finder screen is visible (started) or not. */
    fun setScreenOpen(open: Boolean) {
        if (open) refreshPrefs()
        setConsumer(LiveConsumer.SCREEN, open)
    }

    // --- mode, wake lock ----------------------------------------------------------------------

    // Caller holds lock.
    private fun recomputeLocked() {
        val next = testKind ?: when {
            walking -> FinderMode.WALK
            headlessSession -> FinderMode.HEADLESS
            else -> FinderMode.IDLE
        }
        _mode.value = next
        _ui.update { it.copy(walking = walking, runningTest = testKind) }
        updateWakeLock(next != FinderMode.IDLE)
    }

    private fun updateWakeLock(active: Boolean) {
        val wl = wakeLock ?: return
        try {
            if (active && !wl.isHeld) {
                wl.acquire(WAKE_LOCK_TIMEOUT_MS)
            } else if (!active && wl.isHeld) {
                wl.release()
            }
        } catch (e: Exception) {
            graph.log.w(TAG, "Wake lock change failed", e)
        }
    }

    // Caller holds lock.
    private fun workScopeLocked(): CoroutineScope = serviceScope?.takeIf { it.isActive } ?: graph.appScope

    private fun workScope(): CoroutineScope = synchronized(lock) { workScopeLocked() }

    // --- walk mode ----------------------------------------------------------------------------

    override fun startWalk() {
        synchronized(lock) {
            if (walking) return
            walking = true
            consumers.add(LiveConsumer.WALK)
            ensureLiveLocked()
            walkJob = workScopeLocked().launch { runWalk() }
            recomputeLocked()
        }
        graph.log.i(TAG, "Walk mode on")
    }

    /** Ends walk mode only (a running test continues). */
    fun stopWalk() {
        val wasWalking = synchronized(lock) {
            val was = walking
            stopWalkLocked()
            recomputeLocked()
            was
        }
        if (wasWalking) graph.log.i(TAG, "Walk mode off")
    }

    // Caller holds lock.
    private fun stopWalkLocked() {
        walking = false
        walkJob?.cancel()
        walkJob = null
        consumers.remove(LiveConsumer.WALK)
        ensureLiveLocked()
    }

    private suspend fun runWalk() {
        if (!muted) audio.speak("Walk mode", flush = true)
        coroutineScope {
            launch {
                audio.runTone(score = ::toneScore, audible = { !muted && _mode.value == FinderMode.WALK })
            }
            launch {
                while (isActive) {
                    val everyS = graph.settings.current.finder.speakEveryS.coerceIn(MIN_SPEAK_S, MAX_SPEAK_S)
                    delay(everyS * 1000L)
                    if (!muted && _mode.value == FinderMode.WALK) {
                        val score = _live.value?.score
                        audio.speak(if (score == null) "No signal" else score.roundToInt().toString(), flush = true)
                    }
                }
            }
        }
    }

    /** The tone follows the newest reading (a walking person needs it at once), else the smoothed score. */
    private fun toneScore(): Double? {
        val now = graph.clock.elapsedMs()
        val weights = graph.settings.current.finder.weights
        return freshReading(now, TONE_FRESH_MS)?.rawScore(weights) ?: smoother.mean(now)
    }

    override fun stopFinder() {
        val test = synchronized(lock) {
            stopWalkLocked()
            endHeadlessSessionLocked()
            recomputeLocked()
            testJob
        }
        test?.cancel()
        if (audioLazy.isInitialized()) audio.silence()
        graph.log.i(TAG, "Finder stopped")
    }

    fun setMuted(on: Boolean) {
        muted = on
        _ui.update { it.copy(muted = on) }
        if (on && audioLazy.isInitialized()) audio.silence()
        graph.appScope.launch(Dispatchers.IO) { graph.prefs.putBoolean(PREF_MUTED, on) }
    }

    private fun announce(text: String, force: Boolean) {
        if (force || !muted) audio.speak(text)
    }

    // --- tests --------------------------------------------------------------------------------

    fun isTestRunning(): Boolean = synchronized(lock) { testJob?.isActive == true }

    /** Cancels the running spot or speed test, if any. */
    fun cancelTest() {
        synchronized(lock) { testJob }?.cancel()
    }

    /** Starts a spot test from the local screen; the result arrives through [ui] and [spots]. */
    fun launchSpotTest(name: String, orientation: String?, durationS: Int? = null): Job = workScope().launch {
        runCatching { spotTest(name, orientation, durationS) }.onFailure { if (it is CancellationException) throw it }
    }

    /** Starts a speed test from the local screen. */
    fun launchSpeedTest(spotId: String?): Job = workScope().launch {
        runCatching { speedTest(spotId) }.onFailure { if (it is CancellationException) throw it }
    }

    override suspend fun spotTest(name: String, orientation: String?, durationS: Int?): FinderSpot =
        runTest(FinderMode.SPOT) { doSpotTest(name, orientation, durationS, forceSpeech = false) }

    override suspend fun speedTest(spotId: String?): SpeedSession = speedTestInternal(spotId, forceSpeech = false)

    private suspend fun speedTestInternal(spotId: String?, forceSpeech: Boolean): SpeedSession {
        val wd = graph.watchdog.state.value
        if (wd.stepRunning || wd.radioResting || (wd.internetDownSinceMs != null && wd.ladderStep > 0)) {
            throw report(FinderBusyException("The watchdog is recovering the connection; run the speed test later"))
        }
        val target = spotId?.let { id -> loadSpot(id) ?: throw report(IllegalArgumentException("Spot $id not found")) }
        return runTest(FinderMode.SPEED) { doSpeedTest(target, forceSpeech) }
    }

    /**
     * Runs [block] as the one Finder test, in the work scope (so [stopFinder] and the screen's
     * Cancel can stop it). A test cancelled by someone else surfaces as FinderCancelledException;
     * when the caller itself is cancelled the test is cancelled too.
     */
    private suspend fun <T> runTest(kind: FinderMode, block: suspend CoroutineScope.() -> T): T {
        if (!testMutex.tryLock()) {
            val running = synchronized(lock) { testKind }
            throw report(FinderBusyException("A ${testName(running)} is already running"))
        }
        try {
            val deferred = synchronized(lock) {
                val d = workScopeLocked().async { block(this) }
                testJob = d
                testKind = kind
                consumers.add(LiveConsumer.TEST)
                ensureLiveLocked()
                recomputeLocked()
                d
            }
            _ui.update { it.copy(error = null) }
            return try {
                deferred.await()
            } catch (e: CancellationException) {
                if (currentCoroutineContext().isActive) throw report(FinderCancelledException())
                withContext(NonCancellable) { deferred.cancelAndJoin() }
                throw e
            } catch (e: Exception) {
                throw report(e)
            }
        } finally {
            synchronized(lock) {
                testJob = null
                testKind = null
                progressState = null
                consumers.remove(LiveConsumer.TEST)
                ensureLiveLocked()
                recomputeLocked()
            }
            testMutex.unlock()
        }
    }

    private suspend fun doSpotTest(rawName: String, orientation: String?, durationS: Int?, forceSpeech: Boolean): FinderSpot =
        coroutineScope {
            val finder = graph.settings.current.finder
            val duration = (durationS ?: finder.spotTestS).coerceIn(MIN_SPOT_S, MAX_SPOT_S)
            val name = rawName.trim().take(MAX_NAME_CHARS).ifEmpty { FinderMath.nextSpotName(loadAllSpots().map { it.name }) }
            val createdAt = graph.clock.isoNow()
            val createdAtSystemMs = graph.clock.systemNowMs()
            announce("Spot test, $name. Hold still for $duration seconds.", forceSpeech)

            val t0 = graph.clock.elapsedMs()
            progressState = ProgressState("Spot test: $name", t0, duration, estimated = false)
            val sampler = OrientationSampler(graph.app)
            val sensorsOn = runCatching { sampler.start() }.getOrDefault(false)
            // Must not fail: a failed child would cancel the whole test.
            val fix = async {
                try {
                    LocationFix.fresh(graph.app, duration * 1000L + FIX_GRACE_MS)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    graph.log.w(TAG, "Location fix failed", e)
                    null
                }
            }
            val acc = SpotAccumulator(finder.weights)
            val switchesBefore = cellSwitches.total()
            try {
                for (i in 1..duration) {
                    val wait = t0 + i * 1000L - graph.clock.elapsedMs()
                    if (wait > 0) delay(wait)
                    freshReading(graph.clock.elapsedMs())?.let(acc::add)
                }
            } finally {
                sampler.stop()
            }
            val switches = (cellSwitches.total() - switchesBefore).coerceAtLeast(0)
            val metrics = acc.metrics()
            if (metrics == null) {
                fix.cancel()
                throw NoSignalException("No signal readings during the spot test")
            }
            val location = fix.await()
            val position = graph.telemetry.position.value
            val lat = location?.latitude ?: position?.lat
            val lon = location?.longitude ?: position?.lon
            val accM = location?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble() ?: position?.accM
            val attitude = if (sensorsOn) sampler.result() else null
            val compass = attitude?.magneticHeadingDeg?.let { OrientationSampler.trueHeading(it, lat, lon, createdAtSystemMs) }
                ?: position?.azimuthDeg
            val orientationText = orientation?.trim()?.take(MAX_ORIENTATION_CHARS)?.ifEmpty { null } ?: attitude?.orientation

            val spot = FinderSpot(
                id = Ids.spot(),
                name = name,
                createdAt = createdAt,
                durationS = duration,
                lat = lat?.let(::round6),
                lon = lon?.let(::round6),
                accM = accM?.let(::round1),
                compassDeg = compass?.let(::round1),
                orientation = orientationText,
                pitchDeg = (attitude?.pitchDeg ?: position?.pitchDeg)?.let(::round1),
                rollDeg = (attitude?.rollDeg ?: position?.rollDeg)?.let(::round1),
                hasPhoto = false,
                serving = acc.servingCell() ?: ServingCell(netType = NetType.UNKNOWN),
                cellSwitches = switches,
                metrics = metrics,
            )
            spotMutex.withLock { saveAndSend(spot) }
            graph.events.emit(
                EventKind.FINDER, Severity.INFO,
                "Spot test \"$name\": score ${metrics.score.avg}" + if (switches > 0) ", $switches cell switches" else "",
                jsonObjectOf("spot_id" to spot.id, "test" to "spot", "score" to metrics.score.avg, "cell_switches" to switches),
            )
            announce(spotSpeech(spot), forceSpeech)
            _ui.update {
                it.copy(message = "Saved \"$name\": score ${metrics.score.avg} (${acc.samples} samples)", error = null, lastSpotId = spot.id)
            }
            graph.log.i(TAG, "Spot test saved: ${spot.id} \"$name\" score ${metrics.score.avg}, $switches switches")
            spot
        }

    private suspend fun doSpeedTest(spot: FinderSpot?, forceSpeech: Boolean): SpeedSession {
        val settings = graph.settings.current
        val finder = settings.finder
        val runs = finder.speedRuns.coerceIn(1, MAX_SPEED_RUNS)
        val bytes = finder.speedBytes.coerceIn(MIN_SPEED_BYTES, MAX_SPEED_BYTES)
        val at = graph.clock.isoNow()
        val peak = FinderMath.isPeakHour(graph.clock.systemNowMs(), ZoneId.systemDefault())
        val where = spot?.let { " at ${it.name}" }.orEmpty()
        announce("Speed test$where. $runs ${if (runs == 1) "run" else "runs"}.", forceSpeech)

        val t0 = graph.clock.elapsedMs()
        var estimateS = runs * INITIAL_RUN_ESTIMATE_S
        val results = ArrayList<SpeedRun>(runs)
        for (i in 1..runs) {
            val run = speed.runOnce(finder, settings.telemetry, bytes) { phase ->
                progressState = ProgressState("Speed run $i/$runs: $phase", t0, estimateS, estimated = true)
            }
            results.add(run)
            if (i == 1) estimateS = (((graph.clock.elapsedMs() - t0) / 1000).toInt() * runs).coerceAtLeast(runs)
            graph.log.i(TAG, "Speed run $i/$runs: down ${run.downMbps} up ${run.upMbps} ping ${run.pingMs} jitter ${run.jitterMs}")
        }
        if (results.none { it.downMbps != null || it.upMbps != null || it.pingMs != null }) {
            throw IOException("Speed test failed: ${speed.lastError ?: "no measurement succeeded"}")
        }
        val session = FinderMath.buildSession(at, peak, results)
        val attachedTo = spot?.let { attachSession(it.id, session) }
        if (spot != null && attachedTo == null) graph.log.w(TAG, "Spot ${spot.id} was deleted during the speed test")
        graph.events.emit(
            EventKind.FINDER, Severity.INFO,
            "Speed test$where: down ${session.downMbps ?: "-"} / up ${session.upMbps ?: "-"} Mbit/s, ping ${session.pingMs ?: "-"} ms",
            jsonObjectOf(
                "spot_id" to attachedTo?.id,
                "test" to "speed",
                "down_mbps" to session.downMbps,
                "up_mbps" to session.upMbps,
                "ping_ms" to session.pingMs,
                "jitter_ms" to session.jitterMs,
                "peak_hour" to session.peakHour,
            ),
        )
        announce(speedSpeech(session), forceSpeech)
        _ui.update {
            it.copy(
                message = "Speed$where: ${session.downMbps ?: "-"} down / ${session.upMbps ?: "-"} up Mbit/s, " +
                    "ping ${session.pingMs ?: "-"} ms" + if (session.peakHour) " (peak hour)" else "",
                error = null,
            )
        }
        return session
    }

    private suspend fun attachSession(spotId: String, session: SpeedSession): FinderSpot? = spotMutex.withLock {
        val current = loadSpot(spotId) ?: return@withLock null
        val updated = current.copy(speed = (current.speed + session).takeLast(MAX_SESSIONS_PER_SPOT))
        saveAndSend(updated)
        updated
    }

    // --- spots --------------------------------------------------------------------------------

    override suspend fun setBaseline(spotId: String): Boolean {
        val target = spotMutex.withLock {
            val all = loadAllSpots()
            val chosen = all.firstOrNull { it.id == spotId } ?: return@withLock null
            // Before the writes: the hub also follows the baseline through `spots` and then stores
            // the spot's own fix as the reference, which this reset must not wipe afterwards.
            requestPositionReferenceReset()
            for (s in all) {
                val wanted = s.id == spotId
                // Re-send the new baseline even if already flagged, so the Server surely has it.
                if (s.baseline != wanted || wanted) saveAndSend(s.copy(baseline = wanted))
            }
            chosen
        } ?: return false
        _ui.update { it.copy(message = "Baseline: \"${target.name}\"", error = null) }
        graph.log.i(TAG, "Baseline set to ${target.id} \"${target.name}\"")
        return true
    }

    /**
     * The installed position changed: the telemetry hub takes the current location and tilt as the
     * new reference for `moved`.
     */
    private fun requestPositionReferenceReset() {
        runCatching { graph.telemetry.resetPositionReference() }
            .onFailure { graph.log.w(TAG, "Position reference reset failed", it) }
    }

    override suspend fun deleteSpot(spotId: String): Boolean {
        val removed = spotMutex.withLock { graph.db.finderSpotDao().delete(spotId) > 0 }
        // Sent even when unknown here, so a spot the Server still lists disappears there too.
        graph.outbox.enqueue(FinderSpotDeletedMsg(spotId = spotId))
        graph.sync.kick()
        photoFile(spotId)?.let { file -> withContext(Dispatchers.IO) { file.delete() } }
        if (removed) {
            _ui.update { if (it.lastSpotId == spotId) it.copy(lastSpotId = null) else it }
            graph.log.i(TAG, "Spot $spotId deleted")
        }
        return removed
    }

    override suspend fun exportCsv(): File {
        val spots = loadAllSpots()
        return withContext(Dispatchers.IO) {
            val dir = File(graph.app.cacheDir, EXPORT_DIR).apply { mkdirs() }
            dir.listFiles()
                ?.filter { it.name.startsWith(CSV_PREFIX) && it.name.endsWith(".csv") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(KEEP_EXPORTS - 1)
                ?.forEach { it.delete() }
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT))
            val file = File(dir, "$CSV_PREFIX$stamp.csv")
            file.writeText(SpotCsv.build(spots, ZoneId.systemDefault()), Charsets.UTF_8)
            graph.log.i(TAG, "Exported ${spots.size} spots to ${file.name}")
            file
        }
    }

    /** Where a spot's photo lives (app files, never uploaded); null for an unsafe id. */
    fun photoFile(spotId: String): File? =
        if (SAFE_ID.matches(spotId)) File(graph.app.filesDir, "$PHOTO_DIR/$spotId.jpg") else null

    /** Temporary camera output inside the FileProvider's exports path (cache/exports/). */
    fun photoCaptureFile(): File = File(File(graph.app.cacheDir, EXPORT_DIR), "photo-capture.jpg")

    /** Moves a captured photo into app files and flags the spot (has_photo, re-sent). */
    suspend fun attachPhoto(spotId: String, captured: File): Boolean {
        val dest = photoFile(spotId) ?: return false
        return spotMutex.withLock {
            val spot = loadSpot(spotId) ?: return@withLock false
            val moved = withContext(Dispatchers.IO) {
                if (!captured.exists() || captured.length() == 0L) return@withContext false
                dest.parentFile?.mkdirs()
                if (dest.exists()) dest.delete()
                captured.renameTo(dest) || runCatching {
                    captured.copyTo(dest, overwrite = true)
                    captured.delete()
                    true
                }.getOrDefault(false)
            }
            if (!moved) return@withLock false
            if (spot.hasPhoto != true) saveAndSend(spot.copy(hasPhoto = true))
            graph.log.i(TAG, "Photo attached to $spotId")
            true
        }
    }

    private suspend fun saveAndSend(spot: FinderSpot) {
        val json = AgentJson.encodeToString(FinderSpot.serializer(), spot)
        graph.db.finderSpotDao().upsert(FinderSpotEntity(id = spot.id, json = json, updatedAtMs = graph.clock.serverNow()))
        graph.outbox.enqueue(FinderSpotMsg(spot = spot))
        graph.sync.kick()
    }

    private suspend fun loadAllSpots(): List<FinderSpot> =
        graph.db.finderSpotDao().all().mapNotNull { decodeSpot(it.json) }

    private suspend fun loadSpot(id: String): FinderSpot? =
        graph.db.finderSpotDao().get(id)?.let { decodeSpot(it.json) }

    private fun decodeSpot(json: String): FinderSpot? = try {
        AgentJson.decodeFromString(FinderSpot.serializer(), json)
    } catch (e: Exception) {
        graph.log.w(TAG, "Unreadable stored spot skipped", e)
        null
    }

    // --- headless -----------------------------------------------------------------------------

    override fun setHeadless(on: Boolean) {
        _ui.update { it.copy(headlessEnabled = on) }
        graph.appScope.launch(Dispatchers.IO) { graph.prefs.putBoolean(PREF_HEADLESS, on) }
        val scope = synchronized(lock) { serviceScope?.takeIf { it.isActive } }
        if (on) {
            if (scope != null) headless.start(scope)
        } else {
            headless.stop()
            synchronized(lock) {
                endHeadlessSessionLocked()
                recomputeLocked()
            }
        }
        graph.log.i(TAG, "Headless volume keys ${if (on) "on" else "off"}")
    }

    private fun headlessEnabled(): Boolean = graph.prefs.getBoolean(PREF_HEADLESS, true)

    private fun refreshPrefs() {
        val headlessOn = headlessEnabled()
        muted = graph.prefs.getBoolean(PREF_MUTED, false)
        _ui.update { it.copy(headlessEnabled = headlessOn, muted = muted) }
    }

    private fun onHeadlessStatus(listening: Boolean, detail: String) {
        _ui.update { it.copy(headlessListening = listening, headlessDetail = detail) }
    }

    private fun onHeadlessHold(key: VolumeKey, kind: PressKind) {
        audio.beep(double = kind == PressKind.VERY_LONG)
    }

    private fun onHeadlessGesture(gesture: KeyGesture) {
        workScope().launch {
            touchHeadlessSession()
            try {
                handleGesture(gesture)
            } catch (e: CancellationException) {
                throw e
            } catch (e: FinderCancelledException) {
                // Stopped by the stop gesture, which already said "Stopped".
            } catch (e: Exception) {
                graph.log.w(TAG, "Headless ${gesture.key} ${gesture.kind} failed", e)
                audio.speak(failureSpeech(e))
            } finally {
                if (isActive) touchHeadlessSession()
            }
        }
    }

    /**
     * Long volume up: spot test. Very long (3 s) volume up: walk mode on/off.
     * Long volume down: speed test on the newest spot. Very long volume down: stop everything.
     */
    private suspend fun handleGesture(g: KeyGesture) {
        when {
            g.key == VolumeKey.DOWN && g.kind == PressKind.VERY_LONG -> {
                stopFinder()
                audio.speak("Stopped")
            }
            isTestRunning() -> audio.speak("Busy. Hold volume down for three seconds to stop.")
            g.key == VolumeKey.UP && g.kind == PressKind.LONG -> {
                val name = FinderMath.nextSpotName(loadAllSpots().map { it.name })
                runTest(FinderMode.SPOT) { doSpotTest(name, null, null, forceSpeech = true) }
            }
            g.key == VolumeKey.UP && g.kind == PressKind.VERY_LONG -> {
                if (synchronized(lock) { walking }) {
                    stopWalk()
                    audio.speak("Walk mode off")
                } else {
                    if (muted) setMuted(false)
                    startWalk()
                }
            }
            g.key == VolumeKey.DOWN && g.kind == PressKind.LONG -> {
                val newest = loadAllSpots().maxByOrNull { Clock.parseIso(it.createdAt) ?: 0L }
                speedTestInternal(newest?.id, forceSpeech = true)
            }
        }
    }

    /** A headless session keeps live values at 1 Hz for a while after the last key gesture. */
    private fun touchHeadlessSession() {
        synchronized(lock) {
            headlessSession = true
            consumers.add(LiveConsumer.HEADLESS)
            ensureLiveLocked()
            headlessSessionJob?.cancel()
            headlessSessionJob = workScopeLocked().launch {
                delay(HEADLESS_SESSION_MS)
                synchronized(lock) {
                    headlessSessionJob = null
                    endHeadlessSessionLocked()
                    recomputeLocked()
                }
            }
            recomputeLocked()
        }
    }

    // Caller holds lock.
    private fun endHeadlessSessionLocked() {
        if (!headlessSession) return
        headlessSession = false
        headlessSessionJob?.cancel()
        headlessSessionJob = null
        consumers.remove(LiveConsumer.HEADLESS)
        ensureLiveLocked()
    }

    // --- speech and messages ------------------------------------------------------------------

    private fun spotSpeech(spot: FinderSpot): String {
        val parts = mutableListOf("${spot.name} saved.", "Score ${spot.metrics.score.avg.roundToInt()}.")
        spot.orientation?.let { parts.add(spokenOrientation(it) + ".") }
        parts.add(
            if (spot.cellSwitches > 0) {
                "Unstable: the cell changed ${spot.cellSwitches} ${if (spot.cellSwitches == 1) "time" else "times"}."
            } else {
                "Stable cell."
            },
        )
        return parts.joinToString(" ")
    }

    private fun speedSpeech(s: SpeedSession): String {
        val parts = mutableListOf<String>()
        s.downMbps?.let { parts.add("Download ${spokenNumber(it)} megabits.") }
        s.upMbps?.let { parts.add("Upload ${spokenNumber(it)}.") }
        s.pingMs?.let { parts.add("Ping ${it.roundToInt()} milliseconds.") }
        return if (parts.isEmpty()) "Speed test finished without results." else parts.joinToString(" ")
    }

    private fun failureSpeech(e: Throwable): String = when (e) {
        is NoSignalException -> "Spot test failed. No signal."
        is FinderBusyException -> "Busy."
        is FinderCancelledException -> "Cancelled."
        else -> "Failed. " + (e.message ?: e.javaClass.simpleName).take(MAX_SPOKEN_ERROR_CHARS)
    }

    private fun spokenOrientation(o: String): String = when (o) {
        OrientationMath.UPRIGHT -> "Upright"
        OrientationMath.FLAT -> "Flat"
        OrientationMath.ROT90 -> "Turned 90 degrees"
        else -> o
    }

    private fun spokenNumber(v: Double): String =
        if (v < 10) String.format(Locale.US, "%.1f", v) else v.roundToInt().toString()

    private fun testName(kind: FinderMode?): String = when (kind) {
        FinderMode.SPEED -> "speed test"
        FinderMode.SPOT -> "spot test"
        else -> "test"
    }

    /** Records [e] as the screen's error line and returns it (for `throw report(e)`). */
    private fun <E : Throwable> report(e: E): E {
        if (e is FinderCancelledException) {
            _ui.update { it.copy(message = "Cancelled", error = null) }
        } else {
            _ui.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
        }
        graph.log.w(TAG, "Finder: ${e.message ?: e.javaClass.simpleName}")
        return e
    }

    private fun smoothWindowMs(finder: FinderSettings): Long = finder.smoothS.coerceIn(1, MAX_SMOOTH_S) * 1000L

    private fun round6(v: Double): Double = Math.round(v * 1e6) / 1e6

    companion object {
        private const val TAG = "finder"
        const val PREF_HEADLESS = "finder.headless"
        const val PREF_MUTED = "finder.muted"
        const val FILE_PROVIDER_AUTHORITY = "com.hotspotmonitor.agent.files"
        private const val EXPORT_DIR = "exports"
        private const val PHOTO_DIR = "finder/photos"
        private const val CSV_PREFIX = "finder-spots-"
        private const val KEEP_EXPORTS = 5
        private const val WAKE_LOCK_TAG = "hsm:finder"
        private const val WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 60_000L
        private val SAFE_ID = Regex("""^[A-Za-z0-9_-]{1,64}$""")

        private const val DEFAULT_SMOOTH_MS = 5_000L
        private const val MAX_SMOOTH_S = 120
        private const val LIVE_TICK_MS = 1_000L
        private const val MIN_TICK_DELAY_MS = 100L
        private const val MIRROR_SLACK_MS = 100L
        private const val FRESH_READING_MS = 5_000L
        private const val TONE_FRESH_MS = 3_000L
        private const val MIN_SPEAK_S = 1
        private const val MAX_SPEAK_S = 120
        private const val HEADLESS_SESSION_MS = 5 * 60_000L

        private const val MIN_SPOT_S = 5
        private const val MAX_SPOT_S = 600
        private const val FIX_GRACE_MS = 2_000L
        private const val MAX_NAME_CHARS = 80
        private const val MAX_ORIENTATION_CHARS = 40
        private const val MAX_SPOKEN_ERROR_CHARS = 80

        private const val MAX_SPEED_RUNS = 10
        private const val MIN_SPEED_BYTES = 100_000L
        private const val MAX_SPEED_BYTES = 200_000_000L
        private const val INITIAL_RUN_ESTIMATE_S = 30
        private const val MAX_SESSIONS_PER_SPOT = 50
    }
}
