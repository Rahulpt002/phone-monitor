package com.hotspotmonitor.agent.sync

import com.hotspotmonitor.agent.core.AgentLog
import com.hotspotmonitor.agent.core.Clock
import com.hotspotmonitor.agent.core.ConnPhase
import com.hotspotmonitor.agent.core.ConnState
import com.hotspotmonitor.agent.core.Ids
import com.hotspotmonitor.agent.core.OutboxStore
import com.hotspotmonitor.agent.core.SettingsRepo
import com.hotspotmonitor.agent.protocol.ScreenStartMsg
import com.hotspotmonitor.agent.protocol.ScreenStopMsg
import com.hotspotmonitor.agent.protocol.AckMsg
import com.hotspotmonitor.agent.protocol.AgentToServer
import com.hotspotmonitor.agent.protocol.BatchMsg
import com.hotspotmonitor.agent.protocol.BufferedMsg
import com.hotspotmonitor.agent.protocol.CloseCodes
import com.hotspotmonitor.agent.protocol.CmdKeepMsg
import com.hotspotmonitor.agent.protocol.CmdMsg
import com.hotspotmonitor.agent.protocol.ErrorCodes
import com.hotspotmonitor.agent.protocol.ErrorMsg
import com.hotspotmonitor.agent.protocol.FinderLive
import com.hotspotmonitor.agent.protocol.FinderLiveMsg
import com.hotspotmonitor.agent.protocol.HeartbeatMsg
import com.hotspotmonitor.agent.protocol.HelloMsg
import com.hotspotmonitor.agent.protocol.ProbeAckMsg
import com.hotspotmonitor.agent.protocol.ProbeMsg
import com.hotspotmonitor.agent.protocol.ProtocolLimits
import com.hotspotmonitor.agent.protocol.Rates
import com.hotspotmonitor.agent.protocol.RatesMsg
import com.hotspotmonitor.agent.protocol.SettingsMsg
import com.hotspotmonitor.agent.protocol.Wire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/** What the engine needs from the rest of the Agent (AgentGraph in production, a fake in tests). */
internal interface SyncHost {
    /** Current pairing, or null when not paired. */
    fun credentials(): SyncCredentials?

    /**
     * The Server rejected [rejected] with 4001: forget that pairing (only if it is still the
     * current one; the user may have re-paired meanwhile).
     */
    fun onUnauthorized(rejected: SyncCredentials)

    /** After `hello`: tell the executor, then publish static + slow + fast telemetry. */
    fun onLinkUp()

    /** rates.mirror changed (graph.finder.setMirror). */
    fun onMirror(on: Boolean)

    /** A `cmd` (graph.commands.handle); runs in the module scope, not tied to the socket. */
    suspend fun onCommand(cmd: CmdMsg)
    fun onKeep(id: String)
    fun onProbeAck(id: String)
    suspend fun heartbeat(): HeartbeatMsg

    /** Latest Finder live values for the mirror fallback, or null. */
    fun finderLive(): FinderLive?

    /** Remote screen start and stop (graph.screen). */
    fun onScreenStart(msg: ScreenStartMsg) {}
    fun onScreenStop(msg: ScreenStopMsg) {}
}

internal class SyncCredentials(val wsUrl: String, val token: String, val deviceId: String) {
    // The token must never reach a log line.
    override fun toString(): String = "SyncCredentials(wsUrl=$wsUrl, deviceId=$deviceId)"
}

