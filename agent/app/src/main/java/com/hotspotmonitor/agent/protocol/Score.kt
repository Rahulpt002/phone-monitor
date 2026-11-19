package com.hotspotmonitor.agent.protocol

import kotlin.math.floor

/*
 * Signal score (plan 7.2, docs/protocol.md section 4). Must match finder.ts `signalScore` exactly;
 * ScoreTest checks shared/fixtures/score-cases.json.
 */

data class ScoreWeights(val wSinr: Double, val wRsrp: Double, val wRsrq: Double) {
    companion object {
        val DEFAULT = ScoreWeights(wSinr = 0.45, wRsrp = 0.35, wRsrq = 0.2)
    }
}

private fun clamp01(v: Double): Double = v.coerceIn(0.0, 1.0)

/** JavaScript Math.round(x * 10) / 10 (half rounds up, also for negatives). */
fun round1(x: Double): Double = floor(x * 10 + 0.5) / 10

private fun Number?.finiteOrNull(): Double? = this?.toDouble()?.takeUnless { it.isNaN() }

/**
 * score = 100 * (w_sinr * n_sinr + w_rsrp * n_rsrp + w_rsrq * n_rsrq), rounded to 1 decimal.
 * Null when RSRP is missing; a missing SINR or RSRQ counts as 0 for its term.
 */
fun signalScore(rsrp: Number?, sinr: Number?, rsrq: Number?, w: ScoreWeights = ScoreWeights.DEFAULT): Double? {
    val p = rsrp.finiteOrNull() ?: return null
    val nRsrp = clamp01((p + 120) / 40)
    val nSinr = sinr.finiteOrNull()?.let { clamp01((it + 5) / 30) } ?: 0.0
    val nRsrq = rsrq.finiteOrNull()?.let { clamp01((it + 20) / 12) } ?: 0.0
    return round1(100 * (w.wSinr * nSinr + w.wRsrp * nRsrp + w.wRsrq * nRsrq))
}

/** The (rsrp, sinr, rsrq) a score is computed from for this reading. */
data class ScoreInputs(val rsrp: Double?, val sinr: Double?, val rsrq: Double?)

/**
 * Picks the measurements the score uses: NR_SA uses the SS-* values of the NR leg when present;
 * every other type (including NR_NSA, which scores its LTE anchor) uses the top-level values.
 */
fun RadioInfo.scoreInputs(): ScoreInputs {
    val leg = nr
    if (netType == NetType.NR_SA && leg != null && leg.ssRsrp != null) {
        return ScoreInputs(leg.ssRsrp.toDouble(), leg.ssSinr, leg.ssRsrq?.toDouble())
    }
    return ScoreInputs(rsrp?.toDouble(), sinr, rsrq?.toDouble())
}

/** Unsmoothed score of one reading. */
fun RadioInfo.rawScore(w: ScoreWeights = ScoreWeights.DEFAULT): Double? =
    scoreInputs().let { signalScore(it.rsrp, it.sinr, it.rsrq, w) }

enum class Grade { EXCELLENT, GOOD, FAIR, POOR }

/** Plan 7.1. Boundary values belong to the better grade, except "above" thresholds (finder.ts). */
fun gradeRsrp(v: Double): Grade = when {
    v > -80 -> Grade.EXCELLENT
    v >= -90 -> Grade.GOOD
    v >= -100 -> Grade.FAIR
    else -> Grade.POOR
}

fun gradeSinr(v: Double): Grade = when {
    v > 20 -> Grade.EXCELLENT
    v >= 13 -> Grade.GOOD
    v >= 0 -> Grade.FAIR
    else -> Grade.POOR
}

fun gradeRsrq(v: Double): Grade = when {
    v > -10 -> Grade.EXCELLENT
    v >= -15 -> Grade.GOOD
    v >= -20 -> Grade.FAIR
    else -> Grade.POOR
}

fun gradeScore(v: Double): Grade = when {
    v >= 75 -> Grade.EXCELLENT
    v >= 55 -> Grade.GOOD
    v >= 35 -> Grade.FAIR
    else -> Grade.POOR
}

/**
 * Time-window mean ("smoothed over finder.smooth_s seconds", plan 7.2): the mean of the samples
 * whose timestamp is within [windowMs] of the newest sample. Thread-safe.
 * Use a monotonic clock (Clock.elapsedMs) for the timestamps.
 */
class Smoother(windowMs: Long) {
    private data class Sample(val atMs: Long, val value: Double)

    private val samples = ArrayDeque<Sample>()

    @Volatile
    var windowMs: Long = windowMs
        set(value) {
            require(value > 0) { "windowMs must be positive" }
            field = value
        }

    init {
        require(windowMs > 0) { "windowMs must be positive" }
    }

    /** Adds a sample and returns the new mean. NaN values are ignored. */
    @Synchronized
    fun add(value: Double, atMs: Long): Double? {
        if (!value.isNaN()) samples.addLast(Sample(atMs, value))
        prune(atMs)
        return meanLocked()
    }

    /** Mean of the samples still inside the window at [atMs], or null when there are none. */
    @Synchronized
    fun mean(atMs: Long): Double? {
        prune(atMs)
        return meanLocked()
    }

    @get:Synchronized
    val size: Int get() = samples.size

    @Synchronized
    fun clear() = samples.clear()

    private fun prune(nowMs: Long) {
        val cutoff = nowMs - windowMs
        while (samples.isNotEmpty() && samples.first().atMs <= cutoff) samples.removeFirst()
    }

    private fun meanLocked(): Double? = if (samples.isEmpty()) null else samples.sumOf { it.value } / samples.size
}
