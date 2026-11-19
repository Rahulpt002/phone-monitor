@file:UseSerializers(LenientIntSerializer::class, LenientLongSerializer::class)

package com.hotspotmonitor.agent.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonObject

/*
 * Mirror of shared/src/settings.ts. The defaults below MUST stay identical to
 * shared/fixtures/settings-default.json (checked by SettingsDefaultsTest). The watchdog runs on the
 * last applied copy while offline (docs/protocol.md 3.4).
 */

@Serializable
data class TelemetrySettings(
    @SerialName("heartbeat_s") val heartbeatS: Int = 30,
    /** Fast tier while a Dashboard is open. */
    @SerialName("fast_active_s") val fastActiveS: Int = 5,
    @SerialName("fast_idle_s") val fastIdleS: Int = 60,
    /** Finder mirror. */
    @SerialName("mirror_s") val mirrorS: Int = 1,
    @SerialName("slow_s") val slowS: Int = 60,
    /** Interval of the HTTP 204 / ping / DNS probes. */
    @SerialName("internet_check_s") val internetCheckS: Int = 15,
    @SerialName("probe_url") val probeUrl: String = "https://connectivitycheck.gstatic.com/generate_204",
    @SerialName("ping_host") val pingHost: String = "1.1.1.1",
    @SerialName("dns_host") val dnsHost: String = "google.com",
    /** Returns the caller's IP as plain text. */
    @SerialName("public_ip_url") val publicIpUrl: String = "https://api.ipify.org",
    @SerialName("public_ip_every_s") val publicIpEveryS: Int = 900,
)

@Serializable
data class WeeklyReboot(
    val enabled: Boolean = false,
    /** 0 = Sunday .. 6 = Saturday (phone local time). */
    val day: Int = 0,
    /** "04:00" (phone local time). */
    val time: String = "04:00",
)

@Serializable
data class WatchdogSettings(
    val enabled: Boolean = true,
    // internet recovery ladder (plan 6.5)
    /** -> data off/on */
    @SerialName("internet_fail_s") val internetFailS: Int = 90,
    /** How long data stays off during the cycle. */
    @SerialName("data_off_s") val dataOffS: Int = 5,
    /** -> airplane cycle */
    @SerialName("airplane_after_s") val airplaneAfterS: Int = 180,
    @SerialName("airplane_off_s") val airplaneOffS: Int = 10,
    /** -> reboot (within the reboot limit) */
    @SerialName("reboot_after_s") val rebootAfterS: Int = 600,
    /** Retry interval once the limit is hit. */
    @SerialName("reboot_retry_s") val rebootRetryS: Int = 3600,
    // tethering
    /** USB connected but tethering off -> turn it back on. */
    @SerialName("tether_fix") val tetherFix: Boolean = true,
    @SerialName("tether_fix_after_s") val tetherFixAfterS: Int = 20,
    /** 'rndis' | 'ncm' */
    @SerialName("usb_function") val usbFunction: String = "rndis",
    // charging and heat
    /** Stop charging at or above. */
    @SerialName("charge_limit_pct") val chargeLimitPct: Int = 60,
    /** Resume at limit - this. */
    @SerialName("charge_resume_drop_pct") val chargeResumeDropPct: Int = 10,
    /** Warning alert. */
    @SerialName("temp_warn_c") val tempWarnC: Double = 40.0,
    /** Stop charging + critical alert. */
    @SerialName("temp_stop_charge_c") val tempStopChargeC: Double = 45.0,
    /** Resume charging below this. */
    @SerialName("temp_resume_c") val tempResumeC: Double = 40.0,
    /** Also rest the radio. */
    @SerialName("temp_radio_rest_c") val tempRadioRestC: Double = 50.0,
    @SerialName("radio_rest_s") val radioRestS: Int = 600,
    /** Plugged in but battery falling for this long (minutes). */
    @SerialName("weak_power_min") val weakPowerMin: Int = 30,
    // position
    @SerialName("move_tilt_deg") val moveTiltDeg: Double = 15.0,
    @SerialName("move_distance_m") val moveDistanceM: Double = 100.0,
    // reboots
    @SerialName("max_reboots") val maxReboots: Int = 3,
    @SerialName("reboot_window_h") val rebootWindowH: Double = 6.0,
    @SerialName("weekly_reboot") val weeklyReboot: WeeklyReboot = WeeklyReboot(),
)

