package com.hotspotmonitor.agent.finder

import android.os.Build
import com.hotspotmonitor.agent.core.AgentGraph
import com.hotspotmonitor.agent.root.shellQuote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Headless volume keys (plan 7.3): works with the screen off by reading the input devices through
 * root, so it depends neither on an Activity nor on the screen state.
 *
 * Every input device that reports a volume key (found with `getevent -lp`) is streamed as raw
 * `struct input_event` records with `su -c 'exec cat /dev/input/eventN'`. Raw reads are used
 * rather than a `getevent -lq` text stream because getevent's stdout is block-buffered when it is
 * a pipe, which can deliver key presses late. If no device can be read, the listener falls back to
 * streaming `getevent -l -q -t` text (its kernel timestamps keep press durations right even when
 * lines arrive in bursts).
 *
 * Callbacks run on background threads: [onHold] while a key is held (at 1 s and 3 s, for audible
 * feedback), [onGesture] when a held key is released, [onStatus] when listening starts or stops.
 */
internal class HeadlessKeyListener(
    private val graph: AgentGraph,
    private val onGesture: (KeyGesture) -> Unit,
    private val onHold: (VolumeKey, PressKind) -> Unit,
    private val onStatus: (listening: Boolean, detail: String) -> Unit,
) {
    private val lock = Any()
    private var job: Job? = null
    private val detector = LongPressDetector()
    private val holdTimers = ConcurrentHashMap<VolumeKey, Job>()
    private val streams = ConcurrentHashMap<Int, RootStream>()
    private val streamIds = AtomicInteger()

    val running: Boolean get() = synchronized(lock) { job?.isActive == true }

    /** Starts listening in [scope] (the service scope). Idempotent. */
    fun start(scope: CoroutineScope) {
        synchronized(lock) {
            if (job?.isActive == true) return
            job = scope.launch { runLoop() }
        }
    }

    /** Stops listening and kills the reader processes. Safe to call when not running. */
    fun stop() {
        val previous = synchronized(lock) {
            val j = job
            job = null
            j
        }
        previous?.cancel()
        killAllStreams()
        holdTimers.values.forEach { it.cancel() }
        holdTimers.clear()
        detector.reset()
        if (previous != null) onStatus(false, "Headless keys off")
    }

    private suspend fun runLoop() {
        var backoffMs = MIN_BACKOFF_MS
        while (currentCoroutineContext().isActive) {
            val startedAt = graph.clock.elapsedMs()
            try {
                if (!graph.shell.isRooted()) {
                    onStatus(false, "Headless keys need root")
                    delay(NO_ROOT_RETRY_MS)
                    continue
                }
                val devices = discoverDevices()
                val rawWorked = devices.isNotEmpty() && streamRawDevices(devices)
                currentCoroutineContext().ensureActive()
                if (!rawWorked) streamGeteventText()
                currentCoroutineContext().ensureActive()
                onStatus(false, "Key input stream ended; restarting")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                graph.log.w(TAG, "Headless key listener failed", e)
                onStatus(false, "Key listener error: ${e.message ?: e.javaClass.simpleName}")
            }
            if (graph.clock.elapsedMs() - startedAt > STABLE_RUN_MS) backoffMs = MIN_BACKOFF_MS
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private suspend fun discoverDevices(): List<String> {
        val result = graph.shell.run("getevent -lp", DISCOVERY_TIMEOUT_MS)
        val fromGetevent = Getevent.volumeKeyDevices(result.out)
        if (fromGetevent.isNotEmpty()) return fromGetevent
        // getevent missing or an unexpected format: read every event device and filter by key code.
        val ls = graph.shell.run("ls /dev/input", DISCOVERY_TIMEOUT_MS)
        return ls.outLines.map { it.trim() }.filter { EVENT_NODE.matches(it) }.map { "/dev/input/$it" }
    }

    /**
     * Streams every device in parallel until all streams end. Returns false when the streams ended
     * quickly without delivering anything (could not be opened), so the caller falls back to text.
     */
    private suspend fun streamRawDevices(devices: List<String>): Boolean {
        val eventSize = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) Getevent.EVENT_SIZE_64 else Getevent.EVENT_SIZE_32
        val delivered = AtomicInteger()
        val startedAt = graph.clock.elapsedMs()
        onStatus(true, "Listening on ${devices.joinToString()}")
        graph.log.i(TAG, "Headless keys: reading ${devices.joinToString()} ($eventSize-byte events)")
        coroutineScope {
            for (device in devices) {
                launch {
                    val decoder = InputEventDecoder(eventSize)
                    runRootStream("exec cat ${shellQuote(device)}") { input ->
                        val buf = ByteArray(eventSize * 64)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            if (n > 0) delivered.incrementAndGet()
                            decoder.feed(buf, n).forEach(::handleEdge)
                        }
                    }
                }
            }
        }
        return delivered.get() > 0 || graph.clock.elapsedMs() - startedAt > STABLE_STREAM_MS
    }

    private suspend fun streamGeteventText() {
        onStatus(true, "Listening with getevent")
        graph.log.w(TAG, "Headless keys: falling back to getevent text output")
        runRootStream("exec getevent -l -q -t") { input ->
            val reader = input.bufferedReader()
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = reader.readLine() ?: break
                Getevent.parseKeyLine(line)?.let(::handleEdge)
            }
        }
    }

    private fun handleEdge(edge: KeyEdge) {
        val now = graph.clock.elapsedMs()
        if (edge.down) {
            if (!detector.isHeld(edge.key)) startHoldTimer(edge.key)
            detector.onEdge(edge, now)
            return
        }
        holdTimers.remove(edge.key)?.cancel()
        val gesture = detector.onEdge(edge, now) ?: return
        graph.log.i(TAG, "Volume ${edge.key.name.lowercase()} held ${gesture.durationMs} ms: ${gesture.kind}")
        if (gesture.kind == PressKind.SHORT) return
        try {
            onGesture(gesture)
        } catch (e: Exception) {
            graph.log.w(TAG, "Gesture handler failed", e)
        }
    }

    private fun startHoldTimer(key: VolumeKey) {
        val parent = synchronized(lock) { job } ?: return
        holdTimers.remove(key)?.cancel()
        holdTimers[key] = CoroutineScope(parent).launch {
            delay(LongPressDetector.LONG_PRESS_MS)
            notifyHold(key, PressKind.LONG)
            delay(LongPressDetector.VERY_LONG_PRESS_MS - LongPressDetector.LONG_PRESS_MS)
            notifyHold(key, PressKind.VERY_LONG)
        }
    }

    private fun notifyHold(key: VolumeKey, kind: PressKind) {
        try {
            onHold(key, kind)
        } catch (e: Exception) {
            graph.log.w(TAG, "Hold feedback failed", e)
        }
    }

    /**
     * Runs [script] in a root shell process and passes its stdout (after the PID line) to [read]
     * on Dispatchers.IO. The process is killed when the coroutine is cancelled or [read] returns.
     */
    private suspend fun runRootStream(script: String, read: suspend (InputStream) -> Unit) {
        val process = try {
            withContext(Dispatchers.IO) {
                ProcessBuilder("su", "-c", "echo \$\$; $script").redirectErrorStream(false).start()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            graph.log.w(TAG, "Could not start a root process for headless keys", e)
            return
        }
        val id = streamIds.incrementAndGet()
        val stream = RootStream(process)
        streams[id] = stream
        try {
            runCatching { process.outputStream.close() }
            coroutineScope {
                // Blocking reads do not notice cancellation; killing the process ends them.
                // UNDISPATCHED arms the finally even if cancellation comes before it is scheduled.
                val killer = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        stream.kill()
                    }
                }
                try {
                    withContext(Dispatchers.IO) {
                        val input = process.inputStream
                        stream.pid = readPidLine(input)
                        read(input)
                    }
                } finally {
                    killer.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive) graph.log.w(TAG, "Headless key stream ended with an error", e)
        } finally {
            streams.remove(id)
            logAvailableStderr(process)
            stream.kill()
        }
    }

    /** First line of the stream is the PID echoed by the shell (cat/getevent keep it after exec). */
    private fun readPidLine(input: InputStream): Int? {
        val sb = StringBuilder()
        while (sb.length < 16) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            sb.append(b.toChar())
        }
        return sb.toString().trim().toIntOrNull()
    }

    /** Logs what the reader wrote to stderr without blocking (the root side may still hold the pipe). */
    private fun logAvailableStderr(process: Process) {
        runCatching {
            val err = process.errorStream
            val n = err.available().coerceAtMost(2_048)
            if (n > 0) {
                val buf = ByteArray(n)
                val read = err.read(buf)
                if (read > 0) graph.log.w(TAG, "Key reader stderr: ${String(buf, 0, read).trim().take(300)}")
            }
            err.close()
        }
    }

    private fun killAllStreams() {
        streams.values.forEach { it.kill() }
        streams.clear()
    }

    /** One root reader process; [kill] is idempotent and never blocks. */
    private inner class RootStream(private val process: Process) {
        @Volatile
        var pid: Int? = null

        @Volatile
        private var killed = false

        fun kill() {
            if (killed) return
            killed = true
            val rootPid = pid
            runCatching { process.inputStream.close() }
            runCatching { process.destroy() }
            // The root-side process is not our child: ask the root shell to end it so it does not
            // linger until its next write into the closed pipe fails.
            if (rootPid != null && rootPid > 1) {
                graph.appScope.launch { runCatching { graph.shell.run("kill $rootPid", KILL_TIMEOUT_MS) } }
            }
        }
    }

    companion object {
        private const val TAG = "finder"
        private val EVENT_NODE = Regex("""^event\d+$""")
        private const val DISCOVERY_TIMEOUT_MS = 10_000L
        private const val KILL_TIMEOUT_MS = 5_000L
        private const val MIN_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val STABLE_RUN_MS = 60_000L
        private const val STABLE_STREAM_MS = 10_000L
        private const val NO_ROOT_RETRY_MS = 5 * 60_000L
    }
}
