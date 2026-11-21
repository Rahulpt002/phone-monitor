package com.hotspotmonitor.agent.protocol

import com.hotspotmonitor.agent.screen.ScreenMirrorImpl
import com.hotspotmonitor.agent.testutil.Fixtures
import com.hotspotmonitor.agent.testutil.canonical
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** Remote screen wire formats: relay control JSON, scrcpy 5.0.1 control bytes, video frame splitting. */
class ScreenProtocolTest {
    @Test
    fun everyRelayFixtureDecodes() {
        val root = Fixtures.element("screen-relay.json").jsonObject
        val control = root.getValue("control").jsonArray
        val decoded = control.map { ScreenWire.decodeControl(it.toString()) }
        decoded.forEachIndexed { i, msg -> assertNotNull("control[$i] ${control[i]}", msg) }
        assertEquals(ScreenTouch("down", 0, 0.5, 0.25), decoded[0])
        assertEquals(ScreenScroll(0.5, 0.5, 0.0, 2.0), decoded[3])
        assertEquals(ScreenKey("back"), decoded[4])
        assertEquals(ScreenText("hello 123"), decoded[6])
        assertEquals(ScreenPanel("notifications"), decoded[7])
        assertEquals(ScreenKeyframe, decoded[8])
        // Every key the Dashboard can send has a keycode.
        for (m in decoded.filterIsInstance<ScreenKey>()) assertNotNull(ScrcpyControl.KEYCODES[m.key])

        // screen_meta from the Agent matches the TypeScript shape.
        val meta = ScreenMetaMsg(width = 576, height = 1280, deviceName = "SM-M526B")
        assertEquals(canonical(root.getValue("meta")), canonical(AgentJson.parseToJsonElement(ScreenWire.encode(meta))))
    }

    @Test
    fun unknownOrMalformedControlIsIgnored() {
        assertNull(ScreenWire.decodeControl("""{"t":"teleport"}"""))
        assertNull(ScreenWire.decodeControl("not json"))
        assertNull(ScreenWire.decodeControl("""{"t":"touch","action":"down"}""")) // no x, y
    }

    @Test
    fun touchMatchesScrcpyLayout() {
        val b = ScrcpyControl.touch(ScreenTouch("down", 1, 0.5, 0.25), 576, 1280)!!
        assertEquals(32, b.size)
        val buf = ByteBuffer.wrap(b)
        assertEquals(ScrcpyControl.TYPE_INJECT_TOUCH_EVENT, buf.get().toInt())
        assertEquals(ScrcpyControl.MOTION_ACTION_DOWN, buf.get().toInt())
        assertEquals(1L, buf.getLong()) // pointer id
        assertEquals(288, buf.getInt()) // x px
        assertEquals(320, buf.getInt()) // y px
        assertEquals(576, buf.getShort().toInt() and 0xffff)
        assertEquals(1280, buf.getShort().toInt() and 0xffff)
        assertEquals(0xffff, buf.getShort().toInt() and 0xffff) // pressure 1.0
        assertEquals(0, buf.getInt())
        assertEquals(0, buf.getInt())

        val up = ByteBuffer.wrap(ScrcpyControl.touch(ScreenTouch("up", 0, 1.0, 1.0), 576, 1280)!!)
        up.position(10)
        assertEquals(575, up.getInt()) // clamped to the last pixel
        assertEquals(1279, up.getInt())
        up.position(22)
        assertEquals(0, up.getShort().toInt()) // pressure 0 on release

        assertNull("no video size yet", ScrcpyControl.touch(ScreenTouch("down", 0, 0.5, 0.5), 0, 0))
        assertNull(ScrcpyControl.touch(ScreenTouch("hover", 0, 0.5, 0.5), 576, 1280))
    }

    @Test
    fun keyIsDownThenUp() {
        val msgs = ScrcpyControl.encode(ScreenKey("home"), 576, 1280)!!
        assertEquals(2, msgs.size)
        val down = ByteBuffer.wrap(msgs[0])
        assertEquals(14, msgs[0].size)
        assertEquals(ScrcpyControl.TYPE_INJECT_KEYCODE, down.get().toInt())
        assertEquals(ScrcpyControl.KEY_ACTION_DOWN, down.get().toInt())
        assertEquals(3, down.getInt()) // KEYCODE_HOME
        assertEquals(ScrcpyControl.KEY_ACTION_UP, msgs[1][1].toInt())
        assertNull(ScrcpyControl.encode(ScreenKey("selfdestruct"), 576, 1280))
    }

    @Test
    fun textIsLengthPrefixedUtf8AndCapped() {
        val b = ScrcpyControl.text("héllo")!!
        val buf = ByteBuffer.wrap(b)
        assertEquals(ScrcpyControl.TYPE_INJECT_TEXT, buf.get().toInt())
        assertEquals(6, buf.getInt()) // é is two bytes
        assertEquals("héllo", String(b, 5, 6, Charsets.UTF_8))
        assertEquals(5 + ScrcpyControl.MAX_TEXT_BYTES, ScrcpyControl.text("x".repeat(1000))!!.size)
        assertNull(ScrcpyControl.text(""))
    }

    @Test
    fun scrollUsesFixedPointWithInvertedVerticalAxis() {
        val b = ByteBuffer.wrap(ScrcpyControl.scroll(ScreenScroll(0.5, 0.5, 0.0, 16.0), 100, 200)!!)
        assertEquals(21, b.capacity())
        b.position(13)
        assertEquals(0, b.getShort().toInt()) // h
        assertEquals(-32768, b.getShort().toInt()) // v = -1.0 (content moves up)
    }

    @Test
    fun panelAndKeyframeAreSingleBytes() {
        assertArrayEquals(byteArrayOf(5), ScrcpyControl.encode(ScreenPanel("notifications"), 1, 1)!!.single())
        assertArrayEquals(byteArrayOf(17), ScrcpyControl.encode(ScreenKeyframe, 1, 1)!!.single())
    }

    @Test
    fun framesSplitBigPacketsAndKeepTheHeader() {
        val flags = ScreenWire.FLAG_KEY or 123_456L
        val payload = ByteArray(1000) { (it % 251).toByte() }
        val frames = ScreenWire.frames(flags, payload, maxChunk = 300)
        // 300 - 13 = 287 in the first frame, then 299 per continuation: 287 + 299 + 299 + 115.
        assertEquals(listOf(300, 300, 300, 116), frames.map { it.size })
        val first = ByteBuffer.wrap(frames[0])
        assertEquals(ScreenWire.FRAME_START, first.get())
        assertEquals(flags, first.getLong())
        assertEquals(1000, first.getInt())
        assertTrue(frames.drop(1).all { it[0] == ScreenWire.FRAME_CONT })
        val joined = frames[0].copyOfRange(13, frames[0].size) + frames.drop(1).flatMap { it.drop(1) }.toByteArray()
        assertArrayEquals(payload, joined)

        val small = ScreenWire.frames(ScreenWire.FLAG_CONFIG, ByteArray(10))
        assertEquals(1, small.size)
        assertEquals(23, small[0].size)
    }

    @Test
    fun relayUrlFollowsTheAgentSocket() {
        assertEquals(
            "wss://hsm.example.com/ws/screen/agent?session=scr_1",
            ScreenMirrorImpl.relayUrl("wss://hsm.example.com/ws/agent", "https://hsm.example.com", "scr_1"),
        )
        assertEquals(
            "ws://10.0.0.2:8080/ws/screen/agent?session=scr_2",
            ScreenMirrorImpl.relayUrl("ws://other", "http://10.0.0.2:8080/", "scr_2"),
        )
    }
}
