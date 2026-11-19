package com.hotspotmonitor.agent.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PingModeTest {
    @Test
    fun classifiesUnprivilegedPingOutput() {
        assertEquals(PingOutcome.NETWORK, classifyPing("connect: Network is unreachable\n", started = true))
        assertEquals(PingOutcome.NETWORK, classifyPing("ping: sendto: Network is unreachable", started = true))
        assertEquals(PingOutcome.NETWORK, classifyPing("ping: unknown host 1.1.1.1", started = true))
        assertEquals(PingOutcome.NETWORK, classifyPing("ping: one.one: No address associated with hostname", started = true))
        assertEquals(PingOutcome.NOT_PERMITTED, classifyPing("ping: socket: Operation not permitted", started = true))
        assertEquals(PingOutcome.NOT_PERMITTED, classifyPing("ping: socket SOCK_DGRAM 1: Permission denied", started = true))
        assertEquals(PingOutcome.NOT_PERMITTED, classifyPing(null, started = false))
        assertEquals(PingOutcome.UNKNOWN, classifyPing("", started = true))
        val ok = """
            5 packets transmitted, 5 received, 0% packet loss, time 804ms
            rtt min/avg/max/mdev = 20.1/25.3/30.2/3.1 ms
        """.trimIndent()
        assertEquals(PingOutcome.RAN, classifyPing(ok, started = true))
    }

    @Test
    fun anOutageNeverSwitchesPingToTheRootShell() {
        val mode = PingMode()
        repeat(20) { assertEquals(PingMode.Next.NO_READING, mode.onUnprivilegedResult(PingOutcome.NETWORK)) }
        assertFalse(mode.useRoot)
        mode.onUnprivilegedResult(PingOutcome.RAN)
        assertFalse(mode.useRoot)
    }

    @Test
    fun noIcmpPermissionSwitchesAtOnceAndUnexplainedFailuresAfterThreeInARow() {
        val denied = PingMode()
        assertEquals(PingMode.Next.ROOT, denied.onUnprivilegedResult(PingOutcome.NOT_PERMITTED))
        assertTrue(denied.useRoot)

        val unknown = PingMode()
        assertEquals(PingMode.Next.NO_READING, unknown.onUnprivilegedResult(PingOutcome.UNKNOWN))
        assertEquals(PingMode.Next.NO_READING, unknown.onUnprivilegedResult(PingOutcome.UNKNOWN))
        unknown.onUnprivilegedResult(PingOutcome.NETWORK) // resets the run
        assertEquals(PingMode.Next.NO_READING, unknown.onUnprivilegedResult(PingOutcome.UNKNOWN))
        assertEquals(PingMode.Next.NO_READING, unknown.onUnprivilegedResult(PingOutcome.UNKNOWN))
        assertEquals(PingMode.Next.ROOT, unknown.onUnprivilegedResult(PingOutcome.UNKNOWN))
        assertTrue(unknown.useRoot)
    }
}
