# Agent architecture

The Agent is the Android app on the rooted hotspot phone (plan sections 6 and 7). This page is the
contract between the people (or agents) building its modules in parallel. Read it together with
[`docs/plan.md`](../docs/plan.md), [`docs/protocol.md`](../docs/protocol.md) and the TypeScript
contract in [`shared/src/`](../shared/src).

- Single Gradle module `:app`, package `com.hotspotmonitor.agent`, minSdk 29, target/compile SDK 35.
- Kotlin, Jetpack Compose, coroutines, kotlinx.serialization, Room, OkHttp, libsu.

## 1. Package map

```
app/src/main/java/com/hotspotmonitor/agent/
├── AgentApp.kt            Application: configures libsu, creates the AgentGraph
├── core/                  shared infrastructure (integrator-owned)
│   ├── AgentGraph.kt      service locator: every singleton + startAll()/stopAll()
│   ├── Modules.kt         module interfaces (the contract below) and their data classes
│   ├── AgentLog.kt        log: memory ring (1000 lines) + logcat "HSM/<tag>" + files/logs/agent.log (2 x 1 MB)
│   ├── Clock.kt           serverNow()/isoNow() (offset-corrected), elapsedMs(), ISO helpers
│   ├── Ids.kt             e_/c_/s_/m_/p_ random ids
│   ├── KeyValueStore.kt   SharedPreferences wrapper (+ MemoryStore for tests)
│   ├── SettingsRepo.kt    StateFlow<Settings> + version, persisted, settings_applied event
│   ├── SecureStore.kt     AES-256-GCM (AndroidKeyStore key) encrypted values
│   ├── PairingState.kt    server URL, ws URL, device id, token (encrypted), paired flag
│   ├── OutboxStore.kt     buffered messages: enqueue (assigns seq), peekBatch, ackUpTo, trim
│   └── EventSink.kt       emit(kind, severity, message, data) -> outbox + local feed
├── db/                    Room: AgentDatabase, entities, DAOs (integrator-owned)
├── protocol/              Kotlin mirror of shared/src (integrator-owned)
│   ├── Wire.kt            AgentJson, Wire.encode/decode*, ProtocolLimits, CloseCodes
│   ├── Telemetry.kt       RadioInfo, NetInfo, ..., DeviceInfo, CapabilityEntry, TelemetryTier
│   ├── Messages.kt        AgentToServer / BufferedMsg / ServerToAgent sealed hierarchies, Rates
│   ├── Settings.kt        Settings + defaults (== shared/fixtures/settings-default.json)
│   ├── Commands.kt        CommandNames, COMMANDS specs, NetworkMode, RevertConfirm, CmdRejectReason, CommandSource
│   ├── Events.kt          EventKind, Severity, AgentEvent, ALERT_KINDS, EVENT_ALERT
│   ├── Finder.kt          FinderLive, FinderSpot, MetricStats, SpeedRun, SpeedSession, rankSpots
│   ├── Score.kt           signalScore, round1, grades, RadioInfo.rawScore(), Smoother
│   ├── Api.kt             PairRequest/PairResponse/ApiError, ApiPaths
│   ├── Args.kt            typed readers for CmdMsg.args (optInt, requireBoolean, ...)
│   ├── JsonUtil.kt        jsonObjectOf, deepMerge, withoutNulls
│   └── LenientNumbers.kt  Int/Long serializers that accept 30.0 from JS senders
├── root/                  (integrator-owned)
│   ├── RootShell.kt       RootShell interface, LibsuRootShell, ShellResult, shellQuote
│   ├── DeviceProfile.kt   profile schema, GENERIC fallback, template rendering
│   ├── ProfileRepo.kt     loads assets/profiles/*.json, matches the phone, applies profile_override
│   └── RootSetup.kt       one-time self-grant of permissions / appops / doze whitelist via root
├── telemetry/             TelemetryHubImpl, SmsReceiver               (telemetry builder)
├── control/               CommandExecutorImpl, ControlsImpl, ChargeControllerImpl, RebootLimiterImpl (control builder)
├── watchdog/              WatchdogImpl                               (control builder)
├── finder/                FinderEngineImpl                           (finder builder)
├── sync/                  SyncClientImpl, PairingClientImpl          (sync/service/ui builder)
├── service/               AgentService, BootReceiver, ControlReceiver (sync/service/ui builder)
└── ui/                    MainActivity, screens, PairActivity, theme (sync/service/ui builder; FinderScreen: finder builder)
```

Other directories under `agent/`:

