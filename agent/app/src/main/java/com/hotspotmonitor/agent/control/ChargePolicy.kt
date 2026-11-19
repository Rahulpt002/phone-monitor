package com.hotspotmonitor.agent.control

import com.hotspotmonitor.agent.core.KeyValueStore
import com.hotspotmonitor.agent.protocol.StopReason

/** What the charge switch should be, and why it is off. */
internal data class ChargeDecision(val enabled: Boolean, val reason: StopReason?)

/**
 * Charging rules of plan 4.2 / 6.5 without I/O. Precedence: heat stop > manual override > limit.
 * A manual "on" charges past the limit, but never while the heat stop is active. A manual "off"
 * never holds the battery at or below the safety floor ([safetyFloorPct]): there it is lifted, so
 * only heat can keep a nearly empty phone from charging.
 */
internal object ChargePolicy {
    /** The safety floor is never below this, whatever settings.alerts.low_battery_pct says. */
    const val MIN_SAFETY_FLOOR_PCT = 20

    fun safetyFloorPct(lowBatteryPct: Int): Int = maxOf(lowBatteryPct, MIN_SAFETY_FLOOR_PCT)

    /** True when a manual "off" must be lifted: the battery is at or below [floorPct]. */
    fun manualOffLifted(manual: Boolean?, batteryPct: Int?, floorPct: Int): Boolean =
        manual == false && batteryPct != null && batteryPct <= floorPct

    fun decide(heatActive: Boolean, manual: Boolean?, limitStop: Boolean): ChargeDecision = when {
        heatActive -> ChargeDecision(false, StopReason.HEAT)
        manual == false -> ChargeDecision(false, StopReason.MANUAL)
        manual == true -> ChargeDecision(true, null)
        limitStop -> ChargeDecision(false, StopReason.LIMIT)
        else -> ChargeDecision(true, null)
    }

    /**
     * Charge-limit hysteresis: stop at or above [limitPct], resume at or below
     * [limitPct] - [resumeDropPct], otherwise keep [current]. Unknown battery keeps [current].
     */
    fun limitStop(current: Boolean, batteryPct: Int?, limitPct: Int, resumeDropPct: Int): Boolean {
        if (batteryPct == null) return current
        val drop = resumeDropPct.coerceAtLeast(1)
        return when {
            batteryPct >= limitPct -> true
            batteryPct <= limitPct - drop -> false
            else -> current
        }
    }
}

/**
 * The charge controller's persistent inputs (prefs): the manual override, the heat stop and the
 * limit hysteresis. All three survive an Agent restart, so a restart neither resumes charging on a
 * battery that is still too hot (between temp_resume_c and temp_stop_charge_c) nor inside the limit
 * band. The watchdog seeds its heat rule from [heatStop] too.
 */
internal class ChargeMemory(private val prefs: KeyValueStore) {
    var manual: Boolean?
        get() = when (prefs.getString(KEY_MANUAL)) {
            "on" -> true
            "off" -> false
            else -> null
        }
        set(value) = prefs.putString(
            KEY_MANUAL,
            when (value) {
                true -> "on"
                false -> "off"
                null -> null
            },
        )

    var heatStop: Boolean
        get() = prefs.getBoolean(KEY_HEAT, false)
        set(value) {
            if (prefs.getBoolean(KEY_HEAT, false) != value) prefs.putBoolean(KEY_HEAT, value)
        }

    var limitStop: Boolean
        get() = prefs.getBoolean(KEY_LIMIT_STOP, false)
        set(value) {
            if (prefs.getBoolean(KEY_LIMIT_STOP, false) != value) prefs.putBoolean(KEY_LIMIT_STOP, value)
        }

    companion object {
        const val KEY_MANUAL = "control.charge_manual"
        const val KEY_HEAT = "control.charge_heat"
        const val KEY_LIMIT_STOP = "control.charge_limit_stop"
    }
}

/** The Agent's last write to its charge switch (elapsed ms), for telling its own plug changes from real ones. */
internal data class ChargeSwitchWrite(val enabled: Boolean, val atMs: Long)
