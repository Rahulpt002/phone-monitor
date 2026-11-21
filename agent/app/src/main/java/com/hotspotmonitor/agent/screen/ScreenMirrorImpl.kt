package com.hotspotmonitor.agent.screen

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import com.hotspotmonitor.agent.core.AgentGraph
import com.hotspotmonitor.agent.core.ScreenMirror
import com.hotspotmonitor.agent.protocol.CapabilityEntry
import com.hotspotmonitor.agent.protocol.ScrcpyControl
import com.hotspotmonitor.agent.protocol.ScreenControl
import com.hotspotmonitor.agent.protocol.ScreenKeyframe
import com.hotspotmonitor.agent.protocol.ScreenMetaMsg
import com.hotspotmonitor.agent.protocol.ScreenStartMsg
import com.hotspotmonitor.agent.protocol.ScreenStateMsg
import com.hotspotmonitor.agent.protocol.ScreenStates
import com.hotspotmonitor.agent.protocol.ScreenStopMsg
import com.hotspotmonitor.agent.protocol.ScreenWire
import com.hotspotmonitor.agent.root.shellQuote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Remote screen (docs/protocol.md section 8). On `screen_start` it starts the bundled scrcpy
 * server as root, connects to its video and control sockets, and relays them over a separate
 * WebSocket (/ws/screen/agent) so video never competes with telemetry on /ws/agent.
 * One session at a time; a new one replaces the old.
 */
class ScreenMirrorImpl(private val graph: AgentGraph) : ScreenMirror {
    private var moduleScope: CoroutineScope? = null
    private val lock = Any()
    private var job: Job? = null
    private var jobSession: String? = null
    private val _active = MutableStateFlow<String?>(null)
    override val active: StateFlow<String?> = _active.asStateFlow()

