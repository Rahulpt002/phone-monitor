package com.hotspotmonitor.agent.control

import com.hotspotmonitor.agent.protocol.ProtocolLimits
import com.hotspotmonitor.agent.root.shellQuote
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Command output limits (protocol: output truncated to 64 KiB). */
internal object OutputText {
    private const val SUFFIX = "\n…[truncated]"

    /** Cuts [text] to at most [maxBytes] UTF-8 bytes without splitting a character. */
    fun truncate(text: String, maxBytes: Int = ProtocolLimits.MAX_OUTPUT_BYTES): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return text
        val suffixBytes = SUFFIX.toByteArray(Charsets.UTF_8).size
        var end = (maxBytes - suffixBytes).coerceAtLeast(0)
        // Step back over UTF-8 continuation bytes (10xxxxxx) so the cut lands on a character start.
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8) + SUFFIX
    }

    /** stdout, plus stderr marked as such. */
    fun combine(out: String, err: String): String = buildString {
        append(out.trimEnd())
        if (err.isNotBlank()) {
            if (isNotEmpty()) append('\n')
            append("[stderr] ").append(err.trimEnd())
        }
    }
}

/** `pm list packages` parsing for the `app_list` command. */
internal object AppListParser {
    /** Package names from `pm list packages [-f]` output ("package:<name>" or "package:<apk>=<name>"). */
    fun packages(output: String): Set<String> = output.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("package:") }
        .map { line ->
            val rest = line.removePrefix("package:")
            if ('=' in rest) rest.substringAfterLast('=') else rest
        }
        .filter { it.isNotEmpty() }
        .toSet()

    /** JSON array of { pkg, enabled, system }, sorted by package name. */
    fun toJson(all: Set<String>, disabled: Set<String>, system: Set<String>): String =
        JsonArray(
            (all + disabled).sorted().map { pkg ->
                JsonObject(
                    mapOf(
                        "pkg" to JsonPrimitive(pkg),
                        "enabled" to JsonPrimitive(pkg !in disabled),
                        "system" to JsonPrimitive(pkg in system),
                    ),
                )
            },
        ).toString()
}

/** A USB tethering interface with its IPv4 address. */
internal data class TetherIface(val name: String, val ip: String)

/** Finds the USB tethering interface in `ip -4 addr show` output. */
internal object TetherIfaces {
    private val TETHER_NAME = Regex("^(rndis|ncm|usb)\\d*$")
    private val HEADER = Regex("^\\d+:\\s+([^:@\\s]+)(@\\S+)?:")
    private val INET = Regex("^\\s+inet\\s+(\\d{1,3}(?:\\.\\d{1,3}){3})")

    fun find(ipAddrOutput: String): TetherIface? {
        var current: String? = null
        for (line in ipAddrOutput.lineSequence()) {
            val header = HEADER.find(line)
            if (header != null) {
                current = header.groupValues[1]
                continue
            }
            val name = current ?: continue
            if (!TETHER_NAME.matches(name)) continue
            val inet = INET.find(line) ?: continue
            return TetherIface(name, inet.groupValues[1])
        }
        return null
    }
}

/** Built-in root command lines. Every variable part is validated by [ArgRules] and shell-quoted. */
internal object ControlCommands {
    const val IP_ADDR = "ip -4 addr show"
    const val USB_GET_FUNCTIONS = "svc usb getFunctions"

    fun mobileData(enabled: Boolean) = "svc data ${action(enabled)}"

    fun airplaneCmd(enabled: Boolean) = "cmd connectivity airplane-mode ${action(enabled)}"

    /** Android 10 fallback: the setting plus the broadcast that makes the radios follow it. */
    fun airplaneLegacy(enabled: Boolean) =
        "settings put global airplane_mode_on ${if (enabled) 1 else 0} && " +
            "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $enabled"

    fun usbFunctions(function: String?): String =
        if (function == null) "svc usb setFunctions" else "svc usb setFunctions ${ArgRules.usbFunction(function)}"

    fun startSoftAp(ssid: String, password: String) =
        "cmd wifi start-softap ${shellQuote(ssid)} wpa2 ${shellQuote(password)}"

    const val STOP_SOFTAP = "cmd wifi stop-softap"
    const val START_SOFTAP_LOG = "cmd wifi start-softap <ssid> wpa2 <redacted>"

    fun wifiSvc(enabled: Boolean) = "svc wifi ${action(enabled)}"

    fun wifiCmd(enabled: Boolean) = "cmd wifi set-wifi-enabled ${if (enabled) "enabled" else "disabled"}"

    fun bluetoothSvc(enabled: Boolean) = "svc bluetooth ${action(enabled)}"

    fun bluetoothCmd(enabled: Boolean) = "cmd bluetooth_manager ${action(enabled)}"

    fun setAllowedNetworkTypes(slot: Int, value: String) =
        "cmd phone set-allowed-network-types-for-users -s $slot ${shellQuote(value)}"

    fun getAllowedNetworkTypes(slot: Int) = "cmd phone get-allowed-network-types-for-users -s $slot"

    fun screen(on: Boolean) = "input keyevent ${if (on) 224 else 223}"

    fun brightness(level: Int) =
        "settings put system screen_brightness_mode 0 && " +
            "settings put system screen_brightness ${level.coerceIn(ArgRules.BRIGHTNESS)}"

    fun forceStop(pkg: String) = "am force-stop ${shellQuote(ArgRules.packageName(pkg))}"

    fun enableApp(pkg: String) = "pm enable ${shellQuote(ArgRules.packageName(pkg))}"

    fun disableApp(pkg: String) = "pm disable-user --user 0 ${shellQuote(ArgRules.packageName(pkg))}"

    val PROCESS_LIST: List<String> = listOf(
        "top -b -n 1 -m 30",
        "top -n 1 -m 30",
        "ps -A -o PID,USER,%CPU,RSS,NAME",
    )

    val REBOOT: List<String> = listOf("svc power reboot", "reboot")
    val SHUTDOWN: List<String> = listOf("svc power shutdown", "reboot -p")

    private fun action(enabled: Boolean) = if (enabled) "enable" else "disable"
}
