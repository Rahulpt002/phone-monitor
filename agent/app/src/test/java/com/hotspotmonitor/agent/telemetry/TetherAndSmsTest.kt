package com.hotspotmonitor.agent.telemetry

import com.hotspotmonitor.agent.protocol.TetherClient
import com.hotspotmonitor.agent.protocol.UsbFunction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TetherAndSmsTest {
    private val addrs = parseIpAddr(
        """
        25: rndis0    inet 192.168.42.129/24 brd 192.168.42.255 scope global rndis0\       valid_lft forever
        26: swlan0    inet 192.168.43.1/24 brd 192.168.43.255 scope global swlan0\       valid_lft forever
        30: rmnet_data2    inet 10.20.30.40/30 scope global rmnet_data2\       valid_lft forever
        """.trimIndent(),
    )
    private val neighbours = parseIpNeigh(
        """
        192.168.42.10 dev rndis0 lladdr aa:bb:cc:dd:ee:01 REACHABLE
        2405:200::10 dev rndis0 lladdr aa:bb:cc:dd:ee:01 REACHABLE
        2405:200::11 dev rndis0 lladdr aa:bb:cc:dd:ee:02 STALE
        fe80::12 dev rndis0 lladdr aa:bb:cc:dd:ee:03 STALE
        192.168.42.13 dev rndis0  FAILED
        192.168.43.20 dev swlan0 lladdr aa:bb:cc:dd:ee:04 DELAY
        10.20.30.41 dev rmnet_data2 lladdr 00:11:22:33:44:55 REACHABLE
        """.trimIndent(),
    )

    @Test
    fun usbStateFromExtras() {
        val usb = TetherAssembler.usbState(mapOf("connected" to true, "configured" to true, "rndis" to true, "adb" to true, "mtp" to false))
        assertEquals(UsbState(connected = true, configured = true, functions = setOf("rndis")), usb)
        assertEquals(UsbFunction.RNDIS, TetherAssembler.usbFunction(usb))
        assertEquals(UsbFunction.NCM, TetherAssembler.usbFunction(usb.copy(functions = setOf("ncm"))))
        assertEquals(UsbFunction.MTP, TetherAssembler.usbFunction(usb.copy(functions = setOf("mtp"))))
        assertEquals(UsbFunction.OTHER, TetherAssembler.usbFunction(usb.copy(functions = setOf("midi"))))
        assertEquals(UsbFunction.NONE, TetherAssembler.usbFunction(usb.copy(functions = emptySet())))
        assertNull(TetherAssembler.usbFunction(null))
    }

    @Test
    fun usbTetherAndHotspotWithTetherBroadcast() {
        val usb = UsbState(connected = true, configured = true, functions = setOf("rndis"))
        val info = TetherAssembler.assemble(usb, listOf("rndis0", "swlan0"), addrs, neighbours, hotspotFlag = null, hotspotSsid = "Roof")
        assertEquals(true, info.usbConnected)
        assertEquals(UsbFunction.RNDIS, info.usbFunction)
        assertEquals(true, info.usbTetherOn)
        assertEquals("rndis0", info.tetherIf)
        assertEquals("192.168.42.129", info.tetherIp)
        assertEquals(true, info.hotspotOn)
        assertEquals("Roof", info.hotspotSsid)
        assertEquals(
            listOf(
                TetherClient(ip = "192.168.42.10", mac = "aa:bb:cc:dd:ee:01", iface = "rndis0"),
                TetherClient(ip = "192.168.43.20", mac = "aa:bb:cc:dd:ee:04", iface = "swlan0"),
                TetherClient(ip = "2405:200::11", mac = "aa:bb:cc:dd:ee:02", iface = "rndis0"),
            ),
            info.clients,
        )
    }

    @Test
    fun tetherOffWhileUsbConnected() {
        val usb = UsbState(connected = true, configured = true, functions = setOf("mtp"))
        val info = TetherAssembler.assemble(usb, emptyList(), parseIpAddr(""), emptyList(), hotspotFlag = false, hotspotSsid = "Roof")
        assertEquals(false, info.usbTetherOn)
        assertNull(info.tetherIf)
        assertEquals(false, info.hotspotOn)
        assertNull(info.hotspotSsid) // only reported while the hotspot is on
        assertTrue(info.clients!!.isEmpty())
    }

    @Test
    fun withoutTetherBroadcastAddressesDecide() {
        val info = TetherAssembler.assemble(null, null, addrs, null, hotspotFlag = null, hotspotSsid = null)
        assertNull(info.usbConnected)
        assertEquals(true, info.usbTetherOn)
        assertEquals("rndis0", info.tetherIf)
        assertEquals(true, info.hotspotOn) // swlan0 has an address
        assertNull(info.clients)
        val nothing = TetherAssembler.assemble(null, null, null, null, null, null)
        assertNull(nothing.usbTetherOn)
        assertNull(nothing.hotspotOn)
    }

    @Test
    fun smsPartsAreJoinedPerSender() {
        val sms = SmsAssembler.assemble(
            listOf(
                SmsPart("JIO", "Your balance is "),
                SmsPart("+911234567890", "hello"),
                SmsPart("JIO", "Rs 10."),
                SmsPart(null, "anonymous"),
                SmsPart("DATA", null),
            ),
        )
        assertEquals(
            listOf(
                IncomingSms("JIO", "Your balance is Rs 10."),
                IncomingSms("+911234567890", "hello"),
                IncomingSms(SmsAssembler.UNKNOWN_SENDER, "anonymous"),
            ),
            sms,
        )
        val long = SmsAssembler.assemble(listOf(SmsPart("X", "a".repeat(SmsAssembler.MAX_BODY_CHARS + 50))))
        assertEquals(SmsAssembler.MAX_BODY_CHARS, long.single().body.length)
    }
}
