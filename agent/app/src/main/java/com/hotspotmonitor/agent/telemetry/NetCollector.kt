package com.hotspotmonitor.agent.telemetry

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.CancellationSignal
import com.hotspotmonitor.agent.protocol.TelemetrySettings
import com.hotspotmonitor.agent.root.RootShell
import com.hotspotmonitor.agent.root.shellQuote
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Interfaces of the phone's networks, from ConnectivityManager. */
internal data class NetIfaces(
    /** Interface of the default network (what the Agent's own traffic uses). */
    val activeIf: String?,
    /** Interfaces of every cellular network (internet, IMS, ...). */
    val cellularIfs: Set<String>,
    /** Global IPv6 address of the cellular internet network, if any. */
    val cellularIpv6: String?,
)

/** ConnectivityManager queries shared by the net and traffic collectors. */
internal class NetworkInspector(private val app: Context, private val failures: FailureLog) {
    private val cm: ConnectivityManager? = app.getSystemService(ConnectivityManager::class.java)

    fun interfaces(): NetIfaces {
        val manager = cm ?: return NetIfaces(null, emptySet(), null)
        return failures.soft("connectivity_manager") {
            val active = manager.activeNetwork?.let { manager.getLinkProperties(it)?.interfaceName }
            val cellular = cellularNetworks(manager)
            val ifs = cellular.mapNotNullTo(LinkedHashSet()) { it.second.interfaceName }
            val internet = cellular.firstOrNull { (network, _) ->
                manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            }
            val ipv6 = internet?.second?.let { globalIpv6(it) }
            val activeIf = active ?: cellular.firstOrNull { (network, _) ->
                manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            }?.second?.interfaceName
            NetIfaces(activeIf = activeIf, cellularIfs = ifs, cellularIpv6 = ipv6)
        } ?: NetIfaces(null, emptySet(), null)
    }

    @Suppress("DEPRECATION")
    private fun cellularNetworks(manager: ConnectivityManager): List<Pair<Network, LinkProperties>> =
        manager.allNetworks.mapNotNull { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return@mapNotNull null
            val lp = manager.getLinkProperties(network) ?: return@mapNotNull null
            network to lp
        }

    private fun globalIpv6(lp: LinkProperties): String? = lp.linkAddresses
        .map { it.address }
        .filterIsInstance<Inet6Address>()
        .firstOrNull { a ->
            !a.isLinkLocalAddress && !a.isLoopbackAddress && !a.isSiteLocalAddress && !a.isAnyLocalAddress &&
                (a.address[0].toInt() and 0xfe) != 0xfc // fc00::/7 unique local
        }
        ?.hostAddress
        ?.substringBefore('%')
}

/** One internet check (HTTP 204 probe, ping, DNS). */
internal data class CheckResult(
    val httpOk: Boolean,
    val httpMs: Long?,
    val ping: PingStats?,
    val dnsOk: Boolean?,
)

/**
 * The Agent's own internet probes (plan 6.2 "Internet"): HTTP GET expecting 204 with latency,
 * `ping -c 5 -i 0.2 -W 2` (unprivileged ICMP first, root shell as fallback), an uncached DNS
 * lookup, the public IP, and the current mobile throughput from TrafficStats.
 */