    private val relayClient: OkHttpClient by lazy {
        graph.http.newBuilder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override fun start(scope: CoroutineScope) {
        moduleScope = scope
    }

    override fun stop() {
        synchronized(lock) {
            job?.cancel()
            job = null
            jobSession = null
        }
        moduleScope = null
    }

    override fun onStart(msg: ScreenStartMsg) {
        val scope = moduleScope ?: graph.appScope
        synchronized(lock) {
            // The Server repeats screen_start after a reconnect of /ws/agent: keep a running session.
            if (jobSession == msg.sessionId && job?.isActive == true) return
            val previous = job
            jobSession = msg.sessionId
            job = scope.launch { runSession(msg, previous) }
        }
    }

    override fun onStop(msg: ScreenStopMsg) {
        synchronized(lock) {
            if (jobSession != msg.sessionId) return
            graph.log.i(TAG, "Screen session ${msg.sessionId} stopped by the Server (${msg.reason})")
            job?.cancel()
        }
    }

    override suspend fun probe(): CapabilityEntry {
        if (!graph.shell.isRooted()) return CapabilityEntry(supported = false, note = "needs root")
        val asset = runCatching { graph.app.assets.open(ASSET).use { it.available() } }.getOrNull()
        if (asset == null) return CapabilityEntry(supported = false, note = "scrcpy server missing from the app")
        return CapabilityEntry(supported = true, method = "scrcpy $SCRCPY_VERSION")
    }

    // --- one session ----------------------------------------------------------------------------

    private suspend fun runSession(msg: ScreenStartMsg, previous: Job?) {
        previous?.cancelAndJoin()
        val sid = msg.sessionId
        _active.value = sid
        report(sid, ScreenStates.STARTING)
        graph.log.i(TAG, "Screen session $sid starting (${msg.quality}, ${msg.maxSize} px, ${msg.bitRate / 1000} kbit/s, user ${msg.user ?: "?"})")
        val session = Session(sid)
        var outcome: String = ScreenStates.ENDED
        var error: String? = null
        try {
            withTimeout(msg.maxDurationS * 1000L + DURATION_GRACE_MS) {
                session.run(msg)
            }
        } catch (e: TimeoutCancellationException) {
            graph.log.i(TAG, "Screen session $sid reached its time limit")
        } catch (e: CancellationException) {
            report(sid, ScreenStates.ENDED)
            session.close()
            _active.compareAndSet(sid, null)
            throw e
        } catch (e: Exception) {
            outcome = if (session.streamed) ScreenStates.ENDED else ScreenStates.FAILED
            error = e.message?.take(200) ?: e.javaClass.simpleName
            graph.log.w(TAG, "Screen session $sid ${if (session.streamed) "ended" else "failed"}: $error")
        } finally {
            session.close()
        }
        report(sid, outcome, error)
        _active.compareAndSet(sid, null)
        graph.log.i(TAG, "Screen session $sid over: ${session.packets} packets, ${session.bytes / 1024} KB sent, ${session.dropped} dropped")
    }

    private fun report(sid: String, state: String, error: String? = null) {
        graph.sync.sendLive(ScreenStateMsg(sessionId = sid, state = state, error = error))
    }

    private inner class Session(val sid: String) {
        private val scid = Random.nextInt(1, 0x7fffffff)
        private val scidHex = "%08x".format(scid)
        private var process: Process? = null
        private var rootPid: Int? = null
        private var video: LocalSocket? = null
        private var control: LocalSocket? = null
        private var controlOut: OutputStream? = null
        private var relay: WebSocket? = null
        private val incoming = Channel<String>(capacity = 256)
        private val relayClosed = CompletableDeferred<String>()

        @Volatile private var width = 0
        @Volatile private var height = 0
        @Volatile var streamed = false
        @Volatile var packets = 0L
        @Volatile var bytes = 0L
        @Volatile var dropped = 0L
        private var lastKeyRequestMs = 0L

        @Volatile private var closed = false

        /** Why the stream ended; the first reason wins. "closed 1000" is the Server ending it normally. */
        @Volatile private var endReason: String? = null

        private fun ended(why: String) {
            synchronized(this) { if (endReason == null) endReason = why }
            closeStreams()
        }

        suspend fun run(msg: ScreenStartMsg) {
            coroutineScope {
                // Every child below blocks on a stream; close() unblocks them all, so it must run
                // inside this scope (which waits for its children) on every exit path.
                try {
                    val jar = withContext(Dispatchers.IO) { installJar() }
                    val p = withContext(Dispatchers.IO) { startServer(jar, msg) }
                    process = p
                    launch(Dispatchers.IO) { drain(p.inputStream, warn = false) }
                    launch(Dispatchers.IO) { drain(p.errorStream, warn = true) }
                    val name = "scrcpy_$scidHex"
                    val v = connect(name, p).also { video = it }
                    val c = connect(name, p).also { control = it }
                    controlOut = c.outputStream
                    launch(Dispatchers.IO) { discard(c.inputStream) } // device messages (none expected)
                    openRelay()
                    launch(Dispatchers.IO) {
                        val code = runInterruptible { p.waitFor() }
                        ended("the scrcpy server stopped (exit code $code)")
                    }
                    launch { ended(relayClosed.await()) }
                    launch(Dispatchers.IO) { controlLoop() }
                    withContext(Dispatchers.IO) { videoLoop(v.inputStream) }
                    ended("the video stream ended")
                } finally {
                    close()
                }
            }
            val why = endReason ?: "the video stream ended"
            if (!why.startsWith("closed 1000")) throw IOException(why)
        }

        /** Copies the bundled jar to /data/local/tmp (where scrcpy normally runs from) as root. */
        private suspend fun installJar(): String {
            val local = File(graph.app.cacheDir, "scrcpy-server-$SCRCPY_VERSION.jar")
            if (!local.exists() || local.length() == 0L) {
                val tmp = File(local.path + ".tmp")
                graph.app.assets.open(ASSET).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                if (!tmp.renameTo(local)) throw IOException("could not store the scrcpy server")
            }
            val target = "/data/local/tmp/hsm-scrcpy-server-$SCRCPY_VERSION.jar"
            val r = graph.shell.run(
                "cp ${shellQuote(local.path)} $target && chmod 0644 $target",
                timeoutMs = 15_000,
                quiet = true,
            )
            if (!r.ok) throw IOException("could not install the scrcpy server (${r.err.ifBlank { r.out }.take(120)})")
            return target
        }

        private fun startServer(jar: String, msg: ScreenStartMsg): Process {
            // Keep the command line short: on the Galaxy M52 (One UI 5.1) the encoder setup in
            // libsfplugin_ccodec (CCodec::customConfigVideoEncoder) aborts with "stack corruption
            // detected" once /proc/self/cmdline is longer than about 250 bytes. Defaults are left
            // out (control, power_on, cleanup), and the dummy byte is read instead of disabled.
            val args = mutableListOf(
                "scid=$scidHex",
                "log_level=info",
                "tunnel_forward=true",
                "audio=false",
                "send_device_meta=false",
                "video_bit_rate=${msg.bitRate.coerceIn(100_000, 8_000_000)}",
                "max_size=${msg.maxSize.coerceIn(320, 2400)}",
                "max_fps=${msg.maxFps.coerceIn(1, 60)}",
                "stay_awake=true",
            )
            val head = "app_process / com.genymobile.scrcpy.Server $SCRCPY_VERSION"
            while ("$head ${args.joinToString(" ")}".length > MAX_CMDLINE_CHARS && args.size > 4) {
                graph.log.w(TAG, "scrcpy command line too long; dropping ${args.last()}")
                args.removeAt(args.lastIndex)
            }
            val script = "echo \$\$; CLASSPATH=$jar exec $head ${args.joinToString(" ")}"
            val p = ProcessBuilder("su", "-c", script).redirectErrorStream(false).start()
            runCatching { p.outputStream.close() }
            rootPid = readPidLine(p.inputStream)
            return p
        }

        private suspend fun connect(name: String, p: Process): LocalSocket {
            val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
            while (true) {
                val s = LocalSocket()
                try {
                    withContext(Dispatchers.IO) { s.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT)) }
                    return s
                } catch (e: IOException) {
                    runCatching { s.close() }
                    if (!p.isAlive) throw IOException("the scrcpy server exited (code ${p.exitValue()})")
                    if (System.currentTimeMillis() > deadline) throw IOException("the scrcpy server did not open its socket: ${e.message}")
                    delay(100)
                }
            }
        }

