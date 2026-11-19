@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.hotspotmonitor.agent.sync

import com.hotspotmonitor.agent.core.Clock
import com.hotspotmonitor.agent.core.ConnPhase
import com.hotspotmonitor.agent.core.MemoryStore
import com.hotspotmonitor.agent.core.OutboxStore
import com.hotspotmonitor.agent.core.SettingsRepo
import com.hotspotmonitor.agent.protocol.BatchMsg
import com.hotspotmonitor.agent.protocol.CmdMsg
import com.hotspotmonitor.agent.protocol.FinderLive
import com.hotspotmonitor.agent.protocol.FinderLiveMsg
import com.hotspotmonitor.agent.protocol.FinderMode
import com.hotspotmonitor.agent.protocol.ProbeMsg
import com.hotspotmonitor.agent.protocol.Rates
import com.hotspotmonitor.agent.protocol.Settings
import com.hotspotmonitor.agent.protocol.SettingsMsg
import com.hotspotmonitor.agent.protocol.Wire
import com.hotspotmonitor.agent.testutil.FakeOutboxDao
import com.hotspotmonitor.agent.testutil.testLog
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.random.Random

class SyncEngineTest {
    private class Harness(scope: TestScope, config: SyncConfig = SyncConfig(jitter = 0.0)) {
        val clock = VirtualClock(scope.testScheduler)
        val log = testLog()
        val dao = FakeOutboxDao()
        val outbox = OutboxStore(dao, clock, log, scope.backgroundScope)
        val settings = SettingsRepo(MemoryStore(), log)
        val transport = FakeTransport()
        val host = FakeHost()
        val engine = SyncEngine(clock, log, outbox, settings, transport, host, config, Random(7))
    }

    private fun TestScope.started(config: SyncConfig = SyncConfig(jitter = 0.0)): Harness {
        val h = Harness(this, config)
        h.engine.start(backgroundScope)
        runCurrent()
        return h
    }

    private fun TestScope.connected(h: Harness, hello: String = helloFrame()): FakeConnection {
        val c = h.transport.last
        c.acceptWithHello(hello)
        runCurrent()
        return c
    }

    // --- hello -------------------------------------------------------------------------------------

    @Test
    fun helloSetsTheClockOffsetAppliesSettingsAndBringsTheLinkUp() = runTest {
        val h = started()
        assertEquals(1, h.transport.connections.size)
        val c = h.transport.last
        assertEquals("wss://hsm.example.test/ws/agent", c.url)
        assertEquals("tok_secret", c.token)
        assertEquals(ConnPhase.CONNECTING, h.engine.state.value.phase)

        // The Server's clock is 5 s ahead of the phone at receipt.
        val serverTime = Clock.formatIso(h.clock.systemNowMs() + 5_000)
        connected(h, helloFrame(serverTime = serverTime, version = 3))

        assertTrue(h.clock.synced)
        assertEquals(5_000L, h.clock.serverOffsetMs)
        assertEquals(3, h.settings.version.value)
        assertEquals(ConnPhase.CONNECTED, h.engine.state.value.phase)
        assertNull(h.engine.state.value.lastError)
        assertEquals(1, h.host.linkUps)
        assertEquals("one heartbeat right after hello", 1, c.sentOfType("heartbeat").size)

        advanceTimeBy(30_001)
        runCurrent()
        assertEquals("heartbeat every telemetry.heartbeat_s", 2, c.sentOfType("heartbeat").size)
    }

    @Test
    fun settingsAreOnlyAppliedWhenTheVersionDiffers() = runTest {
        val h = Harness(this)
        h.settings.apply(Settings.DEFAULT, 3)
        h.engine.start(backgroundScope)
        runCurrent()
        val changed = Settings.DEFAULT.copy(telemetry = Settings.DEFAULT.telemetry.copy(heartbeatS = 45))
        val c = connected(h, helloFrame(settings = changed, version = 3))
        assertEquals("same version: kept", 30, h.settings.current.telemetry.heartbeatS)

        c.listener.onMessage(Wire.encodeServer(SettingsMsg(settings = changed, version = 4)))
        runCurrent()
        assertEquals(4, h.settings.version.value)
        assertEquals(45, h.settings.current.telemetry.heartbeatS)
    }

    // --- outbox sender -----------------------------------------------------------------------------

