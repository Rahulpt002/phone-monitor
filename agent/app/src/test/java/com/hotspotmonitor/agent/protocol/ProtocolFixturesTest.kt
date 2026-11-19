package com.hotspotmonitor.agent.protocol

import com.hotspotmonitor.agent.testutil.Fixtures
import com.hotspotmonitor.agent.testutil.canonical
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass

/** Every message in shared/fixtures decodes into the right Kotlin type and re-encodes equivalently. */
class ProtocolFixturesTest {
    private val agentTypes: Map<String, KClass<out AgentToServer>> = mapOf(
        "telemetry" to TelemetryMsg::class,
        "event" to EventMsg::class,
        "finder_spot" to FinderSpotMsg::class,
        "finder_spot_deleted" to FinderSpotDeletedMsg::class,
        "sms" to SmsMsg::class,
        "cmd_ack" to CmdAckMsg::class,
        "cmd_result" to CmdResultMsg::class,
        "cmd_revert" to CmdRevertMsg::class,
        "batch" to BatchMsg::class,
        "heartbeat" to HeartbeatMsg::class,
        "probe" to ProbeMsg::class,
        "finder_live" to FinderLiveMsg::class,
    )

    private val serverTypes: Map<String, KClass<out ServerToAgent>> = mapOf(
        "hello" to HelloMsg::class,
        "settings" to SettingsMsg::class,
        "rates" to RatesMsg::class,
        "cmd" to CmdMsg::class,
        "cmd_keep" to CmdKeepMsg::class,
        "ack" to AckMsg::class,
        "probe_ack" to ProbeAckMsg::class,
        "error" to ErrorMsg::class,
    )

    private fun JsonObject.t(): String = getValue("t").jsonPrimitive.content

    @Test
    fun agentToServerFixturesRoundTrip() {
        val fixtures = Fixtures.objects("agent-to-server.json")
        assertEquals("every Agent -> Server type has a fixture", agentTypes.keys, fixtures.map { it.t() }.toSet())
        for (obj in fixtures) {
            val t = obj.t()
            val msg = AgentJson.decodeFromJsonElement(AgentToServer.serializer(), obj)
            assertEquals("type of '$t'", agentTypes.getValue(t), msg::class)
            val encoded = AgentJson.encodeToJsonElement(AgentToServer.serializer(), msg)
            assertEquals("round trip of '$t'", canonical(obj), canonical(encoded))
            // The string path the sender uses gives the same result.
            val viaWire = AgentJson.parseToJsonElement(Wire.encode(msg))
            assertEquals("Wire.encode of '$t'", canonical(obj), canonical(viaWire))
            assertEquals("decodeAgentMessage of '$t'", msg, Wire.decodeAgentMessage(Wire.encode(msg)))
        }
    }

    @Test
    fun serverToAgentFixturesRoundTrip() {
        val fixtures = Fixtures.objects("server-to-agent.json")
        assertEquals("every Server -> Agent type has a fixture", serverTypes.keys, fixtures.map { it.t() }.toSet())
        for (obj in fixtures) {
            val t = obj.t()
            val msg = Wire.decodeServerMessage(obj.toString())
            assertNotNull("decode of '$t'", msg)
            assertEquals("type of '$t'", serverTypes.getValue(t), msg!!::class)
            val encoded = AgentJson.parseToJsonElement(Wire.encodeServer(msg))
            assertEquals("round trip of '$t'", canonical(obj), canonical(encoded))
        }
    }

