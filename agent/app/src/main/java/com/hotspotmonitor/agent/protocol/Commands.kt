package com.hotspotmonitor.agent.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * Mirror of shared/src/commands.ts (plan sections 6.3 and 6.4).
 * Command names stay plain strings on the wire and in CmdMsg so a newer Server can send a name
 * this Agent does not know; the executor rejects it with `unsupported` instead of failing to decode.
 */

object CommandNames {
    // radios and links
    const val MOBILE_DATA = "mobile_data" // { enabled: boolean }
    const val DATA_CYCLE = "data_cycle" // { off_seconds?: number }
    const val AIRPLANE_MODE = "airplane_mode" // { enabled: boolean }
    const val AIRPLANE_CYCLE = "airplane_cycle" // { off_seconds?: number }
    const val USB_TETHERING = "usb_tethering" // { enabled: boolean, function?: 'rndis' | 'ncm' }
    const val HOTSPOT = "hotspot" // { enabled: boolean, ssid?: string, password?: string }
    const val WIFI = "wifi" // { enabled: boolean }
    const val BLUETOOTH = "bluetooth" // { enabled: boolean }
    const val NETWORK_MODE = "network_mode" // { mode: NetworkMode }

    // power and screen
    const val REBOOT = "reboot" // {}
    const val POWER_OFF = "power_off" // {} blocked unless settings.commands.allow_power_off
    const val SCREEN = "screen" // { on: boolean }
    const val BRIGHTNESS = "brightness" // { level: 0..255 }
    const val CHARGING = "charging" // { enabled: boolean }
    const val CHARGE_LIMIT = "charge_limit" // { percent: 20..100 }

    // apps and processes
    const val APP_LIST = "app_list" // {} -> JSON array of { pkg, enabled, system }
    const val APP_FORCE_STOP = "app_force_stop" // { package: string }
    const val APP_DISABLE = "app_disable" // { package: string }
    const val APP_ENABLE = "app_enable" // { package: string }
    const val PROCESS_LIST = "process_list" // {} -> `top -n 1` text

    // messaging
    const val SEND_SMS = "send_sms" // { to: string, body: string }
    const val USSD = "ussd" // { code: string } -> USSD reply text

    // admin
    const val SHELL = "shell" // { cmd: string, timeout_s?: number }
    const val REFRESH = "refresh" // {} send static + slow + fast telemetry now
    const val CAPABILITY_PROBE = "capability_probe" // {}
    const val WATCHDOG_SIMULATE = "watchdog_simulate" // { scenario: 'internet_down', duration_s: number }

    // signal finder
    const val SPEED_TEST = "speed_test" // { spot_id?: string }
    const val FINDER_SPOT_TEST = "finder_spot_test" // { name: string, orientation?: string, duration_s?: number }
    const val FINDER_SET_BASELINE = "finder_set_baseline" // { spot_id: string }
    const val FINDER_DELETE_SPOT = "finder_delete_spot" // { spot_id: string }

    val ALL: List<String> = listOf(
        MOBILE_DATA, DATA_CYCLE, AIRPLANE_MODE, AIRPLANE_CYCLE, USB_TETHERING, HOTSPOT, WIFI, BLUETOOTH,
        NETWORK_MODE, REBOOT, POWER_OFF, SCREEN, BRIGHTNESS, CHARGING, CHARGE_LIMIT, APP_LIST,
        APP_FORCE_STOP, APP_DISABLE, APP_ENABLE, PROCESS_LIST, SEND_SMS, USSD, SHELL, REFRESH,
        CAPABILITY_PROBE, WATCHDOG_SIMULATE, SPEED_TEST, FINDER_SPOT_TEST, FINDER_SET_BASELINE,
        FINDER_DELETE_SPOT,
    )

    fun isKnown(name: String): Boolean = name in COMMANDS
}

