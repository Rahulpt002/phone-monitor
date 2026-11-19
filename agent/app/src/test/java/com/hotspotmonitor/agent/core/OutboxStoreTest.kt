@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.hotspotmonitor.agent.core

import com.hotspotmonitor.agent.protocol.AgentEvent
import com.hotspotmonitor.agent.protocol.AgentJson
import com.hotspotmonitor.agent.protocol.BatchMsg
import com.hotspotmonitor.agent.protocol.CmdAckMsg
import com.hotspotmonitor.agent.protocol.EventKind
import com.hotspotmonitor.agent.protocol.EventMsg
import com.hotspotmonitor.agent.protocol.NetInfo
import com.hotspotmonitor.agent.protocol.Severity
import com.hotspotmonitor.agent.protocol.TelemetryMsg
import com.hotspotmonitor.agent.protocol.TelemetryTier
import com.hotspotmonitor.agent.protocol.Wire
import com.hotspotmonitor.agent.testutil.FakeOutboxDao
import com.hotspotmonitor.agent.testutil.TestClock
import com.hotspotmonitor.agent.testutil.testLog
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxStoreTest {
    private val ts = "2026-11-19T10:15:00.000Z"

    private fun fast() = TelemetryMsg(ts = ts, tier = TelemetryTier.FAST, net = NetInfo(online = true))
    private fun slow() = TelemetryMsg(ts = ts, tier = TelemetryTier.SLOW)
    private fun event(n: Int) = EventMsg(event = AgentEvent("e_$n", ts, EventKind.CONNECTIVITY, Severity.INFO, "event $n"))
    private fun ack(n: Int) = CmdAckMsg(id = "c_$n", accepted = true, ts = ts)

    @Test
    fun enqueueAssignsSeqAndWritesItIntoTheJson() = runTest {
        val dao = FakeOutboxDao()
        val store = OutboxStore(dao, TestClock(), testLog(), backgroundScope)
        val s1 = store.enqueue(fast())
        val s2 = store.enqueue(event(1))
        assertEquals(1L, s1)
        assertEquals(2L, s2)
        assertEquals(2L, store.lastEnqueuedSeq.value)
        for (row in dao.all) {
            val obj = AgentJson.parseToJsonElement(row.json).jsonObject
            assertEquals(row.seq, obj.getValue("seq").jsonPrimitive.long)
            assertEquals(row.type, obj.getValue("t").jsonPrimitive.content)
        }
        assertEquals("fast", dao.all.first().tier)
        assertEquals(null, dao.all.last().tier)
    }

    @Test
    fun peekAckAndBatchFrame() = runTest {
        val dao = FakeOutboxDao()
        val store = OutboxStore(dao, TestClock(), testLog(), backgroundScope)
        repeat(5) { store.enqueue(event(it)) }
        val batch = store.peekBatch(3)
        assertEquals(listOf(1L, 2L, 3L), batch.map { it.seq })
        val frame = Wire.decodeAgentMessage(OutboxStore.batchFrame(batch)) as BatchMsg
        assertEquals(listOf(1L, 2L, 3L), frame.items.map { it.seq })
        assertEquals(3, store.ackUpTo(3))
        assertEquals(listOf(4L, 5L), store.peekBatch(10).map { it.seq })
        runCurrent()
        assertEquals(2, store.pendingCount.value)
    }

    @Test
    fun overTheCapDropsOldestFastTelemetryFirst() = runTest {
        val dao = FakeOutboxDao()
        val store = OutboxStore(dao, TestClock(), testLog(), backgroundScope, cap = 5)
        store.enqueue(fast()) // 1
        store.enqueue(slow()) // 2
        store.enqueue(fast()) // 3
        store.enqueue(event(1)) // 4
        store.enqueue(ack(1)) // 5
        store.enqueue(fast()) // 6 -> drops seq 1 (oldest fast)
        assertEquals(listOf(2L, 3L, 4L, 5L, 6L), dao.all.map { it.seq })
        store.enqueue(event(2)) // 7 -> drops seq 3
        store.enqueue(event(3)) // 8 -> drops seq 6
        assertEquals(listOf(2L, 4L, 5L, 7L, 8L), dao.all.map { it.seq })
        store.enqueue(event(4)) // 9 -> no fast left, drops slow seq 2
        assertEquals(listOf(4L, 5L, 7L, 8L, 9L), dao.all.map { it.seq })
    }

    @Test
    fun eventsAndCommandReportsAreNeverDropped() = runTest {
        val dao = FakeOutboxDao()
        val store = OutboxStore(dao, TestClock(), testLog(), backgroundScope, cap = 3)
        repeat(4) { store.enqueue(event(it)) }
        repeat(2) { store.enqueue(ack(it)) }
        assertEquals(6, dao.all.size)
        assertTrue(dao.all.none { it.type == "telemetry" })
    }
}
