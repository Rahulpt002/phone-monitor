package com.hotspotmonitor.agent.control

import com.hotspotmonitor.agent.db.RebootLogDao
import com.hotspotmonitor.agent.db.RebootLogEntity
import com.hotspotmonitor.agent.protocol.CommandSource
import com.hotspotmonitor.agent.protocol.WatchdogSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RebootLimitPolicyTest {
    private class FakeRebootLogDao : RebootLogDao {
        val rows = mutableListOf<RebootLogEntity>()

        override suspend fun insert(entity: RebootLogEntity): Long {
            val row = entity.copy(id = rows.size + 1L)
            rows += row
            return row.id
        }

        override suspend fun countSince(sinceMs: Long): Int = rows.count { it.atMs >= sinceMs }

        override suspend fun latest(limit: Int): List<RebootLogEntity> = rows.sortedByDescending { it.atMs }.take(limit)

        override suspend fun deleteOlderThan(beforeMs: Long): Int {
            val doomed = rows.filter { it.atMs < beforeMs }
            rows.removeAll(doomed)
            return doomed.size
        }
    }

    private val hour = 3_600_000L
    private val t0 = 1_763_547_300_000L

    @Test
    fun atMostMaxRebootsPerWindowFromAnySource() = runTest {
        val dao = FakeRebootLogDao()
        val policy = RebootLimitPolicy(dao) { WatchdogSettings(maxReboots = 3, rebootWindowH = 6.0) }
        assertTrue(policy.canReboot(t0))
        policy.record(CommandSource.WATCHDOG, t0)
        policy.record(CommandSource.DASHBOARD, t0 + 1 * hour)
        assertTrue(policy.canReboot(t0 + 2 * hour))
        policy.record(CommandSource.SCHEDULE, t0 + 2 * hour)
        assertEquals(3, policy.countInWindow(t0 + 2 * hour))
        assertFalse(policy.canReboot(t0 + 2 * hour))
        assertFalse(policy.canReboot(t0 + 6 * hour))
        // The first reboot leaves the window just after 6 h.
        assertEquals(2, policy.countInWindow(t0 + 6 * hour + 1))
        assertTrue(policy.canReboot(t0 + 6 * hour + 1))
        assertEquals(listOf("watchdog", "dashboard", "schedule"), dao.rows.map { it.source })
    }

    @Test
    fun settingsAreReadOnEveryCall() = runTest {
        var settings = WatchdogSettings(maxReboots = 3, rebootWindowH = 6.0)
        val policy = RebootLimitPolicy(FakeRebootLogDao()) { settings }
        policy.record(CommandSource.WATCHDOG, t0)
        policy.record(CommandSource.WATCHDOG, t0 + hour)
        assertTrue(policy.canReboot(t0 + 2 * hour))
        settings = settings.copy(maxReboots = 2)
        assertFalse(policy.canReboot(t0 + 2 * hour))
        settings = settings.copy(rebootWindowH = 1.5)
        assertEquals(1, policy.countInWindow(t0 + 2 * hour))
        assertTrue(policy.canReboot(t0 + 2 * hour))
    }

    @Test
    fun historyIsPrunedButTheWindowIsKept() = runTest {
        val dao = FakeRebootLogDao()
        val policy = RebootLimitPolicy(dao) { WatchdogSettings(maxReboots = 3, rebootWindowH = 6.0) }
        policy.record(CommandSource.WATCHDOG, t0)
        policy.record(CommandSource.WATCHDOG, t0 + 8 * 24 * hour)
        assertEquals(1, dao.rows.size)
        assertEquals(t0 + 8 * 24 * hour, dao.rows.single().atMs)
    }
}
