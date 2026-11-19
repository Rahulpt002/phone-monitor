/**
 * Device settings: every threshold the plan says must be editable from the Dashboard
 * (sections 6.4, 6.5, 7.2, 8.2, 8.5). The Server stores one Settings object per device,
 * sends it to the Agent in `hello` and in `settings` messages, and the Agent persists the
 * last copy so the watchdog keeps working offline. The Agent ships the same defaults.
 */

export interface TelemetrySettings {
  heartbeat_s: number; // 30
  fast_active_s: number; // 5  (a Dashboard is open)
  fast_idle_s: number; // 60
  mirror_s: number; // 1  (Finder mirror)
  slow_s: number; // 60
  internet_check_s: number; // 15  interval of the HTTP 204 / ping / DNS probes
  probe_url: string; // HTTP 204 endpoint
  ping_host: string;
  dns_host: string;
  public_ip_url: string; // returns the caller's IP as plain text
  public_ip_every_s: number; // 900
}

export interface WeeklyReboot {
  enabled: boolean;
  day: number; // 0 = Sunday .. 6 = Saturday (phone local time)
  time: string; // "04:00" (phone local time)
}

export interface WatchdogSettings {
  enabled: boolean;
  // internet recovery ladder (plan 6.5)
  internet_fail_s: number; // 90  -> data off/on
  data_off_s: number; // 5   how long data stays off during the cycle
  airplane_after_s: number; // 180 -> airplane cycle
  airplane_off_s: number; // 10
  reboot_after_s: number; // 600 -> reboot (within the reboot limit)
  reboot_retry_s: number; // 3600 retry interval once the limit is hit
  // tethering
  tether_fix: boolean; // USB connected but tethering off -> turn it back on
  tether_fix_after_s: number; // 20
  usb_function: 'rndis' | 'ncm';
  // charging and heat
  charge_limit_pct: number; // 60   stop charging at or above
  charge_resume_drop_pct: number; // 10  resume at limit - 10
  temp_warn_c: number; // 40   warning alert
  temp_stop_charge_c: number; // 45   stop charging + critical alert
  temp_resume_c: number; // 40   resume charging below this
  temp_radio_rest_c: number; // 50   also rest the radio
  radio_rest_s: number; // 600
  weak_power_min: number; // 30   plugged in but battery falling for this long
  // position
  move_tilt_deg: number; // 15
  move_distance_m: number; // 100
  // reboots
  max_reboots: number; // 3
  reboot_window_h: number; // 6
  weekly_reboot: WeeklyReboot;
}

export interface CommandSettings {
  revert_after_s: number; // 120 (plan 6.4 rule 1)
  expiry_s: number; // 60 (plan 6.4 rule 6)
  allow_power_off: boolean; // false (plan 6.4 rule 2)
}

export type AlertKind =
  | 'offline'
  | 'back_online'
  | 'weak_power'
  | 'low_battery'
  | 'hot'
  | 'signal_drop'
  | 'cell_changed'
  | 'tether_down'
  | 'usb_power_lost'
  | 'data_quota'
  | 'moved'
  | 'watchdog'
  | 'carrier_sms'
  | 'server_test';

export interface AlertSettings {
  offline_after_s: number; // 180
  low_battery_pct: number; // 20
  signal_drop_pct: number; // 60  (score below 60% of baseline)
  signal_drop_min: number; // 15
  cell_change_min: number; // 30
  tether_down_min: number; // 2
  quota_steps_pct: number[]; // [80, 90, 100]
  cooldown_min: number; // 30  same alert kind is not repeated within this window
  enabled: Record<AlertKind, boolean>;
  channels: { telegram: boolean; push: boolean };
}

export interface PlanSettings {
  quota_gb: number; // 0 = unlimited / quota alerts off
  cycle_start_day: number; // 1..28
}

export interface FinderSettings {
  w_sinr: number; // 0.45
  w_rsrp: number; // 0.35
  w_rsrq: number; // 0.20
  smooth_s: number; // 5
  spot_test_s: number; // 30
  speed_runs: number; // 3
  speed_down_url: string; // GET, server streams `bytes` bytes
  speed_up_url: string; // POST body is discarded
  speed_bytes: number; // bytes per download run
  speak_every_s: number; // 4
}

