package com.hotspotmonitor.agent.control

import com.hotspotmonitor.agent.core.ActionResult
import com.hotspotmonitor.agent.core.AgentGraph
import com.hotspotmonitor.agent.core.Clock
import com.hotspotmonitor.agent.core.CommandExecutor
import com.hotspotmonitor.agent.core.Ids
import com.hotspotmonitor.agent.db.ArmedRevertEntity
import com.hotspotmonitor.agent.db.CommandLogEntity
import com.hotspotmonitor.agent.protocol.AgentJson
import com.hotspotmonitor.agent.protocol.BufferedMsg
import com.hotspotmonitor.agent.protocol.CapabilityEntry
import com.hotspotmonitor.agent.protocol.CapabilityMap
import com.hotspotmonitor.agent.protocol.CmdAckMsg
import com.hotspotmonitor.agent.protocol.CmdMsg
import com.hotspotmonitor.agent.protocol.CmdRejectReason
import com.hotspotmonitor.agent.protocol.CmdResultMsg
import com.hotspotmonitor.agent.protocol.CmdRevertMsg
import com.hotspotmonitor.agent.protocol.COMMANDS
import com.hotspotmonitor.agent.protocol.CommandNames
import com.hotspotmonitor.agent.protocol.CommandSource
import com.hotspotmonitor.agent.protocol.CommandSpec
import com.hotspotmonitor.agent.protocol.CommandStatus
import com.hotspotmonitor.agent.protocol.EventKind
import com.hotspotmonitor.agent.protocol.FinderSpot
import com.hotspotmonitor.agent.protocol.InvalidArgsException
import com.hotspotmonitor.agent.protocol.NetworkMode
import com.hotspotmonitor.agent.protocol.RevertConfirm
import com.hotspotmonitor.agent.protocol.RevertState
import com.hotspotmonitor.agent.protocol.Severity
import com.hotspotmonitor.agent.protocol.SpeedSession
import com.hotspotmonitor.agent.protocol.StopReason
import com.hotspotmonitor.agent.protocol.TelemetryTier
import com.hotspotmonitor.agent.protocol.jsonObjectOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.Collections

/**
 * The command pipeline of docs/protocol.md 3.5.
 *
 * [handle] runs the checks in protocol order (expiry -> known and supported -> power_off policy ->
 * reboot limit -> args -> busy), stores the audit row, enqueues `cmd_ack` and hands execution to a
 * coroutine, so the SyncClient's receive loop is never blocked by a long command. Every accepted
 * command gets a `cmd_result` (also when it fails or times out).
 *
 * A command id is never executed twice: a duplicate (the Server re-sends a command whose ack it has
 * not seen) is ignored and its latest report is enqueued again ([DuplicateReports]).
 *
 * Auto-revert: the undo action is computed and persisted (ArmedRevertEntity, phone wall-clock times,
 * see [RevertRestore]) before the change is applied; [RevertTracker] drives the confirmation (probe
 * round-trip or `cmd_keep`) and the deadline. A newer command on the same state supersedes an armed
 * revert ([RevertTargets]): the earlier one is reported `kept` without its undo, and when the newer
 * command arms a revert itself it inherits the earlier undo, so an unconfirmed chain still returns to
 * the last state that worked. Persisted reverts are restored first at start; commands that arm a
 * revert and `cmd_keep` wait for that ([restored]). Reports (`cmd_ack`, `cmd_result`, `cmd_revert`)
 * all go through the outbox.
 */
class CommandExecutorImpl(private val graph: AgentGraph) : CommandExecutor {
    private val _capabilities = MutableStateFlow<CapabilityMap>(emptyMap())
    override val capabilityMap: StateFlow<CapabilityMap> = _capabilities.asStateFlow()

    private val probeMutex = Mutex()
    private val reverts = RevertTracker()
    private val inFlight: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** Serialises the armed_revert table and the tracker: restore, prepare, commit, abandon, supersede. */
    private val revertMutex = Mutex()

    /** Command ids whose revert is being undone or kept right now (row still present). */
    private val resolving: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** Completed once the persisted reverts of this start are back in the tracker. */
    @Volatile
    private var restored = CompletableDeferred<Unit>()

