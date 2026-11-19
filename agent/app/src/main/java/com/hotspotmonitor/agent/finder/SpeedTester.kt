package com.hotspotmonitor.agent.finder

import com.hotspotmonitor.agent.core.AgentGraph
import com.hotspotmonitor.agent.protocol.FinderSettings
import com.hotspotmonitor.agent.protocol.SpeedRun
import com.hotspotmonitor.agent.protocol.TelemetrySettings
import com.hotspotmonitor.agent.protocol.round1
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One speed-test run (plan 7.3 "Speed test"): ping + jitter, download, upload. Downloads stream
 * finder.speed_down_url with `{bytes}` replaced, uploads speed_bytes / 4 random bytes to
 * speed_up_url, and measures throughput after a warm-up (ThroughputMeter). Every transfer is
 * capped in time so a slow link cannot hold the Finder for minutes. Cancellation cancels the
 * OkHttp call and kills the ping process.
 */
internal class SpeedTester(private val graph: AgentGraph) {
    /** Most recent failure, for the "every measurement failed" message. */
    @Volatile
    var lastError: String? = null
        private set

    private val client: OkHttpClient by lazy {
        graph.http.newBuilder()
            .retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    private val random = SecureRandom()

    private data class Transfer(val mbps: Double?, val bytes: Long)

    private data class Latency(val pingMs: Double?, val jitterMs: Double?, val bytes: Long)

    /** Runs ping, download and upload once; a phase that fails yields null for its values. */
    suspend fun runOnce(finder: FinderSettings, telemetry: TelemetrySettings, downBytes: Long, onPhase: (String) -> Unit): SpeedRun {
        val at = graph.clock.isoNow()
        onPhase("ping")
        val latency = phase("ping") { latency(telemetry) } ?: Latency(null, null, 0)
        onPhase("download")
        val down = phase("download") { download(finder.speedDownUrl, downBytes) } ?: Transfer(null, 0)
        onPhase("upload")
        val up = phase("upload") { upload(finder.speedUpUrl, (downBytes / 4).coerceAtLeast(MIN_UPLOAD_BYTES)) } ?: Transfer(null, 0)
        return SpeedRun(
            downMbps = down.mbps,
            upMbps = up.mbps,
            pingMs = latency.pingMs?.let(::round1),
            jitterMs = latency.jitterMs?.let(::round1),
            bytes = latency.bytes + down.bytes + up.bytes,
            at = at,
        )
    }

    private suspend fun <T> phase(name: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        lastError = "$name: ${e.message ?: e.javaClass.simpleName}"
        graph.log.w(TAG, "Speed test $name failed", e)
        null
    }

    // --- latency ----------------------------------------------------------------------------

    /** ICMP ping to telemetry.ping_host; HTTP round trips to telemetry.probe_url if ICMP fails. */
    private suspend fun latency(telemetry: TelemetrySettings): Latency {
        val icmp = runCatching { icmpRtts(telemetry.pingHost) }.getOrElse { e ->
            if (e is CancellationException) throw e
            graph.log.d(TAG, "ICMP ping unavailable: ${e.message}")
            emptyList()
        }
        if (icmp.size >= 2) {
            return Latency(FinderMath.median(icmp), FinderMath.jitterMs(icmp), icmp.size * ICMP_PACKET_BYTES)
        }
        val http = httpRtts(telemetry.probeUrl)
        if (http.isEmpty()) throw IOException("no ping reply from ${telemetry.pingHost} or ${telemetry.probeUrl}")
        return Latency(FinderMath.median(http), FinderMath.jitterMs(http), http.size * HTTP_PROBE_BYTES)
    }

    private suspend fun icmpRtts(host: String): List<Double> {
        require(HOST.matches(host)) { "invalid ping host" }
        val process = withContext(Dispatchers.IO) {
            ProcessBuilder("ping", "-n", "-c", PING_COUNT.toString(), "-i", "0.2", "-W", "2", "-w", "10", host)
                .redirectErrorStream(true)
                .start()
        }
        return try {
            coroutineScope {
                val killer = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        runCatching { process.destroy() }
                    }
                }
                try {
                    withContext(Dispatchers.IO) {
                        val text = process.inputStream.bufferedReader().use { it.readText() }
                        process.waitFor(PING_WAIT_S, TimeUnit.SECONDS)
                        FinderMath.parsePingRtts(text)
                    }
                } finally {
                    killer.cancel()
                }
            }
        } finally {
            runCatching { process.destroy() }
        }
    }

    private suspend fun httpRtts(url: String): List<Double> {
        val httpUrl = url.toHttpUrlOrNull() ?: throw IOException("invalid probe_url")
        val rtts = ArrayList<Double>()
        // The first request opens the connection (DNS, TCP, TLS) and is not counted.
        for (i in 0..PING_COUNT) {
            currentCoroutineContext().ensureActive()
            val request = Request.Builder().url(httpUrl).header("Cache-Control", "no-cache").get().build()
            val startedNs = System.nanoTime()
            val ok = try {
                execute(request) { response ->
                    response.body?.close()
                    true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                lastError = "ping: ${e.message}"
                false
            }
            if (ok && i > 0) rtts.add((System.nanoTime() - startedNs) / 1_000_000.0)
        }
        return rtts
    }

    // --- download / upload ------------------------------------------------------------------

    private suspend fun download(template: String, bytes: Long): Transfer {
        val url = template.replace("{bytes}", bytes.toString()).toHttpUrlOrNull()
            ?: throw IOException("invalid speed_down_url")
        val request = Request.Builder()
            .url(url)
            .header("Accept-Encoding", "identity") // count wire bytes; no transparent gzip
            .header("Cache-Control", "no-cache")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        return execute(request) { response ->
            if (!response.isSuccessful) throw IOException("download HTTP ${response.code}")
            val body = response.body ?: throw IOException("download returned no body")
            val meter = ThroughputMeter()
            val input = body.byteStream()
            val buf = ByteArray(BUFFER_BYTES)
            var total = 0L
            val start = nowMs()
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                total += n
                val now = nowMs()
                meter.record(total, now)
                if (now - start > TRANSFER_CAP_MS) break
            }
            Transfer(meter.mbps(), total)
        }
    }

    private suspend fun upload(urlText: String, bytes: Long): Transfer {
        val url = urlText.toHttpUrlOrNull() ?: throw IOException("invalid speed_up_url")
        val chunk = ByteArray(BUFFER_BYTES).also { random.nextBytes(it) } // random: defeats compression
        val meter = ThroughputMeter()
        val body = MeteredBody(bytes, chunk, meter)
        val request = Request.Builder()
            .url(url)
            .header("Cache-Control", "no-cache")
            .header("User-Agent", USER_AGENT)
            .post(body)
            .build()
        return try {
            execute(request) { response ->
                val end = nowMs()
                response.body?.close()
                if (!response.isSuccessful) throw IOException("upload HTTP ${response.code}")
                Transfer(meter.mbps(end), body.written)
            }
        } catch (e: UploadCapReached) {
            // Slow link: measure what was sent within the cap.
            Transfer(meter.mbps(), body.written)
        }
    }

    /** Request body that writes [size] bytes in chunks and records progress in [meter]. */
    private inner class MeteredBody(
        private val size: Long,
        private val chunk: ByteArray,
        private val meter: ThroughputMeter,
    ) : RequestBody() {
        @Volatile
        var written: Long = 0
            private set

        override fun contentType() = "application/octet-stream".toMediaType()

        override fun contentLength(): Long = size

        override fun writeTo(sink: BufferedSink) {
            meter.reset()
            written = 0
            val start = nowMs()
            meter.record(0, start)
            while (written < size) {
                val n = minOf(chunk.size.toLong(), size - written).toInt()
                sink.write(chunk, 0, n)
                sink.flush()
                written += n
                val now = nowMs()
                meter.record(written, now)
                if (now - start > TRANSFER_CAP_MS && written < size) throw UploadCapReached()
            }
        }
    }

    private class UploadCapReached : IOException("upload time cap reached")

    /**
     * Executes [request] on Dispatchers.IO and passes the response to [block] (closed afterwards).
     * Cancelling the coroutine cancels the call, which unblocks any read or write.
     */
    private suspend fun <T> execute(request: Request, block: suspend (Response) -> T): T = coroutineScope {
        val call: Call = client.newCall(request)
        val finished = AtomicBoolean(false)
        // UNDISPATCHED: the finally must be armed even if cancellation arrives before it is scheduled.
        val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                // Only cancel an unfinished call: cancelling a finished one could drop the pooled
                // connection the next latency probe should reuse.
                if (!finished.get()) call.cancel()
            }
        }
        try {
            withContext(Dispatchers.IO) {
                call.execute().use { response -> block(response) }
            }
        } finally {
            finished.set(true)
            canceller.cancel()
        }
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000

    companion object {
        private const val TAG = "finder"
        private const val PING_COUNT = 8
        private const val PING_WAIT_S = 12L
        private const val BUFFER_BYTES = 64 * 1024
        private const val TRANSFER_CAP_MS = 20_000L
        private const val MIN_UPLOAD_BYTES = 64 * 1024L
        private const val ICMP_PACKET_BYTES = 2 * 84L
        private const val HTTP_PROBE_BYTES = 600L
        private const val USER_AGENT = "HotspotMonitorAgent"
        private val HOST = Regex("""^[A-Za-z0-9][A-Za-z0-9.:-]{0,253}$""")
    }
}