    @Test
    fun aSinglePendingRowLeavesAsItselfAndTheAckDeletesIt() = runTest {
        val h = Harness(this)
        h.outbox.enqueue(event(1))
        h.engine.start(backgroundScope)
        runCurrent()
        val c = connected(h)

        assertEquals(1, c.sentOfType("event").size)
        assertTrue(c.sentOfType("batch").isEmpty())
        assertEquals(1, h.dao.all.size)

        c.listener.onMessage("""{"t":"ack","seq":1}""")
        runCurrent()
        assertTrue(h.dao.all.isEmpty())

        // A message produced while connected leaves right away.
        h.outbox.enqueue(event(2))
        runCurrent()
        assertEquals(2, c.sentOfType("event").size)
    }

    @Test
    fun aBacklogLeavesInBatchesOfAtMost200InSeqOrder() = runTest {
        val h = Harness(this)
        repeat(250) { h.outbox.enqueue(event(it)) }
        h.engine.start(backgroundScope)
        runCurrent()
        val c = connected(h)

        val first = Wire.decodeAgentMessage(c.sentOfType("batch").single()) as BatchMsg
        assertEquals((1L..200L).toList(), first.items.map { it.seq })

        c.listener.onMessage("""{"t":"ack","seq":200}""")
        runCurrent()
        assertEquals(50, h.dao.all.size)
        val second = Wire.decodeAgentMessage(c.sentOfType("batch")[1]) as BatchMsg
        assertEquals((201L..250L).toList(), second.items.map { it.seq })

        c.listener.onMessage("""{"t":"ack","seq":250}""")
        runCurrent()
        assertTrue(h.dao.all.isEmpty())
    }

    @Test
    fun anAckNeverDeletesRowsThatWereNotSent() = runTest {
        val h = Harness(this)
        h.outbox.enqueue(event(1))
        h.engine.start(backgroundScope)
        runCurrent()
        val c = connected(h)
        // Row 2 is stored after row 1 was sent; a bogus ack beyond what was sent must not drop it.
        h.outbox.enqueue(event(2))
        c.listener.onMessage("""{"t":"ack","seq":99}""")
        runCurrent()
        assertEquals(listOf(2L), h.dao.all.map { it.seq })
    }

    @Test
    fun noAckWithinTheTimeoutReconnectsAndResends() = runTest {
        val h = Harness(this)
        h.outbox.enqueue(event(1))
        h.engine.start(backgroundScope)
        runCurrent()
        val c1 = connected(h)
        assertEquals(1, c1.sentOfType("event").size)

        advanceTimeBy(30_001)
        runCurrent()
        assertTrue(c1.cancelled)
        assertEquals(ConnPhase.WAITING, h.engine.state.value.phase)
        assertEquals(1, h.dao.all.size)

        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(2, h.transport.connections.size)
        val c2 = connected(h)
        assertEquals("unacked rows are sent again", 1, c2.sentOfType("event").size)
    }

    // --- close codes and reconnects ----------------------------------------------------------------

    @Test
    fun close4001ForgetsThePairingAndStopsReconnectingUntilPairedAgain() = runTest {
        val h = started()
        val c = h.transport.last
        c.listener.onOpen()
        c.listener.onMessage("""{"t":"error","code":"unauthorized","message":"invalid device token"}""")
        c.listener.onClosing(4001, "unauthorized")
        runCurrent()

        assertEquals(ConnPhase.UNAUTHORIZED, h.engine.state.value.phase)
        assertEquals(listOf("d_1"), h.host.unauthorized.map { it.deviceId })
        assertNull(h.host.creds)

        advanceTimeBy(10 * 60_000)
        runCurrent()
        assertEquals("no reconnect while unauthorized", 1, h.transport.connections.size)

        h.host.creds = SyncCredentials("wss://hsm.example.test/ws/agent", "tok_new", "d_2")
        h.engine.onPaired()
        runCurrent()
        assertEquals(2, h.transport.connections.size)
        assertEquals("tok_new", h.transport.last.token)
    }

    @Test
    fun aDroppedCloseFrameAfterTheUnauthorizedErrorStillCountsAs4001() = runTest {
        val h = started()
        val c = h.transport.last
        c.listener.onOpen()
        c.listener.onMessage("""{"t":"error","code":"unauthorized"}""")
        c.listener.onFailure(IOException("connection reset"), null)
        runCurrent()
        assertEquals(ConnPhase.UNAUTHORIZED, h.engine.state.value.phase)
    }

