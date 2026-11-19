package com.hotspotmonitor.agent.root

import com.hotspotmonitor.agent.protocol.NetworkMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Device profile (plan 6.1): everything that differs per phone model. Loaded from
 * assets/profiles/<id>.json; the Server can push settings.profile_override, which is deep-merged
 * over the matched profile. Schema documented in agent/ARCHITECTURE.md.
 */

@Serializable
data class ProfileMatch(
    /** Build.MANUFACTURER values, compared case-insensitively. Empty = any. */
    val manufacturer: List<String> = emptyList(),
    /** Regex searched in Build.MODEL. Null = any. */
    @SerialName("model_regex") val modelRegex: String? = null,
) {
    /** generic.json has an empty match and is only used as the fallback. */
    val isEmpty: Boolean get() = manufacturer.isEmpty() && modelRegex.isNullOrBlank()

    /** Number of criteria; with several matching profiles the most specific wins. */
    val specificity: Int get() = (if (manufacturer.isNotEmpty()) 1 else 0) + (if (modelRegex.isNullOrBlank()) 0 else 1)

    /** Whether every given criterion matches. Throws on an invalid [modelRegex]. */
    fun matches(deviceManufacturer: String, deviceModel: String): Boolean {
        if (manufacturer.isNotEmpty() && manufacturer.none { it.equals(deviceManufacturer, ignoreCase = true) }) return false
        val regex = modelRegex
        if (!regex.isNullOrBlank() && !Regex(regex).containsMatchIn(deviceModel)) return false
        return true
    }
}

/** One charge-control sysfs switch: write [on] to allow charging, [off] to stop it. */
@Serializable
data class ChargeControlPath(
    val path: String,
    val on: String,
    val off: String,
    val note: String? = null,
)

/** How to turn BatteryManager / sysfs current_now into mA with positive = charging. */
@Serializable
data class CurrentNowSpec(
    /** "uA" or "mA" */
    val unit: String = "uA",
    /** 1 or -1 */
    val sign: Int = 1,
) {
    fun toMilliamps(raw: Long): Int {
        val ma = if (unit.equals("mA", ignoreCase = true)) raw else raw / 1000
        return (ma * (if (sign < 0) -1 else 1)).toInt()
    }
}

@Serializable
data class AutobootSpec(
    /** fastboot_oem_off_mode_charge | magisk_autoboot | lpm_replace | none | unknown */
    val method: String = "unknown",
    val note: String? = null,
)

/** Substrings matched against /sys/class/thermal/thermal_zoneN/type. */
@Serializable
data class ThermalSpec(
    val cpu: List<String> = listOf("cpu", "tsens", "soc"),
    val modem: List<String> = listOf("modem", "mdm", "pa_therm"),
    val skin: List<String> = listOf("skin", "xo_therm", "quiet_therm"),
    val battery: List<String> = listOf("battery"),
)

@Serializable
data class DeviceProfile(
    val id: String,
    val name: String = id,
    val match: ProfileMatch = ProfileMatch(),
    /** Tried in order; the first path that exists and is writable wins. */
    @SerialName("charge_control") val chargeControl: List<ChargeControlPath> = emptyList(),
    @SerialName("current_now") val currentNow: CurrentNowSpec = CurrentNowSpec(),
    /** rndis | ncm */
    @SerialName("usb_function") val usbFunction: String = "rndis",
    val autoboot: AutobootSpec = AutobootSpec(),
    val thermal: ThermalSpec = ThermalSpec(),
    /** Command name -> shell templates with {placeholders}; the first that succeeds wins. */
    val commands: Map<String, List<String>> = emptyMap(),
    /** NetworkMode wire value -> value passed to the device's mechanism. */
    @SerialName("network_mode") val networkMode: Map<String, String> = emptyMap(),
    val notes: List<String> = emptyList(),
) {
    val isGeneric: Boolean get() = match.isEmpty

    /** Profile templates for a command name (empty = use the built-in implementation). */
    fun commandTemplates(name: String): List<String> = commands[name].orEmpty()

    fun networkModeValue(mode: NetworkMode): String? = networkMode[mode.wire]

    companion object {
        /** Built-in fallback when assets/profiles has no generic.json (or no profiles at all). */
        val GENERIC = DeviceProfile(
            id = "generic",
            name = "Generic rooted Android",
            match = ProfileMatch(),
            chargeControl = listOf(
                ChargeControlPath("/sys/class/power_supply/battery/input_suspend", on = "0", off = "1",
                    note = "Qualcomm: suspends USB input, phone runs from the battery"),
                ChargeControlPath("/sys/class/power_supply/battery/charging_enabled", on = "1", off = "0",
                    note = "Older Qualcomm kernels"),
                ChargeControlPath("/sys/class/power_supply/battery/battery_charging_enabled", on = "1", off = "0",
                    note = "Older Qualcomm kernels"),
                ChargeControlPath("/sys/class/power_supply/battery/batt_slate_mode", on = "0", off = "1",
                    note = "Samsung: slate mode runs the phone from USB while the battery rests"),
                ChargeControlPath("/sys/class/power_supply/battery/charge_disable", on = "0", off = "1",
                    note = "Some MediaTek and Qualcomm kernels"),
                ChargeControlPath("/sys/devices/platform/charger/bypass_charger", on = "0", off = "1",
                    note = "MediaTek bypass charging"),
            ),
            currentNow = CurrentNowSpec(unit = "uA", sign = 1),
            usbFunction = "rndis",
            autoboot = AutobootSpec(method = "unknown", note = "Prove automatic power-on in Phase 0 (plan 4.3)"),
            thermal = ThermalSpec(),
            notes = listOf("Built-in fallback profile; add assets/profiles/<id>.json for this model."),
        )

        private val PLACEHOLDER = Regex("\\{([a-zA-Z0-9_]+)\\}")

        /**
         * Fills `{name}` placeholders in a profile command template with shell-quoted [values].
         * Returns null when the template uses a placeholder that [values] does not provide.
         */
        fun render(template: String, values: Map<String, String>): String? {
            var missing = false
            val out = PLACEHOLDER.replace(template) { m ->
                val v = values[m.groupValues[1]]
                if (v == null) {
                    missing = true
                    m.value
                } else {
                    shellQuote(v)
                }
            }
            return if (missing) null else out
        }
    }
}
