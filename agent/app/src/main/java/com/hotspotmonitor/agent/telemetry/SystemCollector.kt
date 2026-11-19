package com.hotspotmonitor.agent.telemetry

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import com.hotspotmonitor.agent.core.Clock
import com.hotspotmonitor.agent.protocol.ProcessInfo
import com.hotspotmonitor.agent.protocol.SystemInfo
import com.hotspotmonitor.agent.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Inputs the hub reads from other modules for [SystemCollector.collect]. */
internal data class SystemExtras(
    val agentVersion: String,
    val agentUptimeS: Long,
    val rebootsInWindow: Int?,
    /** Clock.serverNow(), for last_boot_at. */
    val serverNowMs: Long,
)

/** Uptime, boot, load, memory, storage, top processes and screen state (plan 6.2 "System"). */
internal class SystemCollector(
    private val app: Context,
    private val shell: () -> RootShell,
    private val failures: FailureLog,
    private val nowElapsedMs: () -> Long,
) {
    private val activityManager: ActivityManager? = app.getSystemService(ActivityManager::class.java)
    private val powerManager: PowerManager? = app.getSystemService(PowerManager::class.java)

    @Volatile
    private var bootReasonCache: String? = null

    @Volatile
    private var topCache: Pair<Long, List<ProcessInfo>?>? = null

    @Volatile
    private var loadAvgNeedsRoot = false

    suspend fun collect(extras: SystemExtras): SystemInfo {
        val uptimeMs = SystemClock.elapsedRealtime()
        val memory = failures.soft("memory_info") {
            ActivityManager.MemoryInfo().also { activityManager?.getMemoryInfo(it) }
        }
        val storage = failures.soft("statfs") { StatFs(Environment.getDataDirectory().path) }
        return SystemInfo(
            uptimeS = uptimeMs / 1000,
            bootCount = bootCount(),
            bootReason = bootReason(),
            lastBootAt = Clock.formatIso(extras.serverNowMs - uptimeMs),
            cpuLoad = loadAvg(),
            ramTotalMb = memory?.totalMem?.takeIf { it > 0 }?.let { it / MB },
            ramAvailMb = memory?.availMem?.takeIf { it > 0 }?.let { it / MB },
            storageTotalMb = storage?.totalBytes?.let { it / MB },
            storageFreeMb = storage?.availableBytes?.let { it / MB },
            top = topProcesses(),
            screenOn = failures.soft("screen_state") { powerManager?.isInteractive },
            agentVersion = extras.agentVersion,
            androidVersion = Build.VERSION.RELEASE,
            agentUptimeS = extras.agentUptimeS,
            rebootsInWindow = extras.rebootsInWindow,
        )
    }

    fun bootCount(): Int? = failures.soft("boot_count") {
        Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 }
    }

    /** sys.boot.reason, else ro.boot.bootreason; root first (the property may be restricted for apps). */
    suspend fun bootReason(): String? {
        bootReasonCache?.let { return it }
        val reason = readProp("sys.boot.reason") ?: readProp("ro.boot.bootreason")
        if (reason != null) bootReasonCache = reason
        return reason
    }

    private suspend fun readProp(name: String): String? {
        val root = shell()
        if (root.rooted.value != false) {
            val r = root.run("getprop $name", 5_000)
            if (r.ok) parseGetprop(r.out)?.let { return it }
        }
        return parseGetprop(runProcess(listOf("getprop", name), 5_000)?.out)
    }

    private suspend fun loadAvg(): List<Double>? {
        if (!loadAvgNeedsRoot) {
            val direct = withContext(Dispatchers.IO) {
                try {
                    parseLoadAvg(File("/proc/loadavg").readText())
                } catch (e: Exception) {
                    null
                }
            }
            if (direct != null) return direct
            loadAvgNeedsRoot = true
        }
        val root = shell()
        if (root.rooted.value == false) return null
        return root.readFile("/proc/loadavg", quiet = true)?.let { parseLoadAvg(it) }
    }

    /** Top 10 by CPU from toybox top (root sees every process). Cached briefly: top holds the shared root shell ~1 s. */
    private suspend fun topProcesses(): List<ProcessInfo>? {
        val now = nowElapsedMs()
        topCache?.let { (at, value) -> if (now - at < TOP_MIN_INTERVAL_MS) return value }
        val root = shell()
        if (root.rooted.value == false) return null
        var r = root.run("top -b -n 1 -d 1 -m 10", 10_000, quiet = true)
        if (!r.ok || parseTop(r.out).isEmpty()) r = root.run("top -n 1 -m 10", 10_000, quiet = true)
        val list = parseTop(r.out).takeIf { it.isNotEmpty() }
        if (list == null) failures.fail("top", "top gave no process list (${r.summary()})") else failures.ok("top")
        topCache = now to list
        return list
    }

    private companion object {
        const val MB = 1024L * 1024L
        const val TOP_MIN_INTERVAL_MS = 55_000L
    }
}