    @Test
    fun close4002WaitsThirtySecondsEvenWhenTheNetworkComesBack() = runTest {
        val h = started()
        val c = connected(h)
        c.listener.onClosing(4002, "replaced")
        runCurrent()
        assertEquals(ConnPhase.WAITING, h.engine.state.value.phase)

        advanceTimeBy(10_000)
        h.engine.onNetworkAvailable()
        runCurrent()
        advanceTimeBy(19_000)
        runCurrent()
        assertEquals(1, h.transport.connections.size)

        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(2, h.transport.connections.size)
    }

    @Test
    fun close4002FollowedByOnClosedStillReconnectsOnceAfterThirtySeconds() = runTest {
        // OkHttp reports both onClosing and onClosed for the same close; only one reconnect may follow.
        val h = started()
        val c = connected(h)
        c.listener.onClosing(4002, "replaced")
        runCurrent()
        c.listener.onClosed(4002, "replaced")
        runCurrent()
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(1, h.transport.connections.size)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, h.transport.connections.size)
        connected(h)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals("no second reconnect from the duplicate close", 2, h.transport.connections.size)
        assertEquals(ConnPhase.CONNECTED, h.engine.state.value.phase)
    }

    @Test
    fun close1001IsANormalReconnectWithBackoff() = runTest {
        // docs/protocol.md 3.1: the Server is shutting down or deploying.
        val h = started()
        val c = connected(h)
        c.listener.onClosing(1001, "server shutting down")
        runCurrent()
        assertEquals(ConnPhase.WAITING, h.engine.state.value.phase)
        assertNotNull(h.host.creds)
        assertTrue(h.host.unauthorized.isEmpty())
        advanceTimeBy(1_999)
        runCurrent()
        assertEquals("first retry after the 2 s backoff, not 30 s", 1, h.transport.connections.size)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, h.transport.connections.size)

        // Render redeploys: refused upgrades while the new instance starts back off further.
        h.transport.last.listener.onFailure(IOException("connection refused"), 502)
        runCurrent()
        advanceTimeBy(3_999)
        runCurrent()
        assertEquals(2, h.transport.connections.size)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(3, h.transport.connections.size)
        connected(h)
        assertEquals(ConnPhase.CONNECTED, h.engine.state.value.phase)
    }

    @Test
    fun closeEventsFromAnAbandonedSocketAreIgnored() = runTest {
        val h = started()
        val old = connected(h)
        // Re-paired: the Agent drops the old socket and connects with the new token.
        h.host.creds = SyncCredentials("wss://hsm.example.test/ws/agent", "tok_2", "d_2")
        h.engine.onPaired()
        runCurrent()
        assertEquals(2, h.transport.connections.size)
        val current = connected(h)
        assertEquals(ConnPhase.CONNECTED, h.engine.state.value.phase)

        // Late callbacks from the old socket: none of them may touch the current link.
        old.listener.onMessage("""{"t":"error","code":"unauthorized"}""")
        old.listener.onClosing(4001, "unauthorized")
        old.listener.onClosed(4002, "replaced")
        old.listener.onFailure(IOException("connection reset"), null)
        runCurrent()
        assertEquals(ConnPhase.CONNECTED, h.engine.state.value.phase)
        assertTrue("4001 from the old socket must not forget the new pairing", h.host.unauthorized.isEmpty())
        assertEquals("tok_2", h.host.creds?.token)
        assertTrue(current.open)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(2, h.transport.connections.size)

        // A socket abandoned after the hello timeout is ignored as well.
        current.listener.onClosed(1006, "")
        runCurrent()
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(3, h.transport.connections.size)
        val third = h.transport.last
        third.listener.onOpen()
        advanceTimeBy(30_001)
        runCurrent()
        assertTrue(third.cancelled)
        advanceTimeBy(4_001) // second failure in a row: 4 s
        runCurrent()
        assertEquals(4, h.transport.connections.size)
        connected(h)
        third.listener.onClosing(4002, "replaced")
        runCurrent()
        assertEquals(ConnPhase.CONNECTED, h.engine.state.value.phase)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(4, h.transport.connections.size)
    }

    @Test
    fun backoffDoublesAndResetsAfterALinkThatStayedUpForAMinute() = runTest {
        val h = started()
        h.transport.last.listener.onFailure(IOException("connection refused"), null)
        runCurrent()
        advanceTimeBy(1_999)
        runCurrent()
        assertEquals("first retry after 2 s", 1, h.transport.connections.size)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, h.transport.connections.size)

        h.transport.last.listener.onFailure(IOException("connection refused"), 502)
        runCurrent()
        advanceTimeBy(3_999)
        runCurrent()
        assertEquals("second retry after 4 s", 2, h.transport.connections.size)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(3, h.transport.connections.size)

        val c3 = connected(h)
        advanceTimeBy(61_000)
        runCurrent()
        c3.listener.onClosed(1006, "")
        runCurrent()
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals("backoff reset after 60 s up", 4, h.transport.connections.size)
    }

    @Test
    fun aNetworkAvailableCallbackSkipsTheBackoffWait() = runTest {
        val h = started()
        h.transport.last.listener.onFailure(IOException("network unreachable"), null)
        runCurrent()
        advanceTimeBy(2_001) // first retry after 2 s
        runCurrent()
        h.transport.last.listener.onFailure(IOException("network unreachable"), null)
        runCurrent()
        advanceTimeBy(4_001) // second retry after 4 s
        runCurrent()
        assertEquals(3, h.transport.connections.size)
        h.transport.last.listener.onFailure(IOException("network unreachable"), null)
        runCurrent()

        // Waiting 8 s now; the network returns after 1 s.
        advanceTimeBy(1_000)
        h.engine.onNetworkAvailable()
        runCurrent()
        advanceTimeBy(1_001) // short settle pause, not the remaining 7 s
        runCurrent()
        assertEquals(4, h.transport.connections.size)
    }

    @Test
    fun noHelloWithinTheTimeoutReconnects() = runTest {
        val h = started()
        h.transport.last.listener.onOpen()
        runCurrent()
        advanceTimeBy(30_001)
        runCurrent()
        assertTrue(h.transport.last.cancelled)
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(2, h.transport.connections.size)
    }

    @Test
    fun aSlowUpgradeFromASleepingServerIsNotCutByTheHelloTimeout() = runTest {
        val h = started()
        // A free-tier Server boots for 60 s before it answers the upgrade (OkHttp read timeout 75 s).
        advanceTimeBy(60_000)
        runCurrent()
        val c = h.transport.last
        assertFalse("the hello timer starts when the socket opens", c.cancelled)
        assertEquals(1, h.transport.connections.size)
        advanceTimeBy(5_000)
        c.acceptWithHello()
        runCurrent()
        assertEquals(ConnPhase.CONNECTED, h.engine.state.value.phase)
        assertEquals(1, h.host.linkUps)
    }

    @Test
    fun anUpgradeThatNeverCompletesStillReconnects() = runTest {
        val h = started()
        advanceTimeBy(100_001)
        runCurrent()
        assertTrue(h.transport.last.cancelled)
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(2, h.transport.connections.size)
    }

    @Test
    fun newPairingReplacesTheSocketAndUnpairingStopsTheLink() = runTest {
        val h = started()
        val c1 = connected(h)
        h.host.creds = SyncCredentials("wss://other.example.test/ws/agent", "tok_2", "d_2")
        h.engine.onPaired()
        runCurrent()
        assertEquals(1000, c1.closedWith)
        assertEquals(2, h.transport.connections.size)
        assertEquals("wss://other.example.test/ws/agent", h.transport.last.url)

        connected(h)
        h.host.creds = null
        h.engine.onUnpaired()
        runCurrent()
        assertEquals(ConnPhase.NOT_PAIRED, h.engine.state.value.phase)
        assertFalse(h.transport.last.open)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(2, h.transport.connections.size)
    }

    @Test
    fun notPairedWaitsWithoutConnecting() = runTest {
        val h = Harness(this)
        h.host.creds = null
        h.engine.start(backgroundScope)
        runCurrent()
        assertEquals(ConnPhase.NOT_PAIRED, h.engine.state.value.phase)
        assertTrue(h.transport.connections.isEmpty())
    }

    // --- Server messages ---------------------------------------------------------------------------

    @Test
    fun ratesUpdateTheFlowAndTheFinderMirror() = runTest {
        val h = started()
        assertEquals("offline: fast_idle_s", Rates(fastS = 60, mirror = false), h.engine.rates.value)
        val c = connected(h, helloFrame(rates = Rates(fastS = 5, mirror = true)))
        assertEquals(Rates(fastS = 5, mirror = true), h.engine.rates.value)
        assertEquals(listOf(true), h.host.mirror)

        c.listener.onMessage("""{"t":"rates","fast_s":60,"mirror":false}""")
        runCurrent()
        assertEquals(Rates(fastS = 60, mirror = false), h.engine.rates.value)
        assertEquals(listOf(true, false), h.host.mirror)

        c.listener.onMessage("""{"t":"rates","fast_s":5,"mirror":true}""")
        runCurrent()
        c.listener.onClosed(1006, "")
        runCurrent()
        assertEquals("back to idle rates while offline", Rates(fastS = 60, mirror = false), h.engine.rates.value)
        assertEquals(listOf(true, false, true, false), h.host.mirror)
    }

    @Test
    fun commandsKeepsAndProbeAcksAreDispatched() = runTest {
        val h = started()
        val c = connected(h)
        c.listener.onMessage(Wire.encodeServer(CmdMsg(id = "c_1", name = "refresh", sentAt = TS)))
        c.listener.onMessage("""{"t":"cmd_keep","id":"c_9"}""")
        val serverTime = Clock.formatIso(h.clock.systemNowMs() + 7_000)
        c.listener.onMessage("""{"t":"probe_ack","id":"p_1","server_time":"$serverTime"}""")
        runCurrent()
        assertEquals(listOf("c_1"), h.host.commands.map { it.id })
        assertEquals(listOf("c_9"), h.host.keeps)
        assertEquals(listOf("p_1"), h.host.probeAcks)
        assertEquals("probe_ack refreshes the clock offset", 7_000L, h.clock.serverOffsetMs)
    }

    @Test
    fun unknownAndMalformedFramesAreIgnored() = runTest {
        val h = started()
        val c = connected(h)
        c.listener.onMessage("""{"t":"something_new","x":1}""")
        c.listener.onMessage("not json at all")
        c.listener.onMessage("""{"t":"ack"}""")
        runCurrent()
        assertEquals(ConnPhase.CONNECTED, h.engine.state.value.phase)
        assertEquals(1, h.transport.connections.size)
        assertTrue(c.open)
    }

    // --- live messages -----------------------------------------------------------------------------

    @Test
    fun liveMessagesNeedTheLinkAndBufferedTypesAreRefused() = runTest {
        val h = started()
        assertFalse(h.engine.sendLive(ProbeMsg("p_early")))
        assertNull(h.engine.sendProbe())

        val c = connected(h)
        val id = h.engine.sendProbe()
        assertNotNull(id)
        assertTrue(id!!.startsWith("p_"))
        assertTrue(c.sentOfType("probe").single().contains(id))
        assertFalse("buffered messages go through the outbox", h.engine.sendLive(event(5)))
        assertTrue(c.sentOfType("event").isEmpty())
    }

    @Test
    fun mirrorFallbackOnlySendsWhenTheFinderIsSilent() = runTest {
        val h = started()
        val live = FinderLive(ts = TS, mode = FinderMode.WALK, score = 50.0)
        h.host.live = live
        val c = connected(h, helloFrame(rates = Rates(fastS = 5, mirror = true)))

        advanceTimeBy(1_001)
        runCurrent()
        assertEquals("the Finder sent nothing: fallback", 1, c.sentOfType("finder_live").size)

        repeat(5) {
            assertTrue(h.engine.sendLive(FinderLiveMsg(live)))
            advanceTimeBy(1_000)
            runCurrent()
        }
        assertEquals("the Finder streams itself: no duplicates", 6, c.sentOfType("finder_live").size)
    }

    @Test
    fun oversizedHeadRowIsDroppedInsteadOfBlockingTheOutbox() = runTest {
        val h = Harness(this, SyncConfig(jitter = 0.0, maxFrameBytes = 400))
        h.outbox.enqueue(event(1, message = "x".repeat(1_000)))
        h.outbox.enqueue(event(2))
        h.engine.start(backgroundScope)
        runCurrent()
        val c = connected(h)
        val sent = c.sentOfType("event")
        assertEquals(1, sent.size)
        assertTrue(sent.single().contains("\"seq\":2"))
        assertEquals(listOf(2L), h.dao.all.map { it.seq })
    }

    @Test
    fun stopClosesTheSocketAndReportsStopped() = runTest {
        val h = started()
        val c = connected(h)
        h.engine.stop()
        runCurrent()
        assertEquals(1000, c.closedWith)
        assertEquals(ConnPhase.STOPPED, h.engine.state.value.phase)
        assertFalse(h.engine.sendLive(ProbeMsg("p_late")))
    }
}