internal class NetCollector(
    private val baseHttp: () -> OkHttpClient,
    private val shell: () -> RootShell,
    private val failures: FailureLog,
) {
    private val probeClient: OkHttpClient by lazy {
        baseHttp().newBuilder()
            .connectTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(PROBE_CALL_TIMEOUT_S, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }

    /** Set once unprivileged ping proved unusable on this phone (ICMP sockets not allowed). */
    @Volatile
    private var pingNeedsRoot = false

    suspend fun check(settings: TelemetrySettings): CheckResult = coroutineScope {
        val http = async { httpProbeWithRetry(settings.probeUrl) }
        // Ping and DNS are informational: their failures must not void the HTTP verdict.
        val ping = async { failures.soft("ping") { ping(settings.pingHost) } }
        val dns = async { failures.soft("dns_check") { dnsCheck(settings.dnsHost) } }
        val latency = http.await()
        CheckResult(httpOk = latency != null, httpMs = latency, ping = ping.await(), dnsOk = dns.await())
    }

    /** Latency in ms of a successful probe; one retry after a short pause so a single lost packet is not an outage. */
    private suspend fun httpProbeWithRetry(url: String): Long? {
        httpProbe(url)?.let { return it }
        delay(PROBE_RETRY_DELAY_MS)
        return httpProbe(url)
    }

    private suspend fun httpProbe(configuredUrl: String): Long? {
        val url = configuredUrl.toHttpUrlOrNull() ?: run {
            // A bad URL from the Dashboard must not make the watchdog think the internet is down.
            failures.fail("probe_url", "Invalid telemetry.probe_url '$configuredUrl'; using the default")
            DEFAULT_PROBE_URL.toHttpUrlOrNull()
        } ?: return null
        val request = Request.Builder().url(url).header("Cache-Control", "no-cache").get().build()
        val started = System.nanoTime()
        return try {
            probeClient.newCall(request).await().use { response ->
                val elapsed = (System.nanoTime() - started) / 1_000_000
                val body = response.body
                val empty = body == null || body.contentLength() == 0L
                if (response.code == 204 || (response.code in 200..299 && empty)) elapsed else null
            }
        } catch (e: java.io.IOException) {
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failures.fail("http_probe", "HTTP probe failed unexpectedly", e)
            null
        }
    }

    /** ping; null when ping could not run at all. */
    suspend fun ping(host: String): PingStats? {
        if (!isSafeHost(host)) {
            failures.fail("ping_host", "Refusing to ping '$host' (not a host name or address)")
            return null
        }
        if (!pingNeedsRoot) {
            val direct = runProcess(listOf("ping", "-c", "5", "-i", "0.2", "-W", "2", host), PING_TIMEOUT_MS)
            val stats = parsePing(direct?.out)
            if (stats != null && stats.ran) return stats
            // No statistics block: ping is missing or ICMP sockets are not allowed for apps.
            pingNeedsRoot = true
            failures.fail("ping_unprivileged", "Unprivileged ping does not work here (${direct?.out?.trim()?.take(120)}); using the root shell")
        }
        val root = shell()
        if (root.rooted.value == false) return null
        val r = root.run("ping -c 5 -i 0.2 -W 2 ${shellQuote(host)}", PING_TIMEOUT_MS + 2_000, quiet = true)
        val stats = parsePing(r.out.ifBlank { r.err })
        return if (stats != null && stats.ran) stats else null
    }

    /** Uncached DNS lookup through DnsResolver (API 29); null only when the resolver failed to run. */
    suspend fun dnsCheck(host: String): Boolean? {
        if (!isSafeHost(host)) return null
        return failures.soft("dns") {
            withTimeoutOrNull(DNS_TIMEOUT_MS) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    DnsResolver.getInstance().query(
                        null, host, DnsResolver.FLAG_NO_CACHE_LOOKUP, DirectExecutor, signal,
                        object : DnsResolver.Callback<List<InetAddress>> {
                            override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                                if (cont.isActive) cont.resume(rcode == 0 && answer.isNotEmpty())
                            }

                            override fun onError(error: DnsResolver.DnsException) {
                                if (cont.isActive) cont.resume(false)
                            }
                        },
                    )
                }
            } ?: false
        }
    }

    /** The public IP as seen by [url] (plain-text reply), or null. */
    suspend fun publicIp(url: String): String? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        val request = Request.Builder().url(httpUrl).header("Cache-Control", "no-cache").get().build()
        return try {
            probeClient.newCall(request).await().use { response ->
                if (!response.isSuccessful) null else parsePublicIp(response.body?.string())
            }
        } catch (e: java.io.IOException) {
            null
        }
    }

    // --- throughput -------------------------------------------------------------------------

    private var lastRx = -1L
    private var lastTx = -1L
    private var lastAtMs = -1L
    private var lastRxBps: Long? = null
    private var lastTxBps: Long? = null

    /**
     * Mobile bits per second since the previous call (TrafficStats mobile counters include
     * tethered clients). Calls closer than [MIN_RATE_WINDOW_MS] reuse the previous rate.
     */
    @Synchronized
    fun throughput(nowElapsedMs: Long): Pair<Long?, Long?> {
        val rx = TrafficStats.getMobileRxBytes()
        val tx = TrafficStats.getMobileTxBytes()
        if (rx < 0 || tx < 0) {
            failures.fail("traffic_stats", "TrafficStats mobile counters unsupported")
            return null to null
        }
        if (lastAtMs < 0 || rx < lastRx || tx < lastTx) {
            lastRx = rx
            lastTx = tx
            lastAtMs = nowElapsedMs
            lastRxBps = null
            lastTxBps = null
            return null to null
        }
        val dt = nowElapsedMs - lastAtMs
        if (dt < MIN_RATE_WINDOW_MS) return lastRxBps to lastTxBps
        lastRxBps = (rx - lastRx) * 8_000 / dt
        lastTxBps = (tx - lastTx) * 8_000 / dt
        lastRx = rx
        lastTx = tx
        lastAtMs = nowElapsedMs
        return lastRxBps to lastTxBps
    }

    companion object {
        const val DEFAULT_PROBE_URL = "https://connectivitycheck.gstatic.com/generate_204"
        private const val PROBE_TIMEOUT_S = 5L
        private const val PROBE_CALL_TIMEOUT_S = 8L
        private const val PROBE_RETRY_DELAY_MS = 2_000L
        private const val PING_TIMEOUT_MS = 10_000L
        private const val DNS_TIMEOUT_MS = 5_000L
        private const val MIN_RATE_WINDOW_MS = 3_000L
    }
}
