package com.hotspotmonitor.dashboard.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

enum class LinkStatus { IDLE, CONNECTING, AUTHENTICATING, OPEN, WAITING }

data class ConnState(
    val status: LinkStatus = LinkStatus.IDLE,
    val attempt: Int = 0,
    val nextRetryAtMs: Long? = null,
    val downSinceMs: Long? = null,
)

/**
 * Live connection to /ws/dashboard (docs/protocol.md section 5): auth, subscribe, ping every 25 s,
 * reconnect with backoff. Close 4001 signs out, 4004 re-authenticates (role changed), anything else
 * reconnects.
 */
class DashboardSocket(
    http: OkHttpClient,
    private val session: SessionStore,
    private val scope: CoroutineScope,
    private val onMessage: (JsonObject) -> Unit,
    private val onAuthFailed: (enrollment: Boolean) -> Unit,
    private val onRoleChanged: () -> Unit,
    private val onOpen: (reconnect: Boolean) -> Unit,
) {
    private val client = http.newBuilder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val _state = MutableStateFlow(ConnState())
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private var ws: WebSocket? = null
    private var stopped = true
    private var hadSession = false
    private var retryJob: Job? = null
    private var pingJob: Job? = null
    private var lastMessageMs = 0L
    private var subscription: Pair<String?, Boolean> = null to false

    fun start() {
        if (session.current.token == null || session.current.totpEnrollmentRequired) return
        if (!stopped && ws != null) return
        stopped = false
        connect()
    }

    fun stop() {
        stopped = true
        retryJob?.cancel()
        pingJob?.cancel()
        ws?.close(1000, "bye")
        ws = null
        hadSession = false
        _state.value = ConnState()
    }

    /** Reconnect now if waiting (app resumed, network back). */
    fun retryNow() {
        if (stopped) return
        if (_state.value.status == LinkStatus.WAITING || ws == null) {
            retryJob?.cancel()
            connect()
        }
    }

    fun subscribe(deviceId: String?, finder: Boolean) {
        subscription = deviceId to finder
        sendSubscribe()
    }

    private fun sendSubscribe() {
        if (_state.value.status != LinkStatus.OPEN) return
        val (id, finder) = subscription
        ws?.send(buildJsonObject {
            put("t", "subscribe")
            if (id != null) put("device_id", id) else put("device_id", kotlinx.serialization.json.JsonNull)
            put("finder", finder)
        }.toString())
    }

    private fun wsUrl(): String {
        val base = session.current.serverUrl.trimEnd('/')
        return base.replaceFirst(Regex("^http", RegexOption.IGNORE_CASE), "ws") + "/ws/dashboard"
    }

    private fun connect() {
        val token = session.current.token ?: return
        ws?.cancel()
        _state.update { it.copy(status = LinkStatus.CONNECTING) }
        val req = Request.Builder().url(wsUrl()).build()
        lateinit var self: WebSocket
        self = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (ws !== webSocket) return
                _state.update { it.copy(status = LinkStatus.AUTHENTICATING) }
                webSocket.send(buildJsonObject { put("t", "auth"); put("token", token) }.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (ws !== webSocket) return
                lastMessageMs = System.currentTimeMillis()
                val msg = runCatching { AppJson.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return
                when ((msg["t"] as? JsonPrimitive)?.contentOrNull) {
                    "auth_ok" -> authOk()
                    "auth_failed" -> {
                        val m = (msg["message"] as? JsonPrimitive)?.contentOrNull
                        stopped = true
                        webSocket.close(1000, null)
                        onAuthFailed(m == "totp_enrollment_required")
                    }
                    "pong" -> Unit
                    else -> onMessage(msg)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                closed(webSocket, code)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closed(webSocket, code)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = closed(webSocket, -1)
        })
        ws = self
    }

    private fun authOk() {
        val reconnect = hadSession
        hadSession = true
        _state.value = ConnState(status = LinkStatus.OPEN)
        sendSubscribe()
        onOpen(reconnect)
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                delay(25_000)
                if (System.currentTimeMillis() - lastMessageMs > 70_000) {
                    ws?.cancel()
                    break
                }
                ws?.send("{\"t\":\"ping\"}")
            }
        }
    }

    private fun closed(webSocket: WebSocket, code: Int) {
        if (ws !== webSocket) return
        ws = null
        pingJob?.cancel()
        when (code) {
            4001 -> {
                stopped = true
                _state.value = ConnState()
                onAuthFailed(false)
                return
            }
            4004 -> onRoleChanged()
        }
        if (stopped) return
        scheduleReconnect(if (code == 4004) 0 else null)
    }

    private fun scheduleReconnect(fixedDelay: Long?) {
        val attempt = _state.value.attempt + 1
        val base = min(30_000.0, 1000.0 * (1 shl min(attempt - 1, 5)))
        val delayMs = fixedDelay ?: (base * (0.8 + Random.nextDouble() * 0.4)).toLong()
        val now = System.currentTimeMillis()
        _state.update { it.copy(status = LinkStatus.WAITING, attempt = attempt, nextRetryAtMs = now + delayMs, downSinceMs = it.downSinceMs ?: now) }
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMs)
            if (!stopped) connect()
        }
    }
}
