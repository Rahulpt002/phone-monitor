package com.hotspotmonitor.agent.sync

import com.hotspotmonitor.agent.core.Clock
import com.hotspotmonitor.agent.protocol.AgentEvent
import com.hotspotmonitor.agent.protocol.CmdMsg
import com.hotspotmonitor.agent.protocol.EventKind
import com.hotspotmonitor.agent.protocol.EventMsg
import com.hotspotmonitor.agent.protocol.FinderLive
import com.hotspotmonitor.agent.protocol.HeartbeatMsg
import com.hotspotmonitor.agent.protocol.HelloMsg
import com.hotspotmonitor.agent.protocol.Rates
import com.hotspotmonitor.agent.protocol.Settings
import com.hotspotmonitor.agent.protocol.Severity
import com.hotspotmonitor.agent.protocol.Wire
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler

/** Clock driven by the test scheduler's virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class VirtualClock(
    private val scheduler: TestCoroutineScheduler,
    private val wallStartMs: Long = 1_763_547_300_000L,
) : Clock() {
    override fun systemNowMs(): Long = wallStartMs + scheduler.currentTime
    override fun elapsedMs(): Long = scheduler.currentTime
}

internal class FakeConnection(val url: String, val token: String, val listener: WsListener) : WsConnection {
    val sent = mutableListOf<String>()
    var open = true
        private set
    var closedWith: Int? = null
        private set
    var cancelled = false
        private set

    override fun send(text: String): Boolean {
        if (!open) return false
        sent += text
        return true
    }

    override fun close(code: Int, reason: String?): Boolean {
        if (!open) return false
        open = false
        closedWith = code
        return true
    }

    override fun cancel() {
        open = false
        cancelled = true
    }

    fun sentOfType(t: String): List<String> = sent.filter { Wire.typeOf(it) == t }

    /** The peer accepted the upgrade and sent `hello`. */
    fun acceptWithHello(hello: String = helloFrame()) {
        listener.onOpen()
        listener.onMessage(hello)
    }
}

internal class FakeTransport : WsTransport {
    val connections = mutableListOf<FakeConnection>()
    val last: FakeConnection get() = connections.last()

    override fun connect(url: String, token: String, listener: WsListener): WsConnection =
        FakeConnection(url, token, listener).also { connections += it }
}

internal class FakeHost : SyncHost {
    var creds: SyncCredentials? = SyncCredentials("wss://hsm.example.test/ws/agent", "tok_secret", "d_1")
    val unauthorized = mutableListOf<SyncCredentials>()
    var linkUps = 0
    val mirror = mutableListOf<Boolean>()
    val commands = mutableListOf<CmdMsg>()
    val keeps = mutableListOf<String>()
    val probeAcks = mutableListOf<String>()
    var live: FinderLive? = null

    override fun credentials(): SyncCredentials? = creds

    override fun onUnauthorized(rejected: SyncCredentials) {
        unauthorized += rejected
        creds = null
    }

    override fun onLinkUp() {
        linkUps++
    }

    override fun onMirror(on: Boolean) {
        mirror += on
    }

    override suspend fun onCommand(cmd: CmdMsg) {
        commands += cmd
    }

    override fun onKeep(id: String) {
        keeps += id
    }

    override fun onProbeAck(id: String) {
        probeAcks += id
    }

    override suspend fun heartbeat(): HeartbeatMsg =
        HeartbeatMsg(ts = TS, uptimeS = 100, agentUptimeS = 10, outbox = 0, settingsVersion = 0)

    override fun finderLive(): FinderLive? = live
}

internal const val TS = "2026-11-19T10:15:00.000Z"

internal fun helloFrame(
    serverTime: String = TS,
    settings: Settings = Settings.DEFAULT,
    version: Int = 3,
    rates: Rates = Rates(fastS = 60, mirror = false),
): String = Wire.encodeServer(
    HelloMsg(serverTime = serverTime, deviceId = "d_1", settings = settings, settingsVersion = version, rates = rates),
)

internal fun event(n: Int, message: String = "event $n"): EventMsg =
    EventMsg(event = AgentEvent("e_$n", TS, EventKind.CONNECTIVITY, Severity.INFO, message))
