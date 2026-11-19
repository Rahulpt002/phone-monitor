package com.hotspotmonitor.agent.finder

import com.hotspotmonitor.agent.protocol.MetricStats
import com.hotspotmonitor.agent.protocol.SpeedRun
import com.hotspotmonitor.agent.protocol.SpeedSession
import com.hotspotmonitor.agent.protocol.round1
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Pure Finder arithmetic (no Android calls, unit-tested in FinderMathTest). */
object FinderMath {
    /** Walk-mode tone pitch at score 0 and at score 100 (plan 7.3 "metal detector"). */
    const val TONE_MIN_HZ = 300.0
    const val TONE_MAX_HZ = 1500.0

    /** Time from one beep start to the next at score 0 and at score 100. */
    const val BEEP_PERIOD_MAX_MS = 1000L
    const val BEEP_PERIOD_MIN_MS = 120L

    /** Length of one beep. */
    const val BEEP_LENGTH_MS = 60L

    /** Plan 7.4 step 4: speed tests between 20:00 and 22:00 phone local time are "peak hour". */
    const val PEAK_START_HOUR = 20
    const val PEAK_END_HOUR = 22

    private fun clampScore(score: Double): Double = if (score.isNaN()) 0.0 else score.coerceIn(0.0, 100.0)

    /** Tone pitch for [score]: 300 Hz at 0 rising linearly to 1500 Hz at 100. */
    fun toneFrequencyHz(score: Double): Double =
        TONE_MIN_HZ + (TONE_MAX_HZ - TONE_MIN_HZ) * clampScore(score) / 100.0

    /** Beep period for [score]: beeps come faster as the score rises (1000 ms at 0, 120 ms at 100). */
    fun beepPeriodMs(score: Double): Long =
        (BEEP_PERIOD_MAX_MS - (BEEP_PERIOD_MAX_MS - BEEP_PERIOD_MIN_MS) * clampScore(score) / 100.0).roundToLong()

    /**
     * avg / min / max / population standard deviation of [values], each rounded to one decimal like
     * the score. Non-finite values are ignored; null when nothing is left.
     */
    fun metricStats(values: List<Double>): MetricStats? {
        val v = values.filter { it.isFinite() }
        if (v.isEmpty()) return null
        val mean = v.average()
        val variance = v.sumOf { (it - mean) * (it - mean) } / v.size
        return MetricStats(
            avg = round1(mean),
            min = round1(v.min()),
            max = round1(v.max()),
            std = round1(sqrt(variance)),
            samples = v.size,
        )
    }

    /** Median (mean of the two middle values for an even count); null for an empty list. */
    fun median(values: List<Double>): Double? {
        val v = values.filter { it.isFinite() }.sorted()
        if (v.isEmpty()) return null
        val mid = v.size / 2
        return if (v.size % 2 == 1) v[mid] else (v[mid - 1] + v[mid]) / 2.0
    }

    /** Whether [epochMs] falls between 20:00 (inclusive) and 22:00 (exclusive) in [zone]. */
    fun isPeakHour(epochMs: Long, zone: ZoneId): Boolean =
        Instant.ofEpochMilli(epochMs).atZone(zone).hour in PEAK_START_HOUR until PEAK_END_HOUR

    /** Mean absolute difference between consecutive round trips (RFC 3550 style); null below 2 samples. */
    fun jitterMs(rttsMs: List<Double>): Double? {
        val v = rttsMs.filter { it.isFinite() }
        if (v.size < 2) return null
        return v.zipWithNext { a, b -> abs(b - a) }.average()
    }

    /** A speed-test session: runs plus the per-metric medians, rounded to one decimal. */
    fun buildSession(at: String, peakHour: Boolean, runs: List<SpeedRun>): SpeedSession = SpeedSession(
        at = at,
        peakHour = peakHour,
        runs = runs,
        downMbps = median(runs.mapNotNull { it.downMbps })?.let(::round1),
        upMbps = median(runs.mapNotNull { it.upMbps })?.let(::round1),
        pingMs = median(runs.mapNotNull { it.pingMs })?.let(::round1),
        jitterMs = median(runs.mapNotNull { it.jitterMs })?.let(::round1),
    )

    private val SPOT_NAME = Regex("""^Spot (\d{1,6})$""")

    /** "Spot N" with N one above the highest existing "Spot <n>" name (headless auto-naming). */
    fun nextSpotName(existingNames: Collection<String>): String {
        val highest = existingNames.mapNotNull { SPOT_NAME.matchEntire(it.trim())?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull()
        return "Spot ${maxOf(highest ?: 0, existingNames.size) + 1}"
    }

    private val PING_TIME = Regex("""time[=<]\s*([0-9]+(?:\.[0-9]+)?)\s*ms""")

    /** Round-trip times from `ping` output lines ("64 bytes from 1.1.1.1: icmp_seq=1 ttl=57 time=23.4 ms"). */
    fun parsePingRtts(output: String): List<Double> =
        output.lineSequence().mapNotNull { line -> PING_TIME.find(line)?.groupValues?.get(1)?.toDoubleOrNull() }.toList()

    /** Bits per second -> megabits per second, rounded to one decimal. */
    fun mbps(bytes: Long, millis: Long): Double? {
        if (bytes <= 0 || millis <= 0) return null
        return round1(bytes * 8.0 / (millis / 1000.0) / 1_000_000.0)
    }
}

/**
 * Throughput of one transfer. Records the cumulative byte count over time and measures after a
 * short warm-up (TCP slow start, TLS, radio ramp-up): the measurement starts at the first sample
 * that is [warmupMs] after the first one, or that has [warmupFraction] of the final byte count,
 * whichever comes first. If too little remains after the warm-up, the whole transfer is used.
 * Timestamps are any monotonic milliseconds. Not thread-safe: record from one thread.
 */
class ThroughputMeter(
    private val warmupMs: Long = 1_000,
    private val warmupFraction: Double = 0.2,
    private val minWindowMs: Long = 250,
) {
    private val times = ArrayList<Long>()
    private val totals = ArrayList<Long>()

    val totalBytes: Long get() = totals.lastOrNull() ?: 0

    val lastAtMs: Long? get() = times.lastOrNull()

    fun reset() {
        times.clear()
        totals.clear()
    }

    /** [cumulativeBytes] transferred so far at [atMs]. */
    fun record(cumulativeBytes: Long, atMs: Long) {
        times.add(atMs)
        totals.add(cumulativeBytes)
    }

    /** Megabits per second, measured up to [endMs] (default: the last sample). Null when unmeasurable. */
    fun mbps(endMs: Long? = null): Double? {
        if (times.isEmpty()) return null
        val end = endMs ?: times.last()
        val total = totals.last()
        val t0 = times.first()
        var start = 0
        for (i in times.indices) {
            if (times[i] - t0 >= warmupMs || totals[i] >= warmupFraction * total) {
                start = i
                break
            }
        }
        if (end - times[start] < minWindowMs || total - totals[start] <= 0) start = 0
        val bytes = total - totals[start]
        val millis = end - times[start]
        if (bytes > 0 && millis > 0) return FinderMath.mbps(bytes, millis)
        return null
    }
}
