package com.hotspotmonitor.agent.control

import com.hotspotmonitor.agent.core.Clock
import com.hotspotmonitor.agent.db.ArmedRevertEntity
import com.hotspotmonitor.agent.protocol.CommandNames
import com.hotspotmonitor.agent.protocol.RevertConfirm
import com.hotspotmonitor.agent.protocol.jsonObjectOf
import com.hotspotmonitor.agent.testutil.TestClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Times are a fake monotonic clock in ms; settle 10 s, probe every 10 s, deadline 120 s. */
class RevertTrackerTest {
    private fun entry(id: String, appliedAt: Long, mode: RevertConfirm = RevertConfirm.AGENT_LINK, afterS: Int = 120) = ArmedRevert(
        commandId = id,
        undoName = CommandNames.MOBILE_DATA,
        undoArgs = jsonObjectOf("enabled" to true),
        mode = mode,
        appliedAtMs = appliedAt,
        deadlineMs = appliedAt + afterS * 1000L,
    )

    @Test
    fun agentLinkIsConfirmedByAProbeSentAfterTheSettleTime() {
        val t = RevertTracker()
        t.arm(entry("c_1", appliedAt = 0))
        assertFalse("no probe before the settle time", t.shouldProbe(9_999, connected = true))
        assertTrue(t.shouldProbe(10_000, connected = true))
        t.onProbeSent("p_1", 10_000)
        assertFalse("next probe only 10 s later", t.shouldProbe(15_000, connected = true))
        assertTrue(t.shouldProbe(20_000, connected = true))

        val kept = t.onProbeAck("p_1")
        assertEquals(listOf("c_1"), kept.map { it.commandId })
        assertFalse(t.isArmed("c_1"))
        assertTrue("an entry leaves the tracker once", t.onProbeAck("p_1").isEmpty())
        assertTrue(t.takeDue(500_000).isEmpty())
    }

    @Test
    fun aProbeSentBeforeAnEntrySettledCannotConfirmIt() {
        val t = RevertTracker()
        t.arm(entry("c_old", appliedAt = 0))
        t.arm(entry("c_new", appliedAt = 8_000))
        assertTrue(t.shouldProbe(10_000, connected = true))
        t.onProbeSent("p_1", 10_000) // c_new settles only at 18 s
        assertEquals(listOf("c_old"), t.onProbeAck("p_1").map { it.commandId })
        assertTrue(t.isArmed("c_new"))
        assertTrue(t.shouldProbe(20_000, connected = true))
        t.onProbeSent("p_2", 20_000)
        assertEquals(listOf("c_new"), t.onProbeAck("p_2").map { it.commandId })
        assertEquals(0, t.armedCount())
    }

    @Test
    fun noProbeWhileDisconnectedAndOneRightAfterReconnect() {
        val t = RevertTracker()
        t.arm(entry("c_1", appliedAt = 0))
        assertFalse(t.shouldProbe(30_000, connected = false))
        t.onProbeSent("p_1", 30_000)
        assertFalse(t.shouldProbe(32_000, connected = true))
        t.onConnected()
        assertTrue("one probe after every reconnect", t.shouldProbe(32_000, connected = true))
        t.onProbeSent("p_2", 32_000)
        assertFalse(t.shouldProbe(33_000, connected = true))
    }

    @Test
    fun reconnectProbeWaitsForTheSettleTime() {
        val t = RevertTracker()
        t.arm(entry("c_1", appliedAt = 0))
        t.onConnected()
        assertFalse(t.shouldProbe(5_000, connected = true))
        assertTrue(t.shouldProbe(10_000, connected = true))
    }

    @Test
    fun unconfirmedChangesAreDueAtTheDeadline() {
        val t = RevertTracker()
        t.arm(entry("c_1", appliedAt = 0))
        t.onProbeSent("p_1", 10_000) // the ack never arrives: the change cut the link
        assertTrue(t.takeDue(119_999).isEmpty())
        val due = t.takeDue(120_000)
        assertEquals(listOf("c_1"), due.map { it.commandId })
        assertTrue("a late ack cannot keep a reverted change", t.onProbeAck("p_1").isEmpty())
        assertFalse(t.shouldProbe(130_000, connected = true))
    }

    @Test
    fun dashboardModeIsOnlyConfirmedByKeep() {
        val t = RevertTracker()
        t.arm(entry("c_usb", appliedAt = 0, mode = RevertConfirm.DASHBOARD))
        assertFalse("dashboard mode never probes", t.shouldProbe(60_000, connected = true))
        t.onProbeSent("p_1", 60_000)
        assertTrue(t.onProbeAck("p_1").isEmpty())
        assertEquals("c_usb", t.onKeep("c_usb")?.commandId)
        assertNull(t.onKeep("c_usb"))
        assertTrue(t.takeDue(200_000).isEmpty())
    }

    @Test
    fun keepForAnUnknownCommandIsIgnored() {
        val t = RevertTracker()
        assertNull(t.onKeep("c_none"))
        t.arm(entry("c_1", appliedAt = 0, mode = RevertConfirm.DASHBOARD, afterS = 30))
        assertEquals(listOf("c_1"), t.takeDue(30_000).map { it.commandId })
        assertNull(t.onKeep("c_1"))
    }

    // --- supersede (F13) ---------------------------------------------------------------------------

