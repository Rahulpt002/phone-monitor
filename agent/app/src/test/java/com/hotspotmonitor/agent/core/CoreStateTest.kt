@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.hotspotmonitor.agent.core

import com.hotspotmonitor.agent.protocol.EventKind
import com.hotspotmonitor.agent.protocol.EventMsg
import com.hotspotmonitor.agent.protocol.PairResponse
import com.hotspotmonitor.agent.protocol.Settings
import com.hotspotmonitor.agent.protocol.Severity
import com.hotspotmonitor.agent.protocol.Wire
import com.hotspotmonitor.agent.protocol.jsonObjectOf
import com.hotspotmonitor.agent.testutil.FakeOutboxDao
import com.hotspotmonitor.agent.testutil.TestClock
import com.hotspotmonitor.agent.testutil.testLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

private class SoftwareCrypter(private val key: SecretKey = newKey()) : Crypter {
    override fun encrypt(plain: ByteArray) = AesGcm.encrypt(key, plain)
    override fun decrypt(blob: ByteArray) = AesGcm.decrypt(key, blob)

    companion object {
        fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
}

class ClockTest {
    @Test
    fun formatsAndParsesIsoWithMillis() {
        assertEquals("1970-01-01T00:00:00.000Z", Clock.formatIso(0))
        val ms = Clock.parseIso("2026-11-19T10:15:00.000Z")!!
        assertEquals("2026-11-19T10:15:00.000Z", Clock.formatIso(ms))
        assertEquals(ms, Clock.parseIso("2026-11-19T10:15:00Z"))
        assertEquals(ms, Clock.parseIso("2026-11-19T15:45:00+05:30"))
        assertEquals(ms + 123, Clock.parseIso("2026-11-19T10:15:00.123Z"))
        assertNull(Clock.parseIso("yesterday"))
        assertNull(Clock.parseIso(null))
    }

    @Test
    fun serverOffsetCorrectsTimestamps() {
        val clock = TestClock(system = Clock.parseIso("2026-11-19T10:00:00.000Z")!!)
        assertFalse(clock.synced)
        assertTrue(clock.syncWithServer("2026-11-19T10:02:00.000Z"))
        assertEquals(120_000L, clock.serverOffsetMs)
        assertEquals("2026-11-19T10:02:00.000Z", clock.isoNow())
        assertFalse(clock.syncWithServer("bad"))
        assertEquals(120_000L, clock.serverOffsetMs)
    }

    @Test
    fun lastOffsetIsPersistedAndRestoredUnsyncedAtTheNextStart() {
        val store = MemoryStore()
        val phone = Clock.parseIso("2026-11-19T10:00:00.000Z")!!
        val first = TestClock(system = phone).apply { attachStore(store) }
        assertEquals("nothing stored yet: offset 0", 0L, first.serverOffsetMs)
        assertTrue(first.syncWithServer("2026-11-19T11:00:00.000Z")) // phone clock 1 h behind
        assertEquals(3_600_000L, store.getLong(Clock.KEY_OFFSET, 0))

        // A jitter of a few ms is not written again (prefs writes go to disk).
        first.setServerOffset(3_600_300L)
        assertEquals(3_600_000L, store.getLong(Clock.KEY_OFFSET, 0))
        first.setServerOffset(3_700_000L)
        assertEquals(3_700_000L, store.getLong(Clock.KEY_OFFSET, 0))

        // Process restart while offline: timestamps keep the Server base, marked unsynced.
        val restarted = TestClock(system = phone).apply { attachStore(store) }
        assertFalse(restarted.synced)
        assertEquals(3_700_000L, restarted.serverOffsetMs)
        assertEquals(phone + 3_700_000L, restarted.serverNow())

        // A sync that happened before attachStore is never overwritten by the stored value.
        val synced = TestClock(system = phone).apply { setServerOffset(5_000) }
        synced.attachStore(store)
        assertTrue(synced.synced)
        assertEquals(5_000L, synced.serverOffsetMs)
    }

    @Test
    fun idsHaveProtocolPrefixes() {
        assertTrue(Ids.event().startsWith("e_"))
        assertTrue(Ids.command().startsWith("c_"))
        assertTrue(Ids.spot().startsWith("s_"))
        assertTrue(Ids.sms().startsWith("m_"))
        assertTrue(Ids.probe().startsWith("p_"))
        assertNotEquals(Ids.event(), Ids.event())
    }
}

class SettingsRepoTest {
    @Test
    fun startsFromDefaultsAndAppliesNewVersionsOnce() {
        val store = MemoryStore()
        val applied = mutableListOf<Int>()
        val repo = SettingsRepo(store, testLog()) { applied += it }
        assertEquals(Settings.DEFAULT, repo.current)
        assertEquals(0, repo.version.value)

        val next = Settings.DEFAULT.copy(watchdog = Settings.DEFAULT.watchdog.copy(chargeLimitPct = 55))
        assertTrue(repo.apply(next, 3))
        assertFalse(repo.apply(next, 3))
        assertEquals(listOf(3), applied)

        val reloaded = SettingsRepo(store, testLog())
        assertEquals(55, reloaded.current.watchdog.chargeLimitPct)
        assertEquals(3, reloaded.version.value)
    }

