package com.hotspotmonitor.agent.control

import com.hotspotmonitor.agent.db.CommandLogEntity
import com.hotspotmonitor.agent.protocol.BufferedMsg
import com.hotspotmonitor.agent.protocol.CmdAckMsg
import com.hotspotmonitor.agent.protocol.CmdRejectReason
import com.hotspotmonitor.agent.protocol.CmdResultMsg
import com.hotspotmonitor.agent.protocol.CmdRevertMsg
import com.hotspotmonitor.agent.protocol.CommandStatus
import com.hotspotmonitor.agent.protocol.RevertState

/**
 * What to send again when the Server re-delivers a command id the Agent has already handled (for
 * example after a reconnect, when the first `cmd_ack` had not reached it yet). A duplicate never
 * runs twice; instead the latest report is enqueued again, rebuilt from the command log. The Server
 * applies reports only forwards (docs/protocol.md 3.2), so a copy that arrives after the original
 * changes nothing.
 */
internal object DuplicateReports {
    private const val MAX_TEXT_CHARS = 2_000

    /**
     * Reports for [row] (the command log entry of that id). [revertDeadline] is the ISO deadline of
     * a still-armed revert, used when the row says `revert_armed`.
     */
    fun forLog(row: CommandLogEntity, revertDeadline: String?, ts: String): List<BufferedMsg> {
        val status = CommandStatus.entries.firstOrNull { it.wire == row.status } ?: return emptyList()
        val text = row.output.orEmpty()
        fun result(ok: Boolean, output: String = text, deadline: String? = null) = CmdResultMsg(
            id = row.id,
            ok = ok,
            output = if (ok) OutputText.truncate(output) else "",
            error = if (ok) null else text.ifBlank { "failed" }.take(MAX_TEXT_CHARS),
            durationMs = 0,
            revertDeadline = deadline,
            ts = ts,
        )
        return when (status) {
            CommandStatus.REJECTED, CommandStatus.EXPIRED -> listOf(
                CmdAckMsg(id = row.id, accepted = false, reason = rejectReason(status, text), ts = ts),
            )
            // Accepted and not finished (still running, or the process died while it ran).
            CommandStatus.QUEUED, CommandStatus.SENT, CommandStatus.ACKED -> listOf(CmdAckMsg(id = row.id, accepted = true, ts = ts))
            CommandStatus.DONE -> listOf(result(ok = true))
            CommandStatus.FAILED -> listOf(result(ok = false))
            CommandStatus.REVERT_ARMED -> listOf(result(ok = true, deadline = revertDeadline))
            // The log output holds the revert detail by now.
            CommandStatus.KEPT, CommandStatus.REVERTED -> listOf(
                result(ok = true, output = ""),
                CmdRevertMsg(
                    id = row.id,
                    state = if (status == CommandStatus.KEPT) RevertState.KEPT else RevertState.REVERTED,
                    detail = text.ifBlank { null }?.take(MAX_TEXT_CHARS),
                    ts = ts,
                ),
            )
        }
    }

    /** The executor stores a rejection as "<reason>: <message>". */
    private fun rejectReason(status: CommandStatus, text: String): CmdRejectReason? {
        if (status == CommandStatus.EXPIRED) return CmdRejectReason.EXPIRED
        val prefix = text.substringBefore(':').trim()
        return CmdRejectReason.entries.firstOrNull { it.name.lowercase() == prefix }
    }
}