| Path | Content | Owner |
|------|---------|-------|
| `app/src/main/assets/profiles/` | device profile JSON files (section 8) | profiles / Phase 0 builder |
| `magisk/` | Magisk keep-alive module (service.d) | Phase 0 builder |
| `app/schemas/` | Room schema export (generated, commit it) | integrator |
| `app/src/main/AndroidManifest.xml`, `app/src/main/res/`, Gradle files | integrator |

## 2. Ownership

| Builder | Owns (may create and edit) |
|---------|----------------------------|
| telemetry | `telemetry/**` |
| control | `control/**`, `watchdog/**` |
| finder | `finder/**`, `ui/FinderScreen.kt` |
| sync/service/ui | `sync/**`, `service/**`, `ui/**` except `ui/FinderScreen.kt` |
| integrator only | `core/**`, `db/**`, `protocol/**`, `root/**`, `AgentApp.kt`, manifest, resources, Gradle files, this page |

Since integration, all of `agent/` except `magisk/` and `app/src/main/assets/profiles/` is maintained
as one unit; the table still says which builder's design a package follows.

Rules:

1. **Do not edit files you do not own.** If you need a change in a core file (a new interface method,
   a DAO query, a manifest entry, a permission, a dependency, a string resource), work around it
   inside your package if you can and **list the exact change in your report** under "requests".
   The integrator applies it.
2. Each `XxxImpl` keeps its class name and its single constructor parameter `(graph: AgentGraph)`.
   You may add private helpers, more classes and files in your package, and `internal` APIs used
   only inside your package.
3. Implementation classes must not call other modules from their constructor. The graph creates
   modules lazily in no fixed order; do that work in `start(scope)` or on first use.
4. You may add unit tests under `app/src/test/java/com/hotspotmonitor/agent/<your package>/`.
5. Before you finish: `./gradlew assembleDebug testDebugUnitTest --console=plain` passes, then
   `./gradlew --stop`.

## 3. The graph

`AgentApp.graph(context)` returns the process-wide `AgentGraph`. Everything is a lazy property:

| Property | Type | Use |
|----------|------|-----|
| `app` | `Context` | application context |
| `appScope` | `CoroutineScope` | process-wide, never cancelled (SupervisorJob + Dispatchers.Default) |
| `log` | `AgentLog` | logging (section 6) |
| `clock` | `Clock` | time (section 6) |
| `prefs` | `KeyValueStore` | small persistent values; prefix your keys with your package, e.g. `finder.headless` |
| `db` | `AgentDatabase` | Room DAOs (section 7) |
| `outbox` | `OutboxStore` | buffered protocol messages |
| `events` | `EventSink` | events |
| `settings` | `SettingsRepo` | settings the Agent runs on |
| `secureStore` | `SecureStore` | encrypted values |
| `pairingState` | `PairingState` | pairing data and token |
| `shell` | `RootShell` | every privileged command |
| `profiles` | `ProfileRepo` | device profile (`profiles.current`, `profiles.profile` flow) |
| `rootSetup` | `RootSetup` | permission self-grant (run by `startAll`; `ensure(force = true)` to retry) |
| `http` | `OkHttpClient` | shared client; derive with `http.newBuilder()` (shares pool and dispatcher) |
| `telemetry` | `TelemetryHub` | module |
| `commands` | `CommandExecutor` | module |
| `controls` | `Controls` | module |
| `charge` | `ChargeController` | module |
| `rebootLimiter` | `RebootLimiter` | module |
| `watchdog` | `Watchdog` | module |
| `finder` | `FinderEngine` | module |
| `sync` | `SyncClient` | module |
| `pairing` | `PairingClient` | module (HTTP pairing) |
| `agentVersion`, `agentVersionCode` | | BuildConfig values |
| `running` | `StateFlow<Boolean>` | between `startAll` and `stopAll` |
| `agentUptimeS()` | `Long` | heartbeat `agent_uptime_s` |

### Start order

`AgentService.onStartCommand` calls `graph.startAll(reason)` (idempotent). It creates a fresh
service scope (SupervisorJob + Dispatchers.Default + a handler that logs uncaught errors and emits
an `error` event), then:

1. launches `rootSetup.ensure()` on Dispatchers.IO (in parallel; permissions may arrive a few
   seconds after modules start, so re-check permissions on use),
2. `profiles.start(scope)`,
3. `start(scope)` of `telemetry`, `charge`, `commands`, `watchdog`, `finder`, `sync`, in that order
   (an exception in one is reported and the next still starts),
4. emits `agent_start` with `{reason, agent_version}`.

