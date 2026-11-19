package com.hotspotmonitor.agent.sync

import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import com.hotspotmonitor.agent.core.AgentGraph
import com.hotspotmonitor.agent.core.ConnState
import com.hotspotmonitor.agent.core.SyncClient
import com.hotspotmonitor.agent.protocol.AgentToServer
import com.hotspotmonitor.agent.protocol.CmdMsg
import com.hotspotmonitor.agent.protocol.FinderLive
import com.hotspotmonitor.agent.protocol.HeartbeatMsg
import com.hotspotmonitor.agent.protocol.Rates
import com.hotspotmonitor.agent.protocol.TelemetryMsg
import com.hotspotmonitor.agent.protocol.TelemetryTier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * The SyncClient module (ARCHITECTURE.md section 4): wires [SyncEngine] (socket, outbox sender,
 * dispatch) and [TelemetryScheduler] (tier schedule) to the graph, and reconnects on Android's
 * network-available callback.
 */
class SyncClientImpl(private val graph: AgentGraph) : SyncClient {
    private val transport = OkHttpWsTransport(
        userAgent = "HotspotAgent/${graph.agentVersion} (Android ${Build.VERSION.SDK_INT})",
    ) {
        graph.http.newBuilder()
            .pingInterval(PING_INTERVAL_S, TimeUnit.SECONDS)
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            // A sleeping free-tier Server answers the upgrade only after it has booted.
            .readTimeout(UPGRADE_READ_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    private val host = GraphHost()

    private val engine: SyncEngine by lazy {
        SyncEngine(graph.clock, graph.log, graph.outbox, graph.settings, transport, host)
    }

    private val scheduler: TelemetryScheduler by lazy {
        TelemetryScheduler(graph.clock, graph.log, graph.outbox, GraphCollector(), onEnqueued = { engine.kick() })
    }

    @Volatile private var moduleScope: CoroutineScope? = null
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override val state: StateFlow<ConnState> get() = engine.state
    override val rates: StateFlow<Rates> get() = engine.rates

    override fun start(scope: CoroutineScope) {
        moduleScope = scope
        engine.start(scope)
        scheduler.start(
            scope,
            fastIntervalS = engine.rates.map { it.fastS },
            slowIntervalS = graph.settings.settings.map { it.telemetry.slowS },
        )
        watchStaticChanges(scope)
        registerNetworkCallback()
    }

    override fun stop() {
        unregisterNetworkCallback()
        engine.stop()
        moduleScope = null
    }

    override fun sendLive(msg: AgentToServer): Boolean = engine.sendLive(msg)

    override fun sendProbe(): String? = engine.sendProbe()

    override fun kick() = engine.kick()

    override suspend fun publishTelemetry(tiers: Set<TelemetryTier>) = scheduler.publish(tiers)

    override fun onPaired() = engine.onPaired()

    override fun onUnpaired() = engine.onUnpaired()

    /** Static telemetry again when the capability map or the device profile changes (protocol 3.3). */
    private fun watchStaticChanges(scope: CoroutineScope) {
        scope.launch {
            merge(
                graph.commands.capabilityMap.drop(1).map { "capability map" },
                graph.profiles.profile.drop(1).map { "device profile" },
            ).collectLatest { what ->
                // Debounce bursts, and skip when a static message was just published explicitly
                // (capability_probe and `refresh` publish it themselves).
                delay(STATIC_DEBOUNCE_MS)
                val since = scheduler.msSinceLastAttempt(TelemetryTier.STATIC)
                if (since != null && since < STATIC_COALESCE_MS) return@collectLatest
                graph.log.i(TAG, "Sending static telemetry: $what changed")
                scheduler.publish(setOf(TelemetryTier.STATIC))
            }
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = graph.app.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                engine.onNetworkAvailable()
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            graph.log.w(TAG, "Could not watch network changes; reconnects rely on the backoff timer", e)
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        val cm = graph.app.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { cm.unregisterNetworkCallback(callback) }
    }

    private inner class GraphHost : SyncHost {
        override fun credentials(): SyncCredentials? {
            val info = graph.pairingState.info.value ?: return null
            val token = graph.pairingState.token()
            if (token.isNullOrEmpty()) {
                graph.log.w(TAG, "Paired, but the device token cannot be read (keystore reset?); pair again")
                return null
            }
            return SyncCredentials(wsUrl = info.wsUrl, token = token, deviceId = info.deviceId)
        }

        override fun onUnauthorized(rejected: SyncCredentials) {
            val info = graph.pairingState.info.value ?: return
            if (info.deviceId == rejected.deviceId && graph.pairingState.token() == rejected.token) {
                graph.pairingState.clear()
            }
        }

        override fun onLinkUp() {
            try {
                graph.commands.onConnected()
            } catch (e: Exception) {
                graph.log.e(TAG, "commands.onConnected failed", e)
            }
            (moduleScope ?: graph.appScope).launch {
                scheduler.publish(setOf(TelemetryTier.STATIC, TelemetryTier.SLOW, TelemetryTier.FAST))
            }
        }

        override fun onMirror(on: Boolean) = graph.finder.setMirror(on)

        override suspend fun onCommand(cmd: CmdMsg) = graph.commands.handle(cmd)

        override fun onKeep(id: String) = graph.commands.onKeep(id)

        override fun onProbeAck(id: String) = graph.commands.onProbeAck(id)

        override suspend fun heartbeat(): HeartbeatMsg {
            val pending = try {
                graph.outbox.count()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                graph.outbox.pendingCount.value
            }
            return HeartbeatMsg(
                ts = graph.clock.isoNow(),
                // elapsedRealtime: time since the phone booted, including deep sleep.
                uptimeS = graph.clock.elapsedMs() / 1000,
                agentUptimeS = graph.agentUptimeS(),
                outbox = pending,
                settingsVersion = graph.settings.version.value,
            )
        }

        override fun finderLive(): FinderLive? = graph.finder.live.value
    }

    private inner class GraphCollector : TierCollector {
        override suspend fun collect(tier: TelemetryTier): TelemetryMsg? = when (tier) {
            TelemetryTier.FAST -> {
                val g = graph.telemetry.collectFast()
                if (g.radio == null && g.net == null) {
                    null
                } else {
                    TelemetryMsg(ts = graph.clock.isoNow(), tier = tier, radio = g.radio, net = g.net)
                }
            }
            TelemetryTier.SLOW -> {
                val g = graph.telemetry.collectSlow()
                TelemetryMsg(
                    ts = graph.clock.isoNow(),
                    tier = tier,
                    power = g.power,
                    heat = g.heat,
                    system = g.system,
                    tether = g.tether,
                    traffic = g.traffic,
                    position = g.position,
                )
            }
            TelemetryTier.STATIC -> {
                val g = graph.telemetry.collectStatic()
                TelemetryMsg(ts = graph.clock.isoNow(), tier = tier, device = g.device, capabilities = g.capabilities)
            }
        }
    }

    private companion object {
        const val TAG = "sync"
        const val PING_INTERVAL_S = 20L
        const val CONNECT_TIMEOUT_S = 20L
        const val UPGRADE_READ_TIMEOUT_S = 75L
        const val STATIC_DEBOUNCE_MS = 2_000L
        const val STATIC_COALESCE_MS = 5_000L
    }
}
