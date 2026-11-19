package com.hotspotmonitor.agent.telemetry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.BatteryManager
import com.hotspotmonitor.agent.control.ChargeControllerImpl
import com.hotspotmonitor.agent.control.ChargeSwitchWrite
import com.hotspotmonitor.agent.core.AgentGraph
import com.hotspotmonitor.agent.core.Clock
import com.hotspotmonitor.agent.core.FastGroups
import com.hotspotmonitor.agent.core.SlowGroups
import com.hotspotmonitor.agent.core.StaticGroups
import com.hotspotmonitor.agent.core.TelemetryHub
import com.hotspotmonitor.agent.protocol.AgentJson
import com.hotspotmonitor.agent.protocol.EventKind
import com.hotspotmonitor.agent.protocol.HeatInfo
import com.hotspotmonitor.agent.protocol.NetInfo
import com.hotspotmonitor.agent.protocol.NetType
import com.hotspotmonitor.agent.protocol.PositionInfo
import com.hotspotmonitor.agent.protocol.PowerInfo
import com.hotspotmonitor.agent.protocol.RadioInfo
import com.hotspotmonitor.agent.protocol.Severity
import com.hotspotmonitor.agent.protocol.Smoother
import com.hotspotmonitor.agent.protocol.SystemInfo
import com.hotspotmonitor.agent.protocol.TetherInfo
import com.hotspotmonitor.agent.protocol.TrafficInfo
import com.hotspotmonitor.agent.protocol.jsonObjectOf
import com.hotspotmonitor.agent.protocol.rawScore
import com.hotspotmonitor.agent.protocol.round1
import com.hotspotmonitor.agent.protocol.withoutNulls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/**
 * The collectors of plan 6.2 and the change events they produce.
 *
 * Loops (in the service scope, Dispatchers.Default; blocking work switches to IO):
 * - radio: every [RADIO_BASE_MS], or every [RADIO_HIGH_RATE_MS] while the Finder asks for it;
 *   cell_change / net_type_change after a new value held for 2 readings.
 * - internet: every telemetry.internet_check_s, and right after the default network changes;
 *   `connectivity` events.
 * - power + battery temperature: every [POWER_MS] (watchdog heat and charge rules read them).
 * - slow groups: every telemetry.slow_s (shared with [collectSlow], so the work is not doubled).
 * - location: refreshed when older than 10 minutes.
 * Broadcasts (USB state, tethering, power) refresh their group immediately and emit
 * usb_* / tether_* / power_* events; a plug change caused by the Agent's own charge switch (paths that
 * cut USB input) is not reported as a power loss ([PowerEventFilter]). Every collector fails soft: a
 * field it cannot read is null.
 */
class TelemetryHubImpl(private val graph: AgentGraph) : TelemetryHub {
    private val failures by lazy { FailureLog(graph.log, TAG) }

    private val _radio = MutableStateFlow<RadioInfo?>(null)
    private val _net = MutableStateFlow<NetInfo?>(null)
    private val _power = MutableStateFlow<PowerInfo?>(null)
    private val _heat = MutableStateFlow<HeatInfo?>(null)
    private val _tether = MutableStateFlow<TetherInfo?>(null)
    private val _traffic = MutableStateFlow<TrafficInfo?>(null)
    private val _system = MutableStateFlow<SystemInfo?>(null)
    private val _position = MutableStateFlow<PositionInfo?>(null)
    private val _online = MutableStateFlow<Boolean?>(null)
    private val _radioFlow = MutableSharedFlow<RadioInfo>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val radio: StateFlow<RadioInfo?> = _radio.asStateFlow()
    override val net: StateFlow<NetInfo?> = _net.asStateFlow()
    override val power: StateFlow<PowerInfo?> = _power.asStateFlow()
    override val heat: StateFlow<HeatInfo?> = _heat.asStateFlow()
    override val tether: StateFlow<TetherInfo?> = _tether.asStateFlow()
    override val traffic: StateFlow<TrafficInfo?> = _traffic.asStateFlow()
    override val system: StateFlow<SystemInfo?> = _system.asStateFlow()
    override val position: StateFlow<PositionInfo?> = _position.asStateFlow()
    override val radioFlow: SharedFlow<RadioInfo> = _radioFlow.asSharedFlow()