`stopAll()` cancels the scope and calls `stop()` in reverse order. The `boot` event comes from
`TelemetryHubImpl` (once per `BOOT_COUNT`, with `boot_reason` read through root), not from
`BootReceiver`, so it is emitted exactly once whoever starts the service (BootReceiver, or the
Magisk script before BOOT_COMPLETED). It is skipped on the first run after install.

## 4. Module interfaces (core/Modules.kt)

Common types:

```kotlin
interface AgentModule {
    fun start(scope: CoroutineScope) {}   // launch long-running work in scope; return quickly
    fun stop() {}                         // after the scope is cancelled: unregister receivers, sensors
}

data class ActionResult(val ok: Boolean, val output: String = "", val error: String? = null, val method: String? = null)
// ActionResult.success(output, method), ActionResult.failure(error, output, method)
```

### TelemetryHub (telemetry builder)

```kotlin
data class FastGroups(val radio: RadioInfo?, val net: NetInfo?)
data class SlowGroups(val power: PowerInfo?, val heat: HeatInfo?, val system: SystemInfo?,
                      val tether: TetherInfo?, val traffic: TrafficInfo?, val position: PositionInfo?)
data class StaticGroups(val device: DeviceInfo, val capabilities: CapabilityMap)

interface TelemetryHub : AgentModule {
    val radio: StateFlow<RadioInfo?>        // score smoothed over finder.smooth_s
    val net: StateFlow<NetInfo?>            // internet check every telemetry.internet_check_s
    val power: StateFlow<PowerInfo?>
    val heat: StateFlow<HeatInfo?>
    val tether: StateFlow<TetherInfo?>
    val traffic: StateFlow<TrafficInfo?>
    val system: StateFlow<SystemInfo?>
    val position: StateFlow<PositionInfo?>
    val radioFlow: SharedFlow<RadioInfo>    // every reading, also repeats
    val online: StateFlow<Boolean?>         // latest internet check (HTTP 204, one retry); null before the first
    suspend fun collectFast(): FastGroups
    suspend fun collectSlow(): SlowGroups
    suspend fun collectStatic(): StaticGroups
    suspend fun checkInternetNow(): NetInfo
    fun setHighRateRadio(on: Boolean)       // 1 Hz while the Finder needs it
    fun resetPositionReference()            // Finder baseline set: current location + tilt become the `moved` reference
}
```

Collects only; it does not schedule telemetry messages (SyncClient does). It emits change events
itself: `boot`, `cell_change`, `net_type_change`, `connectivity`, `tether_up/down`,
`usb_connected/disconnected`, `power_connected`, `power_lost`, `moved`, `sms_received` (+ buffered
`sms` messages from SmsReceiver). It also follows `graph.finder.spots`: when the baseline spot
changes it re-references the position, using the spot's own fix when its accuracy is 100 m or
better. The watchdog's internet rule reads `net.value?.online`; `net.internet_down_since` mirrors
`watchdog.state.internetDownSinceMs` when set, so the watchdog never derives its own down-since
from it.
`power.charging_enabled / charge_limit_pct / charge_control / stop_reason` come from
`graph.charge.state`; `system.reboots_in_window` from `graph.rebootLimiter`; `heat.radio_resting`
and `net.internet_down_since` from `graph.watchdog.state`.

### CommandExecutor, Controls, ChargeController, RebootLimiter (control builder)

```kotlin
interface CommandExecutor : AgentModule {
    suspend fun handle(cmd: CmdMsg)                 // from SyncClient; never throws
    fun onProbeAck(id: String)
    fun onKeep(id: String)
    fun onConnected()                               // after hello: probe armed agent_link reverts
    suspend fun runLocal(name: String, args: JsonObject, source: CommandSource): ActionResult
    val capabilityMap: StateFlow<CapabilityMap>
    suspend fun probeCapabilities(): CapabilityMap
}

interface Controls {
    suspend fun setMobileData(enabled: Boolean): ActionResult
    suspend fun cycleData(offSeconds: Int): ActionResult
    suspend fun setAirplane(enabled: Boolean): ActionResult
    suspend fun airplaneCycle(offSeconds: Int): ActionResult
    suspend fun setUsbTethering(enabled: Boolean, function: String? = null): ActionResult
    suspend fun setHotspot(enabled: Boolean, ssid: String? = null, password: String? = null): ActionResult
    suspend fun setWifi(enabled: Boolean): ActionResult
    suspend fun setBluetooth(enabled: Boolean): ActionResult
    suspend fun setNetworkMode(mode: NetworkMode): ActionResult
    suspend fun reboot(source: CommandSource, reason: String): ActionResult   // honours RebootLimiter
    suspend fun powerOff(): ActionResult
    suspend fun setScreen(on: Boolean): ActionResult
    suspend fun setBrightness(level: Int): ActionResult
    suspend fun restRadio(seconds: Int): ActionResult
    suspend fun listApps(): ActionResult
    suspend fun forceStopApp(pkg: String): ActionResult
    suspend fun setAppEnabled(pkg: String, enabled: Boolean): ActionResult
    suspend fun processList(): ActionResult
    suspend fun sendSms(to: String, body: String): ActionResult
    suspend fun runUssd(code: String): ActionResult
    suspend fun shell(cmd: String, timeoutS: Int): ActionResult
    suspend fun probe(name: String): CapabilityEntry
}

data class ChargeState(val chargingEnabled: Boolean? = null, val limitPct: Int = 60,
                       val stopReason: StopReason? = null, val method: String? = null)

interface ChargeController : AgentModule {
    val state: StateFlow<ChargeState>
    suspend fun setCharging(enabled: Boolean, reason: StopReason): ActionResult
    suspend fun applyLimit(limitPct: Int, resumeDropPct: Int, batteryPct: Int?): ActionResult?
    suspend fun probe(): CapabilityEntry
}

interface RebootLimiter {                           // nowMs = graph.clock.systemNowMs()
    suspend fun canReboot(nowMs: Long): Boolean
    suspend fun record(source: CommandSource, nowMs: Long)
    suspend fun countInWindow(nowMs: Long): Int
}
```

