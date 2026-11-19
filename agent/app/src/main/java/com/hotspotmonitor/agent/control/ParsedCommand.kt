package com.hotspotmonitor.agent.control

import com.hotspotmonitor.agent.protocol.CommandNames
import com.hotspotmonitor.agent.protocol.InvalidArgsException
import com.hotspotmonitor.agent.protocol.NetworkMode
import com.hotspotmonitor.agent.protocol.Settings
import com.hotspotmonitor.agent.protocol.optInt
import com.hotspotmonitor.agent.protocol.optString
import com.hotspotmonitor.agent.protocol.requireBoolean
import com.hotspotmonitor.agent.protocol.requireInt
import com.hotspotmonitor.agent.protocol.requireString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** A command whose arguments passed validation (protocol 3.5 check "args"). */
internal sealed class ParsedCommand {
    data class MobileData(val enabled: Boolean) : ParsedCommand()
    data class DataCycle(val offSeconds: Int) : ParsedCommand()
    data class Airplane(val enabled: Boolean) : ParsedCommand()
    data class AirplaneCycle(val offSeconds: Int) : ParsedCommand()

    /** [function] null = the profile / settings default. */
    data class UsbTethering(val enabled: Boolean, val function: String?) : ParsedCommand()

    /** Null [ssid] / [password] = the last values used on this phone. */
    data class Hotspot(val enabled: Boolean, val ssid: String?, val password: String?) : ParsedCommand() {
        override fun toString(): String = "Hotspot(enabled=$enabled, ssid=$ssid, password=${if (password == null) "null" else "<redacted>"})"
    }

    data class Wifi(val enabled: Boolean) : ParsedCommand()
    data class Bluetooth(val enabled: Boolean) : ParsedCommand()
    data class SetNetworkMode(val mode: NetworkMode) : ParsedCommand()
    data object Reboot : ParsedCommand()
    data object PowerOff : ParsedCommand()
    data class Screen(val on: Boolean) : ParsedCommand()
    data class Brightness(val level: Int) : ParsedCommand()
    data class Charging(val enabled: Boolean) : ParsedCommand()
    data class ChargeLimit(val percent: Int) : ParsedCommand()
    data object AppList : ParsedCommand()
    data class AppForceStop(val pkg: String) : ParsedCommand()
    data class AppDisable(val pkg: String) : ParsedCommand()
    data class AppEnable(val pkg: String) : ParsedCommand()
    data object ProcessList : ParsedCommand()
    data class SendSms(val to: String, val body: String) : ParsedCommand()
    data class Ussd(val code: String) : ParsedCommand()
    data class Shell(val cmd: String, val timeoutS: Int) : ParsedCommand()
    data object Refresh : ParsedCommand()
    data object CapabilityProbe : ParsedCommand()
    data class WatchdogSimulate(val durationS: Int) : ParsedCommand()
    data class SpeedTest(val spotId: String?) : ParsedCommand()
    data class SpotTest(val name: String, val orientation: String?, val durationS: Int?) : ParsedCommand()
    data class SetBaseline(val spotId: String) : ParsedCommand()
    data class DeleteSpot(val spotId: String) : ParsedCommand()
}

/** Turns `cmd.args` into a [ParsedCommand]; throws [InvalidArgsException] (-> `invalid_args`). */
internal object CommandParser {
    const val SIMULATE_SCENARIO = "internet_down"

