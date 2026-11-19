package com.hotspotmonitor.agent.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Mirror of shared/src/telemetry.ts (plan section 6.2).
 * Every field the phone may be unable to read is nullable with a null default: the Agent omits it
 * (explicitNulls = false) instead of inventing a value. Units follow the field name.
 */

@Serializable
enum class NetType { LTE, NR_NSA, NR_SA, WCDMA, GSM, CDMA, TDSCDMA, UNKNOWN }

@Serializable
data class NeighbourCell(
    val type: NetType,
    val pci: Int? = null,
    /** LTE EARFCN or NR ARFCN. */
    val earfcn: Int? = null,
    val rsrp: Int? = null,
    val rsrq: Int? = null,
)

/** The NR (5G) leg for NR_NSA, or the serving cell for NR_SA. SS-* measurements. */
@Serializable
data class NrLeg(
    @SerialName("ss_rsrp") val ssRsrp: Int? = null,
    @SerialName("ss_rsrq") val ssRsrq: Int? = null,
    @SerialName("ss_sinr") val ssSinr: Double? = null,
    /** n78 -> 78 */
    val band: Int? = null,
    val arfcn: Int? = null,
    val pci: Int? = null,
    /** NR cell identity (36-bit). */
    val nci: Long? = null,
)

@Serializable
enum class SimState {
    @SerialName("ready") READY,
    @SerialName("absent") ABSENT,
    @SerialName("pin_required") PIN_REQUIRED,
    @SerialName("puk_required") PUK_REQUIRED,
    @SerialName("locked") LOCKED,
    @SerialName("not_ready") NOT_READY,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
data class RadioInfo(
    /** e.g. "Jio 4G" */
    val operator: String? = null,
    @SerialName("mcc_mnc") val mccMnc: String? = null,
    @SerialName("net_type") val netType: NetType = NetType.UNKNOWN,
    /** LTE band number, or the NR band for NR_SA. */
    val band: Int? = null,
    /** EARFCN for LTE, ARFCN for NR_SA. */
    val earfcn: Int? = null,
    val pci: Int? = null,
    /** LTE ECI (28-bit) or NR NCI (36-bit). */
    @SerialName("cell_id") val cellId: Long? = null,
    val tac: Int? = null,
    /** dBm (SS-RSRP for NR_SA). */
    val rsrp: Int? = null,
    /** dB */
    val rsrq: Int? = null,
    /** dB (RSSNR on LTE). */
    val sinr: Double? = null,
    /** dBm */
    val rssi: Int? = null,
    val cqi: Int? = null,
    /** Timing advance (LTE units, ~78 m per step). */
    val ta: Int? = null,
    /** Android 0..4 bars. */
    @SerialName("signal_level") val signalLevel: Int? = null,
    /** Present for NR_NSA (secondary leg) and NR_SA. */
    val nr: NrLeg? = null,
    val neighbours: List<NeighbourCell>? = null,
    @SerialName("sim_state") val simState: SimState? = null,
    val roaming: Boolean? = null,
    @SerialName("data_enabled") val dataEnabled: Boolean? = null,
    val airplane: Boolean? = null,
    /** Signal Finder score 0..100 (plan 7.2), smoothed over finder.smooth_s. */
    val score: Double? = null,
)

@Serializable
data class NetInfo(
    /** HTTP 204 probe succeeded within the last check. */
    val online: Boolean,
    @SerialName("http_ms") val httpMs: Long? = null,
    @SerialName("ping_ms") val pingMs: Double? = null,
    /** 0..100 */
    @SerialName("ping_loss_pct") val pingLossPct: Double? = null,
    @SerialName("jitter_ms") val jitterMs: Double? = null,
    @SerialName("dns_ok") val dnsOk: Boolean? = null,
    @SerialName("public_ipv4") val publicIpv4: String? = null,
    @SerialName("public_ipv6") val publicIpv6: String? = null,
    /** Download speed now, bits per second, on the mobile interface. */
    @SerialName("rx_bps") val rxBps: Long? = null,
    @SerialName("tx_bps") val txBps: Long? = null,
    /** e.g. "rmnet_data2" */
    @SerialName("active_if") val activeIf: String? = null,
    /** ISO ts when the watchdog first saw the internet fail. */
    @SerialName("internet_down_since") val internetDownSince: String? = null,
)

@Serializable
data class IfCounters(val rx: Long, val tx: Long)

@Serializable
data class TrafficInfo(
    /** Bytes on mobile since local midnight. */
    @SerialName("today_rx") val todayRx: Long? = null,
    @SerialName("today_tx") val todayTx: Long? = null,
    /** Bytes since the billing cycle start day (settings.plan.cycle_start_day). */
    @SerialName("cycle_rx") val cycleRx: Long? = null,
    @SerialName("cycle_tx") val cycleTx: Long? = null,
    /** ISO date of the current cycle start. */
    @SerialName("cycle_start") val cycleStart: String? = null,
    /** Cumulative counters from /sys/class/net/<if>/statistics. */
    @SerialName("per_if") val perIf: Map<String, IfCounters>? = null,
)

@Serializable
data class TetherClient(
    val ip: String,
    val mac: String? = null,
    val iface: String? = null,
    val hostname: String? = null,
)

@Serializable
enum class UsbFunction {
    @SerialName("rndis") RNDIS,
    @SerialName("ncm") NCM,
    @SerialName("mtp") MTP,
    @SerialName("none") NONE,
    @SerialName("other") OTHER,
}

@Serializable
data class TetherInfo(
    /** USB data connection to a host is present. */
    @SerialName("usb_connected") val usbConnected: Boolean? = null,
    @SerialName("usb_function") val usbFunction: UsbFunction? = null,
    @SerialName("usb_tether_on") val usbTetherOn: Boolean? = null,
    /** e.g. "rndis0" / "ncm0" */
    @SerialName("tether_if") val tetherIf: String? = null,
    /** Gateway IP the indoor device sees, e.g. 192.168.42.129. */
    @SerialName("tether_ip") val tetherIp: String? = null,
    @SerialName("hotspot_on") val hotspotOn: Boolean? = null,
    @SerialName("hotspot_ssid") val hotspotSsid: String? = null,
    val clients: List<TetherClient>? = null,
)

@Serializable
enum class BatteryStatus {
    @SerialName("charging") CHARGING,
    @SerialName("discharging") DISCHARGING,
    @SerialName("full") FULL,
    @SerialName("not_charging") NOT_CHARGING,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
enum class PlugType {
    @SerialName("usb") USB,
    @SerialName("ac") AC,
    @SerialName("wireless") WIRELESS,
    @SerialName("dock") DOCK,
    @SerialName("none") NONE,
}

@Serializable
enum class BatteryHealth {
    @SerialName("good") GOOD,
    @SerialName("overheat") OVERHEAT,
    @SerialName("dead") DEAD,
    @SerialName("over_voltage") OVER_VOLTAGE,
    @SerialName("cold") COLD,
    @SerialName("failure") FAILURE,
    @SerialName("unknown") UNKNOWN,
}

/** Why charging is currently stopped by the Agent. */
@Serializable
enum class StopReason {
    @SerialName("limit") LIMIT,
    @SerialName("heat") HEAT,
    @SerialName("manual") MANUAL,
}

@Serializable
data class PowerInfo(
    /** 0..100 */
    val percent: Int? = null,
    val status: BatteryStatus? = null,
    /** Net battery current in mA. Positive = charging (into the battery), negative = draining. */
    @SerialName("current_ma") val currentMa: Int? = null,
    @SerialName("voltage_mv") val voltageMv: Int? = null,
    val health: BatteryHealth? = null,
    val plug: PlugType? = null,
    /** The root charge switch state (false = stopped by the Agent). */
    @SerialName("charging_enabled") val chargingEnabled: Boolean? = null,
    /** Limit currently enforced by the Agent. */
    @SerialName("charge_limit_pct") val chargeLimitPct: Int? = null,
    /** Charge-control method in use (profile id / sysfs path), null if none. */
    @SerialName("charge_control") val chargeControl: String? = null,
    @SerialName("stop_reason") val stopReason: StopReason? = null,
)

@Serializable
data class HeatInfo(
    @SerialName("battery_c") val batteryC: Double? = null,
    @SerialName("cpu_c") val cpuC: Double? = null,
    @SerialName("modem_c") val modemC: Double? = null,
    @SerialName("skin_c") val skinC: Double? = null,
    /** PowerManager thermal status 0 none .. 6 shutdown. */
    @SerialName("thermal_status") val thermalStatus: Int? = null,
    /** Raw thermal_zone type -> °C (slow tier only, may be trimmed). */
    val zones: Map<String, Double>? = null,
    /** The Agent rested the radio because of heat (plan 6.5, 50 °C). */
    @SerialName("radio_resting") val radioResting: Boolean? = null,
)

@Serializable
data class ProcessInfo(
    val pid: Int,
    val name: String,
    @SerialName("cpu_pct") val cpuPct: Double? = null,
    @SerialName("mem_mb") val memMb: Double? = null,
)

@Serializable
data class SystemInfo(
    @SerialName("uptime_s") val uptimeS: Long? = null,
    /** Settings.Global.BOOT_COUNT */
    @SerialName("boot_count") val bootCount: Int? = null,
    /** ro.boot.bootreason / sys.boot.reason */
    @SerialName("boot_reason") val bootReason: String? = null,
    @SerialName("last_boot_at") val lastBootAt: String? = null,
    /** /proc/loadavg: 1, 5, 15 minutes. */
    @SerialName("cpu_load") val cpuLoad: List<Double>? = null,
    @SerialName("ram_total_mb") val ramTotalMb: Long? = null,
    @SerialName("ram_avail_mb") val ramAvailMb: Long? = null,
    @SerialName("storage_total_mb") val storageTotalMb: Long? = null,
    @SerialName("storage_free_mb") val storageFreeMb: Long? = null,
    /** Top processes by CPU (max 10). */
    val top: List<ProcessInfo>? = null,
    @SerialName("screen_on") val screenOn: Boolean? = null,
    @SerialName("agent_version") val agentVersion: String? = null,
    @SerialName("android_version") val androidVersion: String? = null,
    /** Since the Agent service started. */
    @SerialName("agent_uptime_s") val agentUptimeS: Long? = null,
    /** Agent-initiated reboots in the reboot-limit window. */
    @SerialName("reboots_in_window") val rebootsInWindow: Int? = null,
)

@Serializable
data class PositionInfo(
    val lat: Double? = null,
    val lon: Double? = null,
    @SerialName("acc_m") val accM: Double? = null,
    @SerialName("fix_at") val fixAt: String? = null,
    @SerialName("pitch_deg") val pitchDeg: Double? = null,
    @SerialName("roll_deg") val rollDeg: Double? = null,
    /** Compass. */
    @SerialName("azimuth_deg") val azimuthDeg: Double? = null,
    /** Change vs the installed reference orientation. */
    @SerialName("tilt_change_deg") val tiltChangeDeg: Double? = null,
    /** Distance vs the installed reference location. */
    @SerialName("moved_m") val movedM: Double? = null,
)

@Serializable
data class DeviceInfo(
    /** Assigned by the Server at pairing. */
    @SerialName("device_id") val deviceId: String? = null,
    val manufacturer: String,
    val model: String,
    /** Build.DEVICE */
    val device: String,
    /** Build.VERSION.RELEASE */
    @SerialName("android_version") val androidVersion: String,
    val sdk: Int,
    @SerialName("build_id") val buildId: String? = null,
    @SerialName("agent_version") val agentVersion: String,
    @SerialName("agent_version_code") val agentVersionCode: Int? = null,
    /** Device profile in use (plan 6.1), e.g. "generic" or "samsung_galaxy_s". */
    @SerialName("profile_id") val profileId: String,
    val rooted: Boolean,
    /** "magisk 27.0" / "kernelsu" / null */
    @SerialName("root_impl") val rootImpl: String? = null,
    /** Last 4 digits only, never the full IMEI. */
    @SerialName("imei_tail") val imeiTail: String? = null,
)

/** Result of the capability probe (plan 6.1). Keyed by command name (see [CommandNames]). */
@Serializable
data class CapabilityEntry(
    val supported: Boolean,
    /** Which implementation will be used, e.g. "cmd connectivity airplane-mode". */
    val method: String? = null,
    /** Why it is not supported, or caveats. */
    val note: String? = null,
)

typealias CapabilityMap = Map<String, CapabilityEntry>

@Serializable
enum class TelemetryTier {
    @SerialName("fast") FAST,
    @SerialName("slow") SLOW,
    @SerialName("static") STATIC,
}
