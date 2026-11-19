package com.hotspotmonitor.agent.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArgsAndJsonTest {
    private val args = buildJsonObject {
        put("enabled", true)
        put("off_seconds", 10.0)
        put("level", 300)
        put("package", "com.example")
        put("text_number", "12")
        put("nothing", JsonNull)
    }

    @Test
    fun optionalReadersReturnNullForMissingOrWrongTypes() {
        assertEquals(true, args.optBoolean("enabled"))
        assertEquals(10, args.optInt("off_seconds"))
        assertEquals("com.example", args.optString("package"))
        assertNull(args.optInt("text_number"))
        assertNull(args.optString("level"))
        assertNull(args.optBoolean("nothing"))
        assertNull(args.optInt("missing"))
        assertNull(buildJsonObject { put("x", 1.5) }.optInt("x"))
    }

    @Test
    fun requiredReadersThrowInvalidArgs() {
        assertEquals("com.example", args.requireString("package"))
        assertThrows(InvalidArgsException::class.java) { args.requireBoolean("missing") }
        assertThrows(InvalidArgsException::class.java) { args.requireInt("level", 0..255) }
        assertThrows(InvalidArgsException::class.java) { buildJsonObject { put("s", " ") }.requireString("s") }
        assertNull(args.optInt("missing", 0..255))
        assertThrows(InvalidArgsException::class.java) { args.optInt("level", 0..255) }
    }

    @Test
    fun jsonObjectOfEncodesKotlinValues() {
        val obj = jsonObjectOf(
            "n" to 1, "s" to "x", "b" to false, "null" to null,
            "kind" to EventKind.SETTINGS_APPLIED, "net" to NetType.LTE, "list" to listOf(1, "a"),
            "map" to mapOf("k" to 2.5),
        )
        assertEquals(JsonPrimitive(1), obj["n"])
        assertEquals(JsonNull, obj["null"])
        assertEquals(JsonPrimitive("settings_applied"), obj["kind"])
        assertEquals(JsonPrimitive("LTE"), obj["net"])
        assertEquals(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive("a"))), obj["list"])
        assertEquals(2.5, (obj["map"] as kotlinx.serialization.json.JsonObject).optDouble("k")!!, 0.0)
    }

    @Test
    fun deepMergeFollowsMergeSettingsSemantics() {
        val base = buildJsonObject {
            put("a", 1)
            put("nested", buildJsonObject { put("x", 1); put("y", 2) })
            put("arr", JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))))
        }
        val patch = buildJsonObject {
            put("nested", buildJsonObject { put("y", 3); put("z", 4) })
            put("arr", JsonArray(listOf(JsonPrimitive(9))))
            put("new", "v")
        }
        val merged = base.deepMerge(patch)
        assertEquals(buildJsonObject { put("x", 1); put("y", 3); put("z", 4) }, merged["nested"])
        assertEquals(JsonArray(listOf(JsonPrimitive(9))), merged["arr"])
        assertEquals(JsonPrimitive(1), merged["a"])
        assertEquals(JsonPrimitive("v"), merged["new"])
    }

    @Test
    fun commandCatalogMatchesCommandsTs() {
        assertEquals(30, CommandNames.ALL.size)
        assertEquals(CommandNames.ALL.toSet(), COMMANDS.keys)
        assertTrue(COMMANDS.getValue(CommandNames.REBOOT).isReboot)
        val off = buildJsonObject { put("enabled", false) }
        val on = buildJsonObject { put("enabled", true) }
        assertEquals(RevertConfirm.DASHBOARD, COMMANDS.getValue(CommandNames.USB_TETHERING).revertFor(off))
        assertNull(COMMANDS.getValue(CommandNames.USB_TETHERING).revertFor(on))
        assertEquals(RevertConfirm.AGENT_LINK, COMMANDS.getValue(CommandNames.AIRPLANE_MODE).revertFor(on))
        assertEquals(RevertConfirm.AGENT_LINK, COMMANDS.getValue(CommandNames.NETWORK_MODE).revertFor(buildJsonObject { }))
        assertEquals(NetworkMode.LTE_ONLY, NetworkMode.fromWire("lte_only"))
        assertNull(NetworkMode.fromWire("6g"))
    }
}