    @Test
    fun agentFixtureValuesDecodeIntoTypedFields() {
        val msgs = Fixtures.objects("agent-to-server.json").map {
            AgentJson.decodeFromJsonElement(AgentToServer.serializer(), it)
        }
        val fast = msgs.filterIsInstance<TelemetryMsg>().first { it.tier == TelemetryTier.FAST }
        assertEquals(101L, fast.seq)
        assertEquals(NetType.NR_NSA, fast.radio!!.netType)
        assertEquals(68_719_476_735L, fast.radio!!.nr!!.nci)
        assertEquals(SimState.READY, fast.radio!!.simState)
        assertEquals(12.5, fast.radio!!.sinr!!, 0.0)
        assertTrue(fast.net!!.online)

        val slow = msgs.filterIsInstance<TelemetryMsg>().first { it.tier == TelemetryTier.SLOW }
        assertEquals(StopReason.LIMIT, slow.power!!.stopReason)
        assertEquals(-35, slow.power!!.currentMa)
        assertEquals(912_345_678_901L, slow.traffic!!.perIf!!.getValue("rmnet_data2").rx)
        assertEquals(listOf(0.42, 0.51, 0.6), slow.system!!.cpuLoad)
        assertEquals(UsbFunction.RNDIS, slow.tether!!.usbFunction)

        val static = msgs.filterIsInstance<TelemetryMsg>().first { it.tier == TelemetryTier.STATIC }
        assertEquals("samsung_galaxy_s", static.device!!.profileId)
        assertFalse(static.capabilities!!.getValue(CommandNames.NETWORK_MODE).supported)

        val event = msgs.filterIsInstance<EventMsg>().single().event
        assertEquals(EventKind.WATCHDOG_ACTION, event.kind)
        assertEquals(Severity.WARNING, event.severity)
        assertEquals("data_cycle", event.data!!.optString("action"))

        val batch = msgs.filterIsInstance<BatchMsg>().single()
        assertEquals(listOf(7L, 8L), batch.items.map { it.seq })
        assertTrue(batch.items.all { it is TelemetryMsg })

        val rejected = msgs.filterIsInstance<CmdAckMsg>().single { !it.accepted }
        assertEquals(CmdRejectReason.EXPIRED, rejected.reason)
        assertEquals(RevertState.REVERTED, msgs.filterIsInstance<CmdRevertMsg>().single().state)
        assertEquals(SmsDirection.IN, msgs.filterIsInstance<SmsMsg>().single().sms.direction)

        val spot = msgs.filterIsInstance<FinderSpotMsg>().single().spot
        assertEquals(42.1, spot.bestDownMbps!!, 0.0)
        assertEquals(30, spot.metrics.score.samples)
        assertEquals(FinderMode.WALK, msgs.filterIsInstance<FinderLiveMsg>().single().live.mode)
    }

    @Test
    fun serverFixtureValuesDecodeIntoTypedFields() {
        val msgs = Fixtures.objects("server-to-agent.json").map { Wire.decodeServerMessage(it.toString())!! }
        val hello = msgs.filterIsInstance<HelloMsg>().single()
        assertEquals(Settings.DEFAULT, hello.settings)
        assertEquals(3, hello.settingsVersion)
        assertEquals(Rates(fastS = 60, mirror = false), hello.rates)
        assertEquals(4, msgs.filterIsInstance<SettingsMsg>().single().version)
        assertEquals(Rates(5, true), msgs.filterIsInstance<RatesMsg>().single().toRates())

        val cmds = msgs.filterIsInstance<CmdMsg>()
        val cycle = cmds.first { it.name == CommandNames.AIRPLANE_CYCLE }
        assertEquals(10, cycle.args.optInt("off_seconds"))
        assertNull(cycle.revertConfirm)
        val dataOff = cmds.first { it.name == CommandNames.MOBILE_DATA }
        assertEquals(false, dataOff.args.optBoolean("enabled"))
        assertEquals(120, dataOff.revertAfterS)
        assertEquals(RevertConfirm.AGENT_LINK, dataOff.revertConfirm)
        assertEquals(RevertConfirm.AGENT_LINK, COMMANDS.getValue(dataOff.name).revertFor(dataOff.args))

        assertEquals(112L, msgs.filterIsInstance<AckMsg>().single().seq)
        assertEquals(ErrorCodes.UNAUTHORIZED, msgs.filterIsInstance<ErrorMsg>().single().code)
    }

    @Test
    fun unknownMessageTypesAndMalformedFramesDecodeToNull() {
        assertNull(Wire.decodeServerMessage("""{"t":"something_new","x":1}"""))
        assertNull(Wire.decodeServerMessage("""{"no_type":true}"""))
        assertNull(Wire.decodeServerMessage("not json"))
        assertNull(Wire.decodeServerMessage("""{"t":"ack"}""")) // required field missing
        assertNull(Wire.decodeAgentMessage("""{"t":"something_new"}"""))
        assertEquals("something_new", Wire.typeOf("""{"t":"something_new","x":1}"""))
        assertNull(Wire.typeOf("[1,2]"))
    }

    @Test
    fun unknownFieldsAndValuesAreTolerated() {
        assertEquals(AckMsg(5), Wire.decodeServerMessage("""{"t":"ack","seq":5,"extra":{"nested":[1,2]},"v":2}"""))

        val cmd = Wire.decodeServerMessage(
            """{"t":"cmd","id":"c_1","name":"future_command","args":{"x":1},"sent_at":"2026-11-19T10:16:00.000Z",
               "revert_confirm":"some_new_mode","revert_after_s":90.0,"priority":"high"}""",
        ) as CmdMsg
        assertEquals("future_command", cmd.name)
        assertNull("unknown enum value falls back to null", cmd.revertConfirm)
        assertEquals(90, cmd.revertAfterS)

        // A partial settings object (newer or older Server) keeps defaults for the rest.
        val settings = Wire.decodeServerMessage(
            """{"t":"settings","version":7,"settings":{"watchdog":{"enabled":false,"internet_fail_s":30.6,"new_rule":true},"brand_new_group":{}}}""",
        ) as SettingsMsg
        assertFalse(settings.settings.watchdog.enabled)
        assertEquals(31, settings.settings.watchdog.internetFailS)
        assertEquals(Settings.DEFAULT.telemetry, settings.settings.telemetry)
        assertEquals(7, settings.version)
    }

