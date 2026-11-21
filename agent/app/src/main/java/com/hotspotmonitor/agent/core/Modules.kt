package com.hotspotmonitor.agent.core

import com.hotspotmonitor.agent.protocol.AgentToServer
import com.hotspotmonitor.agent.protocol.CapabilityEntry
import com.hotspotmonitor.agent.protocol.CapabilityMap
import com.hotspotmonitor.agent.protocol.CmdMsg
import com.hotspotmonitor.agent.protocol.CommandSource
import com.hotspotmonitor.agent.protocol.DeviceInfo
import com.hotspotmonitor.agent.protocol.FinderLive
import com.hotspotmonitor.agent.protocol.FinderMode
import com.hotspotmonitor.agent.protocol.FinderSpot
import com.hotspotmonitor.agent.protocol.HeatInfo
import com.hotspotmonitor.agent.protocol.NetInfo
import com.hotspotmonitor.agent.protocol.NetworkMode
import com.hotspotmonitor.agent.protocol.PairResponse
import com.hotspotmonitor.agent.protocol.PositionInfo
import com.hotspotmonitor.agent.protocol.PowerInfo
import com.hotspotmonitor.agent.protocol.RadioInfo
import com.hotspotmonitor.agent.protocol.ScreenStartMsg
import com.hotspotmonitor.agent.protocol.ScreenStopMsg
import com.hotspotmonitor.agent.protocol.Rates
import com.hotspotmonitor.agent.protocol.SpeedSession
import com.hotspotmonitor.agent.protocol.StopReason
import com.hotspotmonitor.agent.protocol.SystemInfo
import com.hotspotmonitor.agent.protocol.TelemetryTier
import com.hotspotmonitor.agent.protocol.TetherInfo
import com.hotspotmonitor.agent.protocol.TrafficInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import java.io.File

/*
 * Module contracts. Each interface has exactly one implementation class, created lazily by
 * AgentGraph as `XxxImpl(graph)`. Implementations reach every other part through the graph
 * (graph.settings, graph.events, graph.shell, graph.telemetry, ...), and must not call another
 * module from their constructor: the graph builds modules lazily and in no fixed order.
 *
 * This file is owned by the integrator. Module builders do not edit it; they list needed changes
 * in their report (see agent/ARCHITECTURE.md).
 */

/** Lifecycle shared by the long-running modules. */
interface AgentModule {
    /**
     * Called once per service start by [AgentGraph.startAll], in the order documented in
     * ARCHITECTURE.md. Launch long-running work in [scope] (a SupervisorJob scope that
     * [AgentGraph.stopAll] cancels) and return quickly; never block here.
     */
    fun start(scope: CoroutineScope) {}

    /** Called by [AgentGraph.stopAll] after the scope is cancelled: unregister receivers, sensors. */
    fun stop() {}
}

/** Generic result of one control action or local command. */
data class ActionResult(
    val ok: Boolean,
    /** Human summary or command stdout (truncated to ProtocolLimits.MAX_OUTPUT_BYTES by the sender). */
    val output: String = "",
    val error: String? = null,
    /** Implementation used, e.g. "svc data" or a sysfs path (capability map `method`). */
    val method: String? = null,
) {
    companion object {
        fun success(output: String = "", method: String? = null) = ActionResult(true, output, null, method)
        fun failure(error: String, output: String = "", method: String? = null) = ActionResult(false, output, error, method)
    }
}

// ---------------------------------------------------------------------------------------------
// telemetry/  (owner: telemetry builder)
// ---------------------------------------------------------------------------------------------

/** The groups of a `fast` telemetry message (TIER_GROUPS.fast). */
data class FastGroups(val radio: RadioInfo?, val net: NetInfo?)

/** The groups of a `slow` telemetry message (TIER_GROUPS.slow). */
data class SlowGroups(
    val power: PowerInfo?,
    val heat: HeatInfo?,
    val system: SystemInfo?,
    val tether: TetherInfo?,
    val traffic: TrafficInfo?,
    val position: PositionInfo?,
)

/** The groups of a `static` telemetry message (TIER_GROUPS.static). */
data class StaticGroups(val device: DeviceInfo, val capabilities: CapabilityMap)

/**
 * Collectors (plan 6.2): radio, internet checks, traffic, tethering, power, heat, system, position,
 * SMS. Keeps the latest reading of every group in a StateFlow for the watchdog, Finder and UI.
 *
 * The hub only collects. Scheduling `telemetry` messages into the outbox is the SyncClient's job
 * ([SyncClient.publishTelemetry]); the hub emits change events (cell_change, tether_up/down,
 * usb_*, power_*, connectivity, boot, moved, sms_received) itself through graph.events.
 */
