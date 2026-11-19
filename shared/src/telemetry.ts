/**
 * Telemetry groups reported by the Agent (plan section 6.2).
 *
 * Conventions (shared with the Kotlin Agent, see docs/protocol.md):
 * - JSON keys are snake_case.
 * - Every field the phone may be unable to read is optional or nullable; the Agent
 *   omits or sends null rather than inventing a value.
 * - Units are fixed by the field name or the comment: dBm, dB, bps, bytes, mA, mV, °C, seconds.
 * - Timestamps are ISO-8601 UTC strings ("2026-11-19T10:15:00.000Z").
 */

export type NetType =
  | 'LTE'
  | 'NR_NSA' // 5G non-standalone (LTE anchor + NR leg)
  | 'NR_SA' // 5G standalone
  | 'WCDMA'
  | 'GSM'
  | 'CDMA'
  | 'TDSCDMA'
  | 'UNKNOWN';

export interface NeighbourCell {
  type: NetType;
  pci?: number | null;
  earfcn?: number | null; // LTE EARFCN or NR ARFCN
  rsrp?: number | null;
  rsrq?: number | null;
}

/** The NR (5G) leg for NR_NSA, or the serving cell for NR_SA. SS-* measurements. */
export interface NrLeg {
  ss_rsrp?: number | null; // dBm
  ss_rsrq?: number | null; // dB
  ss_sinr?: number | null; // dB
  band?: number | null; // n78 -> 78
  arfcn?: number | null;
  pci?: number | null;
  nci?: number | null; // NR cell identity (36-bit, fits a JS number / Kotlin Long)
}

export interface RadioInfo {
  operator?: string | null; // e.g. "Jio 4G"
  mcc_mnc?: string | null; // e.g. "405857"
  net_type: NetType;
  band?: number | null; // LTE band number (e.g. 40) or NR band for NR_SA
  earfcn?: number | null; // channel number (EARFCN for LTE, ARFCN for NR_SA)
  pci?: number | null;
  cell_id?: number | null; // LTE ECI (28-bit) or NR NCI (36-bit)
  tac?: number | null;
  rsrp?: number | null; // dBm (SS-RSRP for NR_SA)
  rsrq?: number | null; // dB
  sinr?: number | null; // dB (RSSNR on LTE)
  rssi?: number | null; // dBm
  cqi?: number | null;
  ta?: number | null; // timing advance (LTE units; ~78 m per step)
  signal_level?: number | null; // Android 0..4 bars
  nr?: NrLeg | null; // present for NR_NSA (secondary leg) and NR_SA
  neighbours?: NeighbourCell[];
  sim_state?: 'ready' | 'absent' | 'pin_required' | 'puk_required' | 'locked' | 'not_ready' | 'unknown';
  roaming?: boolean | null;
  data_enabled?: boolean | null;
  airplane?: boolean | null;
  /** Signal Finder score 0..100 of the current reading (plan 7.2), smoothed over finder.smooth_s. */
  score?: number | null;
}

export interface NetInfo {
  online: boolean; // HTTP 204 probe succeeded within the last check
  http_ms?: number | null; // latency of the 204 probe
  ping_ms?: number | null; // average RTT
  ping_loss_pct?: number | null; // 0..100
  jitter_ms?: number | null;
  dns_ok?: boolean | null;
  public_ipv4?: string | null;
  public_ipv6?: string | null;
  rx_bps?: number | null; // download speed now, bits per second, on the mobile interface
  tx_bps?: number | null; // upload speed now
  active_if?: string | null; // e.g. "rmnet_data2"
  internet_down_since?: string | null; // ISO ts when the watchdog first saw the internet fail
}

export interface TrafficInfo {
  today_rx?: number | null; // bytes on mobile, since local midnight
  today_tx?: number | null;
  cycle_rx?: number | null; // bytes since the billing cycle start day (settings.plan.cycle_start_day)
  cycle_tx?: number | null;
  cycle_start?: string | null; // ISO date of the current cycle start
  per_if?: Record<string, { rx: number; tx: number }>; // cumulative counters from /sys/class/net/<if>/statistics
}

export interface TetherClient {
  ip: string;
  mac?: string | null;
  iface?: string | null;
  hostname?: string | null;
}

export interface TetherInfo {
  usb_connected?: boolean | null; // USB data connection to a host is present
  usb_function?: 'rndis' | 'ncm' | 'mtp' | 'none' | 'other' | null;
  usb_tether_on?: boolean | null;
  tether_if?: string | null; // e.g. "rndis0" / "ncm0"
  tether_ip?: string | null; // gateway IP the indoor device sees, e.g. 192.168.42.129
  hotspot_on?: boolean | null;
  hotspot_ssid?: string | null;
  clients?: TetherClient[];
}

