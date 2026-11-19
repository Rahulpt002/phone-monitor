package com.hotspotmonitor.agent.control

import com.hotspotmonitor.agent.protocol.CommandNames
import com.hotspotmonitor.agent.protocol.RevertConfirm
import com.hotspotmonitor.agent.protocol.jsonObjectOf
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
}
