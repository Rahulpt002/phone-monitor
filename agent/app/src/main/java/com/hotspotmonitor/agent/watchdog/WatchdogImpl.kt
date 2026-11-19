package com.hotspotmonitor.agent.watchdog

import com.hotspotmonitor.agent.control.ControlsImpl
import com.hotspotmonitor.agent.control.RadioActivity
import com.hotspotmonitor.agent.control.TetherIntent
import com.hotspotmonitor.agent.core.ActionResult
import com.hotspotmonitor.agent.core.AgentGraph
import com.hotspotmonitor.agent.core.Watchdog
import com.hotspotmonitor.agent.core.WatchdogState
import com.hotspotmonitor.agent.protocol.CommandNames
import com.hotspotmonitor.agent.protocol.CommandSource
import com.hotspotmonitor.agent.protocol.EventKind
import com.hotspotmonitor.agent.protocol.FinderMode
import com.hotspotmonitor.agent.protocol.PlugType
import com.hotspotmonitor.agent.protocol.Severity
import com.hotspotmonitor.agent.protocol.StopReason
import com.hotspotmonitor.agent.protocol.WatchdogSettings
import com.hotspotmonitor.agent.protocol.jsonObjectOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * Local recovery rules of plan 6.5, evaluated every [TICK_MS] on the service scope with thresholds
 * re-read from settings.watchdog on every tick:
 *
 * - internet ladder (data cycle -> airplane cycle -> reboot within the limit), driven by the
 *   telemetry internet check, paused while a user command has an armed auto-revert, while the
 *   Finder runs a speed test and while the radio rests for heat,
 * - USB tethering back on when USB is connected but tethering is off (unless a user wants it off),
 * - charge limit and heat stop through graph.charge, heat alerts, radio rest at temp_radio_rest_c,
 * - weak power supply, USB power loss (logged), the optional weekly reboot.
 *
 * Charge limit, heat stop and weak-power detection protect the battery and keep running when
 * settings.watchdog.enabled is false; the recovery actions do not.
 * Every action is reported as a `watchdog_action` event {rule, action, step, outcome}.
 */
class WatchdogImpl(private val graph: AgentGraph) : Watchdog {
    private val _state = MutableStateFlow(WatchdogState(enabled = graph.settings.current.watchdog.enabled))
    override val state: StateFlow<WatchdogState> = _state.asStateFlow()

    // Rule state; touched only by the tick coroutine unless marked @Volatile.
    private val ladder = InternetLadder()
    private val heat = HeatRule()
    private val weakPower = WeakPowerDetector()
    private val tetherFix = TetherFixRule()
    private var downSinceServerMs: Long? = null
    private var lastPlugged: Boolean? = null
    private var lastSuppressedLogMs = Long.MIN_VALUE / 2
    private var radioRestJob: Job? = null

    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var simulatedUntilMs: Long? = null
    @Volatile private var restEndedAtMs: Long? = null
    @Volatile private var ladderRunning = false
    @Volatile private var tetherRunning = false
    @Volatile private var restRunning = false
    @Volatile private var scheduleRunning = false

    override fun start(scope: CoroutineScope) {
        this.scope = scope
        scope.launch {
            while (true) {
                tick()
                delay(TICK_MS)
            }
        }
    }

    override fun stop() {
        scope = null
        radioRestJob = null
        ladderRunning = false
        tetherRunning = false
        restRunning = false
        scheduleRunning = false
        _state.update { it.copy(stepRunning = false, radioResting = false) }
    }

    override fun simulateInternetDown(durationS: Int) {
        val seconds = durationS.coerceIn(1, MAX_SIMULATION_S)
        simulatedUntilMs = graph.clock.elapsedMs() + seconds * 1000L
        _state.update { it.copy(simulatedUntilMs = graph.clock.serverNow() + seconds * 1000L) }
        emitAction(
            RULE_INTERNET, ACTION_SIMULATE, 0, "started",
            "Simulating internet loss for $seconds s (the recovery ladder acts for real)",
            Severity.INFO, mapOf("duration_s" to seconds),
        )
    }

    // --- tick -----------------------------------------------------------------------------------

