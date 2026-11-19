package com.hotspotmonitor.agent.core

import com.hotspotmonitor.agent.db.OutboxDao
import com.hotspotmonitor.agent.db.OutboxEntity
import com.hotspotmonitor.agent.protocol.BufferedMsg
import com.hotspotmonitor.agent.protocol.CmdAckMsg
import com.hotspotmonitor.agent.protocol.CmdResultMsg
import com.hotspotmonitor.agent.protocol.CmdRevertMsg
import com.hotspotmonitor.agent.protocol.EventMsg
import com.hotspotmonitor.agent.protocol.FinderSpotDeletedMsg
import com.hotspotmonitor.agent.protocol.FinderSpotMsg
import com.hotspotmonitor.agent.protocol.ProtocolLimits
import com.hotspotmonitor.agent.protocol.SmsMsg
import com.hotspotmonitor.agent.protocol.TelemetryMsg
import com.hotspotmonitor.agent.protocol.TelemetryTier
import com.hotspotmonitor.agent.protocol.Wire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn

/** One outbox row ready to send: [json] already contains `"seq": seq`. */
data class OutboxRow(val seq: Long, val type: String, val json: String)

/**
 * The local outbox of buffered messages (docs/protocol.md 3.2). Lives in core so every module can
 * enqueue; the SyncClient is its only reader.
 *
 * - [enqueue] stores a message and assigns its `seq` (the Room autoincrement id) in one transaction,
 *   writing the seq into the stored JSON, so a row is sent byte-for-byte as stored.
 * - The sender drains in seq order with [peekBatch] and deletes with [ackUpTo] when `ack` arrives.
 * - Cap [cap] rows. Over the cap, the oldest `fast` telemetry is dropped first, then `slow`, then
 *   `static` telemetry. Events, command reports, spots and SMS are never dropped.
 */
class OutboxStore(
    private val dao: OutboxDao,
    private val clock: Clock,
    private val log: AgentLog,
    scope: CoroutineScope,
    private val cap: Int = DEFAULT_CAP,
) {
    /** Rows waiting to be acknowledged by the Server (heartbeat `outbox`, Status screen). */
    val pendingCount: StateFlow<Int> = dao.countFlow()
        .catch { e -> log.e(TAG, "Outbox count query failed", e) }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    private val _lastEnqueuedSeq = MutableStateFlow(0L)

    /** Seq of the newest stored row; the sender collects it to wake up and drain right away. */
    val lastEnqueuedSeq: StateFlow<Long> = _lastEnqueuedSeq.asStateFlow()

    /**
     * Stores [msg] (its own seq is ignored) and returns the assigned seq, or null if the database
     * write failed (the failure is logged; the caller carries on).
     */
    suspend fun enqueue(msg: BufferedMsg): Long? {
        val (type, tier) = classify(msg)
        val seq = try {
            dao.insertAssigningSeq(
                OutboxEntity(type = type, json = "", tier = tier, createdAt = clock.serverNow()),
            ) { assigned -> Wire.encodeBuffered(msg.withSeq(assigned)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(TAG, "Could not store $type in the outbox", e)
            return null
        }
        if (seq > _lastEnqueuedSeq.value) _lastEnqueuedSeq.value = seq
        try {
            trimIfNeeded()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(TAG, "Outbox trim failed", e)
        }
        return seq
    }

    /** The oldest [limit] rows in seq order (default: one `batch`). */
    suspend fun peekBatch(limit: Int = ProtocolLimits.MAX_BATCH_ITEMS): List<OutboxRow> =
        dao.peek(limit).map { OutboxRow(it.seq, it.type, it.json) }

    /** Deletes every row with seq <= [seq] (Server `ack`). Returns the number of rows deleted. */
    suspend fun ackUpTo(seq: Long): Int = dao.deleteUpTo(seq)

    suspend fun count(): Int = dao.count()

    private suspend fun trimIfNeeded() {
        var excess = dao.count() - cap
        if (excess <= 0) return
        for (tier in TRIM_ORDER) {
            if (excess <= 0) break
            val deleted = dao.deleteOldest(TYPE_TELEMETRY, tier, excess)
            if (deleted > 0) log.w(TAG, "Outbox over its cap of $cap: dropped the $deleted oldest $tier telemetry rows")
            excess -= deleted
        }
        if (excess > 0) {
            log.w(TAG, "Outbox is $excess rows over its cap with only protected rows left; keeping them")
        }
    }

    companion object {
        private const val TAG = "outbox"
        const val DEFAULT_CAP = 50_000
        const val TYPE_TELEMETRY = "telemetry"
        private val TRIM_ORDER = listOf("fast", "slow", "static")

        /** Protocol `t` and, for telemetry, the tier of a buffered message. */
        fun classify(msg: BufferedMsg): Pair<String, String?> = when (msg) {
            is TelemetryMsg -> TYPE_TELEMETRY to tierWire(msg.tier)
            is EventMsg -> "event" to null
            is FinderSpotMsg -> "finder_spot" to null
            is FinderSpotDeletedMsg -> "finder_spot_deleted" to null
            is SmsMsg -> "sms" to null
            is CmdAckMsg -> "cmd_ack" to null
            is CmdResultMsg -> "cmd_result" to null
            is CmdRevertMsg -> "cmd_revert" to null
        }

        private fun tierWire(tier: TelemetryTier): String = when (tier) {
            TelemetryTier.FAST -> "fast"
            TelemetryTier.SLOW -> "slow"
            TelemetryTier.STATIC -> "static"
        }

        /** A `batch` frame of [rows] without re-encoding them (protocol 3.2 step 2). */
        fun batchFrame(rows: List<OutboxRow>): String = Wire.batchFrame(rows.map { it.json })
    }
}