    private fun tether(id: String, appliedAt: Long) = ArmedRevert(
        commandId = id,
        undoName = CommandNames.USB_TETHERING,
        undoArgs = jsonObjectOf("enabled" to true, "function" to "rndis"),
        mode = RevertConfirm.DASHBOARD,
        appliedAtMs = appliedAt,
        deadlineMs = appliedAt + 120_000L,
    )

    @Test
    fun aNewerCommandOnTheSameStateSupersedesTheArmedRevert() {
        val t = RevertTracker()
        // usb_tethering {enabled:false} armed an undo to rndis; data off armed its own.
        t.arm(tether("c_off", appliedAt = 0))
        t.arm(entry("c_data", appliedAt = 0))
        // Within the window the user switches tethering on with ncm: the stale undo must never run.
        val superseded = t.supersede(RevertTargets.of(CommandNames.USB_TETHERING)!!, byCommandId = "c_on")
        assertEquals(listOf("c_off"), superseded.map { it.commandId })
        assertFalse(t.isArmed("c_off"))
        assertTrue("another state is not touched", t.isArmed("c_data"))
        assertEquals(listOf("c_data"), t.takeDue(120_000).map { it.commandId })
        assertTrue(t.supersede("tether", "c_on").isEmpty())
    }

    @Test
    fun revertTargetsMatchTheSimulator() {
        assertEquals("data", RevertTargets.of(CommandNames.MOBILE_DATA))
        assertEquals("data", RevertTargets.of(CommandNames.DATA_CYCLE))
        assertEquals("airplane", RevertTargets.of(CommandNames.AIRPLANE_MODE))
        assertEquals("airplane", RevertTargets.of(CommandNames.AIRPLANE_CYCLE))
        assertEquals("tether", RevertTargets.of(CommandNames.USB_TETHERING))
        assertEquals("network_mode", RevertTargets.of(CommandNames.NETWORK_MODE))
        assertNull(RevertTargets.of(CommandNames.HOTSPOT))
        val t = RevertTracker()
        t.arm(entry("c_1", appliedAt = 0)) // mobile_data off
        assertEquals("a data cycle takes the data state over", listOf("c_1"), t.supersede(RevertTargets.of(CommandNames.DATA_CYCLE)!!, "c_2").map { it.commandId })
    }

    // --- restore across a restart (F05) ------------------------------------------------------------

    private fun row(appliedAt: Long, afterS: Int = 120) = ArmedRevertEntity(
        commandId = "c_1",
        undoName = CommandNames.NETWORK_MODE,
        undoArgsJson = """{"mode":"auto"}""",
        deadlineMs = appliedAt + afterS * 1000L,
        confirmMode = "agent_link",
        appliedAtMs = appliedAt,
    )

    @Test
    fun restoredDeadlineDoesNotDependOnTheServerOffset() {
        // Phone clock 1 h behind the Server: the offset was +1 h before the process died.
        val before = TestClock(system = Clock.parseIso("2026-11-19T10:00:00.000Z")!!, elapsed = 50_000)
        before.setServerOffset(3_600_000)
        // network_mode lte_only armed (120 s): persisted in phone wall-clock time.
        val persisted = row(appliedAt = before.systemNowMs())

        // Restart 30 s later in a new process: offset unknown (0) until hello, elapsed restarted.
        val after = TestClock(system = before.system + 30_000, elapsed = 5_000)
        val entry = RevertRestore.restore(persisted, jsonObjectOf("mode" to "auto"), RevertConfirm.AGENT_LINK, after.systemNowMs(), after.elapsedMs())
        assertFalse(entry.overdueAtStart)
        assertEquals("90 s left, not 1 h + 90 s", 5_000L + 90_000L, entry.deadlineMs)
        assertEquals("the window stays 120 s", 120_000L, entry.deadlineMs - entry.appliedAtMs)

        val t = RevertTracker()
        t.arm(entry)
        assertTrue(t.takeDue(after.elapsedMs() + 89_999).isEmpty())
        assertEquals(listOf("c_1"), t.takeDue(after.elapsedMs() + 90_000).map { it.commandId })
    }

    @Test
    fun restoredRevertPastItsDeadlineIsDueAtOnceAndAClockSetBackIsCapped() {
        val applied = Clock.parseIso("2026-11-19T10:00:00.000Z")!!
        val overdue = RevertRestore.restore(row(applied), jsonObjectOf(), RevertConfirm.AGENT_LINK, applied + 600_000, nowElapsedMs = 1_000)
        assertTrue(overdue.overdueAtStart)
        assertEquals(1_000L, overdue.deadlineMs)
        val t = RevertTracker()
        t.arm(overdue)
        assertFalse("a past deadline is never confirmed by a probe", t.shouldProbe(1_000, connected = true))
        assertEquals(listOf("c_1"), t.takeDue(1_000).map { it.commandId })

        // The phone clock went back 2 days across a reboot: at most the original 120 s window.
        val capped = RevertRestore.restore(row(applied), jsonObjectOf(), RevertConfirm.AGENT_LINK, applied - 2 * 86_400_000L, nowElapsedMs = 1_000)
        assertFalse(capped.overdueAtStart)
        assertEquals(121_000L, capped.deadlineMs)
        assertEquals(1_000L, capped.appliedAtMs)
    }
}
