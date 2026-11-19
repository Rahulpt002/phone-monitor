package com.hotspotmonitor.agent.watchdog

import com.hotspotmonitor.agent.protocol.UsbFunction
import com.hotspotmonitor.agent.protocol.WatchdogSettings
import com.hotspotmonitor.agent.protocol.WeeklyReboot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

class HeatAndPowerRulesTest {
    private val s = WatchdogSettings() // warn 40, stop 45, resume 40, rest 50, rest 600 s

    // --- heat -----------------------------------------------------------------------------------

    @Test
    fun warningWithHysteresisAndNoEventSpam() {
        val rule = HeatRule()
        assertTrue(rule.evaluate(39.9, s, canRestRadio = true).isEmpty())
        assertEquals(listOf(HeatSignal.Level(HeatLevel.NORMAL, HeatLevel.WARNING)), rule.evaluate(40.0, s, true))
        assertTrue(rule.evaluate(39.9, s, true).isEmpty())
        assertTrue(rule.evaluate(40.1, s, true).isEmpty())
        assertTrue(rule.evaluate(39.1, s, true).isEmpty())
        assertEquals(listOf(HeatSignal.Level(HeatLevel.WARNING, HeatLevel.NORMAL)), rule.evaluate(38.9, s, true))
        assertFalse(rule.heatStop)
    }

    @Test
    fun heatStopsChargingUntilBelowTheResumeTemperature() {
        val rule = HeatRule()
        rule.evaluate(41.0, s, true)
        assertEquals(
            listOf(HeatSignal.StopCharging, HeatSignal.Level(HeatLevel.WARNING, HeatLevel.CRITICAL)),
            rule.evaluate(45.0, s, true),
        )
        assertTrue(rule.heatStop)
        assertTrue("stays stopped between resume and stop", rule.evaluate(42.0, s, true).isEmpty())
        assertTrue(rule.evaluate(40.0, s, true).isEmpty())
        assertEquals(
            listOf(HeatSignal.ResumeCharging, HeatSignal.Level(HeatLevel.CRITICAL, HeatLevel.NORMAL)),
            rule.evaluate(39.9, s, true),
        )
        assertFalse(rule.heatStop)
    }

    @Test
    fun jumpingStraightToCriticalEmitsOneLevelChange() {
        val rule = HeatRule()
        assertEquals(
            listOf(HeatSignal.StopCharging, HeatSignal.Level(HeatLevel.NORMAL, HeatLevel.CRITICAL)),
            rule.evaluate(46.0, s, true),
        )
        assertTrue(rule.evaluate(46.5, s, true).isEmpty())
    }

    @Test
    fun radioRestAtTheRestTemperatureWhenAllowed() {
        val rule = HeatRule()
        val signals = rule.evaluate(50.0, s, canRestRadio = true)
        assertTrue(HeatSignal.RestRadio in signals)
        assertTrue(HeatSignal.StopCharging in signals)
        assertFalse(HeatSignal.RestRadio in rule.evaluate(51.0, s, canRestRadio = false))
        assertFalse(HeatSignal.RestRadio in rule.evaluate(49.9, s, canRestRadio = true))
    }

    @Test
    fun radioRestCooldown() {
        assertTrue(RadioRestPolicy.allowed(nowMs = 0, lastRestEndedMs = null, radioRestS = 600, resting = false))
        assertFalse(RadioRestPolicy.allowed(nowMs = 0, lastRestEndedMs = null, radioRestS = 600, resting = true))
        assertFalse(RadioRestPolicy.allowed(nowMs = 599_999, lastRestEndedMs = 0, radioRestS = 600, resting = false))
        assertTrue(RadioRestPolicy.allowed(nowMs = 600_000, lastRestEndedMs = 0, radioRestS = 600, resting = false))
    }

    @Test
    fun loweredTestThresholdsApplyImmediately() {
        val rule = HeatRule()
        val test = WatchdogSettings(tempWarnC = 30.0, tempStopChargeC = 33.0, tempResumeC = 31.0, tempRadioRestC = 35.0)
        assertTrue(rule.evaluate(32.0, s, true).isEmpty())
        assertEquals(listOf(HeatSignal.Level(HeatLevel.NORMAL, HeatLevel.WARNING)), rule.evaluate(32.0, test, true))
        assertTrue(HeatSignal.StopCharging in rule.evaluate(33.0, test, true))
        assertTrue(HeatSignal.ResumeCharging in rule.evaluate(30.9, test, true))
    }

    // --- weak power -----------------------------------------------------------------------------

    private val minute = 60_000L

