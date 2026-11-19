package com.hotspotmonitor.agent.watchdog

import com.hotspotmonitor.agent.protocol.WatchdogSettings

internal enum class HeatLevel { NORMAL, WARNING, CRITICAL }

/** What the heat rule asks the watchdog to do after one reading. */
internal sealed class HeatSignal {
    /** Alert level changed: temp_warning / temp_critical / temp_normal. */
    data class Level(val from: HeatLevel, val to: HeatLevel) : HeatSignal()
    data object StopCharging : HeatSignal()
    data object ResumeCharging : HeatSignal()
    data object RestRadio : HeatSignal()
}

/**
 * Battery heat rules of plan 6.5 without I/O:
 *
 * - charging stops at temp_stop_charge_c and resumes only below temp_resume_c,
 * - alert levels: WARNING at temp_warn_c, CRITICAL at temp_stop_charge_c (charging stopped);
 *   back to NORMAL below temp_resume_c. A warning that never became critical clears below
 *   min(temp_resume_c, temp_warn_c - [WARN_HYSTERESIS_C]), so a reading hovering at the warning
 *   threshold does not produce an event every few seconds,
 * - at temp_radio_rest_c the radio rests, when [evaluate]'s caller allows it.
 */
internal class HeatRule {
    var level: HeatLevel = HeatLevel.NORMAL
        private set

    var heatStop: Boolean = false
        private set

    fun evaluate(tempC: Double, s: WatchdogSettings, canRestRadio: Boolean): List<HeatSignal> {
        val signals = ArrayList<HeatSignal>()
        if (!heatStop && tempC >= s.tempStopChargeC) {
            heatStop = true
            signals += HeatSignal.StopCharging
        } else if (heatStop && tempC < s.tempResumeC) {
            heatStop = false
            signals += HeatSignal.ResumeCharging
        }
        val next = nextLevel(tempC, s)
        if (next != level) {
            signals += HeatSignal.Level(level, next)
            level = next
        }
        if (tempC >= s.tempRadioRestC && canRestRadio) signals += HeatSignal.RestRadio
        return signals
    }

    private fun nextLevel(t: Double, s: WatchdogSettings): HeatLevel = when (level) {
        HeatLevel.NORMAL -> when {
            t >= s.tempStopChargeC -> HeatLevel.CRITICAL
            t >= s.tempWarnC -> HeatLevel.WARNING
            else -> HeatLevel.NORMAL
        }
        HeatLevel.WARNING -> when {
            t >= s.tempStopChargeC -> HeatLevel.CRITICAL
            t < minOf(s.tempResumeC, s.tempWarnC - WARN_HYSTERESIS_C) -> HeatLevel.NORMAL
            else -> HeatLevel.WARNING
        }
        HeatLevel.CRITICAL -> if (t < s.tempResumeC) HeatLevel.NORMAL else HeatLevel.CRITICAL
    }

    companion object {
        const val WARN_HYSTERESIS_C = 1.0
    }
}

/** When the next heat radio rest may start: never during one, and only radio_rest_s after the last ended. */
internal object RadioRestPolicy {
    fun allowed(nowMs: Long, lastRestEndedMs: Long?, radioRestS: Int, resting: Boolean): Boolean {
        if (resting) return false
        val ended = lastRestEndedMs ?: return true
        return nowMs - ended >= radioRestS.coerceAtLeast(0) * 1000L
    }
}