    @Test
    fun localEditsAreReplacedByTheNextServerCopy() {
        val repo = SettingsRepo(MemoryStore(), testLog())
        repo.apply(Settings.DEFAULT, 2)
        repo.updateLocal { it.copy(finder = it.finder.copy(smoothS = 3)) }
        assertTrue(repo.hasLocalEdits)
        assertEquals(3, repo.current.finder.smoothS)
        assertTrue(repo.needsApply(2))
        assertTrue(repo.apply(Settings.DEFAULT, 2))
        assertEquals(5, repo.current.finder.smoothS)
        assertFalse(repo.hasLocalEdits)
    }

    @Test
    fun unreadableStoredSettingsFallBackToDefaults() {
        val store = MemoryStore().apply {
            putString("settings.json", "{not json")
            putLong("settings.version", 9)
        }
        val repo = SettingsRepo(store, testLog())
        assertEquals(Settings.DEFAULT, repo.current)
        assertEquals(0, repo.version.value)
    }
}

class SecureStoreAndPairingTest {
    @Test
    fun secureValuesRoundTripAndAreNotStoredInPlainText() {
        val kv = MemoryStore()
        val secure = SecureStore(kv, SoftwareCrypter(), testLog())
        assertTrue(secure.putString("device_token", "tok_secret_123"))
        assertEquals("tok_secret_123", secure.getString("device_token"))
        assertFalse(kv.getString("secure.device_token")!!.contains("tok_secret"))
    }

    @Test
    fun undecryptableValueReadsAsNull() {
        val kv = MemoryStore()
        SecureStore(kv, SoftwareCrypter(), testLog()).putString("device_token", "abc")
        assertNull(SecureStore(kv, SoftwareCrypter(), testLog()).getString("device_token"))
    }

    @Test
    fun pairingPersistsAndClears() {
        val kv = MemoryStore()
        val crypter = SoftwareCrypter()
        val state = PairingState(kv, SecureStore(kv, crypter, testLog()), testLog())
        assertFalse(state.paired.value)
        assertNull(state.token())

        val response = PairResponse("d_1", "tok_abc", "wss://hsm.example.com/ws/agent", "https://hsm.example.com/")
        assertTrue(state.save(response, pairedAtMs = 42))
        assertTrue(state.paired.value)
        assertEquals("https://hsm.example.com", state.serverUrl)
        assertEquals("tok_abc", state.token())
        assertFalse("toString hides the token", response.toString().contains("tok_abc"))

        val reloaded = PairingState(kv, SecureStore(kv, crypter, testLog()), testLog())
        assertEquals(PairingInfo("https://hsm.example.com", "wss://hsm.example.com/ws/agent", "d_1", 42), reloaded.info.value)
        assertEquals("tok_abc", reloaded.token())

        reloaded.clear()
        assertFalse(reloaded.paired.value)
        assertNull(reloaded.token())
        assertNull(PairingState(kv, SecureStore(kv, crypter, testLog()), testLog()).info.value)
    }
}

class EventSinkTest {
    @Test
    fun eventsReachTheOutboxInOrderAndTheLocalFeed() = runTest {
        val dao = FakeOutboxDao()
        val clock = TestClock()
        val outbox = OutboxStore(dao, clock, testLog(), backgroundScope)
        val sink = EventSink(outbox, clock, testLog(), backgroundScope)

        val first = sink.emit(EventKind.BOOT, Severity.INFO, "booted", jsonObjectOf("boot_count" to 7))
        sink.emit(EventKind.TETHER_UP, Severity.INFO, "tether up")
        sink.emit(EventKind.TEMP_WARNING, Severity.WARNING, "hot")
        runCurrent()

        assertTrue(first.id.startsWith("e_"))
        assertEquals(clock.isoNow(), first.ts)
        val stored = dao.all.map { (Wire.decodeAgentMessage(it.json) as EventMsg) }
        assertEquals(listOf(EventKind.BOOT, EventKind.TETHER_UP, EventKind.TEMP_WARNING), stored.map { it.event.kind })
        assertEquals(listOf(1L, 2L, 3L), stored.map { it.seq })
        assertEquals(7, stored.first().event.data!!.getValue("boot_count").jsonPrimitive.int)

        val recent = sink.recent.take(3).toList()
        assertEquals(first, recent.first())
        assertEquals(EventKind.TEMP_WARNING, sink.recent.first { it.kind == EventKind.TEMP_WARNING }.kind)
    }
}
