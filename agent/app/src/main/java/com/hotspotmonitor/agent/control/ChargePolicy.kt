package com.hotspotmonitor.agent.control

import com.hotspotmonitor.agent.protocol.StopReason

/** What the charge switch should be, and why it is off. */
internal data class ChargeDecision(val enabled: Boolean, val reason: StopReason?)

/**
 * Charging rules of plan 4.2 / 6.5 without I/O. Precedence: heat stop > manual override > limit.
 * A manual "on" charges past the limit, but never while the heat stop is active.
 */
internal object ChargePolicy {
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