    override val online: StateFlow<Boolean?> = _online.asStateFlow()

    // Collectors are created on first use: the constructor must not touch other parts of the graph.
    private val radioCollector by lazy { RadioCollector(graph.app, failures) }
    private val inspector by lazy { NetworkInspector(graph.app, failures) }
    private val netCollector by lazy { NetCollector({ graph.http }, { graph.shell }, failures) }
    private val trafficCollector by lazy { TrafficCollector(graph.app, graph.prefs, { graph.shell }, inspector, failures) }
    private val tetherCollector by lazy { TetherCollector(graph.app, { graph.shell }, failures, graph.clock::elapsedMs) }
    private val powerCollector by lazy { PowerCollector(graph.app, failures) }
    private val heatCollector by lazy { HeatCollector(graph.app, { graph.shell }, failures) }
    private val systemCollector by lazy { SystemCollector(graph.app, { graph.shell }, failures, graph.clock::elapsedMs) }
    private val positionCollector by lazy { PositionCollector(graph.app, graph.prefs, failures, graph.clock::elapsedMs) }
    private val staticCollector by lazy { StaticCollector(graph.app, { graph.shell }, failures) }

    private val radioLock = Mutex()
    private val netLock = Mutex()
    private val powerLock = Mutex()
    private val tetherLock = Mutex()
    private val slowLock = Mutex()

    private val smoother = Smoother(5_000)
    private val cellChanges = ChangeDebouncer<CellRef>(CHANGE_READINGS) { a, b -> a.sameCellAs(b) }
    private val netTypeChanges = ChangeDebouncer<NetType>(CHANGE_READINGS)
    private val connectivity = ConnectivityTracker()
    private val powerEvents = PowerEventFilter()

    @Volatile private var radioAtMs = NEVER
    @Volatile private var slowAtMs = NEVER
    @Volatile private var powerAtMs = NEVER
    @Volatile private var zonesAtMs = NEVER
    @Volatile private var netCheckAtMs = NEVER
    @Volatile private var highRate = false
    @Volatile private var zones: ThermalSummary? = null
    @Volatile private var lastCheck: CheckResult? = null
    @Volatile private var publicIpv4: String? = null
    @Volatile private var publicIpv6: String? = null
    @Volatile private var publicIpAtMs = NEVER
    @Volatile private var publicIpIf: String? = null

    private val radioWake = Channel<Unit>(Channel.CONFLATED)
    private val netWake = Channel<Unit>(Channel.CONFLATED)
    private val tetherWake = Channel<Unit>(Channel.CONFLATED)
    private val powerWake = Channel<Unit>(Channel.CONFLATED)

    private var receiversRegistered = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // ---------------------------------------------------------------------------------------------
    // lifecycle
    // ---------------------------------------------------------------------------------------------

    override fun start(scope: CoroutineScope) {
        seedChangeDetectors()
        registerReceivers()
        registerNetworkCallback()
        // Collectors make blocking binder, file and process calls: they run on the IO dispatcher.
        scope.launch(Dispatchers.IO) { checkBoot() }
        scope.launch(Dispatchers.IO) { loop("radio") { radioTick() } }
        scope.launch(Dispatchers.IO) { loop("internet") { netTick() } }
        scope.launch(Dispatchers.IO) { loop("power") { powerTick() } }
        scope.launch(Dispatchers.IO) { loop("slow") { slowTick() } }
        scope.launch(Dispatchers.IO) { loop("location") { locationTick() } }
        scope.launch(Dispatchers.IO) { loop("tether_trigger") { tetherTriggerTick() } }
        scope.launch(Dispatchers.IO) { loop("power_trigger") { powerTriggerTick() } }
        scope.launch(Dispatchers.IO) { followFinderBaseline() }
    }

