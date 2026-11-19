package com.hotspotmonitor.agent.watchdog

import com.hotspotmonitor.agent.protocol.WatchdogSettings
import com.hotspotmonitor.agent.testutil.TestClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Drives the ladder with a fake monotonic clock (TestClock.elapsed), default thresholds 90/180/600/3600 s. */
class InternetLadderTest {
    private val s = WatchdogSettings()
    private val clock = TestClock(elapsed = 1_000_000)
    private val ladder = InternetLadder()

    private fun at(seconds: Long): Long = 1_000_000 + seconds * 1000

    @Test
    fun fullLadderWithRebootRetry() {
        ladder.observe(at(0), online = false)
        assertNull(ladder.due(at(89), s))
        assertEquals(LadderStep.DATA_CYCLE, ladder.due(at(90), s))
        ladder.onStepDone(LadderStep.DATA_CYCLE, at(90))
        assertNull("each step once per outage", ladder.due(at(100), s))
        assertNull(ladder.due(at(179), s))
        assertEquals(LadderStep.AIRPLANE_CYCLE, ladder.due(at(180), s))
        ladder.onStepDone(LadderStep.AIRPLANE_CYCLE, at(180))
        assertNull(ladder.due(at(599), s))
        assertEquals(LadderStep.REBOOT, ladder.due(at(600), s))
        // The reboot limit blocked it: retried every reboot_retry_s.
        ladder.onStepDone(LadderStep.REBOOT, at(600), rebootStarted = false)
        assertEquals(3, ladder.step)
        assertNull(ladder.due(at(600 + 3599), s))
        assertEquals(LadderStep.REBOOT, ladder.due(at(600 + 3600), s))
        ladder.onStepDone(LadderStep.REBOOT, at(600 + 3600), rebootStarted = false)
        assertNull(ladder.due(at(600 + 3600 + 10), s))

        val recovery = ladder.observe(at(5000), online = true)
        assertEquals(Recovery(LadderStep.REBOOT, 5000_000), recovery)
        assertEquals(0, ladder.step)
        assertNull(ladder.downSinceMs)
        assertNull(ladder.due(at(6000), s))
    }

    @Test
    fun recoveryAfterTheDataCycleNamesThatStep() {
        ladder.observe(at(0), online = false)
        ladder.observe(at(45), online = false)
        assertEquals(at(0), ladder.downSinceMs)
        ladder.onStepDone(ladder.due(at(90), s)!!, at(90))
        assertEquals(Recovery(LadderStep.DATA_CYCLE, 100_000), ladder.observe(at(100), online = true))
    }

    @Test
    fun shortOutagesNeedNoStepAndReportNothing() {
        ladder.observe(at(0), online = false)
        assertNull(ladder.observe(at(30), online = true))
        assertNull(ladder.observe(at(31), online = true))
        assertNull(ladder.downSinceMs)
    }

    @Test
    fun stepsRunInOrderEvenWhenLaterThresholdsHavePassed() {
        // e.g. the Agent restarted in the middle of a long outage, or thresholds were lowered.
        ladder.observe(at(0), online = false)
        val now = at(700)
        val order = mutableListOf<LadderStep>()
        while (true) {
            val step = ladder.due(now, s) ?: break
            order += step
            ladder.onStepDone(step, now)
        }
        assertEquals(listOf(LadderStep.DATA_CYCLE, LadderStep.AIRPLANE_CYCLE, LadderStep.REBOOT), order)
    }

    @Test
    fun thresholdsAreReadOnEveryCall() {
        val fast = WatchdogSettings(internetFailS = 10, airplaneAfterS = 20, rebootAfterS = 40, rebootRetryS = 60)
        ladder.observe(at(0), online = false)
        assertNull(ladder.due(at(10), s))
        assertEquals(LadderStep.DATA_CYCLE, ladder.due(at(10), fast))
        ladder.onStepDone(LadderStep.DATA_CYCLE, at(10))
        assertEquals(LadderStep.AIRPLANE_CYCLE, ladder.due(at(20), fast))
        ladder.onStepDone(LadderStep.AIRPLANE_CYCLE, at(20))
        assertEquals(LadderStep.REBOOT, ladder.due(at(40), fast))
    }

    @Test
    fun aStartedRebootIsNotRetried() {
        ladder.observe(at(0), online = false)
        ladder.onStepDone(LadderStep.REBOOT, at(600), rebootStarted = true)
        assertNull(ladder.due(at(600 + 10_000), s))
    }

    @Test
    fun simulatedOutageUsesTheSameClock() {
        // The watchdog feeds `online = false` while a simulation runs; timing is identical.
        clock.elapsed = at(0)
        ladder.observe(clock.elapsedMs(), online = false)
        clock.elapsed = at(90)
        assertEquals(LadderStep.DATA_CYCLE, ladder.due(clock.elapsedMs(), s))
        assertEquals(90_000L, ladder.downForMs(clock.elapsedMs()))
    }
}