    @Test
    fun weakPowerAfterAFullFallingWindowOncePerEpisode() {
        val d = WeakPowerDetector()
        var alert: WeakPowerAlert? = null
        // Battery falls 1 % every 10 min while plugged in and allowed to charge; current says draining.
        for (m in 0..30) {
            val a = d.observe(m * minute, pluggedIn = true, chargingAllowed = true, pct = 60 - m / 10, currentMa = -250, windowMin = 30)
            if (m < 30) assertNull("no alert before a full window (minute $m)", a) else alert = a
        }
        assertEquals(WeakPowerAlert(fromPct = 60, toPct = 57, minutes = 30, avgCurrentMa = -250), alert)
        assertTrue(d.inEpisode)
        for (m in 31..90) {
            assertNull(d.observe(m * minute, true, true, 57 - (m - 30) / 10, -250, 30))
        }
        // Recovering ends the episode; a new one needs another full falling window.
        assertNull(d.observe(91 * minute, true, true, 55, 300, 30))
        assertFalse(d.inEpisode)
        for (m in 92..120) assertNull(d.observe(m * minute, true, true, 54, -100, 30))
        assertNotNull(d.observe(121 * minute, true, true, 53, -100, 30))
    }

    @Test
    fun noWeakPowerWhenTheAgentStoppedChargingOrUnplugged() {
        val d = WeakPowerDetector()
        for (m in 0..40) {
            // The charge limit stopped charging: a falling battery is expected.
            assertNull(d.observe(m * minute, pluggedIn = true, chargingAllowed = false, pct = 60 - m / 5, currentMa = -400, windowMin = 30))
        }
        for (m in 41..80) {
            assertNull(d.observe(m * minute, pluggedIn = false, chargingAllowed = true, pct = 50 - (m - 40) / 5, currentMa = -400, windowMin = 30))
        }
    }

    @Test
    fun currentIsASecondSignal() {
        val d = WeakPowerDetector()
        var last: WeakPowerAlert? = null
        for (m in 0..30) {
            // Percent dips (e.g. a recalibration) but the current shows charging.
            last = d.observe(m * minute, true, true, if (m < 15) 60 else 59, currentMa = 500, windowMin = 30)
        }
        assertNull(last)
        // Without current readings the percent alone decides.
        val noCurrent = WeakPowerDetector()
        for (m in 0..30) last = noCurrent.observe(m * minute, true, true, if (m < 15) 60 else 59, currentMa = null, windowMin = 30)
        assertEquals(WeakPowerAlert(60, 59, 30, null), last)
    }

    @Test
    fun steadyOrRisingBatteryIsFine() {
        val d = WeakPowerDetector()
        for (m in 0..60) assertNull(d.observe(m * minute, true, true, 58, -20, 30))
        val rising = WeakPowerDetector()
        for (m in 0..60) assertNull(rising.observe(m * minute, true, true, 50 + m / 10, 400, 30))
    }

    // --- tethering ------------------------------------------------------------------------------

    @Test
    fun tetherFixAfterTheDelayWithBackoff() {
        val r = TetherFixRule()
        assertFalse(r.observe(0, usbConnected = true, tetherOn = false, suppressed = false, fixAfterS = 20))
        assertFalse(r.observe(19_999, true, false, false, 20))
        assertTrue(r.observe(20_000, true, false, false, 20))
        r.onAttempt(20_000, 20)
        assertEquals(1, r.attempts)
        assertFalse(r.observe(39_999, true, false, false, 20))
        assertTrue(r.observe(40_000, true, false, false, 20))
        r.onAttempt(40_000, 20)
        assertFalse("second retry waits 2 x 20 s", r.observe(79_999, true, false, false, 20))
        assertTrue(r.observe(80_000, true, false, false, 20))
        // Tethering came up: everything resets.
        assertFalse(r.observe(81_000, true, true, false, 20))
        assertEquals(0, r.attempts)
        assertFalse(r.observe(82_000, true, false, false, 20))
        assertTrue(r.observe(102_000, true, false, false, 20))
    }

    @Test
    fun tetherFixRespectsSuppressionAndUsbDisconnect() {
        val r = TetherFixRule()
        r.observe(0, true, false, suppressed = false, fixAfterS = 20)
        assertFalse(r.observe(30_000, true, false, suppressed = true, fixAfterS = 20))
        // After the suppression ends the full delay applies again.
        assertFalse(r.observe(31_000, true, false, suppressed = false, fixAfterS = 20))
        assertTrue(r.observe(51_000, true, false, suppressed = false, fixAfterS = 20))
        assertFalse(r.observe(52_000, usbConnected = false, tetherOn = false, suppressed = false, fixAfterS = 20))
        assertFalse(r.observe(53_000, true, false, false, 20))
    }

    // --- weekly reboot --------------------------------------------------------------------------