    private suspend fun tick() {
        val s = graph.settings.current.watchdog
        val now = graph.clock.elapsedMs()
        if (_state.value.enabled != s.enabled) _state.update { it.copy(enabled = s.enabled) }
        rule("simulation") { expireSimulation(now) }
        rule("heat") { heatRule(s, now) }
        rule("charge limit") { graph.charge.applyLimit(s.chargeLimitPct, s.chargeResumeDropPct, graph.telemetry.power.value?.percent) }
        rule("power") { powerRule(s, now) }
        if (!s.enabled) {
            if (ladder.downSinceMs != null) {
                ladder.reset()
                publishLadder(now)
            }
            tetherFix.reset()
            return
        }
        rule("internet") { internetRule(s, now) }
        rule("tether") { tetherRule(s, now) }
        rule("weekly reboot") { weeklyRebootRule(s) }
    }

    private suspend fun rule(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            graph.log.e(TAG, "Rule '$name' failed", e)
        }
    }

    // --- internet ladder ------------------------------------------------------------------------

    private fun expireSimulation(now: Long) {
        val until = simulatedUntilMs ?: return
        if (now < until) return
        simulatedUntilMs = null
        _state.update { it.copy(simulatedUntilMs = null) }
        emitAction(RULE_INTERNET, ACTION_SIMULATE, 0, "ended", "Simulated internet loss ended", Severity.INFO)
    }

    private fun isSimulating(now: Long): Boolean = simulatedUntilMs?.let { now < it } == true

    private suspend fun internetRule(s: WatchdogSettings, now: Long) {
        if (_state.value.radioResting) {
            // A heat rest takes the radio down on purpose: measure the outage afresh afterwards.
            if (ladder.downSinceMs != null) {
                ladder.reset()
                publishLadder(now)
            }
            return
        }
        val simulated = isSimulating(now)
        val online: Boolean = if (simulated) false else graph.telemetry.net.value?.online ?: return
        ladder.observe(now, online)?.let { emitRecovered(it) }
        publishLadder(now)
        if (online) return
        val step = ladder.due(now, s) ?: return
        actionBlocker()?.let { blocker ->
            logSuppressed(now, "Internet down: ${step.action} postponed because $blocker")
            return
        }
        if (!simulated) {
            // Confirm with a fresh check: the telemetry reading may be a few seconds old.
            val fresh = try {
                graph.telemetry.checkInternetNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                graph.log.w(TAG, "Internet re-check failed", e)
                null
            }
            if (fresh?.online == true) {
                ladder.observe(graph.clock.elapsedMs(), true)?.let { emitRecovered(it) }
                publishLadder(now)
                return
            }
        }
        runLadderStep(step, s, now)
    }

    private suspend fun runLadderStep(step: LadderStep, s: WatchdogSettings, now: Long) {
        val owner = "watchdog:${step.action}"
        if (!RadioActivity.tryAcquire(owner)) {
            logSuppressed(now, "Internet down: ${step.action} waits for ${RadioActivity.current.value}")
            return
        }
        val downS = (ladder.downForMs(now) ?: 0L) / 1000
        ladderRunning = true
        publishRunning()
        try {
            when (step) {
                LadderStep.DATA_CYCLE -> {
                    val r = graph.controls.cycleData(s.dataOffS)
                    ladder.onStepDone(step, now)
                    emitAction(
                        RULE_INTERNET, step.action, step.step, outcome(r),
                        if (r.ok) "Internet down $downS s: cycled mobile data" else "Internet down $downS s: data cycle failed (${r.error})",
                    )
                }
                LadderStep.AIRPLANE_CYCLE -> {
                    val r = graph.controls.airplaneCycle(s.airplaneOffS)
                    ladder.onStepDone(step, now)
                    emitAction(
                        RULE_INTERNET, step.action, step.step, outcome(r),
                        if (r.ok) "Internet down $downS s: airplane mode on for ${s.airplaneOffS} s" else "Internet down $downS s: airplane cycle failed (${r.error})",
                    )
                }
                LadderStep.REBOOT -> rebootStep(step, s, now, downS)
            }
        } finally {
            RadioActivity.release(owner)
            ladderRunning = false
            publishRunning()
        }
    }

    private suspend fun rebootStep(step: LadderStep, s: WatchdogSettings, now: Long, downS: Long) {
        val allowed = graph.rebootLimiter.canReboot(graph.clock.systemNowMs())
        val result = reboot(CommandSource.WATCHDOG, "internet down $downS s") {
            graph.events.emitAndStore(
                EventKind.WATCHDOG_ACTION, Severity.WARNING, "Internet down $downS s: rebooting",
                actionData(RULE_INTERNET, step.action, step.step, OUTCOME_REBOOTING),
            )
        }
        // Only reached when the phone did not reboot.
        ladder.onStepDone(step, now, rebootStarted = false)
        val message = if (!allowed) {
            "Internet down $downS s: reboot blocked by the reboot limit; retrying every ${s.rebootRetryS} s"
        } else {
            "Internet down $downS s: reboot failed (${result.error})"
        }
        emitAction(RULE_INTERNET, step.action, step.step, if (!allowed) OUTCOME_BLOCKED else OUTCOME_FAILED, message)
    }

    /**
     * Reboots through ControlsImpl with [beforeReboot] storing the event that explains the reboot.
     * Returns only if the phone did not reboot (after waiting [REBOOT_WAIT_MS] when the command ran).
     */
    private suspend fun reboot(source: CommandSource, reason: String, beforeReboot: suspend () -> Unit): ActionResult {
        val controls = graph.controls as? ControlsImpl
        val result = controls?.reboot(source, reason, beforeReboot) ?: graph.controls.reboot(source, reason)
        if (!result.ok) return result
        delay(REBOOT_WAIT_MS)
        return ActionResult.failure("the reboot command returned, but the phone did not restart", method = result.method)
    }

    private fun emitRecovered(recovery: Recovery) {
        val step = recovery.lastStep
        emitAction(
            RULE_INTERNET, step.action, step.step, OUTCOME_RECOVERED,
            "Internet back after ${recovery.downForMs / 1000} s (last step: ${describe(step)})",
            Severity.INFO, mapOf("down_s" to recovery.downForMs / 1000),
        )
    }

    private fun publishLadder(now: Long) {
        val since = ladder.downSinceMs
        downSinceServerMs = when {
            since == null -> null
            downSinceServerMs == null -> graph.clock.serverNow() - (now - since)
            else -> downSinceServerMs
        }
        val down = downSinceServerMs
        _state.update {
            if (it.internetDownSinceMs == down && it.ladderStep == ladder.step) it
            else it.copy(internetDownSinceMs = down, ladderStep = ladder.step)
        }
    }

    // --- tethering ------------------------------------------------------------------------------

    private suspend fun tetherRule(s: WatchdogSettings, now: Long) {
        if (!s.tetherFix) {
            tetherFix.reset()
            return
        }
        val tether = graph.telemetry.tether.value ?: return
        val usb = tether.usbConnected ?: return
        val on = tether.usbTetherOn ?: return
        if (!usb && TetherIntent.userWantsOff(graph.prefs)) {
            TetherIntent.set(graph.prefs, false)
            graph.log.i(TAG, "USB disconnected: a user's 'tethering off' no longer applies")
        }
        if (!usb || on) {
            tetherFix.observe(now, usbConnected = usb, tetherOn = on, suppressed = false, fixAfterS = s.tetherFixAfterS)
            return
        }
        val suppressed = TetherIntent.userWantsOff(graph.prefs) || _state.value.radioResting || armedReverts() > 0
        if (!tetherFix.observe(now, usbConnected = true, tetherOn = false, suppressed = suppressed, fixAfterS = s.tetherFixAfterS)) return
        val owner = "watchdog:usb_tethering"
        if (!RadioActivity.tryAcquire(owner)) return
        tetherRunning = true
        publishRunning()
        try {
            val r = graph.controls.setUsbTethering(true, s.usbFunction)
            tetherFix.onAttempt(graph.clock.elapsedMs(), s.tetherFixAfterS)
            emitAction(
                RULE_TETHER, CommandNames.USB_TETHERING, tetherFix.attempts, outcome(r),
                if (r.ok) "USB connected but tethering was off: turned it back on (${s.usbFunction})"
                else "USB connected but tethering is off; turning it on failed (${r.error})",
            )
        } finally {
            RadioActivity.release(owner)
            tetherRunning = false
            publishRunning()
        }
    }

    // --- heat, charging and power ---------------------------------------------------------------

    private suspend fun heatRule(s: WatchdogSettings, now: Long) {
        val temp = graph.telemetry.heat.value?.batteryC ?: return
        val canRest = s.enabled && RadioRestPolicy.allowed(now, restEndedAtMs, s.radioRestS, _state.value.radioResting)
        for (signal in heat.evaluate(temp, s, canRest)) {
            when (signal) {
                HeatSignal.StopCharging -> {
                    val r = graph.charge.setCharging(false, StopReason.HEAT)
                    if (!r.ok) graph.log.w(TAG, "Heat stop: could not stop charging (${r.error})")
                }
                HeatSignal.ResumeCharging -> graph.charge.setCharging(true, StopReason.HEAT)
                is HeatSignal.Level -> emitHeatLevel(signal, temp, s)
                HeatSignal.RestRadio -> startRadioRest(s, temp)
            }
        }
        if (_state.value.heatStop != heat.heatStop) _state.update { it.copy(heatStop = heat.heatStop) }
    }

    private fun emitHeatLevel(signal: HeatSignal.Level, temp: Double, s: WatchdogSettings) {
        val t = celsius(temp)
        when (signal.to) {
            HeatLevel.WARNING -> graph.events.emit(
                EventKind.TEMP_WARNING, Severity.WARNING, "Battery $t °C (warning at ${celsius(s.tempWarnC)} °C)",
                jsonObjectOf("battery_c" to temp, "threshold_c" to s.tempWarnC),
            )
            HeatLevel.CRITICAL -> graph.events.emit(
                EventKind.TEMP_CRITICAL, Severity.CRITICAL,
                "Battery $t °C: charging stopped until it is below ${celsius(s.tempResumeC)} °C",
                jsonObjectOf("battery_c" to temp, "threshold_c" to s.tempStopChargeC, "resume_c" to s.tempResumeC),
            )
            HeatLevel.NORMAL -> graph.events.emit(
                EventKind.TEMP_NORMAL, Severity.INFO, "Battery back to $t °C",
                jsonObjectOf("battery_c" to temp, "from" to signal.from.name.lowercase()),
            )
        }
    }

    private fun startRadioRest(s: WatchdogSettings, temp: Double) {
        if (radioRestJob?.isActive == true) return
        val serviceScope = scope ?: return
        val owner = "watchdog:radio_rest"
        if (!RadioActivity.tryAcquire(owner)) {
            logSuppressed(graph.clock.elapsedMs(), "Radio rest waits for ${RadioActivity.current.value}")
            return
        }
        val seconds = s.radioRestS.coerceAtLeast(1)
        restRunning = true
        _state.update { it.copy(radioResting = true) }
        publishRunning()
        graph.events.emit(
            EventKind.RADIO_REST, Severity.WARNING,
            "Battery ${celsius(temp)} °C: radio off for $seconds s to cool down",
            jsonObjectOf("seconds" to seconds, "battery_c" to temp),
        )
        val job = serviceScope.launch {
            val r = graph.controls.restRadio(seconds)
            emitAction(
                RULE_HEAT, ACTION_RADIO_REST, 1, outcome(r),
                if (r.ok) "Radio rested $seconds s after the battery reached ${celsius(temp)} °C" else "Radio rest failed (${r.error})",
                extra = mapOf("seconds" to seconds, "battery_c" to temp),
            )
        }
        job.invokeOnCompletion { cause ->
            RadioActivity.release(owner)
            restRunning = false
            restEndedAtMs = graph.clock.elapsedMs()
            _state.update { it.copy(radioResting = false) }
            publishRunning()
            if (cause != null) graph.log.w(TAG, "Radio rest interrupted: ${cause.message}")
        }
        radioRestJob = job
    }

    private fun powerRule(s: WatchdogSettings, now: Long) {
        val power = graph.telemetry.power.value ?: return
        val plugged = power.plug != null && power.plug != PlugType.NONE
        if (lastPlugged == true && !plugged) {
            graph.log.w(TAG, "USB power lost (battery ${power.percent ?: "?"} %); telemetry reports power_lost")
        }
        lastPlugged = plugged
        val chargingAllowed = graph.charge.state.value.chargingEnabled != false
        val alert = weakPower.observe(now, plugged, chargingAllowed, power.percent, power.currentMa, s.weakPowerMin) ?: return
        graph.events.emit(
            EventKind.WEAK_POWER, Severity.WARNING,
            "Power supply too weak: battery fell from ${alert.fromPct} % to ${alert.toPct} % in ${alert.minutes} min while plugged in",
            jsonObjectOf(
                "from_pct" to alert.fromPct, "to_pct" to alert.toPct, "minutes" to alert.minutes,
                "current_ma" to alert.avgCurrentMa, "plug" to power.plug?.name?.lowercase(),
            ),
        )
    }

    // --- weekly reboot --------------------------------------------------------------------------

    private suspend fun weeklyRebootRule(s: WatchdogSettings) {
        val cfg = s.weeklyReboot
        if (!cfg.enabled) return
        val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(graph.clock.systemNowMs()), ZoneId.systemDefault())
        val key = WeeklySchedule.dueOccurrence(local, cfg, graph.prefs.getString(KEY_WEEKLY_LAST)) ?: return
        val now = graph.clock.elapsedMs()
        if (_state.value.stepRunning) return // retried on the next tick, within the window
        actionBlocker()?.let { blocker ->
            logSuppressed(now, "Weekly reboot postponed because $blocker")
            return
        }
        val owner = "watchdog:weekly_reboot"
        if (!RadioActivity.tryAcquire(owner)) return
        scheduleRunning = true
        publishRunning()
        try {
            // Stored before rebooting, so the phone never reboots twice for the same week.
            graph.prefs.putString(KEY_WEEKLY_LAST, key)
            val allowed = graph.rebootLimiter.canReboot(graph.clock.systemNowMs())
            val result = reboot(CommandSource.SCHEDULE, "weekly reboot") {
                graph.events.emitAndStore(
                    EventKind.WATCHDOG_ACTION, Severity.INFO, "Weekly reboot (${cfg.time})",
                    actionData(RULE_WEEKLY, CommandNames.REBOOT, 1, OUTCOME_REBOOTING),
                )
            }
            emitAction(
                RULE_WEEKLY, CommandNames.REBOOT, 1, if (!allowed) OUTCOME_BLOCKED else OUTCOME_FAILED,
                if (!allowed) "Weekly reboot skipped: reboot limit reached" else "Weekly reboot failed (${result.error})",
            )
        } finally {
            RadioActivity.release(owner)
            scheduleRunning = false
            publishRunning()
        }
    }

    // --- helpers --------------------------------------------------------------------------------

    /** Why recovery actions must wait now, or null. */
    private suspend fun actionBlocker(): String? {
        if (graph.finder.mode.value == FinderMode.SPEED) return "a Finder speed test is running"
        if (armedReverts() > 0) return "a user command with auto-revert is waiting for confirmation"
        return null
    }

    private suspend fun armedReverts(): Int = try {
        graph.db.armedRevertDao().count()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        graph.log.w(TAG, "Could not read armed reverts", e)
        0
    }

    private fun publishRunning() {
        val running = ladderRunning || tetherRunning || restRunning || scheduleRunning
        _state.update { if (it.stepRunning == running) it else it.copy(stepRunning = running) }
    }

    private fun emitAction(
        rule: String,
        action: String,
        step: Int,
        outcome: String,
        message: String,
        severity: Severity = Severity.WARNING,
        extra: Map<String, Any?> = emptyMap(),
    ) {
        graph.events.emit(EventKind.WATCHDOG_ACTION, severity, message, actionData(rule, action, step, outcome, extra))
        _state.update { it.copy(lastAction = message, lastActionAtMs = graph.clock.serverNow()) }
    }

    private fun actionData(rule: String, action: String, step: Int, outcome: String, extra: Map<String, Any?> = emptyMap()): JsonObject {
        val pairs = ArrayList<Pair<String, Any?>>()
        pairs += "rule" to rule
        pairs += "action" to action
        pairs += "step" to step
        pairs += "outcome" to outcome
        for ((k, v) in extra) pairs += k to v
        return jsonObjectOf(*pairs.toTypedArray())
    }

    private fun logSuppressed(now: Long, message: String) {
        if (now - lastSuppressedLogMs < SUPPRESSED_LOG_EVERY_MS) return
        lastSuppressedLogMs = now
        graph.log.i(TAG, message)
    }

    private fun outcome(r: ActionResult): String = if (r.ok) OUTCOME_DONE else OUTCOME_FAILED

    private fun describe(step: LadderStep): String = when (step) {
        LadderStep.DATA_CYCLE -> "mobile data cycled"
        LadderStep.AIRPLANE_CYCLE -> "airplane mode cycled"
        LadderStep.REBOOT -> "reboot attempted"
    }

    private fun celsius(value: Double): String = String.format(Locale.US, "%.1f", value)

    companion object {
        private const val TAG = "watchdog"
        const val TICK_MS = 5_000L
        private const val MAX_SIMULATION_S = 86_400
        private const val REBOOT_WAIT_MS = 60_000L
        private const val SUPPRESSED_LOG_EVERY_MS = 60_000L
        private const val KEY_WEEKLY_LAST = "watchdog.weekly_reboot_last"

        private const val RULE_INTERNET = "internet"
        private const val RULE_TETHER = "tether"
        private const val RULE_HEAT = "heat"
        private const val RULE_WEEKLY = "weekly_reboot"
        private const val ACTION_SIMULATE = "simulate"
        private const val ACTION_RADIO_REST = "radio_rest"
        private const val OUTCOME_DONE = "done"
        private const val OUTCOME_FAILED = "failed"
        private const val OUTCOME_RECOVERED = "recovered"
        private const val OUTCOME_BLOCKED = "blocked"
        private const val OUTCOME_REBOOTING = "rebooting"
    }
}
