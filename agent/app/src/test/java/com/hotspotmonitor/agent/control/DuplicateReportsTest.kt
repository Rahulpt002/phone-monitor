package com.hotspotmonitor.agent.control

import com.hotspotmonitor.agent.db.CommandLogEntity
import com.hotspotmonitor.agent.protocol.CmdAckMsg
import com.hotspotmonitor.agent.protocol.CmdRejectReason
import com.hotspotmonitor.agent.protocol.CmdResultMsg
import com.hotspotmonitor.agent.protocol.CmdRevertMsg
import com.hotspotmonitor.agent.protocol.CommandStatus
import com.hotspotmonitor.agent.protocol.RevertState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A re-delivered command id never runs again; its latest report is rebuilt from the command log. */
class DuplicateReportsTest {
    private val ts = "2026-11-19T10:15:00.000Z"

    private fun log(status: CommandStatus, output: String?) = CommandLogEntity(
        id = "c_1", name = "data_cycle", argsJson = "{}", source = "dashboard", status = status.wire, output = output, atMs = 0,
    )

    @Test
    fun rejectionIsAcknowledgedAgainWithItsReason() {
        val reports = DuplicateReports.forLog(log(CommandStatus.REJECTED, "busy: a watchdog recovery step is running"), null, ts)
        assertEquals(listOf(CmdAckMsg(id = "c_1", accepted = false, reason = CmdRejectReason.BUSY, ts = ts)), reports)
        val limit = DuplicateReports.forLog(log(CommandStatus.REJECTED, "reboot_limit: 3 of 3 reboots used"), null, ts).single() as CmdAckMsg
        assertEquals(CmdRejectReason.REBOOT_LIMIT, limit.reason)
        val unknown = DuplicateReports.forLog(log(CommandStatus.REJECTED, null), null, ts).single() as CmdAckMsg
        assertNull(unknown.reason)
    }

    @Test
    fun runningOrInterruptedCommandIsOnlyAcknowledged() {
        assertEquals(
            listOf(CmdAckMsg(id = "c_1", accepted = true, ts = ts)),
            DuplicateReports.forLog(log(CommandStatus.ACKED, null), null, ts),
        )
    }

    @Test
    fun finishedCommandSendsItsResultAgain() {
        val done = DuplicateReports.forLog(log(CommandStatus.DONE, "Mobile data cycled"), null, ts).single() as CmdResultMsg
        assertTrue(done.ok)
        assertEquals("Mobile data cycled", done.output)
        assertNull(done.revertDeadline)

        val failed = DuplicateReports.forLog(log(CommandStatus.FAILED, "Timed out after 180 s"), null, ts).single() as CmdResultMsg
        assertEquals(false, failed.ok)
        assertEquals("Timed out after 180 s", failed.error)

        val armed = DuplicateReports.forLog(log(CommandStatus.REVERT_ARMED, "Mobile data off"), "2026-11-19T10:17:00.000Z", ts)
            .single() as CmdResultMsg
        assertEquals("2026-11-19T10:17:00.000Z", armed.revertDeadline)
    }

    @Test
    fun resolvedRevertSendsResultAndRevertState() {
        val reports = DuplicateReports.forLog(log(CommandStatus.REVERTED, "no Server round-trip within 120 s"), null, ts)
        assertEquals(2, reports.size)
        assertTrue((reports[0] as CmdResultMsg).ok)
        val revert = reports[1] as CmdRevertMsg
        assertEquals(RevertState.REVERTED, revert.state)
        assertEquals("no Server round-trip within 120 s", revert.detail)
        val kept = DuplicateReports.forLog(log(CommandStatus.KEPT, "Kept from the Dashboard"), null, ts)[1] as CmdRevertMsg
        assertEquals(RevertState.KEPT, kept.state)
    }

    @Test
    fun unknownStatusSendsNothing() {
        val row = log(CommandStatus.DONE, "x").copy(status = "weird")
        assertTrue(DuplicateReports.forLog(row, null, ts).isEmpty())
    }
}
