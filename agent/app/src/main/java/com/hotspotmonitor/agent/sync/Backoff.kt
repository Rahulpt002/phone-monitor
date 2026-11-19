package com.hotspotmonitor.agent.sync

import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Reconnect delays (docs/protocol.md 3.1): 2 s, 4 s, 8 s ... capped at 60 s, each with +/- [jitter]
 * so a fleet of phones (or one phone after a Server restart) does not reconnect in lockstep. The
 * jittered value never exceeds [maxMs]. Not thread-safe: used by the single connection loop.
 */
internal class Backoff(
    private val baseMs: Long = 2_000,
    private val maxMs: Long = 60_000,
    private val jitter: Double = 0.2,
    private val random: Random = Random.Default,
) {
    init {
        require(baseMs > 0 && maxMs >= baseMs) { "need 0 < baseMs <= maxMs" }
        require(jitter in 0.0..1.0) { "jitter must be within 0..1" }
    }

    /** Number of delays handed out since the last [reset]. */
    var attempt: Int = 0
        private set

    /** The un-jittered delay for the next attempt. */
    fun nominalNextMs(): Long {
        // 2^20 * baseMs is far beyond any sane max; capping the exponent avoids overflow.
        val factor = 1L shl min(attempt, 20)
        return min(maxMs, baseMs * factor)
    }

    fun nextDelayMs(): Long {
        val nominal = nominalNextMs()
        attempt++
        if (jitter == 0.0) return nominal
        val spread = 1.0 + jitter * (2.0 * random.nextDouble() - 1.0)
        return min(maxMs, (nominal * spread).roundToLong()).coerceAtLeast(1)
    }

    fun reset() {
        attempt = 0
    }
}
