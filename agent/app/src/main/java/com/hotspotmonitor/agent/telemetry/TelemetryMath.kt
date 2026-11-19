package com.hotspotmonitor.agent.telemetry

import com.hotspotmonitor.agent.protocol.BatteryHealth
import com.hotspotmonitor.agent.protocol.BatteryStatus
import com.hotspotmonitor.agent.protocol.IfCounters
import com.hotspotmonitor.agent.protocol.PlugType
import com.hotspotmonitor.agent.protocol.round1
import com.hotspotmonitor.agent.root.CurrentNowSpec
import com.hotspotmonitor.agent.root.ThermalSpec
import kotlinx.serialization.Serializable
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Pure logic behind the collectors (no Android calls), unit-tested on the JVM.
 */

// ---------------------------------------------------------------------------------------------
// Internet up / down
// ---------------------------------------------------------------------------------------------

/**
 * Turns individual internet checks into `connectivity` transitions. "Down" is reported after
 * [confirmDownChecks] failed checks in a row (one lost probe is not an outage); "up" on the first
 * success after a reported down, with the outage length. [downSinceMs] marks the first failed check
 * of the current failure streak (NetInfo.internet_down_since).
 */
internal class ConnectivityTracker(private val confirmDownChecks: Int = 2) {
    data class Transition(val online: Boolean, val downForS: Long?)

    var downSinceMs: Long? = null
        private set

    /** The state last reported as an event; null before the first report. */
    var reportedOnline: Boolean? = null
        private set

    private var failures = 0

    fun onCheck(ok: Boolean, nowMs: Long): Transition? {
        if (ok) {
            val since = downSinceMs
            failures = 0
            downSinceMs = null
            val was = reportedOnline
            reportedOnline = true
            return if (was == false) Transition(true, since?.let { ((nowMs - it) / 1000).coerceAtLeast(0) }) else null
        }
        failures++
        if (downSinceMs == null) downSinceMs = nowMs
        if (failures >= confirmDownChecks && reportedOnline != false) {
            reportedOnline = false
            return Transition(false, null)
        }
        return null
    }
}

// ---------------------------------------------------------------------------------------------
// Traffic
// ---------------------------------------------------------------------------------------------

/** Start of the billing cycle containing [today] (settings.plan.cycle_start_day, clamped to 1..28). */
internal fun cycleStart(today: LocalDate, startDay: Int): LocalDate {
    val day = startDay.coerceIn(1, 28)
    return if (today.dayOfMonth >= day) today.withDayOfMonth(day) else today.minusMonths(1).withDayOfMonth(day)
}

/** Persisted state of the sysfs-counter fallback for today / cycle totals. */
@Serializable
internal data class TrafficAccState(
    /** Identifies the boot the [counters] belong to (counters restart at 0 after a reboot). */
    val bootId: String,
    /** Last raw counters per mobile interface. */
    val counters: Map<String, IfCounters>,
    /** ISO date of [dayRx] / [dayTx]. */
    val day: String,
    val dayRx: Long,
    val dayTx: Long,
    /** ISO date of the cycle [cycleRx] / [cycleTx] belong to. */
    val cycleStart: String,
    val cycleRx: Long,
    val cycleTx: Long,
)

/**
 * Accumulates mobile bytes from raw interface counters when NetworkStatsManager is unavailable.
 * Handles counter resets: a new boot, an interface that was re-created (counter went down) or a new
 * interface all count from zero. The very first sample only sets the baseline, because bytes from
 * before the Agent was installed cannot be attributed to a day.
 */
internal object TrafficAccumulator {
    fun update(
        previous: TrafficAccState?,
        bootId: String,
        today: LocalDate,
        cycleStart: LocalDate,
        current: Map<String, IfCounters>,
    ): TrafficAccState {
        val dayText = today.toString()
        val cycleText = cycleStart.toString()
        if (previous == null) {
            return TrafficAccState(bootId, current, dayText, 0, 0, cycleText, 0, 0)
        }
        val sameBoot = previous.bootId == bootId
        var addRx = 0L
        var addTx = 0L
        for ((iface, now) in current) {
            val before = if (sameBoot) previous.counters[iface] else null
            addRx += delta(before?.rx, now.rx)
            addTx += delta(before?.tx, now.tx)
        }
        val newDay = previous.day != dayText
        val newCycle = previous.cycleStart != cycleText
        return TrafficAccState(
            bootId = bootId,
            counters = current,
            day = dayText,
            dayRx = (if (newDay) 0 else previous.dayRx) + addRx,
            dayTx = (if (newDay) 0 else previous.dayTx) + addTx,
            cycleStart = cycleText,
            cycleRx = (if (newCycle) 0 else previous.cycleRx) + addRx,
            cycleTx = (if (newCycle) 0 else previous.cycleTx) + addTx,
        )
    }

    private fun delta(before: Long?, now: Long): Long = when {
        now < 0 -> 0
        before == null -> now
        now >= before -> now - before
        else -> now // counter restarted
    }
}

/**
 * Mobile-data interfaces by name, for the counter fallback. Aggregate interfaces that carry the
 * same bytes again (rmnet_ipa0, rmnet_mhi0, clat v4-*) are excluded.
 */