        private suspend fun openRelay() {
            val info = graph.pairingState.info.value ?: throw IOException("not paired")
            val token = graph.pairingState.token() ?: throw IOException("device token unavailable")
            val url = relayUrl(info.wsUrl, info.serverUrl, sid)
            val opened = CompletableDeferred<Unit>()
            val request = Request.Builder().url(url)
                .header("Authorization", "Bearer $token")
                .header("User-Agent", "HotspotAgent/${graph.agentVersion} screen")
                .build()
            val ws = relayClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.complete(Unit)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (incoming.trySend(text).isFailure) dropped++
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    relayClosed.complete("closed $code $reason")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    relayClosed.complete("closed $code $reason")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val why = "relay connection failed: ${t.message ?: t.javaClass.simpleName}" +
                        (response?.let { " (HTTP ${it.code})" } ?: "")
                    opened.completeExceptionally(IOException(why))
                    relayClosed.complete(why)
                }
            })
            relay = ws
            try {
                withTimeout(RELAY_OPEN_TIMEOUT_MS) { opened.await() }
            } catch (e: TimeoutCancellationException) {
                ws.cancel()
                throw IOException("the relay socket did not open in time")
            }
        }

        private fun videoLoop(stream: InputStream) {
            val input = DataInputStream(BufferedInputStream(stream, 1 shl 16))
            val codec = try {
                // tunnel_forward: one dummy byte first (send_dummy_byte defaults to true).
                input.readByte()
                input.readInt()
            } catch (e: EOFException) {
                throw IOException("the scrcpy server closed the video stream before it started")
            }
            if (codec == 0 || codec == 1) throw IOException("the video encoder could not start (code $codec)")
            if (codec != CODEC_H264) throw IOException("unexpected codec 0x%08x".format(codec))
            val ws = relay ?: throw IOException("no relay")
            var waitForKey = true
            while (!closed) {
                val head = try {
                    input.readLong()
                } catch (e: EOFException) {
                    return
                }
                if (head and ScreenWire.FLAG_SESSION != 0L) {
                    width = (head and 0xffffffffL).toInt()
                    height = input.readInt()
                    ws.send(ScreenWire.encode(ScreenMetaMsg(width = width, height = height, deviceName = Build.MODEL)))
                    continue
                }
                val size = input.readInt()
                if (size < 0 || size > MAX_PACKET_BYTES) throw IOException("bad packet size $size")
                val payload = ByteArray(size)
                input.readFully(payload)
                val config = head and ScreenWire.FLAG_CONFIG != 0L
                val key = head and ScreenWire.FLAG_KEY != 0L
                if (!config) {
                    if (key) {
                        waitForKey = false
                    } else if (waitForKey) {
                        dropped++
                        continue
                    } else if (ws.queueSize() > MAX_QUEUE_BYTES) {
                        // The uplink cannot keep up: skip to the next key frame instead of lagging.
                        waitForKey = true
                        dropped++
                        requestKeyframe()
                        continue
                    }
                }
                for (frame in ScreenWire.frames(head, payload)) {
                    if (!ws.send(frame.toByteString())) throw IOException("the relay socket closed")
                    bytes += frame.size
                }
                packets++
                if (!streamed) {
                    streamed = true
                    report(sid, ScreenStates.STREAMING)
                    graph.log.i(TAG, "Screen session $sid streaming (${width}x$height)")
                }
            }
        }

        private suspend fun controlLoop() {
            for (text in incoming) {
                val msg: ScreenControl = ScreenWire.decodeControl(text) ?: continue
                if (msg == ScreenKeyframe) {
                    requestKeyframe(force = true)
                    continue
                }
                val bytesOut = ScrcpyControl.encode(msg, width, height) ?: continue
                for (b in bytesOut) writeControl(b)
            }
        }

        private fun requestKeyframe(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastKeyRequestMs < KEYFRAME_REQUEST_MS) return
            lastKeyRequestMs = now
            writeControl(ScrcpyControl.resetVideo())
        }

        private fun writeControl(b: ByteArray) {
            val out = controlOut ?: return
            try {
                synchronized(out) {
                    out.write(b)
                    out.flush()
                }
            } catch (e: IOException) {
                if (!closed) graph.log.d(TAG, "Control write failed: ${e.message}")
            }
        }

        private fun drain(stream: InputStream, warn: Boolean) {
            runCatching {
                stream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val l = line.trim()
                        if (l.isEmpty()) continue
                        if (warn || l.contains("ERROR") || l.contains("WARN")) graph.log.w(TAG, "scrcpy: ${l.take(300)}")
                        else graph.log.d(TAG, "scrcpy: ${l.take(300)}")
                    }
                }
            }
        }

        private fun discard(stream: InputStream) {
            runCatching {
                val buf = ByteArray(4096)
                while (stream.read(buf) >= 0) Unit
            }
        }

        private fun closeStreams() {
            runCatching { video?.shutdownInput() }
            runCatching { video?.close() }
            runCatching { control?.close() }
        }

        fun close() {
            if (closed) return
            closed = true
            incoming.close()
            closeStreams()
            relay?.let { runCatching { it.close(1000, "screen session over") } }
            process?.let { p ->
                runCatching { p.inputStream.close() }
                runCatching { p.errorStream.close() }
                runCatching { p.destroy() }
            }
            // The root-side app_process is not our child: end it by pid and by its unique scid.
            val pid = rootPid
            graph.appScope.launch {
                runCatching {
                    graph.shell.run(
                        (if (pid != null && pid > 1) "kill $pid 2>/dev/null; " else "") + "pkill -f 'scid=$scidHex' 2>/dev/null; true",
                        timeoutMs = 5_000,
                        quiet = true,
                    )
                }
            }
        }
    }

    /** First line of the root process output is the PID echoed before `exec`. */
    private fun readPidLine(input: InputStream): Int? {
        val sb = StringBuilder()
        while (sb.length < 16) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            sb.append(b.toChar())
        }
        return sb.toString().trim().toIntOrNull()
    }

    companion object {
        private const val TAG = "screen"
        const val SCRCPY_VERSION = "5.0.1"
        const val ASSET = "scrcpy/scrcpy-server-$SCRCPY_VERSION.jar"
        private const val CODEC_H264 = 0x68323634
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val RELAY_OPEN_TIMEOUT_MS = 25_000L
        private const val DURATION_GRACE_MS = 5_000L
        private const val KEYFRAME_REQUEST_MS = 2_000L
        /** Unsent relay data above which delta frames are skipped until the next key frame. */
        private const val MAX_QUEUE_BYTES = 1L * 1024 * 1024
        private const val MAX_PACKET_BYTES = 16 * 1024 * 1024
        /** app_process command-line limit; see startServer (Samsung encoder bug). */
        private const val MAX_CMDLINE_CHARS = 220

        /** wss://host/ws/agent -> wss://host/ws/screen/agent?session=<id> */
        fun relayUrl(wsUrl: String, serverUrl: String, sessionId: String): String {
            val base = when {
                wsUrl.endsWith("/ws/agent") -> wsUrl.removeSuffix("/ws/agent")
                else -> serverUrl.trimEnd('/').replaceFirst(Regex("^http", RegexOption.IGNORE_CASE), "ws")
            }
            return "$base/ws/screen/agent?session=${URLEncoder.encode(sessionId, "UTF-8")}"
        }
    }
}