    @Test
    fun weeklyRebootWindowInLocalTime() {
        val cfg = WeeklyReboot(enabled = true, day = 0, time = "04:00") // Sunday 04:00
        val sunday = LocalDateTime.of(2026, 11, 22, 4, 0)
        assertEquals("2026-11-22", WeeklySchedule.dueOccurrence(sunday, cfg, null))
        assertEquals("2026-11-22", WeeklySchedule.dueOccurrence(sunday.plusMinutes(14).plusSeconds(59), cfg, "2026-11-15"))
        assertNull(WeeklySchedule.dueOccurrence(sunday.plusMinutes(15), cfg, null))
        assertNull(WeeklySchedule.dueOccurrence(sunday.minusSeconds(1), cfg, null))
        assertNull("once per week", WeeklySchedule.dueOccurrence(sunday.plusMinutes(1), cfg, "2026-11-22"))
        assertNull("Monday", WeeklySchedule.dueOccurrence(sunday.plusDays(1), cfg, null))
        assertNull("disabled", WeeklySchedule.dueOccurrence(sunday, cfg.copy(enabled = false), null))
        assertEquals("2026-11-28", WeeklySchedule.dueOccurrence(LocalDateTime.of(2026, 11, 28, 23, 50), cfg.copy(day = 6, time = "23:45"), null))
    }

    @Test
    fun weeklyRebootTimeParsing() {
        assertEquals(LocalTime.of(4, 0), WeeklySchedule.parseTime("04:00"))
        assertEquals(LocalTime.of(4, 5), WeeklySchedule.parseTime(" 4:05 "))
        assertNull(WeeklySchedule.parseTime("4am"))
        assertNull(WeeklySchedule.parseTime("25:00"))
        assertNull(WeeklySchedule.parseTime("04:60"))
        assertNull(WeeklySchedule.dueOccurrence(LocalDateTime.of(2026, 11, 22, 4, 0), WeeklyReboot(true, 0, "bad"), null))
    }

    // --- restored heat state (F07) -----------------------------------------------------------------

    @Test
    fun restoredHeatStopDoesNotResumeOrRepeatAlertsInsideTheBand() {
        // Before the restart: 45 °C stopped charging and raised temp_critical.
        val before = HeatRule()
        assertTrue(HeatSignal.StopCharging in before.evaluate(45.0, s, canRestRadio = false))
        // A fresh rule (the old behaviour) at 43 °C: no stop signal, and a duplicate warning.
        val fresh = HeatRule()
        assertFalse(HeatSignal.StopCharging in fresh.evaluate(43.0, s, false))
        assertFalse(fresh.heatStop)

        val restored = HeatRule(heatStop = true, level = HeatLevel.CRITICAL)
        assertTrue("no resume, no repeated alert at 43 °C", restored.evaluate(43.0, s, false).isEmpty())
        assertTrue(restored.heatStop)
        assertEquals(
            listOf(HeatSignal.ResumeCharging, HeatSignal.Level(HeatLevel.CRITICAL, HeatLevel.NORMAL)),
            restored.evaluate(39.9, s, false),
        )
        assertEquals("level follows the stop by default", HeatLevel.CRITICAL, HeatRule(heatStop = true).level)
    }

    // --- tether fix function (F28) -----------------------------------------------------------------

    @Test
    fun tetherFixRestoresTheFunctionLastSeenUp() {
        assertEquals("ncm", TetherFunction.seen(tetherOn = true, function = UsbFunction.NCM))
        assertEquals("rndis", TetherFunction.seen(tetherOn = true, function = UsbFunction.RNDIS))
        assertNull("off: nothing learned", TetherFunction.seen(tetherOn = false, function = UsbFunction.NCM))
        assertNull(TetherFunction.seen(tetherOn = true, function = UsbFunction.MTP))
        assertNull(TetherFunction.seen(tetherOn = null, function = null))
    }

    // --- recovery steps beside the tick (F29) ------------------------------------------------------

    @Test
    fun heatAndChargeRulesKeepTickingWhileARecoveryStepRuns() = runTest {
        val steps = StepRunner { _, _ -> }
        var ticks = 0
        val tickJob = backgroundScope.launch {
            while (true) {
                ticks++ // heat + charge limit, evaluated every tick
                if (!steps.active) {
                    // airplane_cycle with airplane_off_s = 600
                    steps.launch(backgroundScope, "airplane_cycle") { delay(600_000) }
                }
                delay(5_000)
            }
        }
        runCurrent()
        assertTrue(steps.active)
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue("the step is still running", steps.active)
        assertEquals("ticks kept running every 5 s", 13, ticks)
        assertFalse("one step at a time", steps.launch(backgroundScope, "reboot") {})
        advanceTimeBy(540_000)
        runCurrent()
        assertEquals(121, ticks)
        assertTrue("a new step starts once the first one finished", steps.active)
        tickJob.cancel()
    }

    @Test
    fun aFailingStepIsReportedAndFreesTheRunner() = runTest {
        val errors = mutableListOf<String>()
        val steps = StepRunner { name, e -> errors += "$name: ${e.message}" }
        assertTrue(steps.launch(backgroundScope, "data_cycle") { error("boom") })
        runCurrent()
        assertEquals(listOf("data_cycle: boom"), errors)
        assertFalse(steps.active)
    }
}