interface TelemetryHub : AgentModule {
    /** Latest serving-cell reading, `score` smoothed over finder.smooth_s. Null before the first. */
    val radio: StateFlow<RadioInfo?>

    /** Latest internet check (HTTP 204 / ping / DNS every telemetry.internet_check_s) and speeds. */
    val net: StateFlow<NetInfo?>
    val power: StateFlow<PowerInfo?>
    val heat: StateFlow<HeatInfo?>
    val tether: StateFlow<TetherInfo?>
    val traffic: StateFlow<TrafficInfo?>
    val system: StateFlow<SystemInfo?>
    val position: StateFlow<PositionInfo?>

    /** Every new radio reading, also when equal to the previous one (Finder sampling). */
    val radioFlow: SharedFlow<RadioInfo>

    /**
     * Result of the latest internet check (HTTP 204 with one retry); null before the first check.
     * Same as `net.value?.online`, but changes only when a check completes.
     */
    val online: StateFlow<Boolean?>

    /** Fresh radio + net readings for a `fast` message (may reuse readings younger than 1 s). */
    suspend fun collectFast(): FastGroups

    /** Fresh readings for a `slow` message. */
    suspend fun collectSlow(): SlowGroups

    /** Device info (with device_id, profile_id, root state) and the current capability map. */
    suspend fun collectStatic(): StaticGroups

    /** Runs one internet check now and updates [net] (watchdog: "still down after the step?"). */
    suspend fun checkInternetNow(): NetInfo

    /** 1 Hz radio updates while the Finder needs them; otherwise the normal, slower rate. */
    fun setHighRateRadio(on: Boolean)

    /**
     * Takes the current location and tilt as the new installed reference for `moved`
     * (watchdog.moved_m, tilt_change_deg). Called when the Finder baseline is set.
     */
    fun resetPositionReference()
}

// ---------------------------------------------------------------------------------------------
// control/  (owner: control builder)
// ---------------------------------------------------------------------------------------------

/**
 * Executes Server commands (protocol 3.5): checks in order expiry -> capability -> policy ->
 * reboot limit -> args -> busy, enqueues `cmd_ack`, runs the command, enqueues `cmd_result`, and
 * handles auto-revert (persisted in ArmedRevertEntity, confirmed by probe or `cmd_keep`, undone at
 * the deadline, and undone at start when already past the deadline). Writes CommandLogEntity.
 */
interface CommandExecutor : AgentModule {
    /** A `cmd` from the Server (called by the SyncClient). Never throws. */
    suspend fun handle(cmd: CmdMsg)

    /** A `probe_ack` arrived (called by the SyncClient). */
    fun onProbeAck(id: String)

    /** A `cmd_keep` arrived (called by the SyncClient). */
    fun onKeep(id: String)

    /** The link is up after `hello` (called by the SyncClient): probe armed agent_link reverts. */
    fun onConnected()

    /**
     * Runs a command that did not come from the Server (local UI, watchdog, schedule). Applies the
     * same policy checks except expiry, reports it as event kind `command`, and returns the result.
     */
    suspend fun runLocal(name: String, args: JsonObject, source: CommandSource): ActionResult

    /** Current capability map (plan 6.1), keyed by command name. Empty before the first probe. */
    val capabilityMap: StateFlow<CapabilityMap>

    /** Re-tests every command on this phone, persists and returns the map. */
    suspend fun probeCapabilities(): CapabilityMap
}

/**
 * High-level root actions shared by the executor, watchdog and Finder (plan 6.3). Each uses the
 * device profile's command templates first when present. All are safe to call concurrently;
 * implementations serialize conflicting actions.
 */
interface Controls {
    suspend fun setMobileData(enabled: Boolean): ActionResult

    /** Data off, wait [offSeconds] (in the coroutine), data on. */
    suspend fun cycleData(offSeconds: Int): ActionResult
    suspend fun setAirplane(enabled: Boolean): ActionResult

    /** Airplane on for [offSeconds], then off. */
    suspend fun airplaneCycle(offSeconds: Int): ActionResult

    /** [function] "rndis" | "ncm"; null = profile / settings default. */
    suspend fun setUsbTethering(enabled: Boolean, function: String? = null): ActionResult
    suspend fun setHotspot(enabled: Boolean, ssid: String? = null, password: String? = null): ActionResult
    suspend fun setWifi(enabled: Boolean): ActionResult
    suspend fun setBluetooth(enabled: Boolean): ActionResult
    suspend fun setNetworkMode(mode: NetworkMode): ActionResult

