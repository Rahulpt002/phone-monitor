package com.hotspotmonitor.agent.telemetry

import com.hotspotmonitor.agent.control.ChargeSwitchWrite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Times are elapsed ms; the charge switch cuts USB input (input_suspend, batt_slate_mode). */
class PowerEventFilterTest {
    @Test
    fun chargeLimitCycleOnAnInputCuttingSwitchRaisesNoPowerEvents() {
        val f = PowerEventFilter()
        // Battery reaches 60 %: the Agent writes 'off', Android reports the charger unplugged.
        val off = ChargeSwitchWrite(enabled = false, atMs = 1_000_000)
        assertFalse("not a power loss", f.onDisconnected(1_001_500, off, usbConnected = true))
        // Hours later, at 50 %: 'on', and the plug comes back.
        val on = ChargeSwitchWrite(enabled = true, atMs = 9_000_000)
        assertNull(f.check(8_000_000, off, plugged = false, usbConnected = true))
        assertFalse("not reported as power_connected either", f.onConnected(9_001_000, on))
        assertNull(f.check(9_015_000, on, plugged = true, usbConnected = true))
    }

    @Test
    fun aRealUnplugIsStillReported() {
        val f = PowerEventFilter()
        assertTrue("no recent switch write", f.onDisconnected(5_000_000, null, usbConnected = true))
        val oldOff = ChargeSwitchWrite(enabled = false, atMs = 1_000_000)
        assertTrue("an 'off' long ago does not explain it", f.onDisconnected(5_000_000, oldOff, usbConnected = true))
        val on = ChargeSwitchWrite(enabled = true, atMs = 4_999_000)
        assertTrue("charging was switched on, not off", f.onDisconnected(5_000_000, on, usbConnected = true))
        assertTrue(f.onConnected(6_000_000, on))
    }

    @Test
    fun aPowerCutDuringThePauseShowsAsTheUsbHostGoingAway() {
        val f = PowerEventFilter()
        val off = ChargeSwitchWrite(enabled = false, atMs = 1_000_000)
        assertFalse(f.onDisconnected(1_001_000, off, usbConnected = true))
        assertNull(f.check(1_015_000, off, plugged = false, usbConnected = true))
        // The indoor router (USB host and power) lost power.
        assertEquals(PowerEventFilter.Lost.USB_GONE, f.check(3_000_000, off, plugged = false, usbConnected = false))
        assertNull("reported once", f.check(3_015_000, off, plugged = false, usbConnected = false))
    }

    @Test
    fun noPlugAfterChargingResumedIsALoss() {
        val f = PowerEventFilter()
        val off = ChargeSwitchWrite(enabled = false, atMs = 1_000_000)
        assertFalse(f.onDisconnected(1_001_000, off, usbConnected = null)) // wall charger: no USB host
        val on = ChargeSwitchWrite(enabled = true, atMs = 9_000_000)
        assertNull("within the window", f.check(9_020_000, on, plugged = false, usbConnected = null))
        assertEquals(PowerEventFilter.Lost.NOT_BACK_AFTER_RESUME, f.check(9_031_000, on, plugged = false, usbConnected = null))
    }

    @Test
    fun plugBackWithoutABroadcastClearsThePendingLoss() {
        val f = PowerEventFilter()
        val off = ChargeSwitchWrite(enabled = false, atMs = 1_000_000)
        assertFalse(f.onDisconnected(1_001_000, off, usbConnected = true))
        val on = ChargeSwitchWrite(enabled = true, atMs = 2_000_000)
        assertNull(f.check(2_005_000, on, plugged = true, usbConnected = true))
        assertNull(f.check(2_100_000, on, plugged = false, usbConnected = false))
    }
}