@Serializable
enum class NetworkMode(val wire: String) {
    @SerialName("auto") AUTO("auto"),
    @SerialName("nr_lte") NR_LTE("nr_lte"),
    @SerialName("lte_only") LTE_ONLY("lte_only"),
    @SerialName("lte_wcdma") LTE_WCDMA("lte_wcdma"),
    @SerialName("wcdma_only") WCDMA_ONLY("wcdma_only");

    companion object {
        fun fromWire(value: String?): NetworkMode? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * How an auto-revert is confirmed (plan 6.4 rule 1).
 * - AGENT_LINK: kept once a probe sent at least [REVERT_SETTLE_S] after the change gets its probe_ack.
 * - DASHBOARD: kept only when `cmd_keep` arrives (the change may cut the home network).
 */
@Serializable
enum class RevertConfirm(val wire: String) {
    @SerialName("agent_link") AGENT_LINK("agent_link"),
    @SerialName("dashboard") DASHBOARD("dashboard");

    companion object {
        fun fromWire(value: String?): RevertConfirm? = entries.firstOrNull { it.wire == value }
    }
}

/** Seconds after applying a link-cutting change before a probe may confirm it. */
const val REVERT_SETTLE_S = 10

@Serializable
enum class CmdRejectReason {
    /** Arrived more than commands.expiry_s after sent_at (plan 6.4 rule 6). */
    @SerialName("expired") EXPIRED,
    /** Not in the capability map. */
    @SerialName("unsupported") UNSUPPORTED,
    /** power_off while allow_power_off = false, or other policy. */
    @SerialName("blocked") BLOCKED,
    /** Plan 6.4 rule 3. */
    @SerialName("reboot_limit") REBOOT_LIMIT,
    @SerialName("invalid_args") INVALID_ARGS,
    /** A conflicting command or watchdog step is running. */
    @SerialName("busy") BUSY,
}

@Serializable
enum class CommandSource(val wire: String) {
    @SerialName("dashboard") DASHBOARD("dashboard"),
    @SerialName("watchdog") WATCHDOG("watchdog"),
    @SerialName("schedule") SCHEDULE("schedule"),
    @SerialName("sms") SMS("sms"),
    @SerialName("lan") LAN("lan"),
    @SerialName("local_ui") LOCAL_UI("local_ui");

    companion object {
        fun fromWire(value: String?): CommandSource? = entries.firstOrNull { it.wire == value }
    }
}

/** Command lifecycle as stored by the Server (commands.ts `CommandStatus`). */
@Serializable
enum class CommandStatus(val wire: String) {
    @SerialName("queued") QUEUED("queued"),
    @SerialName("sent") SENT("sent"),
    @SerialName("acked") ACKED("acked"),
    @SerialName("done") DONE("done"),
    @SerialName("failed") FAILED("failed"),
    @SerialName("rejected") REJECTED("rejected"),
    @SerialName("expired") EXPIRED("expired"),
    @SerialName("revert_armed") REVERT_ARMED("revert_armed"),
    @SerialName("kept") KEPT("kept"),
    @SerialName("reverted") REVERTED("reverted"),
}

enum class CommandGroup { RADIO, POWER, APPS, MESSAGING, ADMIN, FINDER }

/** Static facts about a command; mirrors `CommandSpec` in commands.ts. */
data class CommandSpec(
    val name: String,
    val label: String,
    val group: CommandGroup,
    /** Needs a second confirmation click in the Dashboard (plan 6.4 rule 4). */
    val confirm: Boolean,
    /** Needs the user's password again (plan section 10). */
    val reauth: Boolean,
    val adminOnly: Boolean,
    /** Counts toward the reboot limit (plan 6.4 rule 3). */
    val isReboot: Boolean = false,
    /** Revert confirmation mode when these args can cut a link, or null when no revert is needed. */
    val revertFor: (JsonObject) -> RevertConfirm? = { null },
)

private fun spec(
    name: String,
    label: String,
    group: CommandGroup,
    confirm: Boolean = false,
    reauth: Boolean = false,
    adminOnly: Boolean = false,
    isReboot: Boolean = false,
    revertFor: (JsonObject) -> RevertConfirm? = { null },
) = name to CommandSpec(name, label, group, confirm, reauth, adminOnly, isReboot, revertFor)

val COMMANDS: Map<String, CommandSpec> = mapOf(
    spec(CommandNames.MOBILE_DATA, "Mobile data", CommandGroup.RADIO,
        revertFor = { a -> if (a.optBoolean("enabled") == false) RevertConfirm.AGENT_LINK else null }),
    spec(CommandNames.DATA_CYCLE, "Cycle mobile data", CommandGroup.RADIO),
    spec(CommandNames.AIRPLANE_MODE, "Airplane mode", CommandGroup.RADIO,
        revertFor = { a -> if (a.optBoolean("enabled") == true) RevertConfirm.AGENT_LINK else null }),
    spec(CommandNames.AIRPLANE_CYCLE, "Cycle airplane mode", CommandGroup.RADIO),
    spec(CommandNames.USB_TETHERING, "USB tethering", CommandGroup.RADIO,
        revertFor = { a -> if (a.optBoolean("enabled") == false) RevertConfirm.DASHBOARD else null }),
    spec(CommandNames.HOTSPOT, "Wi-Fi hotspot", CommandGroup.RADIO),
    spec(CommandNames.WIFI, "Wi-Fi", CommandGroup.RADIO),
    spec(CommandNames.BLUETOOTH, "Bluetooth", CommandGroup.RADIO),
    spec(CommandNames.NETWORK_MODE, "Network mode", CommandGroup.RADIO, confirm = true,
        revertFor = { RevertConfirm.AGENT_LINK }),
    spec(CommandNames.REBOOT, "Reboot", CommandGroup.POWER, confirm = true, reauth = true, isReboot = true),
    spec(CommandNames.POWER_OFF, "Power off", CommandGroup.POWER, confirm = true, reauth = true, adminOnly = true),
    spec(CommandNames.SCREEN, "Screen", CommandGroup.POWER),
    spec(CommandNames.BRIGHTNESS, "Brightness", CommandGroup.POWER),
    spec(CommandNames.CHARGING, "Charging", CommandGroup.POWER),
    spec(CommandNames.CHARGE_LIMIT, "Charge limit", CommandGroup.POWER),
    spec(CommandNames.APP_LIST, "List apps", CommandGroup.APPS),
    spec(CommandNames.APP_FORCE_STOP, "Force stop app", CommandGroup.APPS, confirm = true),
    spec(CommandNames.APP_DISABLE, "Disable app", CommandGroup.APPS, confirm = true, reauth = true, adminOnly = true),
    spec(CommandNames.APP_ENABLE, "Enable app", CommandGroup.APPS),
    spec(CommandNames.PROCESS_LIST, "Processes", CommandGroup.APPS),
    spec(CommandNames.SEND_SMS, "Send SMS", CommandGroup.MESSAGING, confirm = true, adminOnly = true),
    spec(CommandNames.USSD, "Run USSD code", CommandGroup.MESSAGING, adminOnly = true),
    spec(CommandNames.SHELL, "Root shell", CommandGroup.ADMIN, confirm = true, reauth = true, adminOnly = true),
    spec(CommandNames.REFRESH, "Refresh all readings", CommandGroup.ADMIN),
    spec(CommandNames.CAPABILITY_PROBE, "Re-test capabilities", CommandGroup.ADMIN),
    spec(CommandNames.WATCHDOG_SIMULATE, "Simulate internet loss", CommandGroup.ADMIN, confirm = true, adminOnly = true),
    spec(CommandNames.SPEED_TEST, "Speed test", CommandGroup.FINDER, confirm = true),
    spec(CommandNames.FINDER_SPOT_TEST, "Spot test", CommandGroup.FINDER),
    spec(CommandNames.FINDER_SET_BASELINE, "Set baseline", CommandGroup.FINDER, confirm = true),
    spec(CommandNames.FINDER_DELETE_SPOT, "Delete spot", CommandGroup.FINDER, confirm = true),
)
