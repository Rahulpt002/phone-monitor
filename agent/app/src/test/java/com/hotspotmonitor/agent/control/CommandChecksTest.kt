package com.hotspotmonitor.agent.control

import com.hotspotmonitor.agent.core.Clock
import com.hotspotmonitor.agent.protocol.CapabilityEntry
import com.hotspotmonitor.agent.protocol.CommandNames
import com.hotspotmonitor.agent.protocol.NetworkMode
import com.hotspotmonitor.agent.testutil.TestClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandChecksTest {
    private val serverTime = Clock.parseIso("2026-11-19T10:02:00.000Z")!!

    @Test
    fun expiryUsesTheServerCorrectedClock() {
        // The phone clock is 2 minutes behind the Server; hello taught the Agent the offset.
        val clock = TestClock(system = serverTime - 120_000)
        clock.setServerOffset(120_000)
        val now = clock.serverNow()
        assertEquals(serverTime, now)

        assertFalse(CommandChecks.isExpired("2026-11-19T10:01:30.000Z", now, expiryS = 60)) // 30 s old
        assertFalse(CommandChecks.isExpired("2026-11-19T10:01:00.000Z", now, expiryS = 60)) // exactly 60 s
        assertTrue(CommandChecks.isExpired("2026-11-19T10:00:59.000Z", now, expiryS = 60)) // 61 s
        assertFalse(CommandChecks.isExpired("2026-11-19T10:02:05.000Z", now, expiryS = 60)) // slightly in the future
        assertEquals(30_000L, CommandChecks.ageMs("2026-11-19T10:01:30.000Z", now))

        // Without the offset the same 90 s old command would look like it came from the future.
        val uncorrected = clock.systemNowMs()
        assertFalse(CommandChecks.isExpired("2026-11-19T10:00:30.000Z", uncorrected, expiryS = 60))
        assertTrue(CommandChecks.isExpired("2026-11-19T10:00:30.000Z", now, expiryS = 60))
    }

    @Test
    fun unparseableSentAtCannotProveFreshness() {
        assertTrue(CommandChecks.isExpired("yesterday", serverTime, expiryS = 60))
        assertTrue(CommandChecks.isExpired("", serverTime, expiryS = 60))
        assertNull(CommandChecks.ageMs("nope", serverTime))
        assertFalse(CommandChecks.isExpired("2026-11-19T10:01:30+00:00", serverTime, expiryS = 60))
    }

    @Test
    fun capabilityGate() {
        assertTrue(CommandChecks.capabilityAllows(CommandNames.MOBILE_DATA, null))
        assertTrue(CommandChecks.capabilityAllows(CommandNames.MOBILE_DATA, CapabilityEntry(true, "svc data")))
        assertFalse(CommandChecks.capabilityAllows(CommandNames.HOTSPOT, CapabilityEntry(false, note = "Android 11+")))
        // power_off with a mechanism but blocked by policy passes this check, so the next one says `blocked`.
        assertTrue(CommandChecks.capabilityAllows(CommandNames.POWER_OFF, CapabilityEntry(false, "svc power shutdown", "blocked")))
        assertFalse(CommandChecks.capabilityAllows(CommandNames.POWER_OFF, CapabilityEntry(false, null, "no svc power")))
    }

    @Test
    fun conflictingCommandsAreRadioAndPower() {
        for (name in listOf(
            CommandNames.MOBILE_DATA, CommandNames.DATA_CYCLE, CommandNames.AIRPLANE_MODE, CommandNames.AIRPLANE_CYCLE,
            CommandNames.USB_TETHERING, CommandNames.HOTSPOT, CommandNames.WIFI, CommandNames.BLUETOOTH,
            CommandNames.NETWORK_MODE, CommandNames.REBOOT, CommandNames.POWER_OFF,
        )) {
            assertTrue(name, CommandChecks.isConflicting(name))
        }
        for (name in listOf(CommandNames.APP_LIST, CommandNames.SHELL, CommandNames.CHARGING, CommandNames.SPEED_TEST)) {
            assertFalse(name, CommandChecks.isConflicting(name))
        }
    }

    @Test
    fun networkModeBitmasks() {
        val lte = NetworkTypes.bit(NetworkTypes.LTE) or NetworkTypes.bit(NetworkTypes.LTE_CA)
        assertEquals(4096L or 262_144L, lte)
        assertEquals(lte, NetworkTypes.bitmaskFor(NetworkMode.LTE_ONLY))
        assertEquals(lte or 524_288L, NetworkTypes.bitmaskFor(NetworkMode.NR_LTE))
        assertEquals("1000001000000000000", NetworkTypes.toBinary(NetworkTypes.bitmaskFor(NetworkMode.LTE_ONLY)))
        assertEquals(
            NetworkTypes.LTE_FAMILY or NetworkTypes.WCDMA_FAMILY,
            NetworkTypes.bitmaskFor(NetworkMode.LTE_WCDMA),
        )
        assertEquals(NetworkTypes.GSM_FAMILY, NetworkTypes.bitmaskFor(NetworkMode.AUTO) and NetworkTypes.GSM_FAMILY)
        for (mode in NetworkMode.entries) {
            assertEquals(mode, NetworkTypes.modeOf(NetworkTypes.bitmaskFor(mode)))
        }
    }

    @Test
    fun allowedTypesOutputIsParsedInEveryForm() {
        val lteOnly = NetworkTypes.bitmaskFor(NetworkMode.LTE_ONLY)
        assertEquals(lteOnly, NetworkTypes.parseAllowed("LTE|LTE_CA\n"))
        assertEquals(lteOnly, NetworkTypes.parseAllowed("1000001000000000000"))
        assertEquals(lteOnly, NetworkTypes.parseAllowed("266240"))
        val full = NetworkTypes.parseAllowed(
            "GPRS|EDGE|UMTS|CDMA|CDMA - EvDo rev. 0|CDMA - EvDo rev. A|CDMA - 1xRTT|HSDPA|HSUPA|HSPA|" +
                "CDMA - EvDo rev. B|LTE|CDMA - eHRPD|HSPA+|GSM|TD_SCDMA|LTE_CA|NR",
        )
        assertEquals(NetworkMode.AUTO, full?.let(NetworkTypes::modeOf))
        assertEquals(NetworkMode.NR_LTE, NetworkTypes.parseAllowed("LTE|NR")?.let(NetworkTypes::modeOf))
        // A 4G-only phone: everything except NR is "auto".
        assertEquals(NetworkMode.AUTO, NetworkTypes.parseAllowed("GPRS|EDGE|UMTS|HSPA|LTE|GSM")?.let(NetworkTypes::modeOf))
        assertEquals(NetworkMode.WCDMA_ONLY, NetworkTypes.parseAllowed("UMTS|HSDPA|HSUPA|HSPA|HSPA+")?.let(NetworkTypes::modeOf))
        assertNull(NetworkTypes.parseAllowed("UNKNOWN"))
        assertNull(NetworkTypes.parseAllowed("LTE|WIMAX"))
        assertNull(NetworkTypes.parseAllowed(""))
        assertNull(NetworkTypes.modeOf(NetworkTypes.GSM_FAMILY))
    }
}