    /** Removes every null-valued key, at any depth (what a Server that omits nulls would send). */
    private fun stripNulls(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.filterValues { it !is JsonNull }.mapValues { stripNulls(it.value) })
        is JsonArray -> JsonArray(element.map { stripNulls(it) })
        else -> element
    }

    private fun decodeServer(obj: JsonObject): ServerToAgent? = Wire.decodeServerMessage(obj.toString())

    @Test
    fun missingAndNullFieldsDecodeTheSame() {
        val fixtures = Fixtures.objects("server-to-agent.json")
        val withNulls = fixtures.filter { it.toString() != stripNulls(it).toString() }.map { it.t() }.toSet()
        assertTrue("the fixture exercises null-valued keys: $withNulls", withNulls.containsAll(setOf("hello", "settings", "cmd")))
        for (obj in fixtures) {
            val t = obj.t()
            val original = decodeServer(obj)
            assertNotNull("decode of '$t'", original)
            val stripped = stripNulls(obj) as JsonObject
            assertEquals("'$t' with every null-valued key removed", original, decodeServer(stripped))
        }

        // The other way round: an explicit null for a field that has a default (coerceInputValues)
        // decodes exactly like the missing field instead of failing the message.
        val hello = fixtures.first { it.t() == "hello" }
        val helloNullRates = decodeServer(JsonObject(hello + ("rates" to JsonNull))) as HelloMsg
        assertEquals(decodeServer(JsonObject(hello - "rates")), helloNullRates)
        assertEquals(Rates(), helloNullRates.rates)

        val cmd = fixtures.first { it.t() == "cmd" }
        val cmdNulls = JsonObject(cmd + mapOf("args" to JsonNull, "user" to JsonNull, "revert_after_s" to JsonNull))
        assertEquals(decodeServer(JsonObject(cmd - setOf("args", "user", "revert_after_s"))), decodeServer(cmdNulls))
        assertEquals(JsonObject(emptyMap()), (decodeServer(cmdNulls) as CmdMsg).args)

        val error = fixtures.first { it.t() == "error" }
        assertEquals(decodeServer(JsonObject(error - "message")), decodeServer(JsonObject(error + ("message" to JsonNull))))

        // Nested settings: a null group or value keeps the default, like a missing one.
        val settingsNull = Wire.decodeServerMessage(
            """{"t":"settings","version":8,"settings":{"telemetry":null,"watchdog":{"enabled":null,"internet_fail_s":null},"profile_override":null}}""",
        ) as SettingsMsg
        val settingsMissing = Wire.decodeServerMessage("""{"t":"settings","version":8,"settings":{"watchdog":{}}}""") as SettingsMsg
        assertEquals(settingsMissing, settingsNull)
        assertEquals(Settings.DEFAULT.watchdog, settingsNull.settings.watchdog)
    }

    @Test
    fun encodingOmitsNullsAndCarriesTheDiscriminator() {
        assertEquals("""{"t":"probe","id":"p_1"}""", Wire.encode(ProbeMsg("p_1")))
        val msg = TelemetryMsg(seq = 3, ts = "2026-11-19T10:15:00.000Z", tier = TelemetryTier.FAST, net = NetInfo(online = false))
        val json = Wire.encodeBuffered(msg)
        assertFalse("nulls are omitted: $json", json.contains("null"))
        val obj = AgentJson.parseToJsonElement(json).jsonObject
        assertEquals("telemetry", obj.t())
        assertEquals(3, obj.getValue("seq").jsonPrimitive.content.toInt())
        assertEquals(setOf("t", "seq", "ts", "tier", "net"), obj.keys)
    }

    @Test
    fun batchFrameWrapsEncodedItems() {
        val items = listOf(
            EventMsg(1, AgentEvent("e_1", "2026-11-19T10:15:00.000Z", EventKind.BOOT, Severity.INFO, "booted")),
            FinderSpotDeletedMsg(2, "s_x"),
        )
        val frame = Wire.batchFrame(items.map { Wire.encodeBuffered(it) })
        val decoded = Wire.decodeAgentMessage(frame) as BatchMsg
        assertEquals(items, decoded.items)
    }
}
