package com.hotspotmonitor.dashboard.ui.screen

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.hotspotmonitor.dashboard.data.AppJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Remote screen session phases, as the viewer sees them. */
enum class ScreenPhase { CONNECTING, WAITING, STARTING, STREAMING, RECONNECTING, ENDED }

data class ScreenState(
    val phase: ScreenPhase = ScreenPhase.CONNECTING,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val bytes: Long = 0,
    val reason: String? = null,
    val message: String? = null,
)

/** One reassembled video packet (shared/src/screen.ts "Binary video frames"). */
class VideoPacket(val config: Boolean, val key: Boolean, val ptsUs: Long, val data: ByteArray)

/** Joins START/CONT frames into whole packets; a frame out of sequence drops the packet. */
class FrameAssembler {
    private var buf: ByteArray? = null
    private var filled = 0
    private var config = false
    private var key = false
    private var pts = 0L

    fun push(frame: ByteArray): VideoPacket? {
        if (frame.isEmpty()) return null
        when (frame[0].toInt()) {
            1 -> {
                if (frame.size < 13) return reset()
                val bb = ByteBuffer.wrap(frame, 1, 12)
                val flags = bb.long
                val size = bb.int
                if (size < 0 || size > 16 shl 20) return reset()
                config = flags and (1L shl 62) != 0L
                key = flags and (1L shl 61) != 0L
                pts = flags and ((1L shl 61) - 1)
                buf = ByteArray(size)
                filled = 0
                return append(frame, 13)
            }
            2 -> return if (buf == null) null else append(frame, 1)
            else -> return null
        }
    }

    private fun append(frame: ByteArray, from: Int): VideoPacket? {
        val b = buf ?: return null
        val n = frame.size - from
        if (filled + n > b.size) return reset()
        System.arraycopy(frame, from, b, filled, n)
        filled += n
        if (filled < b.size) return null
        buf = null
        return VideoPacket(config, key, pts, b)
    }

    private fun reset(): VideoPacket? {
        buf = null
        filled = 0
        return null
    }
}

/**
 * Viewer of one remote-screen session: the /ws/screen/view socket and a hardware H.264 decoder
 * (MediaCodec) rendering into [surface]. Call [close] when leaving.
 */