@Serializable
data class CommandSettings(
    /** Plan 6.4 rule 1. */
    @SerialName("revert_after_s") val revertAfterS: Int = 120,
    /** Plan 6.4 rule 6. */
    @SerialName("expiry_s") val expiryS: Int = 60,
    /** Plan 6.4 rule 2. */
    @SerialName("allow_power_off") val allowPowerOff: Boolean = false,
)

@Serializable
data class AlertChannels(
    val telegram: Boolean = true,
    val push: Boolean = true,
)

@Serializable
data class AlertSettings(
    @SerialName("offline_after_s") val offlineAfterS: Int = 180,
    @SerialName("low_battery_pct") val lowBatteryPct: Int = 20,
    /** Score below this % of the baseline. */
    @SerialName("signal_drop_pct") val signalDropPct: Int = 60,
    @SerialName("signal_drop_min") val signalDropMin: Int = 15,
    @SerialName("cell_change_min") val cellChangeMin: Int = 30,
    @SerialName("tether_down_min") val tetherDownMin: Int = 2,
    @SerialName("quota_steps_pct") val quotaStepsPct: List<Int> = listOf(80, 90, 100),
    /** The same alert kind is not repeated within this window. */
    @SerialName("cooldown_min") val cooldownMin: Int = 30,
    /** Keyed by alert kind (see [ALERT_KINDS]). */
    val enabled: Map<String, Boolean> = ALERT_KINDS.associateWith { true },
    val channels: AlertChannels = AlertChannels(),
)

@Serializable
data class PlanSettings(
    /** 0 = unlimited / quota alerts off. */
    @SerialName("quota_gb") val quotaGb: Double = 0.0,
    /** 1..28 */
    @SerialName("cycle_start_day") val cycleStartDay: Int = 1,
)

@Serializable
data class FinderSettings(
    @SerialName("w_sinr") val wSinr: Double = 0.45,
    @SerialName("w_rsrp") val wRsrp: Double = 0.35,
    @SerialName("w_rsrq") val wRsrq: Double = 0.2,
    @SerialName("smooth_s") val smoothS: Int = 5,
    @SerialName("spot_test_s") val spotTestS: Int = 30,
    @SerialName("speed_runs") val speedRuns: Int = 3,
    /** GET; `{bytes}` is replaced with [speedBytes]. */
    @SerialName("speed_down_url") val speedDownUrl: String = "https://speed.cloudflare.com/__down?bytes={bytes}",
    /** POST; the body is discarded. */
    @SerialName("speed_up_url") val speedUpUrl: String = "https://speed.cloudflare.com/__up",
    /** Bytes per download run. */
    @SerialName("speed_bytes") val speedBytes: Long = 10_000_000,
    @SerialName("speak_every_s") val speakEveryS: Int = 4,
) {
    val weights: ScoreWeights get() = ScoreWeights(wSinr = wSinr, wRsrp = wRsrp, wRsrq = wRsrq)
}

@Serializable
data class Settings(
    val telemetry: TelemetrySettings = TelemetrySettings(),
    val watchdog: WatchdogSettings = WatchdogSettings(),
    val commands: CommandSettings = CommandSettings(),
    val alerts: AlertSettings = AlertSettings(),
    val plan: PlanSettings = PlanSettings(),
    val finder: FinderSettings = FinderSettings(),
    /** Optional device-profile override (plan 6.1); deep-merged over the matched profile. */
    @SerialName("profile_override") val profileOverride: JsonObject? = null,
) {
    companion object {
        val DEFAULT = Settings()
    }
}