    override fun stop() {
        unregisterReceivers()
        unregisterNetworkCallback()
        try {
            radioCollector.stopListening()
        } catch (e: Exception) {
            graph.log.w(TAG, "Could not stop the display-info listener", e)
        }
    }

    /** Runs [tick] forever; a failing iteration is logged and retried after a pause, never fatal. */
    private suspend fun loop(name: String, tick: suspend () -> Unit) {
        while (currentCoroutineContext().isActive) {
            try {
                tick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures.fail("loop_$name", "Telemetry $name loop failed", e)
                delay(LOOP_ERROR_PAUSE_MS)
            }
        }
    }

    private fun now(): Long = graph.clock.elapsedMs()

    /** Waits until [dueAtMs] (elapsed clock) or until [wake] fires, whichever is first. */
    private suspend fun waitUntil(dueAtMs: Long, wake: Channel<Unit>? = null) {
        val wait = dueAtMs - now()
        if (wait <= 0) return
        if (wake == null) delay(wait) else withTimeoutOrNull(wait) { wake.receive() }
    }

    // ---------------------------------------------------------------------------------------------
    // TelemetryHub
    // ---------------------------------------------------------------------------------------------

    override suspend fun collectFast(): FastGroups = withContext(Dispatchers.IO) {
        val cached = _radio.value
        val radio = if (cached != null && now() - radioAtMs <= FAST_REUSE_MS) {
            cached
        } else {
            failures.soft("collect_radio") { refreshRadio(fresh = true) } ?: _radio.value
        }
        // The first check may still be running (started with the service): wait for it, do not repeat it.
        if (lastCheck == null) failures.soft("collect_net") { netLock.withLock { if (lastCheck == null) runCheckLocked() } }
        val net = failures.soft("publish_net") { publishNet() } ?: _net.value
        FastGroups(radio, net)
    }

    override suspend fun collectSlow(): SlowGroups = withContext(Dispatchers.IO) {
        if (now() - slowAtMs > SLOW_REUSE_MS || _system.value == null) refreshSlow()
        SlowGroups(_power.value, _heat.value, _system.value, _tether.value, _traffic.value, _position.value)
    }

    override suspend fun collectStatic(): StaticGroups = withContext(Dispatchers.IO) {
        // StaticCollector fails soft field by field; DeviceInfo itself always builds.
        val device = staticCollector.device(
            DeviceExtras(
                deviceId = graph.pairingState.deviceId,
                agentVersion = graph.agentVersion,
                agentVersionCode = graph.agentVersionCode,
                profileId = graph.profiles.current.id,
            ),
        )
        val capabilities = failures.soft("capability_map") { graph.commands.capabilityMap.value }.orEmpty()
        StaticGroups(device, capabilities)
    }

    override suspend fun checkInternetNow(): NetInfo = withContext(Dispatchers.IO) { netLock.withLock { runCheckLocked() } }

    /**
     * One internet check; the caller holds [netLock]. An unexpected error is not counted as an
     * outage (the watchdog would start cycling data): the previous reading is returned instead.
     */
    private suspend fun runCheckLocked(): NetInfo {
        val settings = graph.settings.current.telemetry
        val result = try {
            netCollector.check(settings)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failures.fail("internet_check", "Internet check crashed; keeping the previous reading", e)
            netCheckAtMs = now()
            return _net.value ?: NetInfo(online = false)
        }
        failures.ok("internet_check")
        netCheckAtMs = now()
        lastCheck = result
        _online.value = result.httpOk
        val transition = connectivity.onCheck(result.httpOk, graph.clock.serverNow())
        if (transition != null) emitConnectivity(transition)
        if (result.httpOk) refreshPublicIp(settings.publicIpUrl, settings.publicIpEveryS)
        return publishNet()
    }

    override fun setHighRateRadio(on: Boolean) {
        if (highRate == on) return
        highRate = on
        radioWake.trySend(Unit)
    }

    /** Forgets the installed position / orientation reference; the next stable reading becomes it. */
    override fun resetPositionReference() {
        positionCollector.resetReference()
    }

