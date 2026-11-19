package com.hotspotmonitor.agent.testutil

import com.hotspotmonitor.agent.core.AgentLog
import com.hotspotmonitor.agent.core.Clock
import com.hotspotmonitor.agent.db.OutboxDao
import com.hotspotmonitor.agent.db.OutboxEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.TreeMap

/** In-memory OutboxDao with Room's autoincrement semantics (ids never reused). */
class FakeOutboxDao : OutboxDao() {
    private val lock = Any()
    private val rows = TreeMap<Long, OutboxEntity>()
    private var nextSeq = 1L
    private val countState = MutableStateFlow(0)

    val all: List<OutboxEntity> get() = synchronized(lock) { rows.values.toList() }

    override suspend fun insert(entity: OutboxEntity): Long = synchronized(lock) {
        val seq = nextSeq++
        rows[seq] = entity.copy(seq = seq)
        countState.value = rows.size
        seq
    }

    override suspend fun updateJson(seq: Long, json: String) = synchronized(lock) {
        rows[seq]?.let { rows[seq] = it.copy(json = json) }
        Unit
    }

    override suspend fun peek(limit: Int): List<OutboxEntity> = synchronized(lock) { rows.values.take(limit) }

    override suspend fun deleteUpTo(seq: Long): Int = synchronized(lock) {
        val doomed = rows.headMap(seq, true).keys.toList()
        doomed.forEach { rows.remove(it) }
        countState.value = rows.size
        doomed.size
    }

    override suspend fun count(): Int = synchronized(lock) { rows.size }

    override fun countFlow(): Flow<Int> = countState

    override suspend fun deleteOldest(type: String, tier: String, limit: Int): Int = synchronized(lock) {
        val doomed = rows.values.filter { it.type == type && it.tier == tier }.take(limit).map { it.seq }
        doomed.forEach { rows.remove(it) }
        countState.value = rows.size
        doomed.size
    }
}

/** Clock with a settable phone time. */
class TestClock(var system: Long = 1_763_547_300_000L, var elapsed: Long = 0) : Clock() {
    override fun systemNowMs(): Long = system
    override fun elapsedMs(): Long = elapsed
}

fun testLog(): AgentLog = AgentLog(logDir = null, echoToLogcat = false)