internal fun isMobileInterfaceName(name: String): Boolean =
    MOBILE_IFACE.matches(name) && !name.startsWith("rmnet_ipa") && !name.startsWith("rmnet_mhi")

private val MOBILE_IFACE = Regex("^(rmnet_data\\d+|rmnet\\d+|ccmni\\d+|seth_lte\\d+|pdp\\d+|wwan\\d+|sipa_eth\\d+|rmnet_usb\\d+)$")

// ---------------------------------------------------------------------------------------------
// Power
// ---------------------------------------------------------------------------------------------

internal object PowerMath {
    // BatteryManager constants (stable since API 1..33).
    fun status(value: Int): BatteryStatus = when (value) {
        2 -> BatteryStatus.CHARGING
        3 -> BatteryStatus.DISCHARGING
        4 -> BatteryStatus.NOT_CHARGING
        5 -> BatteryStatus.FULL
        else -> BatteryStatus.UNKNOWN
    }

    fun health(value: Int): BatteryHealth = when (value) {
        2 -> BatteryHealth.GOOD
        3 -> BatteryHealth.OVERHEAT
        4 -> BatteryHealth.DEAD
        5 -> BatteryHealth.OVER_VOLTAGE
        6 -> BatteryHealth.FAILURE
        7 -> BatteryHealth.COLD
        else -> BatteryHealth.UNKNOWN
    }

    /** EXTRA_PLUGGED: 1 AC, 2 USB, 4 wireless, 8 dock (API 33); several bits can be set. */
    fun plug(value: Int): PlugType = when {
        value <= 0 -> PlugType.NONE
        (value and 2) != 0 -> PlugType.USB
        (value and 1) != 0 -> PlugType.AC
        (value and 4) != 0 -> PlugType.WIRELESS
        (value and 8) != 0 -> PlugType.DOCK
        else -> PlugType.NONE
    }

    fun percent(level: Int, scale: Int): Int? {
        if (level < 0 || scale <= 0) return null
        return (level * 100.0 / scale).toInt().coerceIn(0, 100)
    }

    /** EXTRA_VOLTAGE is millivolts, but some ROMs report volts or microvolts. */
    fun voltageMv(raw: Int): Int? = when {
        raw <= 0 -> null
        raw < 100 -> raw * 1000
        raw > 100_000 -> raw / 1000
        else -> raw
    }

    /** EXTRA_TEMPERATURE is tenths of a degree Celsius. */
    fun batteryTempC(raw: Int): Double? =
        if (raw == Int.MIN_VALUE || raw <= -400 || raw >= 1500) null else round1(raw / 10.0)

    /**
     * Battery current in mA, positive = charging, using the profile's unit and sign.
     * BatteryManager returns Integer.MIN_VALUE (or Long.MIN_VALUE) when the property is unsupported.
     */
    fun currentMa(raw: Long?, spec: CurrentNowSpec): Int? {
        if (raw == null || raw == Long.MIN_VALUE || raw == Int.MIN_VALUE.toLong() || raw == Int.MAX_VALUE.toLong()) return null
        val ma = spec.toMilliamps(raw)
        return ma.takeIf { abs(it) <= 50_000 }
    }
}

// ---------------------------------------------------------------------------------------------
// Heat
// ---------------------------------------------------------------------------------------------

internal data class ThermalZone(val type: String, val celsius: Double)

internal data class ThermalSummary(
    val cpu: Double?,
    val modem: Double?,
    val skin: Double?,
    val battery: Double?,
    val zones: Map<String, Double>,
)

internal object ThermalMath {
    const val MAX_ZONES = 30

    /**
     * A thermal_zone temp in °C. Kernels report millidegrees (most), tenths, or whole degrees;
     * implausible values (disabled sensors report e.g. -273000 or 0x7fffffff) become null.
     */
    fun celsius(raw: Long): Double? {
        val c = when {
            abs(raw) >= 1000 -> raw / 1000.0
            abs(raw) > 150 -> raw / 10.0
            else -> raw.toDouble()
        }
        return if (c < -40.0 || c > 150.0) null else round1(c)
    }

    /** Lines "type|temp" from the thermal shell loop. */
    fun parseZones(output: String?): List<ThermalZone> {
        if (output.isNullOrBlank()) return emptyList()
        return output.lines().mapNotNull { line ->
            val type = line.substringBefore('|', "").trim()
            val temp = line.substringAfter('|', "").trim().toLongOrNull()
            if (type.isEmpty() || temp == null) null else celsius(temp)?.let { ThermalZone(type, it) }
        }
    }