    // ---------------------------------------------------------------------------------------------
    // radio
    // ---------------------------------------------------------------------------------------------

    private suspend fun radioTick() {
        val interval = if (highRate) RADIO_HIGH_RATE_MS else RADIO_BASE_MS
        val due = radioAtMs + interval
        if (now() < due) {
            waitUntil(due, radioWake)
            return
        }
        refreshRadio(fresh = true)
    }

    private suspend fun refreshRadio(fresh: Boolean): RadioInfo? = radioLock.withLock {
        try {
            val reading = radioCollector.read(fresh) ?: return@withLock _radio.value
            val finder = graph.settings.current.finder
            smoother.windowMs = finder.smoothS.coerceAtLeast(1) * 1000L
            val raw = reading.rawScore(finder.weights)
            val score = raw?.let { smoother.add(it, now()) }?.let(::round1)
            val info = reading.copy(score = score)
            _radio.value = info
            _radioFlow.tryEmit(info)
            detectRadioChanges(info)
            info
        } finally {
            radioAtMs = now()
        }
    }

    private fun detectRadioChanges(info: RadioInfo) {
        cellChanges.offer(CellRef.of(info))?.let { change ->
            persistCell(change.to)
            graph.events.emit(
                EventKind.CELL_CHANGE, Severity.INFO,
                "Serving cell changed: ${describe(change.from)} -> ${describe(change.to)}",
                jsonObjectOf("from" to cellJson(change.from), "to" to cellJson(change.to)),
            )
        }
        // Persist the first confirmed cell too (not every reading: prefs writes go to disk).
        cellChanges.confirmed?.let { confirmed ->
            val stored = storedCell
            if (stored == null || !stored.sameCellAs(confirmed)) persistCell(confirmed)
        }
        val type = info.netType.takeIf { it != NetType.UNKNOWN }
        netTypeChanges.offer(type)?.let { change ->
            graph.events.emit(
                EventKind.NET_TYPE_CHANGE, Severity.INFO, "Network type ${change.from} -> ${change.to}",
                jsonObjectOf("from" to change.from, "to" to change.to),
            )
        }
    }

    @Volatile private var storedCell: CellRef? = null

    private fun seedChangeDetectors() {
        val stored = graph.prefs.getString(KEY_LAST_CELL)?.let { json ->
            try {
                AgentJson.decodeFromString(CellRef.serializer(), json)
            } catch (e: Exception) {
                null
            }
        }
        storedCell = stored
        if (cellChanges.confirmed == null) cellChanges.seed(stored)
    }

    /** Remembers the serving cell so a change across an Agent restart or reboot is reported too. */
    private fun persistCell(cell: CellRef) {
        storedCell = cell
        try {
            graph.prefs.putString(KEY_LAST_CELL, AgentJson.encodeToString(CellRef.serializer(), cell))
        } catch (e: Exception) {
            failures.fail("cell_persist", "Could not store the serving cell", e)
        }
    }

    private fun cellJson(cell: CellRef): JsonObject =
        jsonObjectOf("pci" to cell.pci, "cell_id" to cell.cellId, "band" to cell.band, "earfcn" to cell.earfcn, "net_type" to cell.netType)
            .withoutNulls() as JsonObject

    private fun describe(cell: CellRef): String = listOfNotNull(
        cell.pci?.let { "PCI $it" }, cell.band?.let { "B$it" }, cell.cellId?.let { "cell $it" },
    ).joinToString(" ").ifEmpty { "?" }

    // ---------------------------------------------------------------------------------------------
    // internet
    // ---------------------------------------------------------------------------------------------

    private suspend fun netTick() {
        val intervalMs = graph.settings.current.telemetry.internetCheckS.coerceAtLeast(MIN_CHECK_S) * 1000L
        val due = netCheckAtMs + intervalMs
        if (now() < due) {
            waitUntil(due, netWake)
            // A network change wakes us early; keep a minimum spacing between checks.
            if (now() - netCheckAtMs < MIN_CHECK_SPACING_MS) delay(MIN_CHECK_SPACING_MS - (now() - netCheckAtMs))
        }
        checkInternetNow()
        // Network changes signalled while this check ran are covered by it.
        netWake.tryReceive()
    }