    /** The ack of each recent command id, in case its command log row could not be written. */
    private val recentAcks = object : LinkedHashMap<String, CmdAckMsg>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CmdAckMsg>?): Boolean = size > RECENT_IDS
    }

    @Volatile
    private var scope: CoroutineScope? = null

    private val log get() = graph.log
    private val revertDao get() = graph.db.armedRevertDao()
    private val commandLog get() = graph.db.commandLogDao()
    private val capabilitySerializer = MapSerializer(String.serializer(), CapabilityEntry.serializer())

    private sealed class CheckResult {
        class Rejected(val reason: CmdRejectReason, val message: String) : CheckResult()

        class Accepted(val parsed: ParsedCommand, val spec: CommandSpec, private val radioOwner: String?) : CheckResult() {
            fun release() {
                radioOwner?.let { RadioActivity.release(it) }
            }
        }
    }

    /** An undo persisted before the change; armed in the tracker once the change succeeded. */
    private data class PreparedRevert(
        val commandId: String,
        val undoName: String,
        val undoArgs: JsonObject,
        val mode: RevertConfirm,
        val afterS: Int,
    )

    // --- lifecycle ------------------------------------------------------------------------------

    override fun start(scope: CoroutineScope) {
        this.scope = scope
        if (restored.isCompleted) restored = CompletableDeferred()
        val gate = restored
        scope.launch {
            // First and quick (a DB read): commands and cmd_keep from the new link wait for it.
            try {
                guarded("restore armed reverts") { restoreArmedReverts() }
            } finally {
                gate.complete(Unit)
            }
            guarded("pending reboot result") { reportPendingReboot() }
            guarded("load capabilities") { loadStoredCapabilities() }
            guarded("restore interrupted cycles") { (graph.controls as? ControlsImpl)?.restoreInterruptedCycles() }
            guarded("prune command log") { commandLog.deleteOlderThan(graph.clock.serverNow() - COMMAND_LOG_RETENTION_MS) }
        }
        scope.launch { revertLoop() }
        scope.launch { initialProbes() }
        scope.launch {
            graph.settings.settings.map { it.commands.allowPowerOff }.distinctUntilChanged().drop(1).collect {
                guarded("power_off capability") { updateCapability(CommandNames.POWER_OFF) }
            }
        }
        scope.launch {
            graph.profiles.profile.drop(1).collect {
                guarded("capability probe after a profile change") { probeCapabilities() }
            }
        }
    }

    // --- Server commands ------------------------------------------------------------------------

    override suspend fun handle(cmd: CmdMsg) {
        if (!inFlight.add(cmd.id)) {
            // Still being checked or running. Its ack is sent again only once it exists: an early
            // "accepted" could otherwise block a rejection that is still to come.
            val ack = synchronized(recentAcks) { recentAcks[cmd.id] }
            log.i(TAG, "Duplicate ${cmd.name} ${cmd.id}: still running" + if (ack != null) "; acknowledging it again" else "")
            if (ack != null) enqueueReports(listOf(ack.copy(ts = graph.clock.isoNow())))
            return
        }
        var accepted: CheckResult.Accepted? = null
        var handedOff = false
        try {
            val handled = commandLog.get(cmd.id)
            if (handled != null) {
                log.i(TAG, "Duplicate ${cmd.name} ${cmd.id}: already handled (${handled.status}); sending its report again")
                enqueueReports(DuplicateReports.forLog(handled, armedDeadlineIso(cmd.id), graph.clock.isoNow()))
                return
            }
            val recent = synchronized(recentAcks) { recentAcks[cmd.id] }
            if (recent != null) {
                log.i(TAG, "Duplicate ${cmd.name} ${cmd.id}: already handled (no log row); acknowledging it again")
                enqueueReports(listOf(recent.copy(ts = graph.clock.isoNow())))
                return
            }
            when (val decision = runChecks(cmd.id, cmd.name, cmd.args, cmd.sentAt)) {
                is CheckResult.Rejected -> {
                    val ack = CmdAckMsg(id = cmd.id, accepted = false, reason = decision.reason, ts = graph.clock.isoNow())
                    synchronized(recentAcks) { recentAcks[cmd.id] = ack }
                    saveLog(cmd.id, cmd.name, cmd.args, CommandSource.DASHBOARD, CommandStatus.REJECTED, "${wire(decision.reason)}: ${decision.message}")
                    graph.outbox.enqueue(ack)
                    graph.sync.kick()
                    log.i(TAG, "Rejected ${cmd.name} ${cmd.id}: ${wire(decision.reason)} (${decision.message})")
                }
                is CheckResult.Accepted -> {
                    accepted = decision
                    val ack = CmdAckMsg(id = cmd.id, accepted = true, ts = graph.clock.isoNow())
                    synchronized(recentAcks) { recentAcks[cmd.id] = ack }
                    saveLog(cmd.id, cmd.name, cmd.args, CommandSource.DASHBOARD, CommandStatus.ACKED, null)
                    graph.outbox.enqueue(ack)
                    graph.sync.kick()
                    val job = (scope ?: graph.appScope).launch { execute(cmd, decision) }
                    job.invokeOnCompletion {
                        decision.release()
                        inFlight.remove(cmd.id)
                    }
                    handedOff = true
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(TAG, "Could not handle ${cmd.name} ${cmd.id}", e)
            if (accepted != null) reportResult(cmd.id, ActionResult.failure("Agent error: ${errorText(e)}"), 0, null)
        } finally {
            if (!handedOff) {
                accepted?.release()
                inFlight.remove(cmd.id)
            }
        }
    }

    private suspend fun execute(cmd: CmdMsg, accepted: CheckResult.Accepted) {
        val started = graph.clock.elapsedMs()
        if (accepted.parsed is ParsedCommand.PowerOff) {
            powerOffCommand(cmd.id, started)
            return
        }
        var prepared: PreparedRevert? = null
        val result: ActionResult = try {
            prepared = prepareRevert(cmd, accepted)
            runWithTimeout(accepted.parsed, CommandSource.DASHBOARD, cmd.id)
        } catch (e: CancellationException) {
            // The service is stopping. A persisted revert stays armed and is restored at the next start.
            withContext(NonCancellable) {
                reportResult(cmd.id, ActionResult.failure("The Agent stopped while the command was running"), graph.clock.elapsedMs() - started, null)
                updateLogStatus(cmd.id, CommandStatus.FAILED, "interrupted")
            }
            throw e
        } catch (e: Exception) {
            log.e(TAG, "${cmd.name} ${cmd.id} failed", e)
            ActionResult.failure("Agent error: ${errorText(e)}")
        }
        val durationMs = graph.clock.elapsedMs() - started
        var deadlineIso: String? = null
        val undo = prepared
        if (undo != null) {
            if (result.ok) {
                deadlineIso = commitRevert(undo)
            } else {
                withContext(NonCancellable) { abandonRevert(undo) }
            }
        }
        val parsed = accepted.parsed
        if (result.ok && deadlineIso == null && parsed is ParsedCommand.UsbTethering && !parsed.enabled) {
            // Nothing to confirm (tethering was already off): the user's wish applies right away.
            TetherIntent.set(graph.prefs, true)
        }
        reportResult(cmd.id, result, durationMs, deadlineIso)
        val status = when {
            !result.ok -> CommandStatus.FAILED
            deadlineIso != null -> CommandStatus.REVERT_ARMED
            else -> CommandStatus.DONE
        }
        updateLogStatus(cmd.id, status, result.error ?: result.output)
        log.i(TAG, "${cmd.name} ${cmd.id}: ${if (result.ok) "ok" else "failed (${result.error})"} in $durationMs ms" +
            (deadlineIso?.let { ", revert armed until $it" } ?: ""))
    }

    private suspend fun enqueueReports(reports: List<BufferedMsg>) {
        if (reports.isEmpty()) return
        for (msg in reports) graph.outbox.enqueue(msg)
        graph.sync.kick()
    }

    /** Server-time ISO deadline of the persisted revert of [commandId], or null. */
    private suspend fun armedDeadlineIso(commandId: String): String? = try {
        revertDao.get(commandId)?.let { Clock.formatIso(it.deadlineMs + graph.clock.serverOffsetMs) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(TAG, "Could not read the armed revert of $commandId", e)
        null
    }

    private suspend fun reportResult(id: String, result: ActionResult, durationMs: Long, revertDeadline: String?) {
        graph.outbox.enqueue(
            CmdResultMsg(
                id = id,
                ok = result.ok,
                output = OutputText.truncate(result.output),
                error = result.error?.take(MAX_ERROR_CHARS),
                durationMs = durationMs.coerceAtLeast(0),
                revertDeadline = revertDeadline,
                ts = graph.clock.isoNow(),
            ),
        )
        graph.sync.kick()
    }

    // --- checks (protocol 3.5 order) ------------------------------------------------------------

    private suspend fun runChecks(id: String, name: String, args: JsonObject, sentAt: String?): CheckResult {
        val settings = graph.settings.current
        if (sentAt != null) {
            val now = graph.clock.serverNow()
            if (CommandChecks.isExpired(sentAt, now, settings.commands.expiryS)) {
                val age = CommandChecks.ageMs(sentAt, now)
                val why = if (age == null) "sent_at is not a valid timestamp" else
                    "arrived ${age / 1000} s after it was sent (limit ${settings.commands.expiryS} s)"
                return CheckResult.Rejected(CmdRejectReason.EXPIRED, why)
            }
        }
        val spec = COMMANDS[name] ?: return CheckResult.Rejected(CmdRejectReason.UNSUPPORTED, "unknown command '$name'")
        val entry = ensureCapabilities()[name]
        if (!CommandChecks.capabilityAllows(name, entry)) {
            return CheckResult.Rejected(CmdRejectReason.UNSUPPORTED, entry?.note ?: "not supported on this phone")
        }
        if (name == CommandNames.POWER_OFF && !settings.commands.allowPowerOff) {
            return CheckResult.Rejected(CmdRejectReason.BLOCKED, "power off is blocked by settings.commands.allow_power_off")
        }
        if (spec.isReboot) {
            val now = graph.clock.systemNowMs()
            if (!graph.rebootLimiter.canReboot(now)) {
                val count = graph.rebootLimiter.countInWindow(now)
                return CheckResult.Rejected(
                    CmdRejectReason.REBOOT_LIMIT,
                    "$count of ${settings.watchdog.maxReboots} reboots used in the last ${settings.watchdog.rebootWindowH} h",
                )
            }
        }
        val parsed = try {
            CommandParser.parse(name, args, settings)
        } catch (e: InvalidArgsException) {
            return CheckResult.Rejected(CmdRejectReason.INVALID_ARGS, e.message ?: "invalid arguments")
        }
        var owner: String? = null
        if (CommandChecks.isConflicting(name)) {
            if (graph.watchdog.state.value.stepRunning) {
                return CheckResult.Rejected(CmdRejectReason.BUSY, "a watchdog recovery step is running")
            }
            val who = "command:$id"
            if (!RadioActivity.tryAcquire(who)) {
                return CheckResult.Rejected(CmdRejectReason.BUSY, "${RadioActivity.current.value ?: "another radio action"} is running")
            }
            owner = who
        }
        return CheckResult.Accepted(parsed, spec, owner)
    }

    private suspend fun ensureCapabilities(): CapabilityMap {
        val current = _capabilities.value
        if (current.isNotEmpty()) return current
        return try {
            probeCapabilities()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(TAG, "Capability probe failed", e)
            emptyMap()
        }
    }

    // --- running a command ----------------------------------------------------------------------

    private suspend fun runWithTimeout(parsed: ParsedCommand, source: CommandSource, commandId: String): ActionResult {
        val timeoutMs = timeoutFor(parsed)
        return withTimeoutOrNull(timeoutMs) { runParsed(parsed, source, commandId) }
            ?: ActionResult.failure("Timed out after ${timeoutMs / 1000} s")
    }

    private suspend fun runParsed(p: ParsedCommand, source: CommandSource, commandId: String): ActionResult {
        val c = graph.controls
        return when (p) {
            is ParsedCommand.MobileData -> c.setMobileData(p.enabled)
            is ParsedCommand.DataCycle -> c.cycleData(p.offSeconds)
            is ParsedCommand.Airplane -> c.setAirplane(p.enabled)
            is ParsedCommand.AirplaneCycle -> c.airplaneCycle(p.offSeconds)
            is ParsedCommand.UsbTethering -> c.setUsbTethering(p.enabled, p.function).also {
                if (it.ok && p.enabled) TetherIntent.set(graph.prefs, false)
            }
            is ParsedCommand.Hotspot -> c.setHotspot(p.enabled, p.ssid, p.password)
            is ParsedCommand.Wifi -> c.setWifi(p.enabled)
            is ParsedCommand.Bluetooth -> c.setBluetooth(p.enabled)
            is ParsedCommand.SetNetworkMode -> c.setNetworkMode(p.mode)
            is ParsedCommand.Reboot -> rebootCommand(source, commandId)
            is ParsedCommand.PowerOff -> c.powerOff()
            is ParsedCommand.Screen -> c.setScreen(p.on)
            is ParsedCommand.Brightness -> c.setBrightness(p.level)
            is ParsedCommand.Charging -> graph.charge.setCharging(p.enabled, StopReason.MANUAL)
            is ParsedCommand.ChargeLimit -> setChargeLimit(p.percent)
            is ParsedCommand.AppList -> c.listApps()
            is ParsedCommand.AppForceStop -> c.forceStopApp(p.pkg)
            is ParsedCommand.AppDisable -> c.setAppEnabled(p.pkg, false)
            is ParsedCommand.AppEnable -> c.setAppEnabled(p.pkg, true)
            is ParsedCommand.ProcessList -> c.processList()
            is ParsedCommand.SendSms -> c.sendSms(p.to, p.body)
            is ParsedCommand.Ussd -> c.runUssd(p.code)
            is ParsedCommand.Shell -> c.shell(p.cmd, p.timeoutS)
            is ParsedCommand.Refresh -> {
                graph.sync.publishTelemetry(setOf(TelemetryTier.STATIC, TelemetryTier.SLOW, TelemetryTier.FAST))
                ActionResult.success("Sent static, slow and fast telemetry", "telemetry")
            }
            is ParsedCommand.CapabilityProbe -> {
                val map = probeCapabilities()
                graph.sync.publishTelemetry(setOf(TelemetryTier.STATIC))
                ActionResult.success(AgentJson.encodeToString(capabilitySerializer, map), "capability probe")
            }
            is ParsedCommand.WatchdogSimulate -> {
                graph.watchdog.simulateInternetDown(p.durationS)
                ActionResult.success("The watchdog treats the internet as down for ${p.durationS} s", "watchdog")
            }
            is ParsedCommand.SpeedTest -> {
                val session = graph.finder.speedTest(p.spotId)
                ActionResult.success(AgentJson.encodeToString(SpeedSession.serializer(), session), "finder")
            }
            is ParsedCommand.SpotTest -> {
                val spot = graph.finder.spotTest(p.name, p.orientation, p.durationS)
                ActionResult.success(AgentJson.encodeToString(FinderSpot.serializer(), spot), "finder")
            }
            is ParsedCommand.SetBaseline ->
                if (graph.finder.setBaseline(p.spotId)) {
                    ActionResult.success("Baseline set to ${p.spotId}", "finder")
                } else {
                    ActionResult.failure("Spot ${p.spotId} not found", method = "finder")
                }
            is ParsedCommand.DeleteSpot ->
                if (graph.finder.deleteSpot(p.spotId)) {
                    ActionResult.success("Spot ${p.spotId} deleted", "finder")
                } else {
                    ActionResult.failure("Spot ${p.spotId} not found", method = "finder")
                }
        }
    }

    private fun timeoutFor(p: ParsedCommand): Long = when (p) {
        is ParsedCommand.DataCycle -> (p.offSeconds + CYCLE_MARGIN_S) * 1000L
        is ParsedCommand.AirplaneCycle -> (p.offSeconds + CYCLE_MARGIN_S) * 1000L
        is ParsedCommand.Shell -> (p.timeoutS + SHELL_MARGIN_S) * 1000L
        is ParsedCommand.SpeedTest -> SPEED_TEST_TIMEOUT_MS
        is ParsedCommand.SpotTest -> ((p.durationS ?: graph.settings.current.finder.spotTestS) + CYCLE_MARGIN_S) * 1000L
        is ParsedCommand.Reboot -> REBOOT_GRACE_MS + DEFAULT_TIMEOUT_MS
        is ParsedCommand.CapabilityProbe -> PROBE_TIMEOUT_MS
        else -> DEFAULT_TIMEOUT_MS
    }

    /**
     * Reboot through ControlsImpl so the reboot is recorded and, for a Server command, its id is
     * persisted first: the `cmd_result` is sent after the phone is back (see [reportPendingReboot]).
     */
    private suspend fun rebootCommand(source: CommandSource, commandId: String): ActionResult {
        val reportAfterBoot = source == CommandSource.DASHBOARD
        val reason = if (reportAfterBoot) "command $commandId from the Dashboard" else "${source.wire} request"
        val controls = graph.controls as? ControlsImpl
            ?: return graph.controls.reboot(source, reason)
        val result = controls.reboot(source, reason) {
            if (reportAfterBoot) {
                graph.prefs.putString(KEY_PENDING_REBOOT_ID, commandId)
                graph.prefs.putLong(KEY_PENDING_REBOOT_AT, graph.clock.systemNowMs())
            }
            graph.events.emitAndStore(
                EventKind.COMMAND, Severity.WARNING, "Rebooting: $reason",
                jsonObjectOf("id" to commandId, "name" to CommandNames.REBOOT, "source" to source.wire, "status" to "running"),
            )
        }
        if (!result.ok) {
            clearPendingReboot()
            return result
        }
        // Still alive: give the reboot time to happen before calling it a failure.
        delay(REBOOT_GRACE_MS)
        clearPendingReboot()
        return ActionResult.failure("The reboot command returned, but the phone did not restart", method = result.method)
    }

    private suspend fun reportPendingReboot() {
        val id = graph.prefs.getString(KEY_PENDING_REBOOT_ID) ?: return
        val requestedAt = graph.prefs.getLong(KEY_PENDING_REBOOT_AT, 0)
        clearPendingReboot()
        val sinceRequestMs = graph.clock.systemNowMs() - requestedAt
        // elapsedMs counts from boot: a reboot happened if the request is older than the uptime.
        val rebooted = requestedAt > 0 && sinceRequestMs > graph.clock.elapsedMs() + BOOT_SLACK_MS
        val result = if (rebooted) {
            ActionResult.success("Phone rebooted; the Agent was back ${sinceRequestMs / 1000} s after the request", "svc power reboot")
        } else {
            ActionResult.failure("The Agent restarted, but the phone did not reboot")
        }
        reportResult(id, result, sinceRequestMs, null)
        updateLogStatus(id, if (rebooted) CommandStatus.DONE else CommandStatus.FAILED, result.error ?: result.output)
        log.i(TAG, "Reboot command $id: ${if (rebooted) "completed" else "did not reboot"}")
    }

    /** The phone dies with the command, so the `cmd_result` goes out first; a failure becomes an `error` event. */
    private suspend fun powerOffCommand(id: String, started: Long) {
        reportResult(id, ActionResult.success("Shutting down", ControlCommands.SHUTDOWN.first()), graph.clock.elapsedMs() - started, null)
        updateLogStatus(id, CommandStatus.DONE, "shutting down")
        val result = try {
            withTimeoutOrNull(DEFAULT_TIMEOUT_MS) { graph.controls.powerOff() } ?: ActionResult.failure("timed out")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ActionResult.failure(errorText(e))
        }
        if (!result.ok) {
            log.e(TAG, "Power off $id failed: ${result.error}")
            graph.events.emit(
                EventKind.ERROR, Severity.WARNING, "Power off failed: ${result.error}",
                jsonObjectOf("where" to CommandNames.POWER_OFF, "message" to (result.error ?: "").take(MAX_ERROR_CHARS)),
            )
            updateLogStatus(id, CommandStatus.FAILED, result.error)
        }
    }

    private fun clearPendingReboot() {
        graph.prefs.remove(KEY_PENDING_REBOOT_ID, KEY_PENDING_REBOOT_AT)
    }

    private suspend fun setChargeLimit(percent: Int): ActionResult {
        graph.settings.updateLocal { it.copy(watchdog = it.watchdog.copy(chargeLimitPct = percent)) }
        (graph.charge as? ChargeControllerImpl)?.clearManualOverride()
        val drop = graph.settings.current.watchdog.chargeResumeDropPct.coerceAtLeast(1)
        val applied = graph.charge.applyLimit(percent, drop, graph.telemetry.power.value?.percent)
        val summary = "Charge limit $percent % (charging resumes at ${percent - drop} %)"
        return when {
            applied == null -> ActionResult.success(summary, graph.charge.state.value.method)
            applied.ok -> ActionResult.success("$summary; ${applied.output}", applied.method)
            else -> ActionResult.failure("$summary, but ${applied.error}", method = applied.method)
        }
    }

    // --- local commands -------------------------------------------------------------------------

    override suspend fun runLocal(name: String, args: JsonObject, source: CommandSource): ActionResult {
        val id = Ids.command()
        val started = graph.clock.elapsedMs()
        val decision = try {
            runChecks(id, name, args, sentAt = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(TAG, "Checks for local $name failed", e)
            return ActionResult.failure("Agent error: ${errorText(e)}")
        }
        val result: ActionResult
        val status: CommandStatus
        when (decision) {
            is CheckResult.Rejected -> {
                result = ActionResult.failure("Rejected (${wire(decision.reason)}): ${decision.message}")
                status = CommandStatus.REJECTED
            }
            is CheckResult.Accepted -> {
                result = try {
                    if (source != CommandSource.WATCHDOG && source != CommandSource.SCHEDULE) {
                        awaitRestored()
                        revertMutex.withLock { supersedeLocked(id, name) }
                    }
                    runWithTimeout(decision.parsed, source, id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.e(TAG, "Local $name failed", e)
                    ActionResult.failure("Agent error: ${errorText(e)}")
                } finally {
                    decision.release()
                }
                status = if (result.ok) CommandStatus.DONE else CommandStatus.FAILED
                val parsed = decision.parsed
                if (result.ok && parsed is ParsedCommand.UsbTethering && !parsed.enabled && source == CommandSource.LOCAL_UI) {
                    TetherIntent.set(graph.prefs, true)
                }
            }
        }
        val durationMs = graph.clock.elapsedMs() - started
        saveLog(id, name, args, source, status, result.error ?: result.output)
        graph.events.emit(
            EventKind.COMMAND,
            if (result.ok) Severity.INFO else Severity.WARNING,
            "${label(name)} (${source.wire}): ${if (result.ok) "done" else result.error ?: "failed"}",
            jsonObjectOf(
                "id" to id, "name" to name, "args" to redact(args), "source" to source.wire,
                "status" to status.wire, "ok" to result.ok, "output" to result.output.take(EVENT_OUTPUT_CHARS),
                "error" to result.error, "duration_ms" to durationMs,
            ),
        )
        return result
    }

    // --- auto-revert ----------------------------------------------------------------------------

    override fun onProbeAck(id: String) {
        val confirmed = reverts.onProbeAck(id)
        if (confirmed.isEmpty()) return
        launchReport("keep after probe_ack") {
            val now = graph.clock.elapsedMs()
            for (entry in confirmed) {
                keep(entry, "Server round-trip confirmed ${(now - entry.appliedAtMs) / 1000} s after the change")
            }
        }
    }

    override fun onKeep(id: String) {
        val entry = reverts.onKeep(id)
        if (entry != null) {
            launchReport("keep after cmd_keep") { keep(entry, KEPT_FROM_DASHBOARD) }
            return
        }
        if (restored.isCompleted) {
            log.i(TAG, "cmd_keep for $id: no armed revert (already kept or reverted)")
            return
        }
        // Arrived before the persisted reverts were restored: keep it once they are.
        launchReport("keep after restore") {
            awaitRestored()
            val late = reverts.onKeep(id)
            if (late == null) {
                log.i(TAG, "cmd_keep for $id: no armed revert (already kept or reverted)")
            } else {
                keep(late, KEPT_FROM_DASHBOARD)
            }
        }
    }

    override fun onConnected() {
        reverts.onConnected()
    }

    /**
     * Supersedes armed reverts on the same state, then persists this command's undo when it needs a
     * revert. Runs before the change is applied.
     */
    private suspend fun prepareRevert(cmd: CmdMsg, accepted: CheckResult.Accepted): PreparedRevert? {
        awaitRestored()
        return revertMutex.withLock {
            val superseded = supersedeLocked(cmd.id, cmd.name)
            val mode = cmd.revertConfirm ?: accepted.spec.revertFor(cmd.args) ?: return@withLock null
            // An earlier unconfirmed change on this state: undo back to the state before it.
            val inherited = superseded.firstOrNull()?.let { it.undoName to it.undoArgs }
            val (undoName, undoArgs) = inherited ?: undoFor(accepted.parsed) ?: return@withLock null
            val afterS = (cmd.revertAfterS ?: graph.settings.current.commands.revertAfterS).coerceIn(MIN_REVERT_S, MAX_REVERT_S)
            val now = graph.clock.systemNowMs()
            revertDao.upsert(
                ArmedRevertEntity(
                    commandId = cmd.id,
                    undoName = undoName,
                    undoArgsJson = undoArgs.toString(),
                    deadlineMs = now + afterS * 1000L,
                    confirmMode = mode.wire,
                    appliedAtMs = now,
                ),
            )
            PreparedRevert(cmd.id, undoName, undoArgs, mode, afterS)
        }
    }

    /**
     * A newer command ([byId], [name]) changes a state with armed reverts of earlier commands: they
     * are reported `kept` (the user took the state over; no undo, no `revert` event). Call with
     * [revertMutex] held. Returns the superseded entries, oldest first.
     */
    private suspend fun supersedeLocked(byId: String, name: String): List<ArmedRevert> {
        val target = RevertTargets.of(name) ?: return emptyList()
        val superseded = reverts.supersede(target, byId)
        for (entry in superseded) {
            val detail = "Superseded by a newer ${label(name)} command ($byId)"
            graph.outbox.enqueue(CmdRevertMsg(id = entry.commandId, state = RevertState.KEPT, detail = detail, ts = graph.clock.isoNow()))
            deleteRevertRow(entry.commandId)
            updateLogStatus(entry.commandId, CommandStatus.KEPT, detail)
            log.i(TAG, "Armed revert of ${entry.undoName} ${entry.commandId} superseded by $name $byId")
        }
        if (superseded.isNotEmpty()) graph.sync.kick()
        return superseded
    }

    private suspend fun awaitRestored() {
        if (restored.isCompleted) return
        if (withTimeoutOrNull(RESTORE_WAIT_MS) { restored.await() } == null) {
            log.w(TAG, "Armed reverts not restored after ${RESTORE_WAIT_MS / 1000} s; continuing")
        }
    }

    /** The action that restores the state before [parsed], read from the phone before applying. */
    private suspend fun undoFor(parsed: ParsedCommand): Pair<String, JsonObject>? {
        val controls = graph.controls as? ControlsImpl
        return when (parsed) {
            is ParsedCommand.MobileData ->
                if (!parsed.enabled) CommandNames.MOBILE_DATA to jsonObjectOf("enabled" to true) else null
            is ParsedCommand.Airplane ->
                if (parsed.enabled) CommandNames.AIRPLANE_MODE to jsonObjectOf("enabled" to false) else null
            is ParsedCommand.UsbTethering -> {
                if (parsed.enabled) return null
                val previous = controls?.currentUsbFunction()
                when (previous) {
                    USB_NONE -> null // tethering was already off: nothing to restore
                    null -> CommandNames.USB_TETHERING to jsonObjectOf("enabled" to true)
                    else -> CommandNames.USB_TETHERING to jsonObjectOf("enabled" to true, "function" to previous)
                }
            }
            is ParsedCommand.SetNetworkMode -> {
                val previous = controls?.currentNetworkMode() ?: NetworkMode.AUTO
                CommandNames.NETWORK_MODE to jsonObjectOf("mode" to previous.wire)
            }
            else -> null
        }
    }

    /** Returns the Server-time ISO deadline for `cmd_result.revert_deadline`. */
    private suspend fun commitRevert(prepared: PreparedRevert): String = revertMutex.withLock {
        val windowMs = prepared.afterS * 1000L
        val appliedAt = graph.clock.systemNowMs()
        try {
            revertDao.upsert(
                ArmedRevertEntity(
                    commandId = prepared.commandId,
                    undoName = prepared.undoName,
                    undoArgsJson = prepared.undoArgs.toString(),
                    deadlineMs = appliedAt + windowMs,
                    confirmMode = prepared.mode.wire,
                    appliedAtMs = appliedAt,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(TAG, "Could not update the armed revert of ${prepared.commandId}; the in-memory timer still runs", e)
        }
        val now = graph.clock.elapsedMs()
        reverts.arm(
            ArmedRevert(
                commandId = prepared.commandId,
                undoName = prepared.undoName,
                undoArgs = prepared.undoArgs,
                mode = prepared.mode,
                appliedAtMs = now,
                deadlineMs = now + windowMs,
            ),
        )
        Clock.formatIso(graph.clock.serverNow() + windowMs)
    }

    /** The command failed: put the previous state back to be safe and drop the persisted revert. */
    private suspend fun abandonRevert(prepared: PreparedRevert) {
        log.w(TAG, "${prepared.undoName} ${prepared.commandId} failed; restoring the previous state to be safe")
        val undo = runUndo(prepared.undoName, prepared.undoArgs)
        if (!undo.ok) log.w(TAG, "Restore after a failed ${prepared.undoName} also failed: ${undo.error}")
        revertMutex.withLock {
            reverts.disarm(prepared.commandId)
            deleteRevertRow(prepared.commandId)
        }
    }

    private suspend fun revertLoop() {
        while (true) {
            try {
                val now = graph.clock.elapsedMs()
                if (reverts.shouldProbe(now, graph.sync.state.value.connected)) {
                    graph.sync.sendProbe()?.let { reverts.onProbeSent(it, now) }
                }
                for (entry in reverts.takeDue(now)) {
                    withContext(NonCancellable) { revertNow(entry, deadlineText(entry)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.e(TAG, "Auto-revert loop error", e)
            }
            delay(REVERT_TICK_MS)
        }
    }

    /**
     * Puts the persisted reverts back into the tracker (quick: no undo runs here). One whose deadline
     * passed while the Agent was not running is armed as due, so the revert loop undoes it at once.
     */
    private suspend fun restoreArmedReverts() = revertMutex.withLock {
        val rows = revertDao.all()
        if (rows.isEmpty()) return@withLock
        val nowWall = graph.clock.systemNowMs()
        val nowElapsed = graph.clock.elapsedMs()
        for (row in rows) {
            // Armed already (earlier start of this process), being resolved, or a command running now.
            if (reverts.isArmed(row.commandId) || row.commandId in resolving || row.commandId in inFlight) continue
            val entry = RevertRestore.restore(
                row,
                parseArgs(row.undoArgsJson),
                RevertConfirm.fromWire(row.confirmMode) ?: RevertConfirm.AGENT_LINK,
                nowWall,
                nowElapsed,
            )
            reverts.arm(entry)
            if (entry.overdueAtStart) {
                log.w(TAG, "Armed revert of ${row.commandId} (${row.undoName}): the deadline passed while the Agent was not running")
            } else {
                log.i(TAG, "Resumed armed revert of ${row.commandId} (${row.undoName}), ${(entry.deadlineMs - nowElapsed) / 1000} s left")
            }
        }
    }

    private suspend fun revertNow(entry: ArmedRevert, detail: String) {
        resolving += entry.commandId
        try {
            val undo = runUndo(entry.undoName, entry.undoArgs)
            val fullDetail = if (undo.ok) detail else "$detail; undo failed: ${undo.error}"
            graph.outbox.enqueue(
                CmdRevertMsg(id = entry.commandId, state = RevertState.REVERTED, detail = fullDetail.take(MAX_ERROR_CHARS), ts = graph.clock.isoNow()),
            )
            graph.events.emit(
                EventKind.REVERT, Severity.WARNING, "Auto-revert: ${label(entry.undoName)} change undone ($detail)",
                jsonObjectOf("command_id" to entry.commandId, "name" to entry.undoName, "ok" to undo.ok, "undo" to entry.undoArgs),
            )
            deleteRevertRow(entry.commandId)
            updateLogStatus(entry.commandId, CommandStatus.REVERTED, fullDetail)
            graph.sync.kick()
            log.w(TAG, "Reverted ${entry.undoName} ${entry.commandId}: $fullDetail")
        } finally {
            resolving -= entry.commandId
        }
    }

    private suspend fun keep(entry: ArmedRevert, detail: String) {
        resolving += entry.commandId
        try {
            graph.outbox.enqueue(CmdRevertMsg(id = entry.commandId, state = RevertState.KEPT, detail = detail, ts = graph.clock.isoNow()))
            deleteRevertRow(entry.commandId)
            updateLogStatus(entry.commandId, CommandStatus.KEPT, detail)
            if (entry.undoName == CommandNames.USB_TETHERING) TetherIntent.set(graph.prefs, true)
            graph.sync.kick()
            log.i(TAG, "Kept ${entry.undoName} ${entry.commandId}: $detail")
        } finally {
            resolving -= entry.commandId
        }
    }

    private suspend fun runUndo(name: String, args: JsonObject): ActionResult {
        val parsed = try {
            CommandParser.parse(name, args, graph.settings.current)
        } catch (e: InvalidArgsException) {
            return ActionResult.failure("invalid undo action: ${e.message}")
        }
        return try {
            withTimeoutOrNull(UNDO_TIMEOUT_MS) { runParsed(parsed, CommandSource.DASHBOARD, "undo") }
                ?: ActionResult.failure("undo timed out")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ActionResult.failure("undo error: ${errorText(e)}")
        }
    }

    private suspend fun deleteRevertRow(commandId: String) {
        try {
            revertDao.delete(commandId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(TAG, "Could not delete the armed revert of $commandId", e)
        }
    }

    private fun deadlineText(entry: ArmedRevert): String {
        if (entry.overdueAtStart) return "the deadline passed while the Agent was not running"
        val seconds = (entry.deadlineMs - entry.appliedAtMs) / 1000
        return if (entry.mode == RevertConfirm.DASHBOARD) {
            "not kept from the Dashboard within $seconds s"
        } else {
            "no Server round-trip within $seconds s"
        }
    }

    // --- capabilities ---------------------------------------------------------------------------

    override suspend fun probeCapabilities(): CapabilityMap = probeMutex.withLock {
        val controls = graph.controls
        val map = LinkedHashMap<String, CapabilityEntry>()
        for (name in CommandNames.ALL) {
            map[name] = try {
                controls.probe(name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CapabilityEntry(supported = false, note = "probe failed: ${errorText(e)}")
            }
        }
        _capabilities.value = map
        persistCapabilities(map)
        log.i(TAG, "Capability probe: ${map.values.count { it.supported }} of ${map.size} commands supported")
        map
    }

    private suspend fun updateCapability(name: String) {
        val entry = graph.controls.probe(name)
        _capabilities.update { it + (name to entry) }
        persistCapabilities(_capabilities.value)
    }

    private suspend fun initialProbes() {
        delay(START_PROBE_DELAY_MS)
        val map = try {
            probeCapabilities()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(TAG, "Capability probe failed", e)
            return
        }
        // RootSetup grants runtime permissions in parallel; re-test once if an entry waited for one.
        val waiting = map.values.any { !it.supported && it.note?.startsWith(ControlsImpl.PERMISSION_NOTE) == true }
        if (waiting) {
            delay(PERMISSION_REPROBE_MS)
            guarded("capability re-probe") { probeCapabilities() }
        }
    }

    private fun loadStoredCapabilities() {
        if (_capabilities.value.isNotEmpty()) return
        val text = graph.prefs.getString(KEY_CAPABILITIES) ?: return
        val map = runCatching { AgentJson.decodeFromString(capabilitySerializer, text) }.getOrNull() ?: return
        if (_capabilities.value.isEmpty()) _capabilities.value = map
    }

    private fun persistCapabilities(map: CapabilityMap) {
        runCatching { graph.prefs.putString(KEY_CAPABILITIES, AgentJson.encodeToString(capabilitySerializer, map)) }
            .onFailure { log.w(TAG, "Could not store the capability map", it) }
    }

    // --- audit and helpers ----------------------------------------------------------------------

    private suspend fun saveLog(id: String, name: String, args: JsonObject, source: CommandSource, status: CommandStatus, output: String?) {
        try {
            commandLog.upsert(
                CommandLogEntity(
                    id = id,
                    name = name,
                    argsJson = redact(args).toString(),
                    source = source.wire,
                    status = status.wire,
                    output = output?.take(LOG_OUTPUT_CHARS),
                    atMs = graph.clock.serverNow(),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(TAG, "Could not write the command log for $id", e)
        }
    }

    private suspend fun updateLogStatus(id: String, status: CommandStatus, output: String?) {
        try {
            val row = commandLog.get(id) ?: return
            commandLog.upsert(row.copy(status = status.wire, output = output?.take(LOG_OUTPUT_CHARS) ?: row.output))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(TAG, "Could not update the command log for $id", e)
        }
    }

    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(TAG, "Failed: $what", e)
        }
    }

    /** Short reporting work that must finish even while the service scope is being cancelled. */
    private fun launchReport(what: String, block: suspend () -> Unit) {
        graph.appScope.launch { guarded(what, block) }
    }

    private fun parseArgs(json: String): JsonObject =
        runCatching { AgentJson.parseToJsonElement(json).jsonObject }.getOrElse { JsonObject(emptyMap()) }

    private fun redact(args: JsonObject): JsonObject {
        if (!args.containsKey("password")) return args
        return JsonObject(args + ("password" to JsonPrimitive("<redacted>")))
    }

    private fun label(name: String): String = COMMANDS[name]?.label ?: name

    private fun wire(reason: CmdRejectReason): String = reason.name.lowercase()

    private fun errorText(e: Throwable): String = e.message ?: e.javaClass.simpleName

    companion object {
        private const val TAG = "exec"
        private const val KEY_CAPABILITIES = "control.capabilities"
        private const val KEY_PENDING_REBOOT_ID = "control.pending_reboot_id"
        private const val KEY_PENDING_REBOOT_AT = "control.pending_reboot_at"
        private const val USB_NONE = "none"

        private const val MAX_ERROR_CHARS = 2_000
        private const val LOG_OUTPUT_CHARS = 4_000
        private const val EVENT_OUTPUT_CHARS = 2_000
        private const val COMMAND_LOG_RETENTION_MS = 30L * 24 * 3_600_000

        private const val START_PROBE_DELAY_MS = 3_000L
        private const val PERMISSION_REPROBE_MS = 60_000L
        private const val REVERT_TICK_MS = 1_000L
        private const val MIN_REVERT_S = 15
        private const val MAX_REVERT_S = 86_400
        private const val UNDO_TIMEOUT_MS = 60_000L
        private const val RESTORE_WAIT_MS = 30_000L
        private const val RECENT_IDS = 256
        private const val KEPT_FROM_DASHBOARD = "Kept from the Dashboard"

        private const val DEFAULT_TIMEOUT_MS = 3 * 60_000L
        private const val PROBE_TIMEOUT_MS = 5 * 60_000L
        private const val SPEED_TEST_TIMEOUT_MS = 15 * 60_000L
        private const val CYCLE_MARGIN_S = 120
        private const val SHELL_MARGIN_S = 30

        /** How long a still-running Agent waits after `svc power reboot` before reporting failure. */
        private const val REBOOT_GRACE_MS = 60_000L
        private const val BOOT_SLACK_MS = 5_000L
    }
}
