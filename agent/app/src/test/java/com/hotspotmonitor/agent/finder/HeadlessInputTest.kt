package com.hotspotmonitor.agent.finder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HeadlessInputTest {
    @Test
    fun parsesLabelledGeteventLines() {
        assertEquals(
            KeyEdge(VolumeKey.DOWN, down = true, atMs = 8_401_120),
            Getevent.parseKeyLine("[   8401.120552] /dev/input/event2: EV_KEY       KEY_VOLUMEDOWN       DOWN"),
        )
        assertEquals(
            KeyEdge(VolumeKey.UP, down = false, atMs = null),
            Getevent.parseKeyLine("/dev/input/event0: EV_KEY       KEY_VOLUMEUP         UP        "),
        )
        assertEquals(KeyEdge(VolumeKey.UP, true, null), Getevent.parseKeyLine("EV_KEY KEY_VOLUMEUP DOWN"))
    }

    @Test
    fun parsesNumericGeteventLines() {
        assertEquals(KeyEdge(VolumeKey.UP, true, null), Getevent.parseKeyLine("/dev/input/event0: 0001 0073 00000001"))
        assertEquals(KeyEdge(VolumeKey.DOWN, false, 12_000), Getevent.parseKeyLine("[      12.000001] /dev/input/event3: 0001 0072 00000000"))
    }

    @Test
    fun ignoresEverythingElse() {
        assertNull(Getevent.parseKeyLine(""))
        assertNull(Getevent.parseKeyLine("/dev/input/event2: EV_SYN       SYN_REPORT           00000000"))
        assertNull(Getevent.parseKeyLine("/dev/input/event0: EV_KEY       KEY_POWER            DOWN"))
        assertNull(Getevent.parseKeyLine("/dev/input/event0: EV_KEY       KEY_VOLUMEUP         REPEAT"))
        assertNull(Getevent.parseKeyLine("/dev/input/event0: 0001 0073 00000002"))
        assertNull(Getevent.parseKeyLine("/dev/input/event5: EV_ABS       ABS_MT_POSITION_X    00000073"))
        assertNull(Getevent.parseKeyLine("could not get driver version for /dev/input/mice, Not a typewriter"))
        assertNull(Getevent.parseKeyLine("add device 1: /dev/input/event4"))
    }

    @Test
    fun findsTheDevicesThatHaveVolumeKeys() {
        val output = """
            add device 1: /dev/input/event4
              bus:      0000
              vendor    0000
              product   0000
              version   0000
              name:     "gpio-keys"
              location: "gpio-keys/input0"
              id:       ""
              version:  1.0.1
              events:
                KEY (0001): KEY_VOLUMEUP
              input props:
                <none>
            add device 2: /dev/input/event2
              name:     "sec_touchscreen"
              events:
                KEY (0001): KEY_BACK              KEY_HOMEPAGE          KEY_APPSELECT         BTN_TOOL_FINGER
                            BTN_TOUCH             KEY_WAKEUP
                ABS (0003): ABS_MT_SLOT           : value 0, min 0, max 9, fuzz 0, flat 0, resolution 0
                            ABS_MT_TOUCH_MAJOR    : value 0, min 0, max 255, fuzz 0, flat 0, resolution 0
              input props:
                INPUT_PROP_DIRECT
            add device 3: /dev/input/event0
              name:     "qpnp_pon"
              events:
                KEY (0001): KEY_POWER
                            KEY_VOLUMEDOWN
              input props:
                <none>
            could not get driver version for /dev/input/mice, Not a typewriter
        """.trimIndent()
        assertEquals(listOf("/dev/input/event4", "/dev/input/event0"), Getevent.volumeKeyDevices(output))
    }

    @Test
    fun findsVolumeKeysInNumericCapabilityOutput() {
        val output = """
            add device 1: /dev/input/event1
              name:     "pmic_pwrkey"
              events:
                KEY (0001): 0072  0074
              input props:
                <none>
            add device 2: /dev/input/event3
              name:     "touch"
              events:
                ABS (0003): 0035  : value 0, min 0, max 1079, fuzz 0, flat 0, resolution 0
                            0073  : value 0, min 0, max 255, fuzz 0, flat 0, resolution 0
              input props:
                <none>
        """.trimIndent()
        assertEquals(listOf("/dev/input/event1"), Getevent.volumeKeyDevices(output))
        assertTrue(Getevent.volumeKeyDevices("").isEmpty())
    }

    private fun event64(sec: Long, usec: Long, type: Int, code: Int, value: Int): ByteArray =
        ByteBuffer.allocate(24).order(ByteOrder.nativeOrder())
            .putLong(sec).putLong(usec).putShort(type.toShort()).putShort(code.toShort()).putInt(value).array()

    private fun event32(sec: Int, usec: Int, type: Int, code: Int, value: Int): ByteArray =
        ByteBuffer.allocate(16).order(ByteOrder.nativeOrder())
            .putInt(sec).putInt(usec).putShort(type.toShort()).putShort(code.toShort()).putInt(value).array()

    @Test
    fun decodesRawEventsAcrossReadBoundaries() {
        val stream = event64(100, 250_000, 1, 115, 1) + event64(100, 250_000, 0, 0, 0) +
            event64(101, 600_000, 1, 115, 2) + event64(101, 900_000, 1, 115, 0) +
            event64(102, 0, 1, 116, 1) // KEY_POWER: ignored
        val decoder = InputEventDecoder(Getevent.EVENT_SIZE_64)
        val edges = ArrayList<KeyEdge>()
        // Feed in awkward chunk sizes, as pipe reads can split records.
        var offset = 0
        for (size in listOf(5, 19, 30, 1, 40, 200)) {
            val end = minOf(stream.size, offset + size)
            if (offset >= end) break
            val chunk = stream.copyOfRange(offset, end)
            edges += decoder.feed(chunk, chunk.size)
            offset = end
        }
        assertEquals(
            listOf(KeyEdge(VolumeKey.UP, true, 100_250), KeyEdge(VolumeKey.UP, false, 101_900)),
            edges,
        )
    }

    @Test
    fun decodesThirtyTwoBitEvents() {
        val decoder = InputEventDecoder(Getevent.EVENT_SIZE_32)
        val bytes = event32(5, 1_000, 1, 114, 1) + event32(6, 500_000, 1, 114, 0)
        assertEquals(
            listOf(KeyEdge(VolumeKey.DOWN, true, 5_001), KeyEdge(VolumeKey.DOWN, false, 6_500)),
            decoder.feed(bytes, bytes.size),
        )
    }

    @Test
    fun longPressNeedsOneSecondAndVeryLongThree() {
        val d = LongPressDetector()
        assertNull(d.onEdge(KeyEdge(VolumeKey.UP, true, 10_000), nowMs = 0))
        assertTrue(d.isHeld(VolumeKey.UP))
        assertEquals(KeyGesture(VolumeKey.UP, PressKind.SHORT, 400), d.onEdge(KeyEdge(VolumeKey.UP, false, 10_400), nowMs = 400))
        assertFalse(d.isHeld(VolumeKey.UP))

        d.onEdge(KeyEdge(VolumeKey.UP, true, 20_000), nowMs = 0)
        assertEquals(PressKind.LONG, d.onEdge(KeyEdge(VolumeKey.UP, false, 21_000), nowMs = 1_000)!!.kind)

        d.onEdge(KeyEdge(VolumeKey.DOWN, true, 30_000), nowMs = 0)
        assertEquals(PressKind.LONG, d.onEdge(KeyEdge(VolumeKey.DOWN, false, 32_999), nowMs = 2_999)!!.kind)

        d.onEdge(KeyEdge(VolumeKey.DOWN, true, 40_000), nowMs = 0)
        assertEquals(PressKind.VERY_LONG, d.onEdge(KeyEdge(VolumeKey.DOWN, false, 43_000), nowMs = 3_000)!!.kind)
    }

    @Test
    fun kernelTimestampsWinOverArrivalTimes() {
        val d = LongPressDetector()
        // Lines arrived together (buffered output), but the kernel says the key was held 1.5 s.
        d.onEdge(KeyEdge(VolumeKey.UP, true, 5_000), nowMs = 9_000)
        val g = d.onEdge(KeyEdge(VolumeKey.UP, false, 6_500), nowMs = 9_001)!!
        assertEquals(1_500L, g.durationMs)
        assertEquals(PressKind.LONG, g.kind)
        // Without timestamps the arrival times are used.
        d.onEdge(KeyEdge(VolumeKey.DOWN, true, null), nowMs = 100)
        assertEquals(PressKind.VERY_LONG, d.onEdge(KeyEdge(VolumeKey.DOWN, false, null), nowMs = 3_200)!!.kind)
    }

    @Test
    fun repeatedPressesAndStrayReleasesAreIgnored() {
        val d = LongPressDetector()
        assertNull(d.onEdge(KeyEdge(VolumeKey.UP, false, 1_000), nowMs = 0))
        d.onEdge(KeyEdge(VolumeKey.UP, true, 1_000), nowMs = 0)
        d.onEdge(KeyEdge(VolumeKey.UP, true, 1_800), nowMs = 800) // second DOWN keeps the first start
        assertEquals(1_200L, d.onEdge(KeyEdge(VolumeKey.UP, false, 2_200), nowMs = 1_200)!!.durationMs)
        // Keys are tracked independently.
        d.onEdge(KeyEdge(VolumeKey.UP, true, 3_000), nowMs = 0)
        d.onEdge(KeyEdge(VolumeKey.DOWN, true, 3_100), nowMs = 0)
        assertEquals(VolumeKey.DOWN, d.onEdge(KeyEdge(VolumeKey.DOWN, false, 4_200), nowMs = 0)!!.key)
        assertEquals(PressKind.VERY_LONG, d.onEdge(KeyEdge(VolumeKey.UP, false, 6_000), nowMs = 0)!!.kind)
        d.onEdge(KeyEdge(VolumeKey.UP, true, 7_000), nowMs = 0)
        d.reset()
        assertNull(d.onEdge(KeyEdge(VolumeKey.UP, false, 9_000), nowMs = 0))
    }
}