The executor implements docs/protocol.md 3.5 in full: check order expiry (using
`graph.clock.serverNow()` vs `Clock.parseIso(cmd.sentAt)`) -> name known and supported ->
`power_off` policy -> reboot limit -> args (`InvalidArgsException` from Args.kt ->
`invalid_args`) -> busy (`graph.watchdog.state.value.stepRunning` or a conflicting command); then
`cmd_ack`, execute, `cmd_result` (output truncated to `ProtocolLimits.MAX_OUTPUT_BYTES`), and
auto-revert with `ArmedRevertEntity` (persist the undo before applying; `REVERT_SETTLE_S`; probe via
`graph.sync.sendProbe()`; `cmd_revert` + `revert` event). Every handled command is written to
`CommandLogEntity`. `refresh` -> `graph.sync.publishTelemetry(setOf(STATIC, SLOW, FAST))`;
`capability_probe` -> `probeCapabilities()` then `publishTelemetry(setOf(STATIC))`;
`watchdog_simulate` -> `graph.watchdog.simulateInternetDown()`; finder commands -> `graph.finder`.
A command name the Agent does not know is rejected `unsupported` (CmdMsg.name is a plain string).

### Watchdog (control builder)

```kotlin
data class WatchdogState(val enabled: Boolean = true, val internetDownSinceMs: Long? = null,
    val ladderStep: Int = 0, val stepRunning: Boolean = false, val lastAction: String? = null,
    val lastActionAtMs: Long? = null, val simulatedUntilMs: Long? = null,
    val radioResting: Boolean = false, val heatStop: Boolean = false)

interface Watchdog : AgentModule {
    val state: StateFlow<WatchdogState>
    fun simulateInternetDown(durationS: Int)
}
```

All rules of plan 6.5 with thresholds from `graph.settings.settings.value.watchdog` (re-read on
every evaluation, so Dashboard edits apply without restart). Every action is a `watchdog_action`
event `{rule, action, step, outcome}`; charge/heat transitions emit `charging_stopped/resumed`,
`temp_warning/critical/normal`, `radio_rest`, `weak_power`.

### FinderEngine (finder builder)

```kotlin
interface FinderEngine : AgentModule {
    val mode: StateFlow<FinderMode>
    val live: StateFlow<FinderLive?>
    val spots: Flow<List<FinderSpot>>               // ranked
    fun startWalk()
    fun stopFinder()                                // back to idle
    suspend fun spotTest(name: String, orientation: String? = null, durationS: Int? = null): FinderSpot
    suspend fun speedTest(spotId: String? = null): SpeedSession
    suspend fun setBaseline(spotId: String): Boolean
    suspend fun deleteSpot(spotId: String): Boolean
    suspend fun exportCsv(): File                   // cacheDir/exports/, FileProvider authority com.hotspotmonitor.agent.files
    fun setMirror(on: Boolean)                      // SyncClient calls it; send finder_live via graph.sync.sendLive every telemetry.mirror_s
    fun setHeadless(on: Boolean)
}
```

Use `graph.telemetry.radioFlow` + `setHighRateRadio(true)` while sampling, `Smoother` and
`RadioInfo.rawScore(settings.finder.weights)` from Score.kt, `FinderSpotEntity` for storage, and
enqueue `FinderSpotMsg` / `FinderSpotDeletedMsg` plus a `finder` event after each test.

