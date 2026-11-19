package com.hotspotmonitor.agent.control

import com.hotspotmonitor.agent.core.MemoryStore
import com.hotspotmonitor.agent.protocol.StopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargePolicyTest {
    /** Feeds battery readings through the limit hysteresis (limit 60 %, resume 10 % lower). */
    private fun walk(vararg pct: Int?): List<Boolean> {
        var stop = false
        return pct.map { p -> ChargePolicy.limitStop(stop, p, limitPct = 60, resumeDropPct = 10).also { stop = it } }
    }

    @Test
    fun limitHysteresis() {
        assertEquals(
            listOf(false, false, true, true, true, true, false, false, true),
            walk(58, 59, 60, 58, 55, 51, 50, 55, 61),
        )
        assertFalse(ChargePolicy.limitStop(false, null, 60, 10))
        assertTrue("unknown battery keeps the current state", ChargePolicy.limitStop(true, null, 60, 10))
    }

    @Test
    fun zeroResumeDropStillHasAOnePercentBand() {
        assertTrue(ChargePolicy.limitStop(false, 60, 60, 0))
        assertFalse(ChargePolicy.limitStop(true, 59, 60, 0))
        assertTrue(ChargePolicy.limitStop(true, 60, 60, 0))
    }

    @Test
    fun heatWinsOverManualAndLimit() {
        assertEquals(ChargeDecision(false, StopReason.HEAT), ChargePolicy.decide(heatActive = true, manual = true, limitStop = false))
        assertEquals(ChargeDecision(false, StopReason.HEAT), ChargePolicy.decide(heatActive = true, manual = null, limitStop = true))
        assertEquals(ChargeDecision(false, StopReason.HEAT), ChargePolicy.decide(heatActive = true, manual = false, limitStop = false))
    }

    @Test
    fun manualOverridesTheLimit() {
        assertEquals(ChargeDecision(true, null), ChargePolicy.decide(heatActive = false, manual = true, limitStop = true))
        assertEquals(ChargeDecision(false, StopReason.MANUAL), ChargePolicy.decide(heatActive = false, manual = false, limitStop = false))
        assertEquals(ChargeDecision(false, StopReason.MANUAL), ChargePolicy.decide(heatActive = false, manual = false, limitStop = true))
    }

    @Test
    fun automaticFollowsTheLimit() {
        assertEquals(ChargeDecision(false, StopReason.LIMIT), ChargePolicy.decide(heatActive = false, manual = null, limitStop = true))
        assertEquals(ChargeDecision(true, null), ChargePolicy.decide(heatActive = false, manual = null, limitStop = false))
    }

    // --- safety floor (F08, F22) -------------------------------------------------------------------

    @Test
    fun safetyFloorIsLowBatteryAlertButNeverBelow20() {
        assertEquals(20, ChargePolicy.safetyFloorPct(lowBatteryPct = 20))
        assertEquals(20, ChargePolicy.safetyFloorPct(lowBatteryPct = 10))
        assertEquals(30, ChargePolicy.safetyFloorPct(lowBatteryPct = 30))
    }

    @Test
    fun manualOffIsLiftedAtTheFloorOnly() {
        assertFalse(ChargePolicy.manualOffLifted(manual = false, batteryPct = 21, floorPct = 20))
        assertTrue(ChargePolicy.manualOffLifted(manual = false, batteryPct = 20, floorPct = 20))
        assertTrue(ChargePolicy.manualOffLifted(manual = false, batteryPct = 3, floorPct = 20))
        assertFalse("unknown battery: keep the user's choice", ChargePolicy.manualOffLifted(false, null, 20))
        assertFalse(ChargePolicy.manualOffLifted(manual = true, batteryPct = 5, floorPct = 20))
        assertFalse(ChargePolicy.manualOffLifted(manual = null, batteryPct = 5, floorPct = 20))
    }

    @Test
    fun afterTheLiftOnlyHeatCanKeepChargingOff() {
        // The lifted override is null again: the limit (far above the floor) lets it charge.
        assertEquals(ChargeDecision(true, null), ChargePolicy.decide(heatActive = false, manual = null, limitStop = ChargePolicy.limitStop(true, 18, 60, 10)))
        assertEquals(ChargeDecision(false, StopReason.HEAT), ChargePolicy.decide(heatActive = true, manual = null, limitStop = false))
    }

    // --- persisted inputs (F07) --------------------------------------------------------------------

    @Test
    fun heatStopLimitBandAndManualSurviveARestart() {
        val prefs = MemoryStore()
        ChargeMemory(prefs).apply {
            heatStop = true
            limitStop = true
            manual = false
        }
        val restarted = ChargeMemory(prefs)
        assertTrue(restarted.heatStop)
        assertTrue(restarted.limitStop)
        assertEquals(false, restarted.manual)
        // Restored inputs: a battery at 43 °C (between resume 40 and stop 45) stays off for heat.
        assertEquals(ChargeDecision(false, StopReason.HEAT), ChargePolicy.decide(restarted.heatStop, null, restarted.limitStop))
        assertEquals(ChargeDecision(false, StopReason.LIMIT), ChargePolicy.decide(false, null, ChargePolicy.limitStop(restarted.limitStop, 55, 60, 10)))

        restarted.heatStop = false
        restarted.manual = null
        assertFalse(ChargeMemory(prefs).heatStop)
        assertNull(ChargeMemory(prefs).manual)
        assertFalse("never written: false", ChargeMemory(MemoryStore()).limitStop)
    }
}
