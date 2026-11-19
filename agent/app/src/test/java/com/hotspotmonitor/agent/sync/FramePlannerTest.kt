package com.hotspotmonitor.agent.sync

import com.hotspotmonitor.agent.core.OutboxRow
import com.hotspotmonitor.agent.protocol.BatchMsg
import com.hotspotmonitor.agent.protocol.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePlannerTest {
    private fun row(seq: Long, message: String = "event $seq") =
        OutboxRow(seq, "event", Wire.encodeBuffered(event(seq.toInt(), message).withSeq(seq)))

    private fun rows(n: Int) = (1L..n.toLong()).map { row(it) }

    @Test
    fun nothingToSend() {
        assertNull(FramePlanner.plan(emptyList()))
    }

    @Test
    fun aSingleRowLeavesAsItself() {
        val r = row(7)
        val plan = FramePlanner.plan(listOf(r)) as FramePlan.Send
        assertFalse(plan.isBatch)
        assertEquals(r.json, plan.frame)
        assertEquals(7L, plan.lastSeq)
    }

    @Test
    fun severalRowsLeaveAsOneBatchWithoutReEncoding() {
        val input = rows(3)
        val plan = FramePlanner.plan(input) as FramePlan.Send
        assertTrue(plan.isBatch)
        assertEquals(3L, plan.lastSeq)
        assertTrue(plan.frame.contains(input[1].json))
        val batch = Wire.decodeAgentMessage(plan.frame) as BatchMsg
        assertEquals(listOf(1L, 2L, 3L), batch.items.map { it.seq })
    }

    @Test
    fun aBatchHoldsAtMost200Items() {
        val plan = FramePlanner.plan(rows(250)) as FramePlan.Send
        assertEquals(200, plan.rows.size)
        assertEquals(200L, plan.lastSeq)
    }

    @Test
    fun aBatchStaysUnderTheFrameSizeLimit() {
        val input = rows(10)
        val one = utf8Length(input[0].json)
        // Room for the batch wrapper and about two and a half rows.
        val limit = 30 + one * 2 + one / 2
        val plan = FramePlanner.plan(input, maxBytes = limit) as FramePlan.Send
        assertEquals(2, plan.rows.size)
        assertTrue(utf8Length(plan.frame) <= limit)
    }

    @Test
    fun aHeadRowThatOnlyFitsAloneLeavesAlone() {
        val input = rows(2)
        val limit = utf8Length(input[0].json) + 5 // too small for the batch wrapper
        val plan = FramePlanner.plan(input, maxBytes = limit) as FramePlan.Send
        assertFalse(plan.isBatch)
        assertEquals(input[0].json, plan.frame)
    }

    @Test
    fun anOversizedHeadRowIsDropped() {
        val big = row(1, "x".repeat(2_000))
        val plan = FramePlanner.plan(listOf(big, row(2)), maxBytes = 1_000)
        assertTrue(plan is FramePlan.DropOversized)
        assertEquals(1L, (plan as FramePlan.DropOversized).row.seq)
    }

    @Test
    fun utf8LengthCountsEncodedBytes() {
        for (text in listOf("", "plain ascii", "temp 40 °C", "€ 5", "signal 📶 ok")) {
            assertEquals(text, text.toByteArray(Charsets.UTF_8).size, utf8Length(text))
        }
    }
}
