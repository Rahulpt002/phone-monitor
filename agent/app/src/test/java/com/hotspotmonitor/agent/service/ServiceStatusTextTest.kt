package com.hotspotmonitor.agent.service

import com.hotspotmonitor.agent.core.ConnPhase
import com.hotspotmonitor.agent.core.ConnState
import com.hotspotmonitor.agent.protocol.BatteryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceStatusTextTest {
    @Test
    fun fullLine() {
        assertEquals(
            "Connected · score 62.3 · battery 58% (charging)",
            ServiceStatusText.line(ConnState(ConnPhase.CONNECTED), 62.34, 58, BatteryStatus.CHARGING),
        )
    }

    @Test
    fun missingValuesAreLeftOut() {
        assertEquals("Offline", ServiceStatusText.line(ConnState(ConnPhase.WAITING), null, null, null))
        assertEquals("Not paired · battery 61%", ServiceStatusText.line(ConnState(ConnPhase.NOT_PAIRED), Double.NaN, 61, BatteryStatus.DISCHARGING))
    }

    @Test
    fun everyPhaseHasALabel() {
        ConnPhase.entries.forEach { assertTrue(ServiceStatusText.linkLabel(it).isNotBlank()) }
        assertEquals("Pairing revoked", ServiceStatusText.linkLabel(ConnPhase.UNAUTHORIZED))
    }
}