    /**
     * Reboots if graph.rebootLimiter allows it (records the reboot first, and stores a
     * watchdog/command event with EventSink.emitAndStore before rebooting). When the limit is hit
     * it emits `reboot_blocked` and returns a failure.
     */
    suspend fun reboot(source: CommandSource, reason: String): ActionResult

    /** Only called after the executor checked settings.commands.allow_power_off. */
    suspend fun powerOff(): ActionResult
    suspend fun setScreen(on: Boolean): ActionResult

    /** 0..255 */
    suspend fun setBrightness(level: Int): ActionResult

    /** Heat rule (plan 6.5, 50 °C): airplane mode on for [seconds], then off. */
    suspend fun restRadio(seconds: Int): ActionResult

    /** Output: JSON array of { pkg, enabled, system }. */
    suspend fun listApps(): ActionResult
    suspend fun forceStopApp(pkg: String): ActionResult
    suspend fun setAppEnabled(pkg: String, enabled: Boolean): ActionResult

    /** Output: `top -n 1` text. */
    suspend fun processList(): ActionResult
    suspend fun sendSms(to: String, body: String): ActionResult

    /** Output: the USSD reply text. */
    suspend fun runUssd(code: String): ActionResult

    /** Admin root shell (`shell` command). */
    suspend fun shell(cmd: String, timeoutS: Int): ActionResult

    /** Tests whether command [name] works on this phone (capability probe, non-destructive). */
    suspend fun probe(name: String): CapabilityEntry
}

/** Charging switch and limit state (plan 4.2, 6.5). */
data class ChargeState(
    /** Null when unknown or when no charge-control path works on this phone. */
    val chargingEnabled: Boolean? = null,
    val limitPct: Int = 60,
    val stopReason: StopReason? = null,
    /** The working sysfs path (or profile method), null if none. */
    val method: String? = null,
)

/**
 * Root charge control through the profile's charge_control paths. The heat stop always wins over
 * the limit and over a manual "charging on".
 */
interface ChargeController : AgentModule {
    val state: StateFlow<ChargeState>

    /** Switches charging; [reason] is recorded as the stop reason when disabling. */
    suspend fun setCharging(enabled: Boolean, reason: StopReason): ActionResult

    /**
     * Limit with hysteresis: stop at or above [limitPct], resume at or below
     * [limitPct] - [resumeDropPct]. Returns the action taken, or null when nothing changed.
     */
    suspend fun applyLimit(limitPct: Int, resumeDropPct: Int, batteryPct: Int?): ActionResult?

    /** Capability entry for `charging` / `charge_limit` (finds the first working path). */
    suspend fun probe(): CapabilityEntry
}

/** At most settings.watchdog.max_reboots per reboot_window_h, from any source (plan 6.4 rule 3). */
interface RebootLimiter {
    /** [nowMs] = graph.clock.systemNowMs() (the reboot log must survive reboots). */
    suspend fun canReboot(nowMs: Long): Boolean
    suspend fun record(source: CommandSource, nowMs: Long)
    suspend fun countInWindow(nowMs: Long): Int
}

// ---------------------------------------------------------------------------------------------
// watchdog/  (owner: control builder)
// ---------------------------------------------------------------------------------------------

/** What the watchdog is doing, for the Status screen and the executor's `busy` check. */
data class WatchdogState(
    val enabled: Boolean = true,
    /** Epoch ms (Clock.serverNow) when the internet check first failed; null while online. */
    val internetDownSinceMs: Long? = null,
    /** Recovery ladder step reached: 0 none, 1 data cycled, 2 airplane cycled, 3 reboot requested. */
    val ladderStep: Int = 0,
    /** A recovery step is executing right now (the executor answers conflicting commands `busy`). */
    val stepRunning: Boolean = false,
    val lastAction: String? = null,
    val lastActionAtMs: Long? = null,
    /** While set, the internet is treated as down (watchdog_simulate). */
    val simulatedUntilMs: Long? = null,
    val radioResting: Boolean = false,
    /** Charging is stopped by the heat rule. */
    val heatStop: Boolean = false,
)

/** Local recovery rules (plan 6.5), using graph.controls, graph.charge and settings.watchdog. */
interface Watchdog : AgentModule {
    val state: StateFlow<WatchdogState>

    /** Phase 4 test: treat the internet as down for [durationS] and walk the ladder. */
    fun simulateInternetDown(durationS: Int)
}

// ---------------------------------------------------------------------------------------------
// finder/  (owner: finder builder)
// ---------------------------------------------------------------------------------------------

