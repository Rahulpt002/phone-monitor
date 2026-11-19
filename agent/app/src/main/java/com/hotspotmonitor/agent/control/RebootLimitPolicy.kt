package com.hotspotmonitor.agent.control

import com.hotspotmonitor.agent.db.RebootLogDao
import com.hotspotmonitor.agent.db.RebootLogEntity
import com.hotspotmonitor.agent.protocol.CommandSource
import com.hotspotmonitor.agent.protocol.WatchdogSettings
import kotlin.math.max

/**
 * Plan 6.4 rule 3: at most `max_reboots` per `reboot_window_h`, from any source. Times are the
 * phone's wall clock (Clock.systemNowMs) because the log must survive the reboots it counts.
 * Settings are read on every call so Dashboard edits apply at once.
 */
internal class RebootLimitPolicy(
    private val dao: RebootLogDao,
    private val settings: () -> WatchdogSettings,
) {
    fun windowMs(s: WatchdogSettings = settings()): Long = (s.rebootWindowH * MS_PER_HOUR).toLong().coerceAtLeast(0)

    suspend fun countInWindow(nowMs: Long): Int = dao.countSince(nowMs - windowMs())

    suspend fun canReboot(nowMs: Long): Boolean = countInWindow(nowMs) < settings().maxReboots

    suspend fun record(source: CommandSource, nowMs: Long) {
        dao.insert(RebootLogEntity(atMs = nowMs, source = source.wire))
        // Keep at least a week of history for diagnostics, and always the whole window.
        dao.deleteOlderThan(nowMs - max(HISTORY_MS, windowMs()))
    }

    companion object {
        private const val MS_PER_HOUR = 3_600_000.0
        private const val HISTORY_MS = 7 * 24 * 3_600_000L
    }
}