internal data class SyncConfig(
    val backoffBaseMs: Long = 2_000,
    val backoffMaxMs: Long = 60_000,
    val jitter: Double = 0.2,
    /** A link that stayed up this long resets the backoff (docs/protocol.md 3.1). */
    val stableMs: Long = 60_000,
    /** Wait after 4002 (another connection with this token replaced ours). */
    val replacedBackoffMs: Long = 30_000,
    /**
     * After the socket opened (upgrade accepted). The upgrade itself has its own, longer limit
     * ([upgradeTimeoutMs]): a sleeping free-tier Server answers it only after it has booted.
     */
    val helloTimeoutMs: Long = 30_000,
    /** Backstop for DNS + TCP + TLS + upgrade; OkHttp's connect (20 s) and read (75 s) timeouts apply first. */
    val upgradeTimeoutMs: Long = 100_000,
    /** No `ack` within this time means the link is unhealthy: reconnect and re-send. */
    val ackTimeoutMs: Long = 30_000,
    /** The idle sender re-checks the outbox at least this often (kicks normally wake it). */
    val idlePollMs: Long = 5_000,
    /** Pause after a network-available callback before reconnecting (DNS, routes). */
    val networkSettleMs: Long = 1_000,
    val maxFrameBytes: Int = ProtocolLimits.MAX_MESSAGE_BYTES,
    val maxBatchItems: Int = ProtocolLimits.MAX_BATCH_ITEMS,
)

/**
 * The Agent side of WS /ws/agent (docs/protocol.md section 3):
 *
 * - one connection loop: connect with the device token, reconnect with backoff 2..60 s + jitter
 *   (reset after 60 s up, skipped on a network-available callback), 4001 -> unpaired and stopped,
 *   4002 -> wait 30 s;
 * - per connection: `hello` (clock offset, settings, rates), then the single outbox sender (seq
 *   order, one row or a `batch`, wait for `ack`, timeout -> reconnect), the heartbeat, and the
 *   finder_live mirror fallback;
 * - dispatch of Server messages; unknown or malformed frames are ignored.
 *
 * Socket callbacks are funnelled into one channel per connection and handled by one coroutine, so
 * connection state needs no locks. [sendLive] may be called from any thread.
 */