export interface Settings {
  telemetry: TelemetrySettings;
  watchdog: WatchdogSettings;
  commands: CommandSettings;
  alerts: AlertSettings;
  plan: PlanSettings;
  finder: FinderSettings;
  /** Optional device-profile override (plan 6.1); merged over the Agent's built-in profile. */
  profile_override: Record<string, unknown> | null;
}

export const ALERT_KINDS: AlertKind[] = [
  'offline', 'back_online', 'weak_power', 'low_battery', 'hot', 'signal_drop', 'cell_changed',
  'tether_down', 'usb_power_lost', 'data_quota', 'moved', 'watchdog', 'carrier_sms', 'server_test',
];

export const DEFAULT_SETTINGS: Settings = {
  telemetry: {
    heartbeat_s: 30,
    fast_active_s: 5,
    fast_idle_s: 60,
    mirror_s: 1,
    slow_s: 60,
    internet_check_s: 15,
    probe_url: 'https://connectivitycheck.gstatic.com/generate_204',
    ping_host: '1.1.1.1',
    dns_host: 'google.com',
    public_ip_url: 'https://api.ipify.org',
    public_ip_every_s: 900,
  },
  watchdog: {
    enabled: true,
    internet_fail_s: 90,
    data_off_s: 5,
    airplane_after_s: 180,
    airplane_off_s: 10,
    reboot_after_s: 600,
    reboot_retry_s: 3600,
    tether_fix: true,
    tether_fix_after_s: 20,
    usb_function: 'rndis',
    charge_limit_pct: 60,
    charge_resume_drop_pct: 10,
    temp_warn_c: 40,
    temp_stop_charge_c: 45,
    temp_resume_c: 40,
    temp_radio_rest_c: 50,
    radio_rest_s: 600,
    weak_power_min: 30,
    move_tilt_deg: 15,
    move_distance_m: 100,
    max_reboots: 3,
    reboot_window_h: 6,
    weekly_reboot: { enabled: false, day: 0, time: '04:00' },
  },
  commands: {
    revert_after_s: 120,
    expiry_s: 60,
    allow_power_off: false,
  },
  alerts: {
    offline_after_s: 180,
    low_battery_pct: 20,
    signal_drop_pct: 60,
    signal_drop_min: 15,
    cell_change_min: 30,
    tether_down_min: 2,
    quota_steps_pct: [80, 90, 100],
    cooldown_min: 30,
    enabled: {
      offline: true, back_online: true, weak_power: true, low_battery: true, hot: true,
      signal_drop: true, cell_changed: true, tether_down: true, usb_power_lost: true,
      data_quota: true, moved: true, watchdog: true, carrier_sms: true, server_test: true,
    },
    channels: { telegram: true, push: true },
  },
  plan: {
    quota_gb: 0,
    cycle_start_day: 1,
  },
  finder: {
    w_sinr: 0.45,
    w_rsrp: 0.35,
    w_rsrq: 0.2,
    smooth_s: 5,
    spot_test_s: 30,
    speed_runs: 3,
    speed_down_url: 'https://speed.cloudflare.com/__down?bytes={bytes}',
    speed_up_url: 'https://speed.cloudflare.com/__up',
    speed_bytes: 10_000_000,
    speak_every_s: 4,
  },
  profile_override: null,
};

type Plain = Record<string, unknown>;
const isPlain = (v: unknown): v is Plain => typeof v === 'object' && v !== null && !Array.isArray(v);

/**
 * Deep-merge a partial settings object over a base (arrays and scalars replace).
 * `profile_override` is replaced as a whole, so a PUT can remove keys from it in one step.
 */
export function mergeSettings(base: Settings, patch: unknown): Settings {
  const merge = (b: unknown, p: unknown): unknown => {
    if (!isPlain(b) || !isPlain(p)) return p === undefined ? b : p;
    const out: Plain = { ...b };
    for (const [k, v] of Object.entries(p)) out[k] = k in b ? merge(b[k], v) : v;
    return out;
  };
  const merged = merge(base, patch) as Settings;
  if (isPlain(patch) && 'profile_override' in patch) {
    const po = patch.profile_override;
    merged.profile_override = po === undefined ? base.profile_override : (po as Settings['profile_override']);
  }
  return merged;
}
