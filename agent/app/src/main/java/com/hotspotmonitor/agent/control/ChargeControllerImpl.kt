package com.hotspotmonitor.agent.control

import android.content.Intent
import android.os.BatteryManager
import com.hotspotmonitor.agent.core.ActionResult
import com.hotspotmonitor.agent.core.AgentGraph
import com.hotspotmonitor.agent.core.ChargeController
import com.hotspotmonitor.agent.core.ChargeState
import com.hotspotmonitor.agent.protocol.CapabilityEntry
import com.hotspotmonitor.agent.protocol.EventKind
import com.hotspotmonitor.agent.protocol.Severity
import com.hotspotmonitor.agent.protocol.StopReason
import com.hotspotmonitor.agent.protocol.jsonObjectOf
import com.hotspotmonitor.agent.root.ChargeControlPath
import com.hotspotmonitor.agent.telemetry.PowerMath
import com.hotspotmonitor.agent.telemetry.stickyIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Root charge control (plan 4.2, 6.5) through the device profile's charge_control paths: the first
 * path that exists, has its owner write bit set and takes its current value written back wins
 * ([ChargeProbe.choosePath]; root's `[ -w ]` is always true, so it is not used). The first stop
 * while plugged in and charging is then checked like `phase0.sh charge-discover --test`: if the
 * battery keeps charging, the path is restored to `on`, skipped from then on (in this process) and
 * the next candidate takes over. Charging is decided by [ChargePolicy] from three inputs, each set
 * through [setCharging] with its reason:
 *
 * - HEAT (the watchdog's heat rule) always wins,
 * - MANUAL (the `charging` command) overrides the limit; a manual "on" lasts until the battery is
 *   full, then the limit applies again, so a forgotten top-up never holds the battery at 100 %. A
 *   manual "off" is refused, or lifted, at or below the safety floor
 *   (max(alerts.low_battery_pct, 20) %), so a forgotten "off" never drains an unattended phone,
 * - LIMIT, driven by [applyLimit] with hysteresis.
 *
 * All three inputs are persisted ([ChargeMemory]), so a restart keeps a heat stop and the limit band.
 * Every switch is verified by reading the file back; [lastSwitchWrite] lets telemetry tell the plug
 * change of a switch that cuts USB input from a real power loss. Events: charging_stopped /
 * charging_resumed.
 */
class ChargeControllerImpl(private val graph: AgentGraph) : ChargeController {
    private val _state = MutableStateFlow(ChargeState(limitPct = graph.settings.current.watchdog.chargeLimitPct))
    override val state: StateFlow<ChargeState> = _state.asStateFlow()

    private val mutex = Mutex()
    private val memory by lazy { ChargeMemory(graph.prefs) }

    /** The last write to the switch (elapsed ms); read by the telemetry power receiver. */
    @Volatile
    internal var lastSwitchWrite: ChargeSwitchWrite? = null
        private set

    // Guarded by mutex.
    private var initialized = false
    private var path: ChargeControlPath? = null
    private var probeNote: String? = null
    private var probedAtMs: Long? = null
    private var heatActive = false
    private var manual: Boolean? = null
    private var limitStop = false
    private var lastAutoFailure: Pair<Boolean, Long>? = null
    private var lastBatteryPct: Int? = null

    /** A manual "off" just lifted at the safety floor, reported by the next resume (or by [applyLimit]). */
    private var pendingFloorLift: FloorLift? = null

    private data class FloorLift(val batteryPct: Int, val floorPct: Int)

    /** Paths that accepted writes but did not stop charging (cleared when the profile's list changes). */
    private val ineffective = HashSet<String>()

    /** Path whose stop was seen to work, so it is not checked again. */
    private var confirmedPath: String? = null

    /** Incremented on every switch write; an effect check is void once another write happened. */
    private var writeSeq = 0L

    @Volatile
    private var scope: CoroutineScope? = null

    override fun start(scope: CoroutineScope) {
        this.scope = scope
        scope.launch {
            var previous: List<ChargeControlPath>? = null
            graph.profiles.profile.map { it.chargeControl }.distinctUntilChanged().collect { candidates ->
                try {
                    mutex.withLock {
                        if (previous != null && previous != candidates) {
                            ineffective.clear()
                            confirmedPath = null
                        }
                        previous = candidates
                        initLocked(candidates)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    graph.log.e(TAG, "Charge control setup failed", e)
                }
            }
        }
    }

    override suspend fun setCharging(enabled: Boolean, reason: StopReason): ActionResult = mutex.withLock {
        ensureInitLocked()
        when (reason) {
            StopReason.HEAT -> {
                heatActive = !enabled
                memory.heatStop = heatActive
            }
            StopReason.MANUAL -> {
                if (!enabled) {
                    val battery = graph.telemetry.power.value?.percent ?: lastBatteryPct
                    val floor = floorPct()
                    if (battery != null && battery <= floor) {
                        graph.log.w(TAG, "Manual 'charging off' refused: battery $battery % is at or below the $floor % safety floor")
                        return@withLock ActionResult.failure(
                            "Charging stays on: the battery is at $battery %, at or below the $floor % safety floor " +
                                "(max(alerts.low_battery_pct, ${ChargePolicy.MIN_SAFETY_FLOOR_PCT}))",
                            method = path?.path,
                        )
                    }
                }
                manual = enabled
                saveManual()
            }
            StopReason.LIMIT -> {
                limitStop = !enabled
                memory.limitStop = limitStop
            }
        }
        val p = path ?: return@withLock ActionResult.failure("No charge control on this phone: ${probeNote ?: "not probed"}")
        val applied = applyLocked(automatic = false)
        val decision = decision()
        when {
            applied != null && !applied.ok -> applied
            reason == StopReason.MANUAL && enabled && !decision.enabled ->
                ActionResult.failure("Charging stays off: ${explain(decision.reason)}", method = p.path)
            applied != null -> applied
            else -> ActionResult.success("Charging is already ${if (decision.enabled) "on" else "off"}", p.path)
        }
    }

    override suspend fun applyLimit(limitPct: Int, resumeDropPct: Int, batteryPct: Int?): ActionResult? = mutex.withLock {
        ensureInitLocked()
        val limit = limitPct.coerceIn(1, 100)
        if (_state.value.limitPct != limit) _state.update { it.copy(limitPct = limit) }
        if (batteryPct != null) lastBatteryPct = batteryPct
        if (manual == true && batteryPct != null && batteryPct >= FULL_PCT) {
            manual = null
            saveManual()
            graph.log.i(TAG, "Manual charging reached $batteryPct %: back to the $limit % limit")
        }
        val floor = floorPct()
        if (ChargePolicy.manualOffLifted(manual, batteryPct, floor)) {
            manual = null
            saveManual()
            pendingFloorLift = FloorLift(batteryPct ?: floor, floor)
            graph.log.w(TAG, "Battery $batteryPct % reached the $floor % safety floor: manual 'charging off' lifted")
        }
        limitStop = ChargePolicy.limitStop(limitStop, batteryPct, limit, resumeDropPct)
        memory.limitStop = limitStop
        val result = applyLocked(automatic = true)
        pendingFloorLift?.let { lift ->
            // Not reported by a resume: heat still blocks charging, or the switch state was unknown.
            pendingFloorLift = null
            emitFloorLift(lift, decision())
        }
        result
    }

    override suspend fun probe(): CapabilityEntry = mutex.withLock {
        val fresh = initialized && probedAtMs?.let { graph.clock.elapsedMs() - it < PROBE_CACHE_MS } == true
        if (!fresh) initLocked(graph.profiles.current.chargeControl)
        val p = path
        if (p != null) {
            CapabilityEntry(supported = true, method = p.path, note = p.note)
        } else {
            CapabilityEntry(supported = false, note = probeNote ?: "no charge control")
        }
    }

    /** The `charge_limit` command: back to automatic charging (drops a manual on/off). */
    internal suspend fun clearManualOverride() {
        mutex.withLock {
            ensureInitLocked()
            if (manual != null) {
                manual = null
                saveManual()
            }
        }
    }

    // --- internals (call with mutex held) -------------------------------------------------------

    private suspend fun ensureInitLocked() {
        if (!initialized) initLocked(graph.profiles.current.chargeControl)
    }

    private suspend fun initLocked(candidates: List<ChargeControlPath>) {
        manual = loadManual()
        heatActive = memory.heatStop
        limitStop = memory.limitStop
        path = findPath(candidates)
        probedAtMs = graph.clock.elapsedMs()
        initialized = true
        readActualLocked()
        val p = path
        if (p != null) {
            graph.log.i(TAG, "Charge control via ${p.path} (on=${p.on}, off=${p.off})")
        } else {
            graph.log.w(TAG, "No charge control: $probeNote")
        }
    }

    private suspend fun findPath(candidates: List<ChargeControlPath>): ChargeControlPath? {
        if (candidates.isNotEmpty() && !graph.shell.isRooted()) {
            probeNote = "root not available"
            return null
        }
        val choice = ChargeProbe.choosePath(candidates, graph.shell, ineffective, SHELL_TIMEOUT_MS)
        probeNote = choice.note
        if (choice.path?.path != confirmedPath) confirmedPath = null
        return choice.path
    }

    private suspend fun readActualLocked() {
        val p = path
        if (p == null) {
            _state.update { it.copy(chargingEnabled = null, stopReason = null, method = null) }
            return
        }
        val actual = parseSwitch(p, graph.shell.readFile(p.path))
        _state.update {
            it.copy(
                chargingEnabled = actual,
                method = p.path,
                stopReason = if (actual == false) it.stopReason else null,
            )
        }
    }

    /** Writes the decided state when it differs. Null when nothing had to change. */
    private suspend fun applyLocked(automatic: Boolean): ActionResult? {
        val p = path ?: return null
        val decision = decision()
        val current = _state.value
        if (current.chargingEnabled == decision.enabled) {
            if (current.stopReason != decision.reason) _state.update { it.copy(stopReason = decision.reason) }
            lastAutoFailure = null
            return null
        }
        val now = graph.clock.elapsedMs()
        if (automatic) {
            val failure = lastAutoFailure
            if (failure != null && failure.first == decision.enabled && now - failure.second < AUTO_RETRY_MS) return null
        }
        val value = if (decision.enabled) p.on else p.off
        val before = if (!decision.enabled && confirmedPath != p.path) batterySample() else null
        lastSwitchWrite = ChargeSwitchWrite(decision.enabled, now)
        val write = graph.shell.writeFile(p.path, value)
        lastSwitchWrite = ChargeSwitchWrite(decision.enabled, graph.clock.elapsedMs())
        writeSeq++
        val readBack = graph.shell.readFile(p.path)
        val verified = write.ok && (readBack == null || readBack == value.trim())
        val verb = if (decision.enabled) "resume" else "stop"
        if (!verified) {
            lastAutoFailure = decision.enabled to now
            val detail = if (!write.ok) "write failed (${write.summary()})" else "reads '$readBack' after writing '$value'"
            graph.log.w(TAG, "Could not $verb charging via ${p.path}: $detail")
            parseSwitch(p, readBack)?.let { actual -> _state.update { it.copy(chargingEnabled = actual) } }
            return ActionResult.failure("Could not $verb charging: $detail", method = p.path)
        }
        lastAutoFailure = null
        if (before != null && ChargeProbe.worthChecking(before)) scheduleEffectCheckLocked(p)
        val previousReason = current.stopReason
        _state.update { it.copy(chargingEnabled = decision.enabled, stopReason = decision.reason, method = p.path) }
        val battery = graph.telemetry.power.value?.percent
        if (decision.enabled && current.chargingEnabled == null) {
            // The switch state was unknown (first write after start): nothing actually resumed.
            graph.log.i(TAG, "Charging switch set to on via ${p.path}")
        } else if (decision.enabled) {
            val lift = pendingFloorLift
            pendingFloorLift = null
            if (lift != null) {
                emitFloorLift(lift, decision)
            } else {
                graph.events.emit(
                    EventKind.CHARGING_RESUMED, Severity.INFO,
                    "Charging resumed" + (previousReason?.let { " (${describe(it)} cleared)" } ?: ""),
                    jsonObjectOf("reason" to previousReason?.let { wire(it) }, "battery_pct" to battery, "limit_pct" to current.limitPct),
                )
            }
        } else {
            val reason = decision.reason ?: StopReason.MANUAL
            graph.events.emit(
                EventKind.CHARGING_STOPPED,
                if (reason == StopReason.HEAT) Severity.WARNING else Severity.INFO,
                "Charging stopped: ${describe(reason)}",
                jsonObjectOf("reason" to wire(reason), "battery_pct" to battery, "limit_pct" to current.limitPct),
            )
        }
        return ActionResult.success("Charging ${if (decision.enabled) "on" else "off"}", p.path)
    }

    private fun decision(): ChargeDecision = ChargePolicy.decide(heatActive, manual, limitStop)

    private fun floorPct(): Int = ChargePolicy.safetyFloorPct(graph.settings.current.alerts.lowBatteryPct)

    /** The event for a manual "off" lifted at the safety floor; heat may still keep charging off. */
    private fun emitFloorLift(lift: FloorLift, decision: ChargeDecision) {
        val data = jsonObjectOf(
            "reason" to (decision.reason?.let { wire(it) } ?: wire(StopReason.MANUAL)),
            "battery_pct" to lift.batteryPct,
            "limit_pct" to _state.value.limitPct,
            "safety_floor_pct" to lift.floorPct,
            "manual_off_lifted" to true,
        )
        val why = "the battery is at ${lift.batteryPct} %, at or below the ${lift.floorPct} % safety floor, so a user's 'charging off' was lifted"
        if (decision.enabled) {
            graph.events.emit(EventKind.CHARGING_RESUMED, Severity.WARNING, "Charging resumed: $why", data)
        } else {
            graph.events.emit(
                EventKind.CHARGING_STOPPED, Severity.WARNING,
                "Charging stays off: $why, but ${explain(decision.reason)}", data,
            )
        }
    }

    /**
     * Watches the battery after [p] was switched off while charging (phase0.sh charge-discover
     * --test does the same by hand): the first [EFFECT_SETTLE_MS] are ignored while the charger
     * reacts, then [EFFECT_SAMPLES] readings decide. Void when another write happens meanwhile.
     */
    private fun scheduleEffectCheckLocked(p: ChargeControlPath) {
        val s = scope ?: return
        val seq = writeSeq
        // Not cancelled when superseded (this may run inside the previous check): a check whose
        // write sequence is stale simply does nothing.
        s.launch {
            try {
                delay(EFFECT_SETTLE_MS)
                val samples = ArrayList<BatterySample>()
                repeat(EFFECT_SAMPLES) { i ->
                    if (i > 0) delay(EFFECT_INTERVAL_MS)
                    samples += batterySample()
                }
                mutex.withLock {
                    if (seq == writeSeq && path == p) onEffectLocked(p, ChargeProbe.judgeEffect(samples), samples)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                graph.log.w(TAG, "Charge effect check failed", e)
            }
        }
    }

    private suspend fun onEffectLocked(p: ChargeControlPath, effect: ChargeEffect, samples: List<BatterySample>) {
        when (effect) {
            ChargeEffect.STOPPED -> {
                confirmedPath = p.path
                graph.log.i(TAG, "Charge control via ${p.path} confirmed: charging stopped")
            }
            ChargeEffect.UNKNOWN -> graph.log.i(TAG, "Charge control via ${p.path}: effect not clear yet ($samples)")
            ChargeEffect.NO_EFFECT -> {
                ineffective += p.path
                val restore = graph.shell.writeFile(p.path, p.on)
                lastSwitchWrite = ChargeSwitchWrite(true, graph.clock.elapsedMs())
                writeSeq++
                val ma = samples.mapNotNull { it.currentMa }.maxOrNull()
                val message = "${p.path} accepted '${p.off}' but the battery kept charging" +
                    (ma?.let { " (up to $it mA)" } ?: "") + "; restored '${p.on}'" +
                    (if (restore.ok) "" else " (failed: ${restore.summary()})")
                graph.log.w(TAG, message)
                path = findPath(graph.profiles.current.chargeControl)
                probedAtMs = graph.clock.elapsedMs()
                _state.update { it.copy(chargingEnabled = null) }
                readActualLocked()
                val next = path
                graph.events.emit(
                    EventKind.ERROR, Severity.WARNING,
                    "Charge control: $message. " + (next?.let { "Switching to ${it.path}." } ?: "No other charge_control path works."),
                    jsonObjectOf("where" to "charge_control", "message" to message, "next_path" to next?.path),
                )
                if (next != null) applyLocked(automatic = false)
            }
        }
    }

    /** Battery state right now (sticky ACTION_BATTERY_CHANGED and BatteryManager current). */
    private fun batterySample(): BatterySample {
        val intent = try {
            graph.app.stickyIntent(Intent.ACTION_BATTERY_CHANGED)
        } catch (e: Exception) {
            null
        }
        val raw = try {
            graph.app.getSystemService(BatteryManager::class.java)
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.toLong()
        } catch (e: Exception) {
            null
        }
        return BatterySample(
            plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)?.takeIf { it >= 0 }?.let { it != 0 },
            status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)?.takeIf { it >= 0 },
            currentMa = PowerMath.currentMa(raw, graph.profiles.current.currentNow),
        )
    }

    private fun parseSwitch(p: ChargeControlPath, value: String?): Boolean? = when (value) {
        null -> null
        p.on.trim() -> true
        p.off.trim() -> false
        else -> null
    }

    private fun describe(reason: StopReason): String = when (reason) {
        StopReason.LIMIT -> "charge limit ${_state.value.limitPct} % reached"
        StopReason.HEAT -> "battery too hot"
        StopReason.MANUAL -> "switched off by a user"
    }

    private fun explain(reason: StopReason?): String = when (reason) {
        StopReason.HEAT -> "the battery is too hot; charging resumes below " +
            "${graph.settings.current.watchdog.tempResumeC} °C"
        StopReason.LIMIT -> "the charge limit is reached"
        StopReason.MANUAL -> "charging was switched off by a user"
        null -> "unknown reason"
    }

    private fun wire(reason: StopReason): String = reason.name.lowercase()

    private fun loadManual(): Boolean? = memory.manual

    private fun saveManual() {
        memory.manual = manual
    }

    companion object {
        private const val TAG = "charge"
        private const val FULL_PCT = 100
        private const val SHELL_TIMEOUT_MS = 5_000L
        private const val PROBE_CACHE_MS = 10_000L

        /** After a failed automatic switch, wait this long before the limit rule tries again. */
        private const val AUTO_RETRY_MS = 60_000L

        /** Effect check after a stop: ignore the charger's reaction time, then sample. */
        private const val EFFECT_SETTLE_MS = 10_000L
        private const val EFFECT_SAMPLES = 6
        private const val EFFECT_INTERVAL_MS = 4_000L
    }
}