    /** Builds the net group from the latest check plus fresh throughput / interface values. */
    private fun publishNet(): NetInfo {
        val check = lastCheck
        val ifaces = inspector.interfaces()
        val (rx, tx) = netCollector.throughput(now())
        val online = check?.httpOk ?: false
        val watchdogDownMs = failures.soft("watchdog_state") { graph.watchdog.state.value.internetDownSinceMs }
        val downSinceMs = watchdogDownMs ?: if (!online) connectivity.downSinceMs else null
        val info = NetInfo(
            online = online,
            httpMs = check?.httpMs,
            pingMs = check?.ping?.avgMs,
            pingLossPct = check?.ping?.lossPct,
            jitterMs = check?.ping?.jitterMs,
            dnsOk = check?.dnsOk,
            publicIpv4 = publicIpv4,
            publicIpv6 = publicIpv6 ?: ifaces.cellularIpv6,
            rxBps = rx,
            txBps = tx,
            activeIf = ifaces.activeIf,
            internetDownSince = downSinceMs?.let { Clock.formatIso(it) },
        )
        _net.value = info
        return info
    }

    private suspend fun refreshPublicIp(url: String, everyS: Int) {
        val activeIf = inspector.interfaces().activeIf
        val stale = now() - publicIpAtMs >= everyS.coerceAtLeast(60) * 1000L
        val ifChanged = activeIf != null && activeIf != publicIpIf
        if (!stale && !ifChanged) return
        publicIpAtMs = now()
        publicIpIf = activeIf
        val ip = failures.soft("public_ip") { netCollector.publicIp(url) } ?: return
        if (ip.contains(':')) publicIpv6 = ip else publicIpv4 = ip
    }

    private fun emitConnectivity(t: ConnectivityTracker.Transition) {
        if (t.online) {
            graph.events.emit(
                EventKind.CONNECTIVITY, Severity.INFO,
                "Internet back" + (t.downForS?.let { " after ${formatDuration(it)}" } ?: ""),
                jsonObjectOf("online" to true, "down_for_s" to t.downForS).withoutNulls() as JsonObject,
            )
        } else {
            graph.events.emit(EventKind.CONNECTIVITY, Severity.WARNING, "Internet check failing", jsonObjectOf("online" to false))
        }
    }

    private fun registerNetworkCallback() {
        val cm = graph.app.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                netWake.trySend(Unit)
            }

