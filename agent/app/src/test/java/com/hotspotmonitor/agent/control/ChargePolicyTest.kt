package com.hotspotmonitor.agent.control

import com.hotspotmonitor.agent.protocol.StopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
