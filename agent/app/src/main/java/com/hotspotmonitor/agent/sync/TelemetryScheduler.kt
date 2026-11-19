package com.hotspotmonitor.agent.sync

import com.hotspotmonitor.agent.core.AgentLog
import com.hotspotmonitor.agent.core.Clock
import com.hotspotmonitor.agent.core.OutboxStore
import com.hotspotmonitor.agent.protocol.TelemetryMsg
import com.hotspotmonitor.agent.protocol.TelemetryTier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** Reads one telemetry tier. Returns null when there is nothing worth sending. */
internal interface TierCollector {
    suspend fun collect(tier: TelemetryTier): TelemetryMsg?
}

/**
 * The telemetry tier scheduler (docs/protocol.md 3.3, plan 8.2): `fast` every rates.fast_s, `slow`
 * every telemetry.slow_s, `static` on demand. Every message is enqueued in the outbox (buffered), so
 * readings taken while offline back-fill the Server later; [onEnqueued] then wakes the sender.
 *
 * An interval change applies at once: when the new interval has already elapsed since the last
 * reading (Dashboard opened: fast 60 s -> 5 s), the next reading is taken immediately.
 */
internal class TelemetryScheduler(
    private val clock: Clock,
    private val log: AgentLog,
    private val outbox: OutboxStore,
    private val collector: TierCollector,
    private val onEnqueued: () -> Unit,
    private val collectTimeoutMs: Long = DEFAULT_COLLECT_TIMEOUT_MS,
) {
    private val lastAttemptMs = ConcurrentHashMap<TelemetryTier, Long>()
    private val locks: Map<TelemetryTier, Mutex> = TelemetryTier.entries.associateWith { Mutex() }

    /** Runs the periodic fast and slow tiers for the life of [scope]. */
    fun start(scope: CoroutineScope, fastIntervalS: Flow<Int>, slowIntervalS: Flow<Int>) {
        lastAttemptMs.clear()
        scope.launch { runTier(TelemetryTier.FAST, fastIntervalS) }
        scope.launch { runTier(TelemetryTier.SLOW, slowIntervalS) }
    }

    /** Collects and enqueues [tiers] now, in the order static, slow, fast. */
    suspend fun publish(tiers: Set<TelemetryTier>) {
        for (tier in PUBLISH_ORDER) if (tier in tiers) publishOne(tier)
    }

    /** Milliseconds since [tier] was last collected (attempted), or null when never. */
    fun msSinceLastAttempt(tier: TelemetryTier): Long? = lastAttemptMs[tier]?.let { clock.elapsedMs() - it }

    private suspend fun runTier(tier: TelemetryTier, intervalS: Flow<Int>) {
        intervalS.map { it.coerceIn(MIN_INTERVAL_S, MAX_INTERVAL_S) }.distinctUntilChanged().collectLatest { seconds ->
            val intervalMs = seconds * 1000L
            while (true) {
                val last = lastAttemptMs[tier]
                val waitMs = if (last == null) 0L else last + intervalMs - clock.elapsedMs()
                if (waitMs > 0) delay(waitMs) else publishOne(tier)
            }
        }
    }

    private suspend fun publishOne(tier: TelemetryTier) {
        // Recorded before collecting so a failing collector cannot turn the loop into a busy loop.
        lastAttemptMs[tier] = clock.elapsedMs()
        locks.getValue(tier).withLock {
            val collected = try {
                withTimeoutOrNull(collectTimeoutMs) { Collected(collector.collect(tier)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(TAG, "Could not collect ${tier.wire()} telemetry", e)
                return
            }
            if (collected == null) {
                log.w(TAG, "Collecting ${tier.wire()} telemetry took longer than ${collectTimeoutMs / 1000} s; skipped")
                return
            }
            val msg = collected.msg ?: return
            if (outbox.enqueue(msg) != null) onEnqueued()
        }
    }

    private class Collected(val msg: TelemetryMsg?)

    companion object {
        private const val TAG = "sync"
        const val DEFAULT_COLLECT_TIMEOUT_MS = 45_000L
        private const val MIN_INTERVAL_S = 1
        private const val MAX_INTERVAL_S = 86_400
        private val PUBLISH_ORDER = listOf(TelemetryTier.STATIC, TelemetryTier.SLOW, TelemetryTier.FAST)

        fun TelemetryTier.wire(): String = when (this) {
            TelemetryTier.FAST -> "fast"
            TelemetryTier.SLOW -> "slow"
            TelemetryTier.STATIC -> "static"
        }
    }
}