/**
 * Signal Finder (plan 7): walk mode with sound and speech, spot tests, speed tests, ranking, CSV,
 * baseline, headless volume-key control, and the live mirror. Spots persist in FinderSpotEntity
 * and are reported as buffered `finder_spot` / `finder_spot_deleted` messages.
 */
interface FinderEngine : AgentModule {
    val mode: StateFlow<FinderMode>

    /** Latest live values while the Finder runs or the mirror is on; null otherwise. */
    val live: StateFlow<FinderLive?>

    /** Saved spots, ranked (speed first, score second). */
    val spots: Flow<List<FinderSpot>>

    fun startWalk()

    /**
     * Back to idle: stops walk mode, sound and a running test. (Named stopFinder because
     * AgentModule.stop is the service-lifecycle hook.)
     */
    fun stopFinder()

    /** Holds still for [durationS] (default finder.spot_test_s), saves and returns the spot. */
    suspend fun spotTest(name: String, orientation: String? = null, durationS: Int? = null): FinderSpot

    /** finder.speed_runs runs; attached to the spot when [spotId] is given. */
    suspend fun speedTest(spotId: String? = null): SpeedSession

    /** Marks one spot as the baseline (clears the flag on the others). False if not found. */
    suspend fun setBaseline(spotId: String): Boolean
    suspend fun deleteSpot(spotId: String): Boolean

    /** Writes the ranked spots to a CSV file in cacheDir/exports (shared via the FileProvider). */
    suspend fun exportCsv(): File

    /** Called by the SyncClient when rates.mirror changes; when on, sends finder_live via SyncClient.sendLive every telemetry.mirror_s. */
    fun setMirror(on: Boolean)

    /** Headless mode (plan 7.3): volume up = spot test, volume down = speed test, spoken results. */
    fun setHeadless(on: Boolean)
}

// ---------------------------------------------------------------------------------------------
// sync/  (owner: sync/service/ui builder)
// ---------------------------------------------------------------------------------------------

enum class ConnPhase {
    STOPPED,
    NOT_PAIRED,
    CONNECTING,
    CONNECTED,

    /** Waiting for the next reconnect attempt (backoff). */
    WAITING,

    /** Closed with 4001; no reconnect until paired again. */
    UNAUTHORIZED,
}

data class ConnState(
    val phase: ConnPhase = ConnPhase.STOPPED,
    val lastError: String? = null,
    /** Epoch ms of the last phase change. */
    val sinceMs: Long = 0,
) {
    val connected: Boolean get() = phase == ConnPhase.CONNECTED
}

/**
 * The WebSocket link to the Server (protocol section 3): connect with the device token, `hello`
 * handling (clock offset, settings, rates), reconnect with backoff, heartbeat, the single outbox
 * sender (batch + ack), the telemetry tier scheduler, and dispatch of Server messages to the
 * executor / finder.
 */
interface SyncClient : AgentModule {
    val state: StateFlow<ConnState>

    /** Rates from the Server; Rates() defaults (fast_idle_s, no mirror) while offline. */
    val rates: StateFlow<Rates>

    /** Sends a non-buffered message (heartbeat, probe, finder_live) now. False when not connected. */
    fun sendLive(msg: AgentToServer): Boolean

    /** Sends a `probe` with a new "p_" id and returns the id, or null when not connected. */
    fun sendProbe(): String?

    /** Wakes the outbox sender to drain now. */
    fun kick()

    /** Collects and enqueues telemetry of these tiers now (on connect, `refresh`, capability changes). */
    suspend fun publishTelemetry(tiers: Set<TelemetryTier>)

    /** Pairing completed: (re)connect with the new credentials. */
    fun onPaired()

    /** Pairing removed (local unpair or 4001): close the socket and stop reconnecting. */
    fun onUnpaired()
}

/** POST /api/pair and local unpairing (protocol section 2). */
interface PairingClient {
    /** Pairs with a one-time [code]; on success the token is stored and the SyncClient connects. */
    suspend fun pair(serverUrl: String, code: String): Result<PairResponse>

    /** Forgets the pairing locally and disconnects. */
    suspend fun unpair()
}

/**
 * Remote screen (docs/protocol.md section 8): streams the screen through the bundled scrcpy
 * server over a separate relay socket, one session at a time.
 */
interface ScreenMirror : AgentModule {
    /** Session id being streamed, or null. */
    val active: StateFlow<String?>

    /** `screen_start` from the Server: start (or keep) this session, replacing any other. */
    fun onStart(msg: ScreenStartMsg)

    /** `screen_stop` from the Server. */
    fun onStop(msg: ScreenStopMsg)

    /** Capability entry `screen_mirror` for the capability map. */
    suspend fun probe(): CapabilityEntry
}