class ScreenClient(
    http: OkHttpClient,
    private val serverUrl: String,
    private val token: String,
    private val sessionId: String,
    private val scope: CoroutineScope,
) {
    private val client = http.newBuilder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val _state = MutableStateFlow(ScreenState())
    val state: StateFlow<ScreenState> = _state.asStateFlow()

    private var ws: WebSocket? = null
    private val assembler = FrameAssembler()
    private var decoder: DecodeThread? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var closed = false
    @Volatile private var paused = false
    private var attempt = 0
    private var reconnectJob: Job? = null
    private var fpsJob: Job? = null
    @Volatile private var framesThisSecond = 0
    private var lastKeyRequestMs = 0L

    init {
        fpsJob = scope.launch {
            while (!closed) {
                delay(1000)
                val f = framesThisSecond
                framesThisSecond = 0
                if (_state.value.fps != f) _state.update { it.copy(fps = f) }
            }
        }
        connect()
    }

    // --- surface (from the SurfaceView) ---------------------------------------------------------

    fun attachSurface(s: Surface) {
        surface = s
        restartDecoder()
        requestKeyframe(force = true)
    }

    fun detachSurface() {
        surface = null
        decoder?.shutdown()
        decoder = null
    }

    private fun restartDecoder() {
        decoder?.shutdown()
        val s = surface ?: return
        decoder = DecodeThread(s).also { it.start() }
    }

    // --- control -----------------------------------------------------------------------------------

    fun send(msg: JsonObject) {
        ws?.send(msg.toString())
    }

    fun touch(action: String, pointer: Int, x: Float, y: Float) = send(buildJsonObject {
        put("t", "touch"); put("action", action); put("pointer", pointer)
        put("x", x.coerceIn(0f, 1f).toDouble()); put("y", y.coerceIn(0f, 1f).toDouble())
    })

    fun scroll(x: Float, y: Float, dx: Float, dy: Float) = send(buildJsonObject {
        put("t", "scroll"); put("x", x.toDouble()); put("y", y.toDouble())
        put("dx", dx.coerceIn(-16f, 16f).toDouble()); put("dy", dy.coerceIn(-16f, 16f).toDouble())
    })

    fun key(k: String) = send(buildJsonObject { put("t", "key"); put("key", k) })
    fun text(t: String) = send(buildJsonObject { put("t", "text"); put("text", t.take(300)) })
    fun panel(p: String) = send(buildJsonObject { put("t", "panel"); put("panel", p) })

    fun requestKeyframe(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastKeyRequestMs < 2000) return
        lastKeyRequestMs = now
        send(buildJsonObject { put("t", "keyframe") })
    }

    /** Ends the session on the Server, then closes. */
    fun stop() {
        send(buildJsonObject { put("t", "screen_stop") })
        finish("stopped", "Stopped.")
    }

    /** App in the background: drop the socket (no video nobody sees); the Server waits 30 s. */
    fun pause() {
        if (closed || paused) return
        paused = true
        reconnectJob?.cancel()
        ws?.close(1000, "paused")
        ws = null
    }

    fun resume() {
        if (closed || !paused) return
        paused = false
        attempt = 0
        _state.update { it.copy(phase = ScreenPhase.RECONNECTING) }
        connect()
    }

    fun close() {
        if (closed) return
        closed = true
        reconnectJob?.cancel()
        fpsJob?.cancel()
        ws?.close(1000, "viewer closed")
        ws = null
        decoder?.shutdown()
        decoder = null
    }

    // --- socket ------------------------------------------------------------------------------------

    private fun connect() {
        if (closed || paused) return
        val url = serverUrl.trimEnd('/').replaceFirst(Regex("^http", RegexOption.IGNORE_CASE), "ws") + "/ws/screen/view"
        lateinit var self: WebSocket
        self = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (ws !== webSocket) return
                webSocket.send(buildJsonObject { put("t", "screen_auth"); put("token", token); put("session_id", sessionId) }.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (ws !== webSocket) return
                onText(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (ws !== webSocket) return
                val frame = bytes.toByteArray()
                _state.update { it.copy(bytes = it.bytes + frame.size) }
                assembler.push(frame)?.let { p -> decoder?.offer(p) ?: requestKeyframe() }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                onClosed(webSocket, code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (ws !== webSocket) return
                ws = null
                socketGone(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (ws !== webSocket) return
                ws = null
                socketGone(-1, "")
            }
        })
        ws = self
    }

    private fun socketGone(code: Int, reason: String) {
        if (closed || paused) return
        if (code == 4005 || code == 4001 || code == 4002 || _state.value.phase == ScreenPhase.ENDED) {
            if (_state.value.phase != ScreenPhase.ENDED) {
                finish(null, if (code == 4002) "The screen was opened in another window." else reason.ifBlank { "This screen session has ended." })
            }
            return
        }
        val delays = longArrayOf(500, 1000, 2000, 4000, 8000, 8000)
        if (attempt >= delays.size) {
            finish(null, "Lost the connection to the server.")
            return
        }
        val d = delays[attempt++]
        _state.update { it.copy(phase = ScreenPhase.RECONNECTING) }
        reconnectJob = scope.launch {
            delay(d)
            connect()
        }
    }

    private fun onText(text: String) {
        val msg = runCatching { AppJson.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return
        fun s(k: String) = (msg[k] as? JsonPrimitive)?.contentOrNull
        when (s("t")) {
            "screen_status" -> {
                attempt = 0
                when (s("state")) {
                    "ended" -> finish(s("reason"), s("message") ?: "The screen session ended.")
                    "waiting" -> _state.update { it.copy(phase = ScreenPhase.WAITING) }
                    "starting" -> _state.update { it.copy(phase = ScreenPhase.STARTING) }
                    "streaming" -> _state.update { it.copy(phase = ScreenPhase.STREAMING) }
                }
            }
            "screen_meta" -> {
                val w = (msg["width"] as? JsonPrimitive)?.intOrNull ?: return
                val h = (msg["height"] as? JsonPrimitive)?.intOrNull ?: return
                _state.update { it.copy(width = w, height = h) }
            }
            "auth_failed" -> finish(null, s("message") ?: "Not allowed.")
        }
    }

    private fun finish(reason: String?, message: String) {
        _state.update { it.copy(phase = ScreenPhase.ENDED, reason = reason, message = message) }
        close()
    }

    // --- decoding ----------------------------------------------------------------------------------

    /** Feeds packets to MediaCodec on its own thread and renders every decoded frame. */
    private inner class DecodeThread(private val out: Surface) : Thread("hsm-screen-decoder") {
        private val queue = LinkedBlockingQueue<VideoPacket>(120)
        @Volatile private var running = true
        private var codec: MediaCodec? = null
        private var config: ByteArray? = null
        private var needKey = true
        private var codecW = 0
        private var codecH = 0

        fun offer(p: VideoPacket) {
            if (!queue.offer(p)) {
                // Decoding cannot keep up: drop the backlog and continue from the next key frame.
                queue.clear()
                needKey = true
                requestKeyframe()
                if (p.config || p.key) queue.offer(p)
            }
        }

        fun shutdown() {
            running = false
            interrupt()
        }

        override fun run() {
            val info = MediaCodec.BufferInfo()
            try {
                while (running) {
                    val p = queue.poll(15, TimeUnit.MILLISECONDS)
                    if (p != null) handle(p)
                    drain(info)
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                // A decoder error: start over from the next config + key frame.
                needKey = true
                requestKeyframe(force = true)
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                codec = null
            }
        }

        private fun ensureCodec(): MediaCodec? {
            val st = _state.value
            val w = if (st.width > 0) st.width else 1280
            val h = if (st.height > 0) st.height else 720
            val c = codec
            if (c != null && w == codecW && h == codecH) return c
            runCatching { c?.stop() }
            runCatching { c?.release() }
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
            if (Build.VERSION.SDK_INT >= 30) fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            fmt.setInteger(MediaFormat.KEY_PRIORITY, 0)
            val n = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            n.configure(fmt, out, null, 0)
            n.start()
            codec = n
            codecW = w
            codecH = h
            needKey = true
            config?.let { queueBuffer(n, it, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG) }
            return n
        }

        private fun handle(p: VideoPacket) {
            if (p.config) {
                config = p.data
                val c = ensureCodec() ?: return
                queueBuffer(c, p.data, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                return
            }
            if (config == null) {
                requestKeyframe()
                return
            }
            val c = ensureCodec() ?: return
            if (!p.key && needKey) {
                requestKeyframe()
                return
            }
            if (queueBuffer(c, p.data, p.ptsUs, if (p.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)) {
                if (p.key) needKey = false
            } else {
                needKey = true
                requestKeyframe()
            }
        }

        private fun queueBuffer(c: MediaCodec, data: ByteArray, pts: Long, flags: Int): Boolean {
            val idx = c.dequeueInputBuffer(20_000)
            if (idx < 0) return false
            val buf = c.getInputBuffer(idx) ?: return false
            buf.clear()
            if (data.size > buf.capacity()) {
                c.queueInputBuffer(idx, 0, 0, pts, 0)
                return false
            }
            buf.put(data)
            c.queueInputBuffer(idx, 0, data.size, pts, flags)
            return true
        }

        private fun drain(info: MediaCodec.BufferInfo) {
            val c = codec ?: return
            while (true) {
                val idx = c.dequeueOutputBuffer(info, 0)
                if (idx >= 0) {
                    c.releaseOutputBuffer(idx, true)
                    framesThisSecond++
                    if (_state.value.phase != ScreenPhase.STREAMING && _state.value.phase != ScreenPhase.ENDED) {
                        _state.update { it.copy(phase = ScreenPhase.STREAMING) }
                    }
                } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || idx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    continue
                } else {
                    break
                }
            }
        }
    }
}
