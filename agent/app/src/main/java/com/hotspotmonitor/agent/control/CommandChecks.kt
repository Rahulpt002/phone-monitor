package com.hotspotmonitor.agent.control

import com.hotspotmonitor.agent.core.Clock
import com.hotspotmonitor.agent.protocol.COMMANDS
import com.hotspotmonitor.agent.protocol.CapabilityEntry
import com.hotspotmonitor.agent.protocol.CommandGroup
import com.hotspotmonitor.agent.protocol.CommandNames

/** Pure parts of the executor's protocol 3.5 checks. */
internal object CommandChecks {
    /**
     * Commands that change the radios or the power state. Two of them never run at once, and none
     * runs while a watchdog recovery step is running (`busy`).
     */
    val CONFLICTING: Set<String> =
        COMMANDS.values.filter { it.group == CommandGroup.RADIO }.map { it.name }.toSet() +
            setOf(CommandNames.REBOOT, CommandNames.POWER_OFF)

    fun isConflicting(name: String): Boolean = name in CONFLICTING

    /** Milliseconds between `sent_at` and [serverNowMs] (offset-corrected), or null when unparseable. */
    fun ageMs(sentAtIso: String, serverNowMs: Long): Long? = Clock.parseIso(sentAtIso)?.let { serverNowMs - it }

    /**
     * Plan 6.4 rule 6: older than [expiryS] by the Server-corrected clock. A `sent_at` that cannot be
     * parsed cannot prove freshness, so it counts as expired.
     */
    fun isExpired(sentAtIso: String, serverNowMs: Long, expiryS: Int): Boolean {
        val age = ageMs(sentAtIso, serverNowMs) ?: return true
        return age > expiryS * 1000L
    }

    /**
     * Whether the capability map lets [name] run. A missing entry (probe not finished for it) does
     * not block. `power_off` only needs its mechanism here; the policy check that follows answers
     * `blocked` when allow_power_off is false.
     */
    fun capabilityAllows(name: String, entry: CapabilityEntry?): Boolean {
        if (entry == null) return true
        if (entry.supported) return true
        return name == CommandNames.POWER_OFF && entry.method != null
    }
}
