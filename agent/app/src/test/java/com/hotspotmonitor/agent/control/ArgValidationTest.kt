package com.hotspotmonitor.agent.control

import com.hotspotmonitor.agent.protocol.CommandNames
import com.hotspotmonitor.agent.protocol.InvalidArgsException
import com.hotspotmonitor.agent.protocol.NetworkMode
import com.hotspotmonitor.agent.protocol.Settings
import com.hotspotmonitor.agent.protocol.WatchdogSettings
import com.hotspotmonitor.agent.root.shellQuote
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ArgValidationTest {
    private val settings = Settings.DEFAULT

    private fun parse(name: String, args: JsonObject = JsonObject(emptyMap())): ParsedCommand =
        CommandParser.parse(name, args, settings)

    private fun assertInvalid(name: String, args: JsonObject) {
        assertThrows(InvalidArgsException::class.java) { CommandParser.parse(name, args, settings) }
    }

    @Test
    fun packageNamesAreRestricted() {
        assertEquals("com.example.app", ArgRules.packageName(" com.example.app "))
        assertEquals("android", ArgRules.packageName("android"))
        for (bad in listOf("", "com..x", "1com.x", "com.x;reboot", "com.x y", "com.x'", "\$(id)", "com/x")) {
            assertThrows("'$bad' must be rejected", InvalidArgsException::class.java) { ArgRules.packageName(bad) }
        }
        assertThrows(InvalidArgsException::class.java) { ArgRules.packageName("a." + "b".repeat(300)) }
    }

    @Test
    fun hotspotNameAndPassword() {
        assertEquals("Roof Hotspot ü", ArgRules.ssid("Roof Hotspot ü"))
        assertThrows(InvalidArgsException::class.java) { ArgRules.ssid(" ") }
        assertThrows(InvalidArgsException::class.java) { ArgRules.ssid("bad\nname") }
        assertThrows(InvalidArgsException::class.java) { ArgRules.ssid("x".repeat(33)) }
        assertThrows(InvalidArgsException::class.java) { ArgRules.ssid("ü".repeat(17)) } // 34 bytes
        assertEquals("12345678", ArgRules.wpa2Password("12345678"))
        assertEquals("it's \$ \"quoted\"", ArgRules.wpa2Password("it's \$ \"quoted\""))
        assertThrows(InvalidArgsException::class.java) { ArgRules.wpa2Password("1234567") }
        assertThrows(InvalidArgsException::class.java) { ArgRules.wpa2Password("x".repeat(64)) }
        assertThrows(InvalidArgsException::class.java) { ArgRules.wpa2Password("pässwörd1") }
    }

    @Test
    fun phoneUssdShellAndSpotIds() {
        assertEquals("+919876543210", ArgRules.phoneNumber("+91 98765-43210"))
        assertEquals("121", ArgRules.phoneNumber("121"))
        assertThrows(InvalidArgsException::class.java) { ArgRules.phoneNumber("abc") }
        assertThrows(InvalidArgsException::class.java) { ArgRules.phoneNumber("1;reboot") }
        assertEquals("*123#", ArgRules.ussdCode(" *123# "))
        assertEquals("*121*1#", ArgRules.ussdCode("*121*1#"))
        assertThrows(InvalidArgsException::class.java) { ArgRules.ussdCode("123") }
        assertThrows(InvalidArgsException::class.java) { ArgRules.ussdCode("*123#;reboot") }
        assertEquals("ls -l /sdcard", ArgRules.shellCommand("ls -l /sdcard"))
        assertThrows(InvalidArgsException::class.java) { ArgRules.shellCommand("  ") }
        assertThrows(InvalidArgsException::class.java) { ArgRules.shellCommand("ls\u0000") }
        assertThrows(InvalidArgsException::class.java) { ArgRules.shellCommand("x".repeat(ArgRules.SHELL_MAX_CHARS + 1)) }
        assertEquals("s_AbC123", ArgRules.spotId("s_AbC123"))
        assertThrows(InvalidArgsException::class.java) { ArgRules.spotId("s_1;rm") }
        assertEquals("ncm", ArgRules.usbFunction("NCM"))
        assertThrows(InvalidArgsException::class.java) { ArgRules.usbFunction("mtp") }
    }

    @Test
    fun shellQuotingNeutralisesEveryValue() {
        assertEquals("'it'\\''s'", shellQuote("it's"))
        assertEquals("'\$(reboot)'", shellQuote("\$(reboot)"))
        assertEquals(
            "cmd wifi start-softap 'it'\\''s mine' wpa2 'pa\$\$ \"word\"'",
            ControlCommands.startSoftAp("it's mine", "pa\$\$ \"word\""),
        )
        assertEquals("am force-stop 'com.example'", ControlCommands.forceStop("com.example"))
        assertEquals("pm disable-user --user 0 'com.example'", ControlCommands.disableApp("com.example"))
        assertEquals("pm enable 'com.example'", ControlCommands.enableApp("com.example"))
        assertThrows(InvalidArgsException::class.java) { ControlCommands.forceStop("com.x; reboot") }
        assertEquals(
            "cmd phone set-allowed-network-types-for-users -s 1 '1000001000000000000'",
            ControlCommands.setAllowedNetworkTypes(1, "1000001000000000000"),
        )
        assertEquals("svc usb setFunctions ncm", ControlCommands.usbFunctions("ncm"))
        assertEquals("svc usb setFunctions", ControlCommands.usbFunctions(null))
        assertThrows(InvalidArgsException::class.java) { ControlCommands.usbFunctions("rndis; reboot") }
        assertEquals(
            "settings put system screen_brightness_mode 0 && settings put system screen_brightness 255",
            ControlCommands.brightness(999),
        )
    }

    @Test
    fun parserReadsValidArguments() {
        assertEquals(ParsedCommand.MobileData(false), parse(CommandNames.MOBILE_DATA, buildJsonObject { put("enabled", false) }))
        assertEquals(ParsedCommand.DataCycle(settings.watchdog.dataOffS), parse(CommandNames.DATA_CYCLE))
        assertEquals(ParsedCommand.DataCycle(30), parse(CommandNames.DATA_CYCLE, buildJsonObject { put("off_seconds", 30.0) }))
        assertEquals(ParsedCommand.AirplaneCycle(settings.watchdog.airplaneOffS), parse(CommandNames.AIRPLANE_CYCLE))
        assertEquals(
            ParsedCommand.UsbTethering(true, "ncm"),
            parse(CommandNames.USB_TETHERING, buildJsonObject { put("enabled", true); put("function", "NCM") }),
        )
        assertEquals(ParsedCommand.UsbTethering(false, null), parse(CommandNames.USB_TETHERING, buildJsonObject { put("enabled", false) }))
        assertEquals(
            ParsedCommand.SetNetworkMode(NetworkMode.LTE_ONLY),
            parse(CommandNames.NETWORK_MODE, buildJsonObject { put("mode", "lte_only") }),
        )
        assertEquals(ParsedCommand.Shell("id", 30), parse(CommandNames.SHELL, buildJsonObject { put("cmd", "id") }))
        assertEquals(ParsedCommand.Shell("id", 300), parse(CommandNames.SHELL, buildJsonObject { put("cmd", "id"); put("timeout_s", 300) }))
        // The Dashboard console and the Server accept 1..600 s.
        assertEquals(ParsedCommand.Shell("id", 600), parse(CommandNames.SHELL, buildJsonObject { put("cmd", "id"); put("timeout_s", 600) }))
        assertEquals(
            ParsedCommand.SendSms("+15551234", "hi"),
            parse(CommandNames.SEND_SMS, buildJsonObject { put("to", "+1 555-1234"); put("body", "hi") }),
        )
        assertEquals(
            ParsedCommand.WatchdogSimulate(600),
            parse(CommandNames.WATCHDOG_SIMULATE, buildJsonObject { put("scenario", "internet_down"); put("duration_s", 600) }),
        )
        assertEquals(ParsedCommand.ChargeLimit(60), parse(CommandNames.CHARGE_LIMIT, buildJsonObject { put("percent", 60) }))
        assertEquals(ParsedCommand.Reboot, parse(CommandNames.REBOOT))
        assertEquals(ParsedCommand.SpeedTest(null), parse(CommandNames.SPEED_TEST))
        val spot = parse(CommandNames.FINDER_SPOT_TEST, buildJsonObject { put("name", " Roof NE "); put("orientation", "") })
        assertEquals(ParsedCommand.SpotTest("Roof NE", null, null), spot)
        val hotspot = parse(CommandNames.HOTSPOT, buildJsonObject { put("enabled", true) }) as ParsedCommand.Hotspot
        assertNull(hotspot.ssid)
        assertNull(hotspot.password)
    }

    @Test
    fun parserRejectsInvalidArguments() {
        assertInvalid(CommandNames.MOBILE_DATA, JsonObject(emptyMap()))
        assertInvalid(CommandNames.MOBILE_DATA, buildJsonObject { put("enabled", "false") })
        assertInvalid(CommandNames.DATA_CYCLE, buildJsonObject { put("off_seconds", 0) })
        assertInvalid(CommandNames.DATA_CYCLE, buildJsonObject { put("off_seconds", 601) })
        assertInvalid(CommandNames.USB_TETHERING, buildJsonObject { put("enabled", true); put("function", "mtp") })
        assertInvalid(CommandNames.USB_TETHERING, buildJsonObject { put("enabled", true); put("function", 5) })
        assertInvalid(CommandNames.HOTSPOT, buildJsonObject { put("enabled", true); put("ssid", "x"); put("password", "short") })
        assertInvalid(CommandNames.NETWORK_MODE, buildJsonObject { put("mode", "6g") })
        assertInvalid(CommandNames.BRIGHTNESS, buildJsonObject { put("level", 300) })
        assertInvalid(CommandNames.CHARGE_LIMIT, buildJsonObject { put("percent", 10) })
        assertInvalid(CommandNames.APP_DISABLE, buildJsonObject { put("package", "com.x && reboot") })
        assertInvalid(CommandNames.SHELL, buildJsonObject { put("cmd", "id"); put("timeout_s", 601) })
        assertInvalid(CommandNames.SHELL, buildJsonObject { put("cmd", "id"); put("timeout_s", 0) })
        assertInvalid(CommandNames.SEND_SMS, buildJsonObject { put("to", "+1555"); put("body", "x".repeat(1601)) })
        assertInvalid(CommandNames.USSD, buildJsonObject { put("code", "1234") })
        assertInvalid(CommandNames.WATCHDOG_SIMULATE, buildJsonObject { put("scenario", "power_cut"); put("duration_s", 60) })
        assertInvalid(CommandNames.WATCHDOG_SIMULATE, buildJsonObject { put("duration_s", 5) })
        assertInvalid(CommandNames.FINDER_SET_BASELINE, buildJsonObject { put("spot_id", "../x") })
        assertInvalid("format_disk", JsonObject(emptyMap()))
    }

    @Test
    fun cycleDefaultsFollowSettingsWithinRange() {
        val custom = Settings.DEFAULT.copy(watchdog = WatchdogSettings(dataOffS = 9_999, airplaneOffS = 0))
        assertEquals(ParsedCommand.DataCycle(600), CommandParser.parse(CommandNames.DATA_CYCLE, JsonObject(emptyMap()), custom))
        assertEquals(ParsedCommand.AirplaneCycle(1), CommandParser.parse(CommandNames.AIRPLANE_CYCLE, JsonObject(emptyMap()), custom))
    }

    @Test
    fun hotspotToStringNeverShowsThePassword() {
        val text = ParsedCommand.Hotspot(true, "net", "secret-pass").toString()
        assertEquals(false, text.contains("secret-pass"))
    }
}