    /** cpu / modem / skin / battery = the hottest zone whose type contains one of the profile keywords. */
    fun summarize(zones: List<ThermalZone>, spec: ThermalSpec, maxZones: Int = MAX_ZONES): ThermalSummary {
        fun hottest(keywords: List<String>): Double? {
            val keys = keywords.map { it.lowercase() }.filter { it.isNotEmpty() }
            return zones.filter { z -> keys.any { z.type.lowercase().contains(it) } }.maxOfOrNull { it.celsius }
        }
        val allKeywords = (spec.cpu + spec.modem + spec.skin + spec.battery).map { it.lowercase() }.filter { it.isNotEmpty() }
        val prioritized = zones.sortedWith(
            compareBy<ThermalZone> { z -> if (allKeywords.any { z.type.lowercase().contains(it) }) 0 else 1 }
                .thenByDescending { it.celsius },
        )
        val map = LinkedHashMap<String, Double>()
        for (z in prioritized) {
            if (map.size >= maxZones) break
            var key = z.type.take(48)
            var n = 2
            while (map.containsKey(key)) key = "${z.type.take(44)}#${n++}"
            map[key] = z.celsius
        }
        return ThermalSummary(
            cpu = hottest(spec.cpu),
            modem = hottest(spec.modem),
            skin = hottest(spec.skin),
            battery = hottest(spec.battery),
            zones = map,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Position
// ---------------------------------------------------------------------------------------------

internal data class Vec3(val x: Double, val y: Double, val z: Double) {
    val norm: Double get() = sqrt(x * x + y * y + z * z)

    fun unit(): Vec3? = norm.takeIf { it > 1e-6 }?.let { Vec3(x / it, y / it, z / it) }

    fun encode(): String = "$x,$y,$z"

    companion object {
        fun decode(text: String?): Vec3? {
            val parts = text?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() } ?: return null
            return if (parts.size == 3) Vec3(parts[0], parts[1], parts[2]) else null
        }
    }
}

internal object PositionMath {
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance in metres. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Angle in degrees between two gravity vectors: the tilt change, independent of axis conventions. */
    fun angleDeg(a: Vec3, b: Vec3): Double? {
        val ua = a.unit() ?: return null
        val ub = b.unit() ?: return null
        val dot = (ua.x * ub.x + ua.y * ub.y + ua.z * ub.z).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(dot))
    }

    /**
     * Pitch and roll in degrees from the gravity vector, with SensorManager.getOrientation's
     * conventions (pitch = asin(-gy), roll = atan2(-gx, gz) on the normalised vector). Flat on a
     * table, screen up: (0, 0).
     */
    fun pitchRollDeg(g: Vec3): Pair<Double, Double>? {
        val u = g.unit() ?: return null
        val pitch = Math.toDegrees(asin((-u.y).coerceIn(-1.0, 1.0)))
        val roll = Math.toDegrees(atan2(-u.x, u.z))
        return pitch to roll
    }

    /** Azimuth radians from SensorManager.getOrientation -> compass degrees 0..360. */
    fun azimuthDeg(radians: Float): Double {
        val deg = Math.toDegrees(radians.toDouble())
        return (deg % 360.0 + 360.0) % 360.0
    }
}

/**
 * Decides when the installed phone has "moved" (plan 6.5 "Phone tilted or moved"): the tilt or
 * the distance must exceed its threshold in [confirmReadings] readings in a row. It fires once per
 * displacement and re-arms only after the phone is back near the reference (below half of both
 * thresholds) or the reference is reset. A distance only counts when it exceeds the combined fix
 * accuracy, so a coarse network fix cannot raise a false alarm.
 */
internal class MoveDetector(private val confirmReadings: Int = 2) {
    var alerted: Boolean = false
    private var streak = 0

    fun evaluate(
        tiltDeg: Double?,
        movedM: Double?,
        accuracySumM: Double?,
        tiltThresholdDeg: Double,
        distanceThresholdM: Double,
    ): Boolean {
        val tilted = tiltDeg != null && tiltThresholdDeg > 0 && tiltDeg > tiltThresholdDeg
        val moved = movedM != null && distanceThresholdM > 0 && movedM > distanceThresholdM &&
            movedM > (accuracySumM ?: 0.0)
        if (tilted || moved) {
            streak++
            if (!alerted && streak >= confirmReadings) {
                alerted = true
                return true
            }
            return false
        }
        streak = 0
        val tiltCalm = tiltDeg == null || tiltDeg < tiltThresholdDeg / 2
        val distanceCalm = movedM == null || movedM < distanceThresholdM / 2
        if (alerted && tiltCalm && distanceCalm) alerted = false
        return false
    }

    fun reset() {
        alerted = false
        streak = 0
    }
}

/**
 * "First stable reading": a value becomes the reference once readings stay within [tolerance] of
 * the first one for [holdMs] (the phone is mounted and left alone, not carried around).
 */
internal class StabilityGate<T>(
    private val holdMs: Long,
    private val tolerance: Double,
    private val distance: (T, T) -> Double?,
) {
    private var candidate: T? = null
    private var sinceMs: Long = 0

    /** Returns the value to adopt as reference once it has been stable long enough, else null. */
    fun offer(value: T, nowMs: Long): T? {
        val c = candidate
        val d = c?.let { distance(it, value) }
        if (c == null || d == null || d > tolerance) {
            candidate = value
            sinceMs = nowMs
            return null
        }
        return if (nowMs - sinceMs >= holdMs) c.also { reset() } else null
    }

    fun reset() {
        candidate = null
        sinceMs = 0
    }
}
