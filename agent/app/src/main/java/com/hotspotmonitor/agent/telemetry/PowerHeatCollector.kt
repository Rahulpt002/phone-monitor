package com.hotspotmonitor.agent.telemetry

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.PowerManager
import com.hotspotmonitor.agent.core.ChargeState
import com.hotspotmonitor.agent.protocol.PowerInfo
import com.hotspotmonitor.agent.root.DeviceProfile
import com.hotspotmonitor.agent.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Battery state from the sticky ACTION_BATTERY_CHANGED intent and BatteryManager (plan 6.2 "Power"). */
internal class PowerCollector(private val app: Context, private val failures: FailureLog) {
    private val batteryManager: BatteryManager? = app.getSystemService(BatteryManager::class.java)

    fun batteryIntent(): Intent? = failures.soft("battery_intent") { app.stickyIntent(Intent.ACTION_BATTERY_CHANGED) }

    fun collect(battery: Intent?, profile: DeviceProfile, charge: ChargeState?): PowerInfo {
        val percent = battery?.let {
            PowerMath.percent(it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), it.getIntExtra(BatteryManager.EXTRA_SCALE, -1))
        } ?: failures.soft("battery_capacity") {
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        }
        val statusRaw = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)?.takeIf { it >= 0 }
            ?: failures.soft("battery_status") { batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) }
        return PowerInfo(
            percent = percent,
            status = statusRaw?.let { PowerMath.status(it) },
            currentMa = PowerMath.currentMa(readCurrentRaw(), profile.currentNow),
            voltageMv = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.let { PowerMath.voltageMv(it) },
            health = battery?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)?.takeIf { it >= 0 }?.let { PowerMath.health(it) },
            plug = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)?.takeIf { it >= 0 }?.let { PowerMath.plug(it) },
            chargingEnabled = charge?.chargingEnabled,
            chargeLimitPct = charge?.limitPct,
            chargeControl = charge?.method,
            stopReason = charge?.stopReason,
        )
    }

    fun batteryTempC(battery: Intent?): Double? =
        battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.let { PowerMath.batteryTempC(it) }

    /** BatteryManager CURRENT_NOW, else the power_supply sysfs file (readable on most kernels). */
    private fun readCurrentRaw(): Long? {
        val fromManager = failures.soft("current_now") {
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        }
        if (fromManager != null && fromManager != Int.MIN_VALUE) return fromManager.toLong()
        return try {
            File(CURRENT_NOW_PATH).readText().trim().toLongOrNull()
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val CURRENT_NOW_PATH = "/sys/class/power_supply/battery/current_now"
    }
}

/** Thermal zones and thermal status (plan 6.2 "Heat"). */
internal class HeatCollector(
    private val app: Context,
    private val shell: () -> RootShell,
    private val failures: FailureLog,
) {
    private val powerManager: PowerManager? = app.getSystemService(PowerManager::class.java)

    /** Set when sysfs thermal files are not readable for apps on this ROM; root is used afterwards. */
    @Volatile
    private var zonesNeedRoot = false

    fun thermalStatus(): Int? = failures.soft("thermal_status") { powerManager?.currentThermalStatus }

    /** Every thermal zone with a plausible temperature (one root call when sysfs is not readable). */
    suspend fun readZones(): List<ThermalZone> {
        if (!zonesNeedRoot) {
            val direct = withContext(Dispatchers.IO) { readZonesDirect() }
            if (direct.isNotEmpty()) return direct
            zonesNeedRoot = true
        }
        val root = shell()
        if (root.rooted.value == false) {
            failures.fail("thermal_zones", "Thermal zones are not readable without root")
            return emptyList()
        }
        val r = root.run(ZONES_CMD, 10_000, quiet = true)
        val zones = ThermalMath.parseZones(r.out)
        if (zones.isEmpty()) failures.fail("thermal_zones", "No thermal zones read (${r.summary()})")
        else failures.ok("thermal_zones")
        return zones
    }

    private fun readZonesDirect(): List<ThermalZone> {
        val dirs = try {
            File(THERMAL_DIR).listFiles { f -> f.name.startsWith("thermal_zone") }
        } catch (e: SecurityException) {
            null
        } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            try {
                val type = File(dir, "type").readText().trim()
                val raw = File(dir, "temp").readText().trim().toLongOrNull()
                if (type.isEmpty() || raw == null) null else ThermalMath.celsius(raw)?.let { ThermalZone(type, it) }
            } catch (e: Exception) {
                null
            }
        }
    }

    private companion object {
        const val THERMAL_DIR = "/sys/class/thermal"
        const val ZONES_CMD = "for z in /sys/class/thermal/thermal_zone*; do " +
            "echo \"\$(cat \$z/type 2>/dev/null)|\$(cat \$z/temp 2>/dev/null)\"; done"
    }
}
