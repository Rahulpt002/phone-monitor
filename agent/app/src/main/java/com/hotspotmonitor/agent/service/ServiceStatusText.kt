package com.hotspotmonitor.agent.service

import com.hotspotmonitor.agent.core.ConnPhase
import com.hotspotmonitor.agent.core.ConnState
import com.hotspotmonitor.agent.protocol.BatteryStatus
import java.util.Locale

/** The one-line status in the ongoing notification, e.g. "Connected · score 62.3 · battery 58% (charging)". */
internal object ServiceStatusText {
    fun line(conn: ConnState, score: Double?, batteryPct: Int?, battery: BatteryStatus?): String {
        val parts = ArrayList<String>(3)
        parts += linkLabel(conn.phase)
        if (score != null && !score.isNaN()) parts += "score " + String.format(Locale.US, "%.1f", score)
        if (batteryPct != null) {
            parts += "battery $batteryPct%" + when (battery) {
                BatteryStatus.CHARGING -> " (charging)"
                BatteryStatus.FULL -> " (full)"
                BatteryStatus.NOT_CHARGING -> " (not charging)"
                else -> ""
            }
        }
        return parts.joinToString(" · ")
    }

    fun linkLabel(phase: ConnPhase): String = when (phase) {
        ConnPhase.CONNECTED -> "Connected"
        ConnPhase.CONNECTING -> "Connecting"
        ConnPhase.WAITING -> "Offline"
        ConnPhase.NOT_PAIRED -> "Not paired"
        ConnPhase.UNAUTHORIZED -> "Pairing revoked"
        ConnPhase.STOPPED -> "Starting"
    }
}