export type BatteryStatus = 'charging' | 'discharging' | 'full' | 'not_charging' | 'unknown';
export type PlugType = 'usb' | 'ac' | 'wireless' | 'dock' | 'none';

export interface PowerInfo {
  percent?: number | null; // 0..100
  status?: BatteryStatus | null;
  /** Net battery current in mA. Positive = charging (flowing into the battery), negative = draining. */
  current_ma?: number | null;
  voltage_mv?: number | null;
  health?: 'good' | 'overheat' | 'dead' | 'over_voltage' | 'cold' | 'failure' | 'unknown' | null;
  plug?: PlugType | null;
  charging_enabled?: boolean | null; // the root charge switch state (false = stopped by Agent)
  charge_limit_pct?: number | null; // limit currently enforced by the Agent
  charge_control?: string | null; // charge-control method in use (profile id / sysfs path), null if none
  stop_reason?: 'limit' | 'heat' | 'manual' | null; // why charging is currently stopped
}

export interface HeatInfo {
  battery_c?: number | null;
  cpu_c?: number | null;
  modem_c?: number | null;
  skin_c?: number | null;
  /** PowerManager thermal status 0 none, 1 light, 2 moderate, 3 severe, 4 critical, 5 emergency, 6 shutdown. */
  thermal_status?: number | null;
  zones?: Record<string, number>; // raw thermal_zone type -> °C (slow tier only, may be trimmed)
  radio_resting?: boolean | null; // Agent rested the radio because of heat (plan 6.5, 50 °C)
}

export interface ProcessInfo {
  pid: number;
  name: string;
  cpu_pct?: number | null;
  mem_mb?: number | null;
}

export interface SystemInfo {
  uptime_s?: number | null;
  boot_count?: number | null; // Settings.Global.BOOT_COUNT
  boot_reason?: string | null; // ro.boot.bootreason / sys.boot.reason
  last_boot_at?: string | null;
  cpu_load?: [number, number, number] | null; // /proc/loadavg
  ram_total_mb?: number | null;
  ram_avail_mb?: number | null;
  storage_total_mb?: number | null;
  storage_free_mb?: number | null;
  top?: ProcessInfo[]; // top processes by CPU (max 10)
  screen_on?: boolean | null;
  agent_version?: string | null;
  android_version?: string | null;
  agent_uptime_s?: number | null; // since the Agent service started
  reboots_in_window?: number | null; // Agent-initiated reboots in the reboot-limit window
}

export interface PositionInfo {
  lat?: number | null;
  lon?: number | null;
  acc_m?: number | null;
  fix_at?: string | null;
  pitch_deg?: number | null; // from the accelerometer
  roll_deg?: number | null;
  azimuth_deg?: number | null; // compass
  tilt_change_deg?: number | null; // change vs the installed reference orientation
  moved_m?: number | null; // distance vs the installed reference location
}

export interface DeviceInfo {
  device_id?: string | null; // assigned by the Server at pairing
  manufacturer: string;
  model: string;
  device: string; // Build.DEVICE
  android_version: string; // Build.VERSION.RELEASE
  sdk: number;
  build_id?: string | null;
  agent_version: string;
  agent_version_code?: number | null;
  profile_id: string; // device profile in use (plan 6.1), e.g. "generic" or "samsung_galaxy_s"
  rooted: boolean;
  root_impl?: string | null; // "magisk 27.0" / "kernelsu" / null
  imei_tail?: string | null; // last 4 digits only, never the full IMEI
}

/** Result of the capability probe (plan 6.1). Keyed by command name from commands.ts. */
export interface CapabilityEntry {
  supported: boolean;
  method?: string | null; // which implementation will be used (e.g. "cmd connectivity airplane-mode")
  note?: string | null; // why not supported, or caveats
}
export type CapabilityMap = Record<string, CapabilityEntry>;

/** Every group that can appear in a telemetry message. */
export interface TelemetryGroups {
  radio?: RadioInfo;
  net?: NetInfo;
  traffic?: TrafficInfo;
  tether?: TetherInfo;
  power?: PowerInfo;
  heat?: HeatInfo;
  system?: SystemInfo;
  position?: PositionInfo;
  device?: DeviceInfo;
  capabilities?: CapabilityMap;
}

export type TelemetryTier = 'fast' | 'slow' | 'static';

/** Which groups each tier carries (plan 8.2 "Sending tiers"). */
export const TIER_GROUPS: Record<TelemetryTier, (keyof TelemetryGroups)[]> = {
  fast: ['radio', 'net'],
  slow: ['power', 'heat', 'system', 'tether', 'traffic', 'position'],
  static: ['device', 'capabilities'],
};
