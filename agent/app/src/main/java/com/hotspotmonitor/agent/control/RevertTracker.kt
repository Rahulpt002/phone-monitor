package com.hotspotmonitor.agent.control

import com.hotspotmonitor.agent.db.ArmedRevertEntity
import com.hotspotmonitor.agent.protocol.CommandNames
import com.hotspotmonitor.agent.protocol.REVERT_SETTLE_S
import com.hotspotmonitor.agent.protocol.RevertConfirm
import kotlinx.serialization.json.JsonObject

/** One armed auto-revert. Times are in the tracker's time base (Clock.elapsedMs in the Agent). */
internal data class ArmedRevert(
    val commandId: String,
    /** Command name of the undo action; also the name of the original command. */
    val undoName: String,
    val undoArgs: JsonObject,
    val mode: RevertConfirm,
    val appliedAtMs: Long,
    val deadlineMs: Long,
    /** Restored at start with its deadline already past: the Agent was not running at the deadline. */
    val overdueAtStart: Boolean = false,
)

/**
 * Which phone state each command changes (the simulator's REVERT_TARGET). A newer command on the
 * same target supersedes an armed revert of an earlier one, so a stale undo never overrides it.
 */
internal object RevertTargets {
    private val TARGETS = mapOf(
        CommandNames.MOBILE_DATA to "data",
        CommandNames.DATA_CYCLE to "data",
        CommandNames.AIRPLANE_MODE to "airplane",
        CommandNames.AIRPLANE_CYCLE to "airplane",
        CommandNames.USB_TETHERING to "tether",
        CommandNames.NETWORK_MODE to "network_mode",
    )

    fun of(commandName: String): String? = TARGETS[commandName]
}

/**
 * Persisted revert times ([ArmedRevertEntity.deadlineMs] / [ArmedRevertEntity.appliedAtMs]) are
 * phone wall-clock ms (Clock.systemNowMs). That base is the same before and after an Agent restart,
 * unlike Clock.serverNow, whose offset is only re-learned at the next `hello` (a phone clock that is
 * off would otherwise shift a restored deadline by the whole offset). The remaining time is capped
 * at the revert window, so a phone clock set back across a reboot cannot stretch it.
 */
internal object RevertRestore {
    fun restore(row: ArmedRevertEntity, undoArgs: JsonObject, mode: RevertConfirm, nowWallMs: Long, nowElapsedMs: Long): ArmedRevert {
        val windowMs = (row.deadlineMs - row.appliedAtMs).coerceAtLeast(0)
        val remainingMs = (row.deadlineMs - nowWallMs).coerceAtMost(windowMs)
        val deadline = nowElapsedMs + remainingMs.coerceAtLeast(0)
        return ArmedRevert(
            commandId = row.commandId,
            undoName = row.undoName,
            undoArgs = undoArgs,
            mode = mode,
            appliedAtMs = deadline - windowMs,
            deadlineMs = deadline,
            overdueAtStart = remainingMs <= 0,
        )
    }
}

/**
 * The auto-revert state machine of docs/protocol.md 3.5 (plan 6.4 rule 1), without I/O:
 *
 * - `agent_link`: from appliedAt + [settleMs], a probe is due every [probeIntervalMs] while
 *   connected, and once right after each reconnect. The first `probe_ack` for a probe sent at or
 *   after the settle time confirms the change.
 * - `dashboard`: only [onKeep] confirms.
 * - At the deadline the entry is handed out by [takeDue] to be undone.
 *
 * Each entry leaves the tracker exactly once (kept or due), so a late ack cannot keep a change that
 * is already being reverted. Thread-safe.
 */
internal class RevertTracker(
    private val settleMs: Long = REVERT_SETTLE_S * 1000L,
    private val probeIntervalMs: Long = PROBE_INTERVAL_MS,
) {
    private val armed = LinkedHashMap<String, ArmedRevert>()

    /** probe id -> the commands that probe can confirm (those past their settle time when it was sent). */
    private val probeTargets = LinkedHashMap<String, Set<String>>()
    private var lastProbeAtMs: Long? = null
    private var probeAfterReconnect = false

    @Synchronized
    fun arm(entry: ArmedRevert) {
        armed[entry.commandId] = entry
    }

    @Synchronized
    fun isArmed(commandId: String): Boolean = commandId in armed

    @Synchronized
    fun armedCount(): Int = armed.size

    @Synchronized
    fun disarm(commandId: String): ArmedRevert? = armed.remove(commandId)

    /**
     * A newer command ([byCommandId]) changes the state of [target]: the entries of earlier commands
     * on that target leave the tracker (oldest first) without their undo running.
     */
    @Synchronized
    fun supersede(target: String, byCommandId: String): List<ArmedRevert> {
        val doomed = armed.values.filter { it.commandId != byCommandId && RevertTargets.of(it.undoName) == target }
        doomed.forEach { armed.remove(it.commandId) }
        return doomed
    }

    /** The link came back (after `hello`): one probe is due as soon as an entry is past its settle time. */
    @Synchronized
    fun onConnected() {
        probeAfterReconnect = true
    }

    @Synchronized
    fun shouldProbe(nowMs: Long, connected: Boolean): Boolean {
        if (!connected) return false
        if (eligible(nowMs).isEmpty()) return false
        if (probeAfterReconnect) return true
        val last = lastProbeAtMs ?: return true
        return nowMs - last >= probeIntervalMs
    }

    @Synchronized
    fun onProbeSent(probeId: String, nowMs: Long) {
        lastProbeAtMs = nowMs
        probeAfterReconnect = false
        val targets = eligible(nowMs).map { it.commandId }.toSet()
        if (targets.isEmpty()) return
        probeTargets[probeId] = targets
        while (probeTargets.size > MAX_TRACKED_PROBES) {
            probeTargets.remove(probeTargets.keys.first())
        }
    }

    /** Entries confirmed by this ack (removed from the tracker); empty for an unknown or useless probe. */
    @Synchronized
    fun onProbeAck(probeId: String): List<ArmedRevert> {
        val targets = probeTargets.remove(probeId) ?: return emptyList()
        return targets.mapNotNull { armed.remove(it) }
    }

    /** A `cmd_keep` arrived: the entry (removed), or null when it is not armed (any more). */
    @Synchronized
    fun onKeep(commandId: String): ArmedRevert? = armed.remove(commandId)

    /** Entries whose deadline has passed (removed from the tracker); the caller undoes them. */
    @Synchronized
    fun takeDue(nowMs: Long): List<ArmedRevert> {
        val due = armed.values.filter { nowMs >= it.deadlineMs }
        due.forEach { armed.remove(it.commandId) }
        return due
    }

    private fun eligible(nowMs: Long): List<ArmedRevert> = armed.values.filter {
        it.mode == RevertConfirm.AGENT_LINK && nowMs >= it.appliedAtMs + settleMs && nowMs < it.deadlineMs
    }

    companion object {
        const val PROBE_INTERVAL_MS = 10_000L
        private const val MAX_TRACKED_PROBES = 64
    }
}