internal class SyncEngine(
    private val clock: Clock,
    private val log: AgentLog,
    private val outbox: OutboxStore,
    private val settings: SettingsRepo,
    private val transport: WsTransport,
    private val host: SyncHost,
    private val config: SyncConfig = SyncConfig(),
    random: Random = Random.Default,
) {
    private val backoff = Backoff(config.backoffBaseMs, config.backoffMaxMs, config.jitter, random)

    private val _state = MutableStateFlow(ConnState(ConnPhase.STOPPED, null, 0))
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private val _rates = MutableStateFlow(offlineRates())
    val rates: StateFlow<Rates> = _rates.asStateFlow()

    private val kicks = Channel<Unit>(Channel.CONFLATED)
    private val pairingGen = MutableStateFlow(0L)
    private val networkGen = MutableStateFlow(0L)

    @Volatile private var stopped = true
    @Volatile private var moduleScope: CoroutineScope? = null
    @Volatile private var loopJob: Job? = null

    /** The socket being opened or in use. */
    @Volatile private var current: Session? = null

    /** The socket after `hello`; live messages go here. */
    @Volatile private var live: Session? = null

    /** Elapsed ms of the last finder_live sent by the Finder itself through [sendLive]; -1 = never. */
    @Volatile private var lastExternalLiveMs = -1L

    // --- lifecycle --------------------------------------------------------------------------------

    fun start(scope: CoroutineScope) {
        if (loopJob?.isActive == true) return
        stopped = false
        moduleScope = scope
        backoff.reset()
        _rates.value = offlineRates()
        scope.launch { outbox.lastEnqueuedSeq.collect { kicks.trySend(Unit) } }
        loopJob = scope.launch { connectionLoop() }
    }

    fun stop() {
        stopped = true
        loopJob?.cancel()
        loopJob = null
        current?.let { runCatching { it.conn.close(CloseCodes.NORMAL, "agent stopped") } }
        current = null
        if (live != null) {
            live = null
            linkDown()
        }
        _state.value = ConnState(ConnPhase.STOPPED, null, clock.systemNowMs())
    }

    // --- public operations --------------------------------------------------------------------------

    fun sendLive(msg: AgentToServer): Boolean {
        if (msg is BufferedMsg || msg is BatchMsg) {
            log.w(TAG, "sendLive is for heartbeat, probe, finder_live and screen_state; ${msg.javaClass.simpleName} must go through the outbox")
            return false
        }
        val session = live ?: return false
        val sent = sendFrame(session, Wire.encode(msg))
        if (sent && msg is FinderLiveMsg) lastExternalLiveMs = clock.elapsedMs()
        return sent
    }

    fun sendProbe(): String? {
        val id = Ids.probe()
        return if (sendLive(ProbeMsg(id))) id else null
    }

    fun kick() {
        kicks.trySend(Unit)
    }

    /** New credentials: drop the current socket (if any) and connect with them now. */
    fun onPaired() {
        pairingGen.update { it + 1 }
    }

    /** Pairing removed: close the socket; the loop waits until paired again. */
    fun onUnpaired() {
        pairingGen.update { it + 1 }
    }

    /** Android reported a usable network: a waiting reconnect happens now. */
    fun onNetworkAvailable() {
        networkGen.update { it + 1 }
    }

    // --- connection loop ----------------------------------------------------------------------------

    private suspend fun connectionLoop() {
        while (true) {
            try {
                connectOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.e(TAG, "Sync loop error", e)
                setPhase(ConnPhase.WAITING, "Internal error: ${e.javaClass.simpleName}")
                delay(backoff.nextDelayMs())
            }
        }
    }

    private suspend fun connectOnce() {
        // Generations are read before the credentials so an onPaired() racing with this read is seen.
        val pairGen = pairingGen.value
        val netGen = networkGen.value
        val creds = host.credentials()
        if (creds == null) {
            if (_state.value.phase != ConnPhase.UNAUTHORIZED) setPhase(ConnPhase.NOT_PAIRED, null)
            pairingGen.first { it != pairGen }
            backoff.reset()
            return
        }
        setPhase(ConnPhase.CONNECTING, _state.value.lastError)
        when (val outcome = runSession(creds, pairGen)) {
            Outcome.Unauthorized -> {
                log.w(TAG, "The Server rejected the device token (4001): pairing removed, not reconnecting until paired again")
                safely("onUnauthorized") { host.onUnauthorized(creds) }
                if (pairingGen.value != pairGen) {
                    backoff.reset()
                    return
                }
                setPhase(ConnPhase.UNAUTHORIZED, "The Server rejected the device token (4001). Pair again.")
                pairingGen.first { it != pairGen }
                backoff.reset()
            }
            Outcome.PairingChanged -> {
                log.i(TAG, "Pairing changed: reconnecting")
                backoff.reset()
            }
            is Outcome.Replaced -> {
                log.w(TAG, "${outcome.message}; waiting ${config.replacedBackoffMs / 1000} s")
                waitBeforeRetry(config.replacedBackoffMs, outcome.message, netGen, pairGen, wakeOnNetwork = false)
            }
            is Outcome.Closed -> {
                if (outcome.connectedMs >= config.stableMs) backoff.reset()
                val delayMs = backoff.nextDelayMs()
                log.i(TAG, "${outcome.message}; reconnecting in ${formatSeconds(delayMs)}")
                waitBeforeRetry(delayMs, outcome.message, netGen, pairGen, wakeOnNetwork = true)
            }
        }
    }

    private suspend fun waitBeforeRetry(delayMs: Long, reason: String, netGen: Long, pairGen: Long, wakeOnNetwork: Boolean) {
        setPhase(ConnPhase.WAITING, reason)
        val wake = withTimeoutOrNull(delayMs) {
            combine(networkGen, pairingGen) { net, pair ->
                when {
                    pair != pairGen -> Wake.PAIRING
                    wakeOnNetwork && net != netGen -> Wake.NETWORK
                    else -> null
                }
            }.filterNotNull().first()
        }
        when (wake) {
            Wake.NETWORK -> {
                log.i(TAG, "Network available: reconnecting now")
                backoff.reset()
                delay(config.networkSettleMs)
            }
            Wake.PAIRING -> backoff.reset()
            null -> Unit
        }
    }

    private enum class Wake { NETWORK, PAIRING }

    // --- one connection -----------------------------------------------------------------------------

    private sealed interface SocketEvent {
        data object Open : SocketEvent
        class Text(val text: String, val receivedAtMs: Long) : SocketEvent
        class Closing(val code: Int, val reason: String) : SocketEvent
        class Closed(val code: Int, val reason: String) : SocketEvent
        class Failure(val error: Throwable, val httpStatus: Int?) : SocketEvent
        class LocalFailure(val reason: String) : SocketEvent
        data object PairingChanged : SocketEvent
    }

    private sealed interface Outcome {
        data object Unauthorized : Outcome
        data object PairingChanged : Outcome
        class Replaced(val message: String) : Outcome
        class Closed(val message: String, val connectedMs: Long) : Outcome
    }

    private class Session(val conn: WsConnection, val events: Channel<SocketEvent>, val deviceId: String) {
        /** Elapsed ms when `hello` arrived; -1 before. */
        @Volatile var helloAtMs: Long = -1

        /** The upgrade was accepted (onOpen). */
        @Volatile var opened: Boolean = false
        var serverError: String? = null
        val acked = MutableStateFlow(0L)
        @Volatile var maxSentSeq: Long = 0

        val helloReceived: Boolean get() = helloAtMs >= 0
        fun connectedMs(nowMs: Long): Long = if (helloAtMs < 0) 0 else nowMs - helloAtMs
    }

    private suspend fun runSession(creds: SyncCredentials, pairGen: Long): Outcome {
        val events = Channel<SocketEvent>(Channel.UNLIMITED)
        val listener = object : WsListener {
            override fun onOpen() {
                events.trySend(SocketEvent.Open)
            }

            override fun onMessage(text: String) {
                events.trySend(SocketEvent.Text(text, clock.systemNowMs()))
            }

            override fun onClosing(code: Int, reason: String) {
                events.trySend(SocketEvent.Closing(code, reason))
            }

            override fun onClosed(code: Int, reason: String) {
                events.trySend(SocketEvent.Closed(code, reason))
            }

            override fun onFailure(error: Throwable, httpStatus: Int?) {
                events.trySend(SocketEvent.Failure(error, httpStatus))
            }
        }
        log.i(TAG, "Connecting to ${creds.wsUrl}")
        val conn = try {
            transport.connect(creds.wsUrl, creds.token, listener)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            events.close()
            return Outcome.Closed("Cannot open ${creds.wsUrl}: ${e.message ?: e.javaClass.simpleName}", 0)
        }
        val session = Session(conn, events, creds.deviceId)
        current = session
        val parent = currentCoroutineContext()
        val sessionJob = SupervisorJob(parent[Job])
        val sessionScope = CoroutineScope(parent + sessionJob)
        try {
            sessionScope.launch {
                pairingGen.first { it != pairGen }
                events.trySend(SocketEvent.PairingChanged)
            }
            sessionScope.launch {
                delay(config.upgradeTimeoutMs)
                if (!session.opened) {
                    events.trySend(SocketEvent.LocalFailure("The Server did not accept the WebSocket within ${formatSeconds(config.upgradeTimeoutMs)}"))
                }
            }
            for (event in events) {
                val outcome = handleEvent(session, sessionScope, event)
                if (outcome != null) return outcome
            }
            return Outcome.Closed("Socket closed", session.connectedMs(clock.elapsedMs()))
        } finally {
            sessionJob.cancel()
            events.close()
            if (current === session) current = null
            if (live === session) {
                live = null
                linkDown()
            }
            // Completes the close handshake after the peer's close; a no-op after cancel/failure.
            runCatching { conn.close(CloseCodes.NORMAL, null) }
        }
    }

    private fun handleEvent(session: Session, scope: CoroutineScope, event: SocketEvent): Outcome? = when (event) {
        SocketEvent.Open -> {
            log.i(TAG, "Socket open; waiting for hello")
            if (!session.opened) {
                session.opened = true
                // Counted from the accepted upgrade, so a Server still booting is not cut off.
                scope.launch {
                    delay(config.helloTimeoutMs)
                    if (!session.helloReceived) {
                        session.events.trySend(SocketEvent.LocalFailure("No hello from the Server within ${formatSeconds(config.helloTimeoutMs)}"))
                    }
                }
            }
            null
        }
        is SocketEvent.Text -> {
            handleText(session, scope, event.text, event.receivedAtMs)
            null
        }
        is SocketEvent.Closing -> closeOutcome(session, event.code, event.reason)
        is SocketEvent.Closed -> closeOutcome(session, event.code, event.reason)
        is SocketEvent.Failure -> failureOutcome(session, event)
        is SocketEvent.LocalFailure -> {
            log.w(TAG, event.reason)
            session.conn.cancel()
            Outcome.Closed(event.reason, session.connectedMs(clock.elapsedMs()))
        }
        SocketEvent.PairingChanged -> {
            runCatching { session.conn.close(CloseCodes.NORMAL, "pairing changed") }
            Outcome.PairingChanged
        }
    }

    private fun closeOutcome(session: Session, code: Int, reason: String): Outcome = when {
        code == CloseCodes.UNAUTHORIZED || session.serverError == ErrorCodes.UNAUTHORIZED -> Outcome.Unauthorized
        code == CloseCodes.REPLACED || session.serverError == ErrorCodes.REPLACED ->
            Outcome.Replaced("Replaced by another connection with this device's token (4002)")
        code == CloseCodes.PROTOCOL_ERROR -> Outcome.Closed("The Server closed the link: protocol error (4003)", session.connectedMs(clock.elapsedMs()))
        else -> Outcome.Closed(
            "The Server closed the link ($code${if (reason.isNotBlank()) " $reason" else ""})",
            session.connectedMs(clock.elapsedMs()),
        )
    }

    private fun failureOutcome(session: Session, failure: SocketEvent.Failure): Outcome {
        // The Server sends error{unauthorized|replaced} before closing; a dropped close frame must
        // not hide it.
        if (session.serverError == ErrorCodes.UNAUTHORIZED) return Outcome.Unauthorized
        if (session.serverError == ErrorCodes.REPLACED) return Outcome.Replaced("Replaced by another connection with this device's token (4002)")
        val what = when {
            failure.httpStatus != null -> "The Server refused the WebSocket upgrade (HTTP ${failure.httpStatus})"
            else -> "Connection failed: " + (failure.error.message?.takeIf { it.isNotBlank() } ?: failure.error.javaClass.simpleName)
        }
        return Outcome.Closed(what.take(MAX_ERROR_CHARS), session.connectedMs(clock.elapsedMs()))
    }

    // --- Server messages ----------------------------------------------------------------------------

    private fun handleText(session: Session, scope: CoroutineScope, text: String, receivedAtMs: Long) {
        val msg = Wire.decodeServerMessage(text)
        if (msg == null) {
            log.d(TAG, "Ignoring Server frame of type ${Wire.typeOf(text) ?: "<not JSON>"}")
            return
        }
        when (msg) {
            is HelloMsg -> onHello(session, scope, msg, receivedAtMs)
            is SettingsMsg -> safely("settings") { settings.apply(msg.settings, msg.version) }
            is RatesMsg -> applyRates(msg.toRates(), notifyMirrorAlways = false)
            is CmdMsg -> {
                // In the module scope: a command (e.g. airplane_cycle) must finish even when it
                // cuts this very socket.
                val target = moduleScope ?: scope
                target.launch { safelySuspend("cmd ${msg.name}") { host.onCommand(msg) } }
            }
            is CmdKeepMsg -> safely("cmd_keep") { host.onKeep(msg.id) }
            // Clamped to what was sent on this socket, so a bogus or early ack can never
            // acknowledge rows the Server has not seen.
            is AckMsg -> session.acked.update { maxOf(it, minOf(msg.seq, session.maxSentSeq)) }
            is ProbeAckMsg -> {
                clock.syncWithServer(msg.serverTime, receivedAtMs)
                safely("probe_ack") { host.onProbeAck(msg.id) }
            }
            is ScreenStartMsg -> safely("screen_start") { host.onScreenStart(msg) }
            is ScreenStopMsg -> safely("screen_stop") { host.onScreenStop(msg) }
            is ErrorMsg -> {
                session.serverError = msg.code
                log.w(TAG, "Server error '${msg.code}': ${msg.message.take(MAX_ERROR_CHARS)}")
            }
            else -> log.d(TAG, "Ignoring Server message ${msg.javaClass.simpleName}")
        }
    }

    private fun onHello(session: Session, scope: CoroutineScope, hello: HelloMsg, receivedAtMs: Long) {
        if (clock.syncWithServer(hello.serverTime, receivedAtMs)) {
            log.i(TAG, "Clock offset to the Server: ${clock.serverOffsetMs} ms")
        } else {
            log.w(TAG, "hello.server_time '${hello.serverTime.take(40)}' is not a timestamp; clock offset unchanged")
        }
        if (hello.deviceId != session.deviceId) {
            log.w(TAG, "Server calls this device ${hello.deviceId}, pairing says ${session.deviceId}")
        }
        safely("settings") { settings.apply(hello.settings, hello.settingsVersion) }
        val first = !session.helloReceived
        if (first) session.helloAtMs = clock.elapsedMs()
        applyRates(hello.rates, notifyMirrorAlways = true)
        if (!first) return

        live = session
        setPhase(ConnPhase.CONNECTED, null)
        log.i(TAG, "Link up as ${hello.deviceId} (settings v${settings.version.value})")
        safely("onLinkUp") { host.onLinkUp() }
        scope.launch { runSender(session) }
        scope.launch { runHeartbeat(session) }
        scope.launch { runMirrorFallback(session) }
    }

    private fun applyRates(incoming: Rates, notifyMirrorAlways: Boolean) {
        val next = incoming.copy(fastS = incoming.fastS.coerceIn(MIN_FAST_S, MAX_FAST_S))
        val before = _rates.value
        _rates.value = next
        if (before != next) log.i(TAG, "Rates: fast every ${next.fastS} s, mirror ${if (next.mirror) "on" else "off"}")
        if (notifyMirrorAlways || before.mirror != next.mirror) safely("setMirror") { host.onMirror(next.mirror) }
    }

    private fun linkDown() {
        val wasMirroring = _rates.value.mirror
        _rates.value = offlineRates()
        if (wasMirroring) safely("setMirror") { host.onMirror(false) }
    }

    private fun offlineRates(): Rates =
        Rates(fastS = settings.current.telemetry.fastIdleS.coerceIn(MIN_FAST_S, MAX_FAST_S), mirror = false)

    // --- per-connection workers ---------------------------------------------------------------------

    /** The single outbox sender (docs/protocol.md 3.2). */
    private suspend fun runSender(session: Session) {
        while (true) {
            val rows = try {
                outbox.peekBatch(config.maxBatchItems)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.e(TAG, "Could not read the outbox", e)
                delay(config.idlePollMs)
                continue
            }
            when (val plan = FramePlanner.plan(rows, config.maxFrameBytes, config.maxBatchItems)) {
                null -> withTimeoutOrNull(config.idlePollMs) { kicks.receive() }
                is FramePlan.DropOversized -> {
                    log.e(TAG, "Dropping outbox row ${plan.row.seq} (${plan.row.type}): ${plan.bytes} bytes is over the ${config.maxFrameBytes}-byte frame limit")
                    ackLocally(plan.row.seq)
                }
                is FramePlan.Send -> {
                    // Recorded before sending: the ack can arrive before send() returns.
                    session.maxSentSeq = maxOf(session.maxSentSeq, plan.lastSeq)
                    if (!sendFrame(session, plan.frame)) {
                        session.events.trySend(SocketEvent.LocalFailure("Could not send outbox rows up to seq ${plan.lastSeq}"))
                        return
                    }
                    val acked = withTimeoutOrNull(config.ackTimeoutMs) { session.acked.first { it >= plan.lastSeq } }
                    if (acked == null) {
                        session.events.trySend(
                            SocketEvent.LocalFailure("No ack for seq ${plan.lastSeq} within ${formatSeconds(config.ackTimeoutMs)}"),
                        )
                        return
                    }
                    ackLocally(acked)
                }
            }
        }
    }

    private suspend fun ackLocally(seq: Long) {
        try {
            outbox.ackUpTo(seq)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The rows stay and are sent again later; the Server ignores duplicates.
            log.e(TAG, "Could not delete acknowledged outbox rows up to $seq", e)
        }
    }

    private suspend fun runHeartbeat(session: Session) {
        while (true) {
            try {
                sendFrame(session, Wire.encode(host.heartbeat()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(TAG, "Heartbeat failed", e)
            }
            delay(settings.current.telemetry.heartbeatS.coerceIn(MIN_HEARTBEAT_S, MAX_HEARTBEAT_S) * 1000L)
        }
    }

    /**
     * The Finder streams finder_live itself through [sendLive] while the mirror is on (its
     * FinderEngine.setMirror contract). This loop only fills in when it has not done so for two
     * intervals, so the Dashboard's Finder screen never stays blank and is never fed twice.
     */
    private suspend fun runMirrorFallback(session: Session) {
        while (true) {
            _rates.first { it.mirror }
            val intervalMs = settings.current.telemetry.mirrorS.coerceIn(MIN_MIRROR_S, MAX_MIRROR_S) * 1000L
            delay(intervalMs)
            if (!_rates.value.mirror) continue
            val lastExternal = lastExternalLiveMs
            if (lastExternal >= 0 && clock.elapsedMs() - lastExternal <= 2 * intervalMs + MIRROR_GRACE_MS) continue
            val value = try {
                host.finderLive()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(TAG, "Could not read Finder live values", e)
                null
            }
            if (value != null) sendFrame(session, Wire.encode(FinderLiveMsg(value)))
        }
    }

    private fun sendFrame(session: Session, text: String): Boolean {
        val bytes = utf8Length(text)
        if (bytes > config.maxFrameBytes) {
            log.w(TAG, "Not sending a ${Wire.typeOf(text) ?: "?"} frame of $bytes bytes (limit ${config.maxFrameBytes})")
            return false
        }
        return try {
            session.conn.send(text)
        } catch (e: Exception) {
            log.w(TAG, "Socket send failed", e)
            false
        }
    }

    // --- helpers ------------------------------------------------------------------------------------

    private fun setPhase(phase: ConnPhase, error: String?) {
        if (stopped) return
        _state.update { cur ->
            if (cur.phase == phase) cur.copy(lastError = error) else ConnState(phase, error, clock.systemNowMs())
        }
    }

    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(TAG, "Error in $what", e)
        }
    }

    private suspend inline fun safelySuspend(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(TAG, "Error in $what", e)
        }
    }

    companion object {
        private const val TAG = "sync"
        private const val MIN_FAST_S = 1
        private const val MAX_FAST_S = 86_400
        private const val MIN_HEARTBEAT_S = 5
        private const val MAX_HEARTBEAT_S = 600
        private const val MIN_MIRROR_S = 1
        private const val MAX_MIRROR_S = 60
        private const val MIRROR_GRACE_MS = 500L
        private const val MAX_ERROR_CHARS = 200

        fun formatSeconds(ms: Long): String =
            if (ms % 1000 == 0L) "${ms / 1000} s" else String.format(java.util.Locale.US, "%.1f s", ms / 1000.0)
    }
}
