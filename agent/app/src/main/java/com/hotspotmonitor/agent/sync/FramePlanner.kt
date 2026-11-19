package com.hotspotmonitor.agent.sync

import com.hotspotmonitor.agent.core.OutboxRow
import com.hotspotmonitor.agent.core.OutboxStore
import com.hotspotmonitor.agent.protocol.ProtocolLimits
import com.hotspotmonitor.agent.protocol.Wire

/** What the outbox sender does next with the rows at the head of the outbox. */
internal sealed interface FramePlan {
    /** Send [frame] (one row as-is, or a `batch` of [rows]) and wait for an ack >= [lastSeq]. */
    data class Send(val rows: List<OutboxRow>, val frame: String) : FramePlan {
        val lastSeq: Long get() = rows.last().seq
        val isBatch: Boolean get() = rows.size > 1
    }

    /**
     * The head row alone is larger than a frame may be; it can never be delivered and would block
     * the outbox forever, so the sender drops it (acks it locally) and logs an error.
     */
    data class DropOversized(val row: OutboxRow, val bytes: Int) : FramePlan
}

/**
 * Splits the outbox head into frames (docs/protocol.md 3.2): a single pending row leaves as itself,
 * a backlog leaves as `batch` frames of at most [ProtocolLimits.MAX_BATCH_ITEMS] items and at most
 * [ProtocolLimits.MAX_MESSAGE_BYTES] bytes. Rows are never re-encoded.
 */
internal object FramePlanner {
    private val BATCH_OVERHEAD_BYTES = utf8Length(Wire.batchFrame(emptyList()))

    /** [rows] must be in seq order (OutboxStore.peekBatch). Null when there is nothing to send. */
    fun plan(
        rows: List<OutboxRow>,
        maxBytes: Int = ProtocolLimits.MAX_MESSAGE_BYTES,
        maxItems: Int = ProtocolLimits.MAX_BATCH_ITEMS,
    ): FramePlan? {
        if (rows.isEmpty()) return null
        val head = rows.first()
        val headBytes = utf8Length(head.json)
        if (headBytes > maxBytes) return FramePlan.DropOversized(head, headBytes)
        if (rows.size == 1) return FramePlan.Send(rows, head.json)

        val limit = minOf(rows.size, maxItems.coerceAtLeast(1))
        var total = BATCH_OVERHEAD_BYTES + headBytes
        var count = 1
        while (count < limit) {
            val next = utf8Length(rows[count].json) + 1 // + the separating comma
            if (total + next > maxBytes) break
            total += next
            count++
        }
        // Only the head fits (or the head plus batch overhead is too big): it leaves on its own.
        if (count == 1 || total > maxBytes) return FramePlan.Send(listOf(head), head.json)
        val chosen = rows.subList(0, count).toList()
        return FramePlan.Send(chosen, OutboxStore.batchFrame(chosen))
    }
}

/** Byte length of [text] in UTF-8, without allocating the encoded bytes. */
internal fun utf8Length(text: String): Int {
    var bytes = 0
    var i = 0
    val n = text.length
    while (i < n) {
        val c = text[i]
        when {
            c.code < 0x80 -> bytes += 1
            c.code < 0x800 -> bytes += 2
            Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(text[i + 1]) -> {
                bytes += 4
                i++
            }
            else -> bytes += 3
        }
        i++
    }
    return bytes
}