            override fun onLost(network: Network) {
                netWake.trySend(Unit)
            }
        }
        failures.soft("network_callback") {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        val cm = graph.app.getSystemService(ConnectivityManager::class.java) ?: return
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            // Already unregistered.
        }
    }

    // ---------------------------------------------------------------------------------------------
    // power and heat
    // ---------------------------------------------------------------------------------------------

    private suspend fun powerTick() {
        val due = powerAtMs + POWER_MS
        if (now() < due) {
            waitUntil(due)
            return
        }
        refreshPowerAndHeat(zonesMaxAgeMs = ZONES_MAX_AGE_MS)
    }

    private suspend fun powerTriggerTick() {
        powerWake.receive()
        delay(BROADCAST_SETTLE_MS)
        refreshPowerAndHeat(zonesMaxAgeMs = ZONES_MAX_AGE_MS)
    }

    private suspend fun refreshPowerAndHeat(zonesMaxAgeMs: Long) = powerLock.withLock {
        try {
            val battery = powerCollector.batteryIntent()
            val charge = failures.soft("charge_state") { graph.charge.state.value }
            _power.value = powerCollector.collect(battery, graph.profiles.current, charge)
            checkPendingPowerLoss(battery)
            if (zones == null || now() - zonesAtMs >= zonesMaxAgeMs) {
                val read = heatCollector.readZones()
                zones = ThermalMath.summarize(read, graph.profiles.current.thermal)
                zonesAtMs = now()
            }
            val summary = zones
            val watchdog = failures.soft("watchdog_state") { graph.watchdog.state.value }
            _heat.value = HeatInfo(
                batteryC = powerCollector.batteryTempC(battery) ?: summary?.battery,
                cpuC = summary?.cpu,
                modemC = summary?.modem,
                skinC = summary?.skin,
                thermalStatus = heatCollector.thermalStatus(),
                zones = summary?.zones?.ifEmpty { null },
                radioResting = watchdog?.radioResting,
            )
        } finally {
            powerAtMs = now()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // slow groups
    // ---------------------------------------------------------------------------------------------

    private suspend fun slowTick() {
        val intervalMs = graph.settings.current.telemetry.slowS.coerceAtLeast(MIN_SLOW_S) * 1000L
        val due = slowAtMs + intervalMs
        if (now() < due) {
            waitUntil(minOf(due, now() + SLOW_POLL_MS))
            return
        }
        refreshSlow()
    }

    /** Refreshes every slow group; one failing group does not stop the others. */
    private suspend fun refreshSlow() {
        slowLock.withLock {
            try {
                failures.soft("slow_power") { refreshPowerAndHeat(zonesMaxAgeMs = 0) }
                failures.soft("slow_system") { refreshSystem() }
                failures.soft("slow_tether") { refreshTether() }
                failures.soft("slow_traffic") { refreshTraffic() }
                failures.soft("slow_position") { refreshPosition() }
            } finally {
                slowAtMs = now()
            }
        }
    }

    private suspend fun refreshSystem() {
        val rebootLimiter = graph.rebootLimiter
        val reboots = failures.soft("reboot_count") { rebootLimiter.countInWindow(graph.clock.systemNowMs()) }
        _system.value = systemCollector.collect(
            SystemExtras(
                agentVersion = graph.agentVersion,
                agentUptimeS = graph.agentUptimeS(),
                rebootsInWindow = reboots,
                serverNowMs = graph.clock.serverNow(),
            ),
        )
    }

    private suspend fun refreshTraffic() {
        val plan = graph.settings.current.plan
        _traffic.value = trafficCollector.collect(plan.cycleStartDay, graph.clock.systemNowMs(), bootId())
    }

    private suspend fun refreshPosition() {
        val orientation = failures.soft("orientation") { positionCollector.sampleOrientation() }
        val (info, moved) = positionCollector.evaluate(orientation, graph.settings.current.watchdog)
        _position.value = info
        if (moved != null) {
            val parts = listOfNotNull(
                moved.tiltChangeDeg?.let { "tilted ${round1(it)}°" },
                moved.movedM?.let { "moved ${round1(it)} m" },
            )
            graph.events.emit(
                EventKind.MOVED, Severity.WARNING, "Phone ${parts.joinToString(" and ").ifEmpty { "moved" }} since installation",
                jsonObjectOf("tilt_change_deg" to moved.tiltChangeDeg, "moved_m" to moved.movedM, "lat" to moved.lat, "lon" to moved.lon)
                    .withoutNulls() as JsonObject,
            )
        }
    }

    private suspend fun locationTick() {
        positionCollector.refreshLocationIfStale()
        delay(LOCATION_POLL_MS)
    }

    private suspend fun followFinderBaseline() {
        try {
            graph.finder.spots
                .map { spots -> spots.firstOrNull { it.baseline } }
                .distinctUntilChangedBy { it?.id }
                .collect { spot -> failures.soft("baseline_reference") { positionCollector.onBaselineSpot(spot) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failures.fail("baseline_follow", "Cannot follow the Finder baseline; position reference stays as is", e)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // tethering
    // ---------------------------------------------------------------------------------------------

    private suspend fun tetherTriggerTick() {
        tetherWake.receive()
        delay(BROADCAST_SETTLE_MS)
        refreshTether()
    }

    private suspend fun refreshTether() = tetherLock.withLock {
        val next = tetherCollector.collect()
        val previous = _tether.value
        _tether.value = next
        if (previous == null) return@withLock
        val wasUsb = previous.usbConnected
        val isUsb = next.usbConnected
        if (wasUsb != null && isUsb != null && wasUsb != isUsb) {
            graph.events.emit(
                if (isUsb) EventKind.USB_CONNECTED else EventKind.USB_DISCONNECTED, Severity.INFO,
                if (isUsb) "USB connected" else "USB disconnected",
                jsonObjectOf("usb_function" to next.usbFunction),
            )
        }
        val wasOn = previous.usbTetherOn
        val isOn = next.usbTetherOn
        if (wasOn != null && isOn != null && wasOn != isOn) {
            val unexpected = !isOn && isUsb == true
            graph.events.emit(
                if (isOn) EventKind.TETHER_UP else EventKind.TETHER_DOWN,
                if (unexpected) Severity.WARNING else Severity.INFO,
                when {
                    isOn -> "USB tethering on" + (next.tetherIf?.let { " ($it)" } ?: "")
                    unexpected -> "USB tethering off while USB is connected"
                    else -> "USB tethering off"
                },
                jsonObjectOf("tether_if" to (next.tetherIf ?: previous.tetherIf), "usb_connected" to isUsb).withoutNulls() as JsonObject,
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // broadcasts
    // ---------------------------------------------------------------------------------------------

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val connected = when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> true
                Intent.ACTION_POWER_DISCONNECTED -> false
                else -> return
            }
            val battery = try {
                powerCollector.batteryIntent()
            } catch (e: Exception) {
                null
            }
            val percent = battery?.let { b ->
                PowerMath.percent(b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), b.getIntExtra(BatteryManager.EXTRA_SCALE, -1))
            }
            val lastWrite = lastChargeSwitchWrite()
            if (connected) {
                if (powerEvents.onConnected(now(), lastWrite)) {
                    val plug = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)?.takeIf { it >= 0 }?.let { PowerMath.plug(it) }
                    graph.events.emit(
                        EventKind.POWER_CONNECTED, Severity.INFO, "Power connected" + (percent?.let { " at $it%" } ?: ""),
                        jsonObjectOf("percent" to percent, "plug" to plug).withoutNulls() as JsonObject,
                    )
                } else {
                    graph.log.i(TAG, "Plug back after the Agent switched charging on (charge switch cuts USB input): no event")
                }
            } else if (powerEvents.onDisconnected(now(), lastWrite, _tether.value?.usbConnected)) {
                emitPowerLost(percent, null)
            } else {
                graph.log.i(TAG, "Plug gone right after the Agent switched charging off (charge switch cuts USB input): not a power loss")
            }
            powerWake.trySend(Unit)
        }
    }

    private fun lastChargeSwitchWrite(): ChargeSwitchWrite? =
        failures.soft("charge_switch_write") { (graph.charge as? ChargeControllerImpl)?.lastSwitchWrite }

    /** A disconnect put down to the charge switch turned out to be a real loss ([PowerEventFilter.check]). */
    private fun checkPendingPowerLoss(battery: Intent?) {
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)?.takeIf { it >= 0 }?.let { it != 0 }
        val lost = powerEvents.check(now(), lastChargeSwitchWrite(), plugged, _tether.value?.usbConnected) ?: return
        val percent = battery?.let { b ->
            PowerMath.percent(b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), b.getIntExtra(BatteryManager.EXTRA_SCALE, -1))
        }
        emitPowerLost(
            percent,
            when (lost) {
                PowerEventFilter.Lost.USB_GONE -> "USB disconnected while charging was paused"
                PowerEventFilter.Lost.NOT_BACK_AFTER_RESUME -> "no power after charging was switched back on"
            },
        )
    }

    private fun emitPowerLost(percent: Int?, detail: String?) {
        graph.events.emit(
            EventKind.POWER_LOST, Severity.WARNING,
            "USB power lost" + (percent?.let { " at $it%" } ?: "") + (detail?.let { " ($it)" } ?: ""),
            jsonObjectOf("percent" to percent, "detail" to detail).withoutNulls() as JsonObject,
        )
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                TetherCollector.ACTION_USB_STATE, TetherCollector.ACTION_TETHER_STATE_CHANGED -> tetherWake.trySend(Unit)
                Intent.ACTION_BATTERY_CHANGED -> if (now() - powerAtMs >= BATTERY_CHANGED_MIN_MS) powerWake.trySend(Unit)
            }
        }
    }

    @Synchronized
    private fun registerReceivers() {
        if (receiversRegistered) return
        try {
            graph.app.registerSystemReceiver(
                powerReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_POWER_CONNECTED)
                    addAction(Intent.ACTION_POWER_DISCONNECTED)
                },
            )
            graph.app.registerSystemReceiver(
                stateReceiver,
                IntentFilter().apply {
                    addAction(TetherCollector.ACTION_USB_STATE)
                    addAction(TetherCollector.ACTION_TETHER_STATE_CHANGED)
                    addAction(Intent.ACTION_BATTERY_CHANGED)
                },
            )
            receiversRegistered = true
        } catch (e: Exception) {
            graph.log.e(TAG, "Could not register telemetry receivers; power and USB events will be late", e)
            unregisterReceivers()
        }
    }

    @Synchronized
    private fun unregisterReceivers() {
        graph.app.unregisterQuietly(powerReceiver)
        graph.app.unregisterQuietly(stateReceiver)
        receiversRegistered = false
    }

    // ---------------------------------------------------------------------------------------------
    // boot
    // ---------------------------------------------------------------------------------------------

    /**
     * The only source of the `boot` event: emitted once per boot when the Agent starts and the boot
     * id (BOOT_COUNT) differs from the stored one. Works whoever starts the service (BootReceiver,
     * or the Magisk script before BOOT_COMPLETED).
     */
    private suspend fun checkBoot() {
        val count = systemCollector.bootCount()
        val id = bootId()
        val stored = graph.prefs.getString(KEY_BOOT_ID)
        if (stored == id) return
        graph.prefs.putString(KEY_BOOT_ID, id)
        if (stored == null) return // first run after install: not a boot we witnessed
        val reason = failures.soft("boot_reason") { systemCollector.bootReason() }
        graph.events.emit(
            EventKind.BOOT, Severity.INFO,
            "Phone booted" + (count?.let { " (boot #$it)" } ?: "") + (reason?.let { ": $it" } ?: ""),
            jsonObjectOf("boot_count" to count, "boot_reason" to reason).withoutNulls() as JsonObject,
        )
    }

    /** Identifies the current boot: BOOT_COUNT, else the boot time to the minute. */
    private fun bootId(): String {
        systemCollector.bootCount()?.let { return "count:$it" }
        val bootWallMs = graph.clock.systemNowMs() - graph.clock.elapsedMs()
        return "time:${bootWallMs / 60_000}"
    }

    private fun formatDuration(seconds: Long): String = when {
        seconds < 120 -> "$seconds s"
        seconds < 7200 -> "${seconds / 60} min"
        else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
    }

    private companion object {
        const val TAG = "telemetry"
        const val NEVER = Long.MIN_VALUE / 2
        const val CHANGE_READINGS = 2
        const val RADIO_BASE_MS = 5_000L
        const val RADIO_HIGH_RATE_MS = 1_000L
        const val FAST_REUSE_MS = 1_000L
        const val SLOW_REUSE_MS = 10_000L
        const val SLOW_POLL_MS = 5_000L
        const val MIN_SLOW_S = 10
        const val MIN_CHECK_S = 5
        const val MIN_CHECK_SPACING_MS = 3_000L
        const val POWER_MS = 15_000L
        const val ZONES_MAX_AGE_MS = 55_000L
        const val BATTERY_CHANGED_MIN_MS = 5_000L
        const val BROADCAST_SETTLE_MS = 1_000L
        const val LOCATION_POLL_MS = 60_000L
        const val LOOP_ERROR_PAUSE_MS = 5_000L
        const val KEY_LAST_CELL = "telemetry.last_cell"
        const val KEY_BOOT_ID = "telemetry.boot_id"
    }
}
