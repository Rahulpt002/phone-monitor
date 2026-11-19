package com.hotspotmonitor.agent.control

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
import com.hotspotmonitor.agent.root.shellQuote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
 * path that exists and accepts writes wins. Charging is decided by [ChargePolicy] from three
 * inputs, each set through [setCharging] with its reason:
 *
 * - HEAT (the watchdog's heat rule) always wins,
 * - MANUAL (the `charging` command) overrides the limit; a manual "on" lasts until the battery is
 *   full, then the limit applies again, so a forgotten top-up never holds the battery at 100 %,
 * - LIMIT, driven by [applyLimit] with hysteresis.
 *
 * Every switch is verified by reading the file back. Events: charging_stopped / charging_resumed.
 */
class ChargeControllerImpl(private val graph: AgentGraph) : ChargeController {
    private val _state = MutableStateFlow(ChargeState(limitPct = graph.settings.current.watchdog.chargeLimitPct))
    override val state: StateFlow<ChargeState> = _state.asStateFlow()

    private val mutex = Mutex()

    // Guarded by mutex.
    private var initialized = false
    private var path: ChargeControlPath? = null
    private var probeNote: String? = null
    private var probedAtMs: Long? = null
    private var heatActive = false
    private var manual: Boolean? = null
    private var limitStop = false
    private var lastAutoFailure: Pair<Boolean, Long>? = null

    override fun start(scope: CoroutineScope) {
        scope.launch {
            graph.profiles.profile.map { it.chargeControl }.distinctUntilChanged().collect { candidates ->
                try {
                    mutex.withLock { initLocked(candidates) }
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
            StopReason.HEAT -> heatActive = !enabled
            StopReason.MANUAL -> {
                manual = enabled
                saveManual()
            }
            StopReason.LIMIT -> limitStop = !enabled
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
        if (manual == true && batteryPct != null && batteryPct >= FULL_PCT) {
            manual = null
            saveManual()
            graph.log.i(TAG, "Manual charging reached $batteryPct %: back to the $limit % limit")
        }
        limitStop = ChargePolicy.limitStop(limitStop, batteryPct, limit, resumeDropPct)
        applyLocked(automatic = true)
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
        if (candidates.isEmpty()) {
            probeNote = "the device profile lists no charge_control path"
            return null
        }
        if (!graph.shell.isRooted()) {
            probeNote = "root not available"
            return null
        }
        for (c in candidates) {
            val quoted = shellQuote(c.path)
            if (!graph.shell.run("test -e $quoted && test -w $quoted", SHELL_TIMEOUT_MS).ok) continue
            val current = graph.shell.readFile(c.path)
            // Writing the value it already has proves the switch accepts writes without changing anything.
            if (current != null && (current == c.on.trim() || current == c.off.trim())) {
                if (!graph.shell.writeFile(c.path, current).ok) continue
            }
            probeNote = c.note
            return c
        }
        probeNote = "none of the ${candidates.size} charge_control paths exists and accepts writes"
        return null
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
        val write = graph.shell.writeFile(p.path, value)
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
        val previousReason = current.stopReason
        _state.update { it.copy(chargingEnabled = decision.enabled, stopReason = decision.reason, method = p.path) }
        val battery = graph.telemetry.power.value?.percent
        if (decision.enabled && current.chargingEnabled == null) {
            // The switch state was unknown (first write after start): nothing actually resumed.
            graph.log.i(TAG, "Charging switch set to on via ${p.path}")
        } else if (decision.enabled) {
            graph.events.emit(
                EventKind.CHARGING_RESUMED, Severity.INFO,
                "Charging resumed" + (previousReason?.let { " (${describe(it)} cleared)" } ?: ""),
                jsonObjectOf("reason" to previousReason?.let { wire(it) }, "battery_pct" to battery, "limit_pct" to current.limitPct),
            )
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

    private fun loadManual(): Boolean? = when (graph.prefs.getString(KEY_MANUAL)) {
        "on" -> true
        "off" -> false
        else -> null
    }

    private fun saveManual() {
        graph.prefs.putString(
            KEY_MANUAL,
            when (manual) {
                true -> "on"
                false -> "off"
                null -> null
            },
        )
    }

    companion object {
        private const val TAG = "charge"
        private const val KEY_MANUAL = "control.charge_manual"
        private const val FULL_PCT = 100
        private const val SHELL_TIMEOUT_MS = 5_000L
        private const val PROBE_CACHE_MS = 10_000L

        /** After a failed automatic switch, wait this long before the limit rule tries again. */
        private const val AUTO_RETRY_MS = 60_000L
    }
}
