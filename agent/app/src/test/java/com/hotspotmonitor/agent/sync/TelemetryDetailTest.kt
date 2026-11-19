package com.hotspotmonitor.agent.sync

import com.hotspotmonitor.agent.protocol.AgentJson
import com.hotspotmonitor.agent.protocol.AgentToServer
import com.hotspotmonitor.agent.protocol.HeartbeatMsg
import com.hotspotmonitor.agent.protocol.Rates
import com.hotspotmonitor.agent.protocol.TelemetryMsg
import com.hotspotmonitor.agent.protocol.TelemetrySettings
import com.hotspotmonitor.agent.protocol.TelemetryTier
import com.hotspotmonitor.agent.protocol.Wire
import com.hotspotmonitor.agent.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryDetailTest {
    private val msgs = Fixtures.objects("agent-to-server.json").map { AgentJson.decodeFromJsonElement(AgentToServer.serializer(), it) }
    private val slow = msgs.filterIsInstance<TelemetryMsg>().first { it.tier == TelemetryTier.SLOW }
    private val fast = msgs.filterIsInstance<TelemetryMsg>().first { it.tier == TelemetryTier.FAST }
    private val heartbeat = msgs.filterIsInstance<HeartbeatMsg>().first()

    private fun bytes(msg: AgentToServer): Int = Wire.encode(msg).toByteArray(Charsets.UTF_8).size

    @Test
    fun detailListsGoEvery15MinutesOrWhileADashboardIsOpen() {
        assertNotNull(slow.heat!!.zones)
        assertNotNull(slow.system!!.top)
        assertNotNull(slow.traffic!!.perIf)
        assertNotNull(fast.radio!!.neighbours)
        val d = TelemetryDetail()
        val minute = 60_000L

        assertEquals("the first slow message carries the lists", slow, d.slow(slow, 0, dashboardOpen = false))
        val trimmed = d.slow(slow, minute, dashboardOpen = false)
        assertNull(trimmed.heat!!.zones)
        assertNull(trimmed.system!!.top)
        assertNull(trimmed.traffic!!.perIf)
        assertEquals("everything else stays", slow.heat!!.batteryC, trimmed.heat!!.batteryC)
        assertEquals(slow.traffic!!.todayRx, trimmed.traffic!!.todayRx)
        assertEquals(slow.power, trimmed.power)
        assertNull(d.slow(slow, 14 * minute, dashboardOpen = false).heat!!.zones)
        assertNotNull("15 min later", d.slow(slow, 15 * minute, dashboardOpen = false).heat!!.zones)
        assertNotNull("Dashboard open: every time", d.slow(slow, 16 * minute, dashboardOpen = true).heat!!.zones)

        assertNull(d.fast(fast, dashboardOpen = false).radio!!.neighbours)
        assertEquals(fast.radio!!.sinr, d.fast(fast, dashboardOpen = false).radio!!.sinr)
        assertEquals(fast, d.fast(fast, dashboardOpen = true))
    }

    @Test
    fun dashboardOpenMeansTheActiveFastRate() {
        val t = TelemetrySettings() // fast_active_s 5, fast_idle_s 60
        assertTrue(TelemetryDetail.dashboardOpen(Rates(fastS = 5, mirror = false), t))
        assertFalse(TelemetryDetail.dashboardOpen(Rates(fastS = 60, mirror = false), t))
    }

    /** Lists filled to the Agent's caps: 30 zones, 10 processes, 24 interfaces, 12 neighbour cells. */
    private fun atCaps(): Pair<TelemetryMsg, TelemetryMsg> {
        val zones = (1..30).associate { "thermal-zone-sensor-$it" to 41.5 + it / 10.0 }
        val top = List(10) { i -> slow.system!!.top!!.first().copy(pid = 1000 + i, name = "com.example.process$i") }
        val counters = slow.traffic!!.perIf!!.values.first()
        val perIf = (1..24).associate { "rmnet_data$it" to counters }
        val neighbour = fast.radio!!.neighbours!!.first()
        return slow.copy(
            heat = slow.heat!!.copy(zones = zones),
            system = slow.system!!.copy(top = top),
            traffic = slow.traffic!!.copy(perIf = perIf),
        ) to fast.copy(radio = fast.radio!!.copy(neighbours = List(12) { neighbour }))
    }

    /** JSON payload bytes per day with no Dashboard open (slow and fast every 60 s, heartbeat every 30 s). */
    private fun perDay(slowMsg: TelemetryMsg, fastMsg: TelemetryMsg, trim: Boolean): Long {
        val t = TelemetrySettings()
        val d = TelemetryDetail()
        var total = 0L
        var at = 0L
        while (at < 86_400_000L) {
            if (at % (t.slowS * 1000L) == 0L) total += bytes(if (trim) d.slow(slowMsg, at, dashboardOpen = false) else slowMsg)
            if (at % (t.fastIdleS * 1000L) == 0L) total += bytes(if (trim) d.fast(fastMsg, dashboardOpen = false) else fastMsg)
            if (at % (t.heartbeatS * 1000L) == 0L) total += bytes(heartbeat)
            at += 1_000L
        }
        return total
    }

    /** Background payload per day (plan 8.2 says about 1 MB); the numbers go to the test output. */
    @Test
    fun backgroundPayloadPerDay() {
        val fixtureBefore = perDay(slow, fast, trim = false)
        val fixtureAfter = perDay(slow, fast, trim = true)
        val (slowCaps, fastCaps) = atCaps()
        val capsBefore = perDay(slowCaps, fastCaps, trim = false)
        val capsAfter = perDay(slowCaps, fastCaps, trim = true)
        println(
            "Background telemetry JSON payload per day: fixtures ${fixtureBefore / 1000} kB -> ${fixtureAfter / 1000} kB; " +
                "lists at caps ${capsBefore / 1000} kB -> ${capsAfter / 1000} kB " +
                "(slow ${bytes(slow)} -> ${bytes(TelemetryDetail().let { it.slow(slow, 0, false); it.slow(slow, 60_000, false) })} B, " +
                "fast ${bytes(fast)} -> ${bytes(TelemetryDetail().fast(fast, false))} B, heartbeat ${bytes(heartbeat)} B)",
        )
        assertTrue(fixtureAfter < fixtureBefore)
        assertTrue("at the caps the lists were about half the payload", capsAfter < capsBefore * 6 / 10)
    }
}
