package com.hotspotmonitor.agent.finder

import com.hotspotmonitor.agent.protocol.SpeedRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class FinderMathTest {
    @Test
    fun toneFrequencyRisesLinearlyFrom300To1500Hz() {
        assertEquals(300.0, FinderMath.toneFrequencyHz(0.0), 1e-9)
        assertEquals(900.0, FinderMath.toneFrequencyHz(50.0), 1e-9)
        assertEquals(1500.0, FinderMath.toneFrequencyHz(100.0), 1e-9)
        assertEquals(300.0, FinderMath.toneFrequencyHz(-20.0), 1e-9)
        assertEquals(1500.0, FinderMath.toneFrequencyHz(130.0), 1e-9)
        assertEquals(300.0, FinderMath.toneFrequencyHz(Double.NaN), 1e-9)
        var previous = 0.0
        for (s in 0..100) {
            val f = FinderMath.toneFrequencyHz(s.toDouble())
            assertTrue("pitch rises at $s", f > previous)
            previous = f
        }
    }

    @Test
    fun beepsComeFasterAsTheScoreRises() {
        assertEquals(1000L, FinderMath.beepPeriodMs(0.0))
        assertEquals(560L, FinderMath.beepPeriodMs(50.0))
        assertEquals(120L, FinderMath.beepPeriodMs(100.0))
        assertEquals(120L, FinderMath.beepPeriodMs(250.0))
        assertTrue(FinderMath.beepPeriodMs(80.0) < FinderMath.beepPeriodMs(20.0))
        assertTrue(FinderMath.beepPeriodMs(100.0) > FinderMath.BEEP_LENGTH_MS)
    }

    @Test
    fun metricStatsUsesThePopulationStandardDeviation() {
        val s = FinderMath.metricStats(listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0))!!
        assertEquals(5.0, s.avg, 0.0)
        assertEquals(2.0, s.min, 0.0)
        assertEquals(9.0, s.max, 0.0)
        assertEquals(2.0, s.std, 0.0)
        assertEquals(8, s.samples)
    }

    @Test
    fun metricStatsRoundsToOneDecimalAndSkipsNonFinite() {
        val s = FinderMath.metricStats(listOf(-91.0, -92.0, -94.0, Double.NaN, Double.POSITIVE_INFINITY))!!
        assertEquals(-92.3, s.avg, 0.0) // -92.333
        assertEquals(-94.0, s.min, 0.0)
        assertEquals(-91.0, s.max, 0.0)
        assertEquals(1.2, s.std, 0.0) // 1.247
        assertEquals(3, s.samples)
        val single = FinderMath.metricStats(listOf(61.0))!!
        assertEquals(0.0, single.std, 0.0)
        assertNull(FinderMath.metricStats(emptyList()))
        assertNull(FinderMath.metricStats(listOf(Double.NaN)))
    }

    @Test
    fun medianOfOddAndEvenCounts() {
        assertEquals(2.0, FinderMath.median(listOf(3.0, 1.0, 2.0))!!, 0.0)
        assertEquals(2.5, FinderMath.median(listOf(4.0, 1.0, 3.0, 2.0))!!, 0.0)
        assertEquals(7.0, FinderMath.median(listOf(7.0))!!, 0.0)
        assertNull(FinderMath.median(emptyList()))
    }

    @Test
    fun sessionUsesMediansOfTheRunsThatMeasuredSomething() {
        val runs = listOf(
            SpeedRun(downMbps = 42.1, upMbps = 8.2, pingMs = 41.0, jitterMs = 5.0, bytes = 12_500_000, at = "a"),
            SpeedRun(downMbps = 39.8, upMbps = null, pingMs = 44.0, jitterMs = 6.0, bytes = 10_000_000, at = "b"),
            SpeedRun(downMbps = 44.0, upMbps = 8.6, pingMs = 40.0, jitterMs = 4.0, bytes = 12_500_000, at = "c"),
        )
        val s = FinderMath.buildSession("2026-11-19T15:00:00.000Z", peakHour = true, runs = runs)
        assertEquals(42.1, s.downMbps!!, 0.0)
        assertEquals(8.4, s.upMbps!!, 0.0) // median of 8.2 and 8.6
        assertEquals(41.0, s.pingMs!!, 0.0)
        assertEquals(5.0, s.jitterMs!!, 0.0)
        assertTrue(s.peakHour)
        assertEquals(3, s.runs.size)
        val empty = FinderMath.buildSession("t", false, listOf(SpeedRun(bytes = 0, at = "t")))
        assertNull(empty.downMbps)
        assertNull(empty.pingMs)
    }

    @Test
    fun peakHourIsTwentyToTwentyTwoLocalTime() {
        val kolkata = ZoneId.of("Asia/Kolkata")
        fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 11, 19, h, m, 0, 0, kolkata).toInstant().toEpochMilli()
        assertFalse(FinderMath.isPeakHour(at(19, 59), kolkata))
        assertTrue(FinderMath.isPeakHour(at(20, 0), kolkata))
        assertTrue(FinderMath.isPeakHour(at(21, 59), kolkata))
        assertFalse(FinderMath.isPeakHour(at(22, 0), kolkata))
        // The same instant is not peak hour in UTC (14:30).
        assertFalse(FinderMath.isPeakHour(at(20, 0), ZoneId.of("UTC")))
    }

    @Test
    fun jitterIsTheMeanAbsoluteDifferenceOfConsecutivePings() {
        assertEquals(7.5, FinderMath.jitterMs(listOf(10.0, 20.0, 15.0))!!, 1e-9)
        assertEquals(0.0, FinderMath.jitterMs(listOf(30.0, 30.0))!!, 0.0)
        assertNull(FinderMath.jitterMs(listOf(30.0)))
    }

    @Test
    fun pingOutputIsParsed() {
        val out = """
            PING 1.1.1.1 (1.1.1.1) 56(84) bytes of data.
            64 bytes from 1.1.1.1: icmp_seq=1 ttl=57 time=23.4 ms
            64 bytes from 1.1.1.1: icmp_seq=2 ttl=57 time=31 ms
            64 bytes from 1.1.1.1: icmp_seq=3 ttl=57 time<1 ms
            Request timeout for icmp_seq 4

            --- 1.1.1.1 ping statistics ---
            4 packets transmitted, 3 received, 25% packet loss, time 602ms
            rtt min/avg/max/mdev = 0.900/18.100/31.000/12.000 ms
        """.trimIndent()
        assertEquals(listOf(23.4, 31.0, 1.0), FinderMath.parsePingRtts(out))
        assertTrue(FinderMath.parsePingRtts("connect: Network is unreachable").isEmpty())
    }

    @Test
    fun nextSpotNameCountsUpwards() {
        assertEquals("Spot 1", FinderMath.nextSpotName(emptyList()))
        assertEquals("Spot 3", FinderMath.nextSpotName(listOf("Roof NE", "Window")))
        assertEquals("Spot 6", FinderMath.nextSpotName(listOf("Spot 5", "Roof")))
        assertEquals("Spot 4", FinderMath.nextSpotName(listOf("Spot 1", "Spot 3", "Balcony")))
    }

    @Test
    fun throughputIsMeasuredAfterTheWarmUp() {
        // 1 s of slow start at 1 Mbit/s, then 4 s at 40 Mbit/s (5 MB/s), samples every 100 ms.
        val meter = ThroughputMeter(warmupMs = 1_000, warmupFraction = 0.9)
        var total = 0L
        var t = 0L
        meter.record(0, t)
        repeat(10) {
            t += 100
            total += 12_500
            meter.record(total, t)
        }
        repeat(40) {
            t += 100
            total += 500_000
            meter.record(total, t)
        }
        assertEquals(40.0, meter.mbps()!!, 0.05)
        assertEquals(total, meter.totalBytes)
    }

    @Test
    fun throughputFallsBackToTheWholeTransferWhenItIsShort() {
        val meter = ThroughputMeter()
        meter.record(0, 0)
        meter.record(1_000_000, 100) // whole transfer in 100 ms: 80 Mbit/s
        assertEquals(80.0, meter.mbps()!!, 0.0)
        val empty = ThroughputMeter()
        assertNull(empty.mbps())
        empty.record(500, 10)
        assertNull(empty.mbps())
    }

    @Test
    fun throughputUsesTheGivenEndTime() {
        // Upload: bytes are handed to the socket quickly, the response arrives later.
        val meter = ThroughputMeter(warmupMs = 1_000, warmupFraction = 0.2, minWindowMs = 1)
        meter.record(0, 0)
        meter.record(250_000, 10)
        meter.record(1_000_000, 50)
        assertNotNull(meter.mbps())
        // From the warm-up sample (250 kB at 10 ms) to the response at 1010 ms: 750 kB in 1 s.
        assertEquals(6.0, meter.mbps(endMs = 1_010)!!, 0.0)
        meter.reset()
        assertEquals(0L, meter.totalBytes)
    }

    @Test
    fun megabitsPerSecond() {
        assertEquals(8.0, FinderMath.mbps(1_000_000, 1_000)!!, 0.0)
        assertNull(FinderMath.mbps(0, 1_000))
        assertNull(FinderMath.mbps(1_000, 0))
    }
}
