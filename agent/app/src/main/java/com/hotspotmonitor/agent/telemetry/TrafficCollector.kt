package com.hotspotmonitor.agent.telemetry

import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import com.hotspotmonitor.agent.core.KeyValueStore
import com.hotspotmonitor.agent.protocol.AgentJson
import com.hotspotmonitor.agent.protocol.IfCounters
import com.hotspotmonitor.agent.protocol.TrafficInfo
import com.hotspotmonitor.agent.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Data usage (plan 6.2 "Traffic"): today and billing-cycle totals from NetworkStatsManager (needs
 * usage access, granted by RootSetup), with a fallback that accumulates the mobile interfaces'
 * /sys/class/net counters in SharedPreferences across reboots; plus raw per-interface counters.
 */
internal class TrafficCollector(
    private val app: Context,
    private val prefs: KeyValueStore,
    private val shell: () -> RootShell,
    private val inspector: NetworkInspector,
    private val failures: FailureLog,
) {
    private val statsManager: NetworkStatsManager? = app.getSystemService(NetworkStatsManager::class.java)

    /** Set when direct sysfs reads are denied by SELinux, so later reads go through root. */
    @Volatile
    private var countersNeedRoot = false

    suspend fun collect(cycleStartDay: Int, nowMs: Long, bootId: String): TrafficInfo {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val cycle = cycleStart(today, cycleStartDay)
        val counters = readCounters()
        val mobileIfs = inspector.interfaces().cellularIfs
        val mobileCounters = counters.filterKeys { it in mobileIfs || isMobileInterfaceName(it) }
        val acc = updateAccumulator(bootId, today, cycle, mobileCounters)

        val todayStats = queryMobile(startOfDayMs(today, zone), nowMs)
        val cycleStats = queryMobile(startOfDayMs(cycle, zone), nowMs)
        val perIf = counters
            .filter { (name, c) -> isReportableInterface(name) && (c.rx > 0 || c.tx > 0) }
            .entries
            .sortedByDescending { it.value.rx + it.value.tx }
            .take(MAX_PER_IF)
            .associate { it.key to it.value }

        return TrafficInfo(
            todayRx = todayStats?.first ?: acc?.dayRx,
            todayTx = todayStats?.second ?: acc?.dayTx,
            cycleRx = cycleStats?.first ?: acc?.cycleRx,
            cycleTx = cycleStats?.second ?: acc?.cycleTx,
            cycleStart = cycle.toString(),
            perIf = perIf.ifEmpty { null },
        )
    }

    /** Mobile rx/tx bytes between the two times, or null without usage access. */
    @Suppress("DEPRECATION")
    private suspend fun queryMobile(startMs: Long, endMs: Long): Pair<Long, Long>? {
        val nsm = statsManager ?: return null
        if (endMs <= startMs) return 0L to 0L
        return withContext(Dispatchers.IO) {
            failures.soft("network_stats") {
                val bucket = nsm.querySummaryForDevice(ConnectivityManager.TYPE_MOBILE, null, startMs, endMs)
                bucket.rxBytes to bucket.txBytes
            }
        }
    }

    private fun updateAccumulator(bootId: String, today: LocalDate, cycle: LocalDate, current: Map<String, IfCounters>): TrafficAccState? {
        if (current.isEmpty()) return loadAccumulator()?.takeIf { it.day == today.toString() && it.cycleStart == cycle.toString() }
        val next = TrafficAccumulator.update(loadAccumulator(), bootId, today, cycle, current)
        failures.soft("traffic_acc_save") {
            prefs.putString(KEY_ACC, AgentJson.encodeToString(TrafficAccState.serializer(), next))
        }
        return next
    }

    private fun loadAccumulator(): TrafficAccState? {
        val json = prefs.getString(KEY_ACC) ?: return null
        return try {
            AgentJson.decodeFromString(TrafficAccState.serializer(), json)
        } catch (e: Exception) {
            failures.fail("traffic_acc_load", "Stored traffic counters unreadable; starting over", e)
            null
        }
    }

    /** rx/tx bytes of every interface in /sys/class/net. */
    suspend fun readCounters(): Map<String, IfCounters> {
        if (!countersNeedRoot) {
            val direct = withContext(Dispatchers.IO) { readCountersDirect() }
            if (direct.isNotEmpty()) return direct
            countersNeedRoot = true
        }
        val root = shell()
        if (root.rooted.value == false) {
            failures.fail("net_counters", "Cannot read /sys/class/net counters without root")
            return emptyMap()
        }
        val r = root.run(COUNTERS_CMD, 10_000, quiet = true)
        val parsed = parseIfCounters(r.out)
        if (parsed.isEmpty()) failures.fail("net_counters", "No interface counters from the root shell (${r.summary()})")
        else failures.ok("net_counters")
        return parsed
    }

    private fun readCountersDirect(): Map<String, IfCounters> {
        val dirs = try {
            File(SYS_NET).listFiles()
        } catch (e: SecurityException) {
            null
        } ?: return emptyMap()
        val out = LinkedHashMap<String, IfCounters>()
        for (dir in dirs) {
            val rx = readLong(File(dir, "statistics/rx_bytes")) ?: continue
            val tx = readLong(File(dir, "statistics/tx_bytes")) ?: continue
            out[dir.name] = IfCounters(rx = rx, tx = tx)
        }
        return out
    }

    private fun readLong(file: File): Long? = try {
        file.readText().trim().toLongOrNull()
    } catch (e: Exception) {
        null
    }

    private fun startOfDayMs(date: LocalDate, zone: ZoneId): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private companion object {
        const val SYS_NET = "/sys/class/net"
        const val KEY_ACC = "telemetry.traffic_acc"
        const val MAX_PER_IF = 24
        const val COUNTERS_CMD = "for d in /sys/class/net/*; do " +
            "echo \"\${d##*/} \$(cat \$d/statistics/rx_bytes 2>/dev/null) \$(cat \$d/statistics/tx_bytes 2>/dev/null)\"; done"
    }
}
