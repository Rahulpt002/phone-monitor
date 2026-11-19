@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.hotspotmonitor.agent.sync

import com.hotspotmonitor.agent.core.Clock
import com.hotspotmonitor.agent.core.OutboxStore
import com.hotspotmonitor.agent.protocol.NetInfo
import com.hotspotmonitor.agent.protocol.TelemetryMsg
import com.hotspotmonitor.agent.protocol.TelemetryTier
import com.hotspotmonitor.agent.testutil.FakeOutboxDao
import com.hotspotmonitor.agent.testutil.testLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TelemetrySchedulerTest {
    private class Harness(scope: TestScope, val collector: (TelemetryTier, VirtualClock) -> TelemetryMsg?) {
        val clock = VirtualClock(scope.testScheduler)
        val dao = FakeOutboxDao()
        val outbox = OutboxStore(dao, clock, testLog(), scope.backgroundScope)
        val collected = mutableListOf<TelemetryTier>()
        var kicks = 0
        val scheduler = TelemetryScheduler(
            clock, testLog(), outbox,
            collector = object : TierCollector {
                override suspend fun collect(tier: TelemetryTier): TelemetryMsg? {
                    collected += tier
                    return collector(tier, clock)
                }
            },
            onEnqueued = { kicks++ },
        )
        val fast = MutableStateFlow(60)
        val slow = MutableStateFlow(60)

        fun count(tier: TelemetryTier) = collected.count { it == tier }
    }

    private fun reading(tier: TelemetryTier, clock: Clock) =
        TelemetryMsg(ts = Clock.formatIso(clock.systemNowMs()), tier = tier, net = NetInfo(online = true))

    @Test
    fun tiersRunAtTheirIntervalsAndAFasterRateAppliesImmediately() = runTest {
        val h = Harness(this) { tier, clock -> reading(tier, clock) }
        h.scheduler.start(backgroundScope, h.fast, h.slow)
        runCurrent()
        assertEquals("both tiers right at start", 1, h.count(TelemetryTier.FAST))
        assertEquals(1, h.count(TelemetryTier.SLOW))

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, h.count(TelemetryTier.FAST))

        // A Dashboard opens: fast 60 s -> 5 s. 30 s have passed, so a reading is due now.
        h.fast.value = 5
        runCurrent()
        assertEquals(2, h.count(TelemetryTier.FAST))

        advanceTimeBy(30_000) // t = 60 s: fast at 35, 40, ..., 60 and slow at 60
        runCurrent()
        assertEquals(8, h.count(TelemetryTier.FAST))
        assertEquals(2, h.count(TelemetryTier.SLOW))
        assertEquals(10, h.dao.all.size)
        assertEquals(10, h.kicks)
        assertEquals(setOf("fast", "slow"), h.dao.all.take(2).map { it.tier }.toSet())
    }

    @Test
    fun aSlowerRateWaitsFromTheLastReading() = runTest {
        val h = Harness(this) { tier, clock -> reading(tier, clock) }
        h.fast.value = 5
        h.scheduler.start(backgroundScope, h.fast, h.slow)
        runCurrent()
        advanceTimeBy(2_000)
        h.fast.value = 60 // Dashboard closed at t = 2 s
        runCurrent()
        assertEquals(1, h.count(TelemetryTier.FAST))
        advanceTimeBy(57_000) // t = 59 s
        runCurrent()
        assertEquals(1, h.count(TelemetryTier.FAST))
        advanceTimeBy(1_000) // t = 60 s
        runCurrent()
        assertEquals(2, h.count(TelemetryTier.FAST))
    }

    @Test
    fun explicitPublishGoesStaticSlowFastAndIsTracked() = runTest {
        val h = Harness(this) { tier, clock -> reading(tier, clock) }
        h.scheduler.publish(setOf(TelemetryTier.FAST, TelemetryTier.STATIC, TelemetryTier.SLOW))
        assertEquals(listOf(TelemetryTier.STATIC, TelemetryTier.SLOW, TelemetryTier.FAST), h.collected)
        assertEquals(listOf("static", "slow", "fast"), h.dao.all.map { it.tier })
        assertEquals(0L, h.scheduler.msSinceLastAttempt(TelemetryTier.STATIC))
        advanceTimeBy(1_500)
        assertEquals(1_500L, h.scheduler.msSinceLastAttempt(TelemetryTier.STATIC))
    }

    @Test
    fun aFailingOrEmptyCollectorNeitherBusyLoopsNorEnqueues() = runTest {
        val h = Harness(this) { tier, _ ->
            if (tier == TelemetryTier.FAST) error("radio unavailable") else null
        }
        h.scheduler.start(backgroundScope, h.fast, h.slow)
        runCurrent()
        advanceTimeBy(59_000)
        runCurrent()
        assertEquals(1, h.count(TelemetryTier.FAST))
        assertEquals(1, h.count(TelemetryTier.SLOW))
        assertEquals(0, h.dao.all.size)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, h.count(TelemetryTier.FAST))
    }
}