    fun parse(name: String, args: JsonObject, settings: Settings): ParsedCommand = when (name) {
        CommandNames.MOBILE_DATA -> ParsedCommand.MobileData(args.requireBoolean("enabled"))
        CommandNames.DATA_CYCLE -> ParsedCommand.DataCycle(
            args.optInt("off_seconds", ArgRules.OFF_SECONDS)
                ?: settings.watchdog.dataOffS.coerceIn(ArgRules.OFF_SECONDS),
        )
        CommandNames.AIRPLANE_MODE -> ParsedCommand.Airplane(args.requireBoolean("enabled"))
        CommandNames.AIRPLANE_CYCLE -> ParsedCommand.AirplaneCycle(
            args.optInt("off_seconds", ArgRules.OFF_SECONDS)
                ?: settings.watchdog.airplaneOffS.coerceIn(ArgRules.OFF_SECONDS),
        )
        CommandNames.USB_TETHERING -> ParsedCommand.UsbTethering(
            enabled = args.requireBoolean("enabled"),
            function = args.optStringStrict("function")?.let(ArgRules::usbFunction),
        )
        CommandNames.HOTSPOT -> ParsedCommand.Hotspot(
            enabled = args.requireBoolean("enabled"),
            ssid = args.optStringStrict("ssid")?.let(ArgRules::ssid),
            password = args.optStringStrict("password")?.let(ArgRules::wpa2Password),
        )
        CommandNames.WIFI -> ParsedCommand.Wifi(args.requireBoolean("enabled"))
        CommandNames.BLUETOOTH -> ParsedCommand.Bluetooth(args.requireBoolean("enabled"))
        CommandNames.NETWORK_MODE -> {
            val raw = args.requireString("mode")
            val mode = NetworkMode.fromWire(raw)
                ?: throw InvalidArgsException("'mode' must be one of ${NetworkMode.entries.joinToString { it.wire }}")
            ParsedCommand.SetNetworkMode(mode)
        }
        CommandNames.REBOOT -> ParsedCommand.Reboot
        CommandNames.POWER_OFF -> ParsedCommand.PowerOff
        CommandNames.SCREEN -> ParsedCommand.Screen(args.requireBoolean("on"))
        CommandNames.BRIGHTNESS -> ParsedCommand.Brightness(args.requireInt("level", ArgRules.BRIGHTNESS))
        CommandNames.CHARGING -> ParsedCommand.Charging(args.requireBoolean("enabled"))
        CommandNames.CHARGE_LIMIT -> ParsedCommand.ChargeLimit(args.requireInt("percent", ArgRules.CHARGE_LIMIT))
        CommandNames.APP_LIST -> ParsedCommand.AppList
        CommandNames.APP_FORCE_STOP -> ParsedCommand.AppForceStop(ArgRules.packageName(args.requireString("package")))
        CommandNames.APP_DISABLE -> ParsedCommand.AppDisable(ArgRules.packageName(args.requireString("package")))
        CommandNames.APP_ENABLE -> ParsedCommand.AppEnable(ArgRules.packageName(args.requireString("package")))
        CommandNames.PROCESS_LIST -> ParsedCommand.ProcessList
        CommandNames.SEND_SMS -> ParsedCommand.SendSms(
            to = ArgRules.phoneNumber(args.requireString("to")),
            body = ArgRules.smsBody(args.requireString("body")),
        )
        CommandNames.USSD -> ParsedCommand.Ussd(ArgRules.ussdCode(args.requireString("code")))
        CommandNames.SHELL -> ParsedCommand.Shell(
            cmd = ArgRules.shellCommand(args.requireString("cmd")),
            timeoutS = args.optInt("timeout_s", ArgRules.SHELL_TIMEOUT_S) ?: ArgRules.SHELL_TIMEOUT_DEFAULT_S,
        )
        CommandNames.REFRESH -> ParsedCommand.Refresh
        CommandNames.CAPABILITY_PROBE -> ParsedCommand.CapabilityProbe
        CommandNames.WATCHDOG_SIMULATE -> {
            val scenario = args.optStringStrict("scenario") ?: SIMULATE_SCENARIO
            if (scenario != SIMULATE_SCENARIO) throw InvalidArgsException("'scenario' must be '$SIMULATE_SCENARIO'")
            ParsedCommand.WatchdogSimulate(args.requireInt("duration_s", ArgRules.SIMULATE_S))
        }
        CommandNames.SPEED_TEST -> ParsedCommand.SpeedTest(args.optStringStrict("spot_id")?.let(ArgRules::spotId))
        CommandNames.FINDER_SPOT_TEST -> ParsedCommand.SpotTest(
            name = ArgRules.spotName(args.requireString("name")),
            orientation = args.optStringStrict("orientation")?.let(ArgRules::orientation)?.takeIf { it.isNotEmpty() },
            durationS = args.optInt("duration_s", ArgRules.SPOT_TEST_S),
        )
        CommandNames.FINDER_SET_BASELINE -> ParsedCommand.SetBaseline(ArgRules.spotId(args.requireString("spot_id")))
        CommandNames.FINDER_DELETE_SPOT -> ParsedCommand.DeleteSpot(ArgRules.spotId(args.requireString("spot_id")))
        else -> throw InvalidArgsException("unknown command '$name'")
    }

    /** Null when missing or JSON null; throws when present with a non-string value. */
    private fun JsonObject.optStringStrict(key: String): String? {
        val value = this[key] ?: return null
        if (value is JsonNull) return null
        return optString(key) ?: throw InvalidArgsException("'$key' must be a string")
    }
}