Finder errors the executor maps to a failed `cmd_result`: `FinderBusyException` (another test runs
or the watchdog is recovering), `FinderCancelledException`, `NoSignalException`,
`IllegalArgumentException` (unknown spot) and `IOException` (speed test). `setBaseline` calls
`telemetry.resetPositionReference()` before writing the spots, so the hub's own baseline follower
(which stores the spot's fix) always runs after it.

### SyncClient, PairingClient (sync/service/ui builder)

```kotlin
enum class ConnPhase { STOPPED, NOT_PAIRED, CONNECTING, CONNECTED, WAITING, UNAUTHORIZED }
data class ConnState(val phase: ConnPhase = ConnPhase.STOPPED, val lastError: String? = null, val sinceMs: Long = 0) {
    val connected: Boolean
}

interface SyncClient : AgentModule {
    val state: StateFlow<ConnState>
    val rates: StateFlow<Rates>
    fun sendLive(msg: AgentToServer): Boolean       // heartbeat, probe, finder_live only
    fun sendProbe(): String?                        // null when not connected
    fun kick()
    suspend fun publishTelemetry(tiers: Set<TelemetryTier>)
    fun onPaired()
    fun onUnpaired()
}

interface PairingClient {
    suspend fun pair(serverUrl: String, code: String): Result<PairResponse>
    suspend fun unpair()
}
```

The SyncClient owns: the WebSocket (`Authorization: Bearer <pairingState.token()>`,
`pingInterval(20 s)`), `hello` handling (`clock.syncWithServer(serverTime)`, `settings.apply(...)`,
rates, then `commands.onConnected()`, static + slow + fast telemetry, outbox drain), reconnect with
backoff 2 s..60 s + jitter and on network-available, close codes 4001 (clear pairing / stop) and
4002, the **telemetry tier scheduler** (fast every `rates.fast_s`, slow every `telemetry.slow_s`,
always enqueued to the outbox so offline periods back-fill), the heartbeat every
`telemetry.heartbeat_s`, the single outbox sender (live rows one by one, backlog in `batch` frames of
`ProtocolLimits.MAX_BATCH_ITEMS`; `outbox.ackUpTo(ack.seq)`), dispatch of `cmd` / `cmd_keep` /
`probe_ack` to `graph.commands`, `rates.mirror` to `graph.finder.setMirror`, and re-sending
`static` when `graph.profiles.profile` or `graph.commands.capabilityMap` changes.

## 5. Protocol in Kotlin

- Kotlin properties are camelCase with `@SerialName("snake_case")`; JSON on the wire is exactly the
  TypeScript shape. Fields the phone cannot read are null (omitted on the wire).
- **Encode only through `Wire.encode(msg)` / `Wire.encodeBuffered(msg)`** (or the sealed base
  serializers). Encoding a concrete class with its own serializer drops the `t` discriminator.
- Decode Server frames with `Wire.decodeServerMessage(text)`: it returns null for unknown `t` or a
  malformed frame (log `Wire.typeOf(text)` and continue). Unknown fields are ignored, unknown enum
  values fall back to the field default (`coerceInputValues`), and server integers are read leniently.
- Buffered messages are created with `seq = 0`; `outbox.enqueue` assigns the real seq.
- `CmdMsg.args` is a `JsonObject`; read it with `optBoolean/optInt/optLong/optDouble/optString` and
  `requireBoolean/requireInt(key, range)/requireString` from Args.kt.
- Event data: `jsonObjectOf("rule" to "internet", "step" to 1)`.
- Constants: `CommandNames.*`, `COMMANDS[name]` (confirm/reauth/isReboot/revertFor),
  `REVERT_SETTLE_S`, `ProtocolLimits.*`, `CloseCodes.*`, `ErrorCodes.*`, `ApiPaths.*`.
- `Settings.DEFAULT` equals `shared/fixtures/settings-default.json` (unit-tested).

## 6. How to ...

**Log.** `graph.log.d/i/w/e(tag, message, throwable?)`. Use a short tag per module (`telemetry`,
`exec`, `watchdog`, `finder`, `sync`). Never log the device token, hotspot password or full SMS
bodies. Never use `android.util.Log` directly.

**Read settings.** `graph.settings.current` (same as `settings.value`) for one value; collect
`graph.settings.settings` to react. Read again at each use instead of caching, because the Server
can change them at any time. `graph.settings.version.value` is the applied version (heartbeat).
Only the SyncClient calls `settings.apply(settings, version)`; local edits use `updateLocal { }`.

**Emit an event.** `graph.events.emit(EventKind.X, Severity.Y, "short message", jsonObjectOf(...))`.
Non-blocking, ordered, safe from any thread. Right before something that may kill the process
(reboot), use `graph.events.emitAndStore(...)` (suspends until it is in the outbox).

**Send a buffered message.** `graph.outbox.enqueue(SomeMsg(...))` from a coroutine; it returns the
seq or null when the database write failed (already logged). Then optionally `graph.sync.kick()`.
Only buffered types go there (telemetry, event, finder_spot, finder_spot_deleted, sms, cmd_ack,
cmd_result, cmd_revert). heartbeat / probe / finder_live go through `graph.sync.sendLive(msg)`.

**Timestamps.** Every `ts` / `at` field: `graph.clock.isoNow()` (Server-offset corrected).
Durations and timers: `graph.clock.elapsedMs()`. Local wall time (weekly reboot, peak hour):
`graph.clock.systemNowMs()` with the phone's time zone. Parse with `Clock.parseIso(text)`.

**Run a root command.** `graph.shell.run("svc data disable")` -> `ShellResult(code, out, err,
durationMs)`; `ok` = exit 0. It never throws for a failing command. Commands share one root shell
and run one at a time: never `sleep` in a command, use `delay` between calls. Quote arguments with
`shellQuote(value)`; pass `logAs = "cmd wifi start-softap <redacted>"` for secrets. Periodic reads
(telemetry's `ip addr`, `ip neigh`, `top`, thermal zones, interface counters, root ping) pass
`quiet = true`: a successful call is not logged, a failing one still is. Helpers: `runAll`,
`runFirstSuccess` (profile fallbacks), `readFile(path, quiet = …)`, `writeFile`, `fileExists`,
`isRooted()`, `rootImpl()`.

**Use the device profile.** `graph.profiles.current` (or collect `graph.profiles.profile`).
`profile.commandTemplates(CommandNames.X)` gives override templates (try them first, in order);
render with `DeviceProfile.render(template, mapOf("ssid" to ssid))` (values are shell-quoted, null
when a placeholder is missing). `profile.chargeControl`, `currentNow.toMilliamps(raw)`,
`usbFunction`, `thermal`, `networkModeValue(mode)`.

**Ids.** `Ids.event()`, `Ids.command()`, `Ids.spot()`, `Ids.sms()`, `Ids.probe()`.

## 7. Database (db/)

| Entity (table) | Columns | DAO |
|----------------|---------|-----|
| `OutboxEntity` (`outbox`) | seq PK autoincrement, type, json, tier?, created_at | `OutboxDao`: insert, updateJson, peek, deleteUpTo, count, countFlow, deleteOldest, insertAssigningSeq (transaction). Use `graph.outbox`, not the DAO. |
| `ArmedRevertEntity` (`armed_revert`) | command_id PK, undo_name, undo_args_json, deadline_ms, confirm_mode, applied_at_ms | `ArmedRevertDao`: upsert, get, all, allFlow, count, delete |
| `RebootLogEntity` (`reboot_log`) | id PK, at_ms, source | `RebootLogDao`: insert, countSince, latest, deleteOlderThan |
| `FinderSpotEntity` (`finder_spot`) | id PK, json (FinderSpot via AgentJson), updated_at_ms | `FinderSpotDao`: upsert, get, all, allFlow, delete |
| `CommandLogEntity` (`command_log`) | id PK, name, args_json, source, status, output?, at_ms | `CommandLogDao`: upsert, get, recentFlow, recent, deleteOlderThan |

Outbox: cap 50,000 rows; over the cap the oldest `fast` telemetry is dropped first, then `slow`,
then `static`. Events, command reports, spots and SMS are never dropped. Schema changes need a
version bump and a Migration (the integrator does this; request it).

## 8. Device profile schema

`app/src/main/assets/profiles/<id>.json`, selected by ProfileRepo (most specific matching `match`
wins; `generic.json` with `"match": {}` or the built-in `DeviceProfile.GENERIC` is the fallback; the
directory may be empty). `settings.profile_override` is deep-merged over the chosen profile.

```json
{
  "id": "samsung_galaxy_s",
  "name": "Samsung Galaxy S10/S20/S21",
  "match": { "manufacturer": ["samsung"], "model_regex": "^SM-G9[789]" },
  "charge_control": [
    { "path": "/sys/class/power_supply/battery/batt_slate_mode", "on": "0", "off": "1", "note": "..." }
  ],
  "current_now": { "unit": "uA", "sign": 1 },
  "usb_function": "rndis",
  "autoboot": { "method": "magisk_autoboot", "note": "..." },
  "thermal": { "cpu": ["big", "mid", "little", "cpu", "tsens"], "modem": ["modem", "mdm", "pa_therm"],
               "skin": ["skin", "xo_therm", "quiet_therm"], "battery": ["battery"] },
  "commands": { "<command name>": ["shell template with {placeholders}", "..."] },
  "network_mode": { "<NetworkMode>": "<value for the device's mechanism>" },
  "notes": ["free text"]
}
```

`commands.<name>` templates run as root **before** the built-in commands, in order; the first that
exits 0 (and, where ControlsImpl can read the state back, really changed it) wins, else the
built-ins are tried. Placeholders (values are shell-quoted by `DeviceProfile.render`; a template
whose placeholder the command does not supply is skipped and logged):

| Command | Placeholders |
|---------|--------------|
| `mobile_data`, `airplane_mode`, `wifi`, `bluetooth` | `{enabled}` true/false, `{state}` 1/0, `{action}` enable/disable |
| `usb_tethering` | the switch three, plus `{function}`: rndis / ncm when on (command arg, else profile `usb_function`), `none` when off |
| `hotspot` | the switch three, plus `{ssid}`, `{password}` when on only (templates logged unrendered) |
| `network_mode` | `{mode}` (wire value), `{value}` (the profile's `network_mode` entry, else `{bitmask_bin}`), `{bitmask}` (decimal), `{bitmask_bin}`, `{slot}` (SIM slot of the default data SIM), `{sub_id}` |
| `screen` | `{on}` true/false, `{keycode}` 224 / 223 |
| `brightness` | `{level}` 0-255 |
| `app_force_stop`, `app_enable`, `app_disable` | `{package}` |
| `reboot`, `power_off`, `process_list` | none |

One template list serves both directions of a switch: the direction reaches it only through
`{enabled}` / `{state}` / `{action}` (`svc data {action}`), so a template without them runs for on
and off alike. `data_cycle` (and the watchdog's data cycle) reuse the `mobile_data` templates;
`airplane_cycle` and the heat rule's radio rest reuse `airplane_mode`. `charging` /
`charge_limit` use `charge_control`, never templates. A template makes the command count as
supported in the capability map.

`network_mode` map: NetworkMode wire value -> value for this phone. Without a `network_mode`
template the Agent runs `cmd phone set-allowed-network-types-for-users -s {slot} <value>`, where
`<value>` is the map entry or the built-in mask; AOSP parses it with radix 2, so a map entry must
be a **binary bitmask string** (`lte_only` = `1000001000000000000`), and one that is not a plain
token (`[A-Za-z0-9_|,.:+-]{1,64}`) is offered to templates only. With a map entry the Agent does
not verify the result (it cannot know the format). docs/device-profiles.md section 3 lists the
built-in masks.

`charge_control`: ChargeProbe picks the first path that exists, has its owner write bit set
(`stat -c %a`, else `ls -l`; never `[ -w ]` / File.canWrite, which root always passes) and, when it
holds its own `on` / `off` value, takes that value written back. The first stop while plugged in
and charging is then watched for ~30 s (like `phase0.sh charge-discover --test`); a path that
left the battery charging is restored to `on`, skipped until restart or a profile change, and an
`error` event (`where: charge_control`) names the next path.

## 9. Threading rules

- Everything long-running is a coroutine in the scope passed to `start(scope)` (Dispatchers.Default).
  Never create threads or `GlobalScope` coroutines; for process-wide work use `graph.appScope`.
- Blocking I/O (root shell, files, sockets that are not OkHttp-async, Android APIs that block)
  runs in `withContext(Dispatchers.IO)`. `RootShell.run` and Room suspend DAOs already switch.
- Do not block the main thread: receivers and activities hand work to a coroutine
  (`graph.appScope.launch { }` or `goAsync()` for receivers that must finish work).
- Expose state as `StateFlow`; mutate it from one place.
- Catch exceptions at the edge of every loop iteration (log + continue); rethrow
  `CancellationException`. A module must never crash the process.

## 10. Manifest (already declared)

Permissions: INTERNET, ACCESS_NETWORK_STATE, CHANGE_NETWORK_STATE, ACCESS_WIFI_STATE,
CHANGE_WIFI_STATE, ACCESS_FINE/COARSE/BACKGROUND_LOCATION, READ_PHONE_STATE, CALL_PHONE, RECEIVE_SMS,
READ_SMS, SEND_SMS, FOREGROUND_SERVICE, FOREGROUND_SERVICE_LOCATION, FOREGROUND_SERVICE_SPECIAL_USE,
RECEIVE_BOOT_COMPLETED, WAKE_LOCK, POST_NOTIFICATIONS, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
PACKAGE_USAGE_STATS, VIBRATE, BLUETOOTH_CONNECT.

Components: `AgentApp`; `ui.MainActivity` (launcher); `ui.PairActivity` (VIEW, `hsm://pair`);
`service.AgentService` (foregroundServiceType `location|specialUse`: it starts as `location`, and
falls back to `specialUse` on Android 14+ only until RootSetup has granted location);
`service.BootReceiver` (BOOT_COMPLETED, LOCKED_BOOT_COMPLETED, MY_PACKAGE_REPLACED);
`service.ControlReceiver` (exported, `com.hotspotmonitor.agent.action.START`);
`telemetry.SmsReceiver` (SMS_RECEIVED, permission BROADCAST_SMS); FileProvider
`com.hotspotmonitor.agent.files` (`cache/exports/`, `files/logs/`). A `<queries>` entry for
`android.intent.action.TTS_SERVICE` lets the Finder bind the text-to-speech engine (Android 11+
package visibility).

RootSetup (run by `startAll`, or "Grant all via root" on the Setup screen) grants the runtime
permissions, usage access, unrestricted background running and the doze whitelist, and switches
location services on (`cmd location set-location-enabled true`, falling back to
`settings put secure location_mode 3` and the per-provider list): Android 10+ returns no cell info
while location is off. The check runs at every service start (and from the Setup screen), so a
location switch turned off later is turned on again at the next start. It does not change
`hidden_api_policy`; hotspot state falls back to the tethering list when
`WifiManager.isWifiApEnabled` is blocked.

Network security: debug builds allow cleartext (LAN dev server on port 8080); release forbids it.

## 11. Tests

JVM unit tests in `app/src/test` (`./gradlew testDebugUnitTest`). `android.*` calls return default
values (`isReturnDefaultValues`), so keep Android-free logic in plain classes. Helpers in
`testutil/`: `Fixtures` (reads `shared/fixtures` through the `fixturesDir` system property),
`canonical(json)`, `FakeOutboxDao`, `TestClock`, `testLog()`, and `core.MemoryStore`.

## 12. Internal hooks between packages

Implementation-only APIs that other packages use through a cast (`as? XxxImpl`, with a fallback to
the interface when the cast fails):

| Hook | Used by | Purpose |
|------|---------|---------|
| `ControlsImpl.reboot(source, reason, beforeReboot)` | executor, watchdog | runs `beforeReboot` (persist the pending command id, `emitAndStore` the explaining event) after the limiter check and right before rebooting |
| `ControlsImpl.restoreInterruptedCycles()` | executor start | re-enables data / leaves airplane mode after a cycle or radio rest cut short by a process death |
| `ControlsImpl.currentUsbFunction()`, `currentNetworkMode()` | executor (auto-revert undo) | read the state to restore before a change |
| `ChargeControllerImpl.clearManualOverride()` | control | a manual "charging on" ends when the battery is full |
| `RadioActivity` | control, watchdog | process-wide gate: one radio/power action at a time (`busy`) |
| `TetherIntent` | executor, watchdog | the user's "tethering off" wish; the watchdog does not undo it until USB is reconnected |
| `FinderEngineImpl.ui`, `setScreenOpen`, `setMuted`, `stopWalk`, `cancelTest`, `launchSpotTest`, `launchSpeedTest`, `photoCaptureFile`, `attachPhoto`, `photoFile`, `FILE_PROVIDER_AUTHORITY` | ui/FinderScreen | local-only Finder UI (mute, photos, progress, cancel) |

## 13. Preference keys

`graph.prefs` (SharedPreferences `agent`), by owner:

| Prefix | Keys |
|--------|------|
| `settings.` | `json`, `version`, `local_edits` (SettingsRepo) |
| `pairing.` | `server_url`, `ws_url`, `device_id`, `paired_at`; the token is in SecureStore |
| `root_setup.` | `done_version` |
| `telemetry.` | `last_cell`, `boot_id`, `traffic_acc`, `ref.gravity`, `ref.lat`, `ref.lon`, `ref.acc`, `ref.moved_alerted`, `ref.baseline_spot` |
| `control.` | `capabilities`, `pending_reboot_id`, `pending_reboot_at`, `tether_user_off`, `hotspot_ssid`, `hotspot_password` (SecureStore), `network_mode`, `restore_mobile_data`, `restore_airplane_off`, `charge_manual` |
| `watchdog.` | `weekly_reboot_last` |
| `finder.` | `headless` (default true), `muted` |
| `service.` | `user_stopped` |
| `ui.` | `setup_done`, `keep_screen_on` |
