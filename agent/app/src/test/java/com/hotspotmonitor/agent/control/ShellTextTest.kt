package com.hotspotmonitor.agent.control

import com.hotspotmonitor.agent.protocol.AgentJson
import com.hotspotmonitor.agent.protocol.ProtocolLimits
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellTextTest {
    @Test
    fun outputIsTruncatedTo64KiBOnACharacterBoundary() {
        val short = "hello"
        assertSame(short, OutputText.truncate(short))

        val ascii = "x".repeat(ProtocolLimits.MAX_OUTPUT_BYTES + 10)
        val cut = OutputText.truncate(ascii)
        assertTrue(cut.toByteArray(Charsets.UTF_8).size <= ProtocolLimits.MAX_OUTPUT_BYTES)
        assertTrue(cut.endsWith("[truncated]"))

        // 3-byte characters: the cut must not split one.
        val wide = "€".repeat(30_000)
        val cutWide = OutputText.truncate(wide, maxBytes = 1_000)
        val bytes = cutWide.toByteArray(Charsets.UTF_8)
        assertTrue(bytes.size <= 1_000)
        assertFalse(cutWide.contains('�'))
        assertEquals(cutWide, String(bytes, Charsets.UTF_8))
    }

    @Test
    fun stdoutAndStderrAreCombined() {
        assertEquals("out", OutputText.combine("out\n", ""))
        assertEquals("out\n[stderr] bad", OutputText.combine("out", "bad\n"))
        assertEquals("[stderr] bad", OutputText.combine("", "bad"))
    }

    @Test
    fun appListIsBuiltFromPmOutput() {
        val all = AppListParser.packages(
            "package:com.android.phone\npackage:/data/app/~~x==/com.example-1/base.apk=com.example\n\nWARNING: noise\npackage:com.bloat\n",
        )
        assertEquals(setOf("com.android.phone", "com.example", "com.bloat"), all)
        val json = AppListParser.toJson(all, disabled = setOf("com.bloat"), system = setOf("com.android.phone", "com.bloat"))
        val items = AgentJson.parseToJsonElement(json).jsonArray.map { it.jsonObject }
        assertEquals(listOf("com.android.phone", "com.bloat", "com.example"), items.map { it.getValue("pkg").jsonPrimitive.content })
        val bloat = items[1]
        assertFalse(bloat.getValue("enabled").jsonPrimitive.boolean)
        assertTrue(bloat.getValue("system").jsonPrimitive.boolean)
        assertTrue(items[2].getValue("enabled").jsonPrimitive.boolean)
        assertFalse(items[2].getValue("system").jsonPrimitive.boolean)
    }

    @Test
    fun tetherInterfaceIsFoundInIpAddrOutput() {
        val output = """
            1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536 qdisc noqueue state UNKNOWN group default qlen 1000
                inet 127.0.0.1/8 scope host lo
            14: rmnet_data2@rmnet_ipa0: <UP,LOWER_UP> mtu 1500 qdisc mq state UNKNOWN group default qlen 1000
                inet 10.71.33.5/30 scope global rmnet_data2
            27: rndis0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc mq state UP group default qlen 1000
                inet 192.168.42.129/24 brd 192.168.42.255 scope global rndis0
        """.trimIndent()
        assertEquals(TetherIface("rndis0", "192.168.42.129"), TetherIfaces.find(output))
        assertEquals(
            TetherIface("ncm0", "192.168.49.1"),
            TetherIfaces.find("5: ncm0: <UP> mtu 1500\n    inet 192.168.49.1/24 scope global ncm0"),
        )
        assertNull(TetherIfaces.find(output.lines().take(4).joinToString("\n")))
        assertNull("an interface without an address is not up", TetherIfaces.find("27: rndis0: <BROADCAST,MULTICAST> mtu 1500"))
    }
}
