package com.hotspotmonitor.agent.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min
import kotlin.random.Random

class BackoffTest {
    @Test
    fun doublesFromTwoSecondsUpToSixty() {
        val b = Backoff(jitter = 0.0)
        val delays = List(8) { b.nextDelayMs() }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L, 60_000L), delays)
        assertEquals(8, b.attempt)
    }

    @Test
    fun resetStartsOver() {
        val b = Backoff(jitter = 0.0)
        repeat(5) { b.nextDelayMs() }
        b.reset()
        assertEquals(0, b.attempt)
        assertEquals(2_000L, b.nextDelayMs())
    }

    @Test
    fun jitterStaysWithinTwentyPercentAndUnderTheCap() {
        val random = Random(42)
        repeat(200) {
            val b = Backoff(jitter = 0.2, random = random)
            var nominal = 2_000L
            repeat(8) {
                val d = b.nextDelayMs()
                assertTrue("$d vs $nominal", d >= (nominal * 0.8).toLong())
                assertTrue("$d vs $nominal", d <= min(60_000L, (nominal * 1.2).toLong() + 1))
                nominal = min(60_000L, nominal * 2)
            }
        }
    }

    @Test
    fun jitterActuallySpreadsTheDelays() {
        val random = Random(1)
        val firsts = List(50) { Backoff(jitter = 0.2, random = random).nextDelayMs() }.toSet()
        assertTrue(firsts.size > 10)
    }

    @Test
    fun manyAttemptsDoNotOverflow() {
        val b = Backoff(jitter = 0.0)
        repeat(10_000) { b.nextDelayMs() }
        assertEquals(60_000L, b.nextDelayMs())
    }
}
