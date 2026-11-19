/**
 * Field metadata for the settings editor: labels, units, limits and help for every editable value
 * in shared Settings, plus validation, per-field defaults and the minimal patch for PUT /settings.
 */
import { ALERT_KINDS, DEFAULT_SETTINGS, type AlertKind, type Settings } from '@hsm/shared';

export type FieldKind = 'int' | 'number' | 'bool' | 'host' | 'url' | 'select' | 'time' | 'weekday' | 'int_list';

export interface FieldDef {
  path: string;
  label: string;
  kind: FieldKind;
  unit?: string;
  help?: string;
  min?: number;
  max?: number;
  step?: number;
  options?: { value: string; label: string }[];
  /** URL fields that must contain a placeholder, e.g. {bytes}. */
  placeholder?: string;
}

export interface SectionDef {
  id: string;
  title: string;
  description: string;
  fields: FieldDef[];
}

const ALERT_LABEL: Record<AlertKind, string> = {
  offline: 'Phone offline',
  back_online: 'Back online',
  weak_power: 'Weak power (plugged in but draining)',
  low_battery: 'Low battery',
  hot: 'Hot (temperature warning / critical)',
  signal_drop: 'Signal drop vs baseline',
  cell_changed: 'Serving cell changed',
  tether_down: 'USB tethering down',
  usb_power_lost: 'USB power lost',
  data_quota: 'Data quota steps',
  moved: 'Phone moved or tilted',
  watchdog: 'Watchdog acted',
  carrier_sms: 'Carrier SMS received',
  server_test: 'Test alerts',
};

export function alertKindLabel(kind: string): string {
  return ALERT_LABEL[kind as AlertKind] ?? kind;
}

export const SETTINGS_SECTIONS: SectionDef[] = [
  {
    id: 'watchdog',
    title: 'Watchdog',
    description: 'Local recovery rules that run on the phone, even without internet (plan 6.5).',
    fields: [
      { path: 'watchdog.enabled', label: 'Watchdog enabled', kind: 'bool', help: 'Turn off only while testing on the bench.' },
      { path: 'watchdog.internet_fail_s', label: 'Cycle mobile data after internet down for', kind: 'int', unit: 's', min: 10, max: 86_400 },
      { path: 'watchdog.data_off_s', label: 'Mobile data off time during the cycle', kind: 'int', unit: 's', min: 1, max: 600 },
      { path: 'watchdog.airplane_after_s', label: 'Cycle airplane mode after internet down for', kind: 'int', unit: 's', min: 10, max: 86_400 },
      { path: 'watchdog.airplane_off_s', label: 'Airplane mode on time during the cycle', kind: 'int', unit: 's', min: 1, max: 600 },
      { path: 'watchdog.reboot_after_s', label: 'Reboot after internet down for', kind: 'int', unit: 's', min: 30, max: 604_800 },
      { path: 'watchdog.reboot_retry_s', label: 'Retry reboot every (once the limit is hit)', kind: 'int', unit: 's', min: 60, max: 604_800 },
      { path: 'watchdog.tether_fix', label: 'Turn USB tethering back on when it drops', kind: 'bool' },
      { path: 'watchdog.tether_fix_after_s', label: 'Re-enable tethering after it is off for', kind: 'int', unit: 's', min: 1, max: 3600 },
      {
        path: 'watchdog.usb_function',
        label: 'USB tethering function',
        kind: 'select',
        options: [
          { value: 'rndis', label: 'RNDIS (Windows, most routers)' },
          { value: 'ncm', label: 'NCM (macOS, Linux, newer routers)' },
        ],
      },
      { path: 'watchdog.charge_limit_pct', label: 'Stop charging at', kind: 'int', unit: '%', min: 20, max: 100, help: 'Keep the battery around 50 to 60 % for safety (plan 4.2).' },
      { path: 'watchdog.charge_resume_drop_pct', label: 'Resume charging this far below the limit', kind: 'int', unit: '%', min: 1, max: 50 },
      { path: 'watchdog.temp_warn_c', label: 'Warning alert at battery temperature', kind: 'number', unit: '°C', min: 0, max: 90, step: 0.5 },
      { path: 'watchdog.temp_stop_charge_c', label: 'Stop charging at battery temperature', kind: 'number', unit: '°C', min: 0, max: 90, step: 0.5 },
      { path: 'watchdog.temp_resume_c', label: 'Resume charging below', kind: 'number', unit: '°C', min: 0, max: 90, step: 0.5 },
      { path: 'watchdog.temp_radio_rest_c', label: 'Rest the radio at battery temperature', kind: 'number', unit: '°C', min: 0, max: 100, step: 0.5 },
      { path: 'watchdog.radio_rest_s', label: 'Radio rest duration', kind: 'int', unit: 's', min: 10, max: 86_400 },
      { path: 'watchdog.weak_power_min', label: '"Power supply too weak" after battery falls while plugged in for', kind: 'int', unit: 'min', min: 1, max: 1440 },
      { path: 'watchdog.move_tilt_deg', label: 'Moved alert when tilt changes by', kind: 'number', unit: '°', min: 1, max: 180 },
      { path: 'watchdog.move_distance_m', label: 'Moved alert when location changes by', kind: 'int', unit: 'm', min: 1, max: 100_000 },
      { path: 'watchdog.max_reboots', label: 'Reboot limit', kind: 'int', unit: 'reboots', min: 0, max: 50, help: 'From any source, within the window below (plan 6.4 rule 3).' },
      { path: 'watchdog.reboot_window_h', label: 'Reboot limit window', kind: 'int', unit: 'h', min: 1, max: 168 },
      { path: 'watchdog.weekly_reboot.enabled', label: 'Weekly reboot', kind: 'bool', help: 'Optional preventive reboot (plan 6.5).' },
      { path: 'watchdog.weekly_reboot.day', label: 'Weekly reboot day', kind: 'weekday' },
      { path: 'watchdog.weekly_reboot.time', label: 'Weekly reboot time (phone local)', kind: 'time' },
    ],
  },
  {
    id: 'commands',
    title: 'Command safety',
    description: 'Rules applied to every remote command (plan 6.4).',
    fields: [
      { path: 'commands.revert_after_s', label: 'Auto-revert timer', kind: 'int', unit: 's', min: 30, max: 3600, help: 'A change that can cut the link is undone if it is not confirmed in time.' },
      { path: 'commands.expiry_s', label: 'Discard commands older than', kind: 'int', unit: 's', min: 10, max: 3600 },
      { path: 'commands.allow_power_off', label: 'Allow remote power off', kind: 'bool', help: 'A phone that is switched off needs a hand to switch it on again.' },
    ],
  },
  {
    id: 'alerts',
    title: 'Alerts',
    description: 'When the Server sends Telegram and push alerts (plan 8.5).',
    fields: [
      { path: 'alerts.offline_after_s', label: 'Phone offline after no heartbeat for', kind: 'int', unit: 's', min: 30, max: 86_400 },
      { path: 'alerts.low_battery_pct', label: 'Low battery at or below', kind: 'int', unit: '%', min: 0, max: 100 },
      { path: 'alerts.signal_drop_pct', label: 'Signal drop when score is below this share of the baseline', kind: 'int', unit: '%', min: 1, max: 100 },
      { path: 'alerts.signal_drop_min', label: 'Signal drop must last', kind: 'int', unit: 'min', min: 1, max: 1440 },
      { path: 'alerts.cell_change_min', label: 'Cell changed must last', kind: 'int', unit: 'min', min: 1, max: 1440 },
      { path: 'alerts.tether_down_min', label: 'Tethering down must last', kind: 'int', unit: 'min', min: 1, max: 1440 },
      { path: 'alerts.quota_steps_pct', label: 'Data quota alert steps', kind: 'int_list', unit: '%', min: 1, max: 1000, help: 'Comma-separated, ascending, e.g. 80, 90, 100. Values above 100 warn after the plan is used up.' },
      { path: 'alerts.cooldown_min', label: 'Do not repeat the same alert within', kind: 'int', unit: 'min', min: 0, max: 10_080 },
      { path: 'alerts.channels.telegram', label: 'Send to Telegram', kind: 'bool' },
      { path: 'alerts.channels.push', label: 'Send browser push notifications', kind: 'bool' },
      ...ALERT_KINDS.map(
        (k): FieldDef => ({ path: `alerts.enabled.${k}`, label: ALERT_LABEL[k], kind: 'bool' }),
      ),
    ],
  },
  {
    id: 'plan',
    title: 'Data plan',
    description: 'Used for the usage bar and quota alerts.',
    fields: [
      { path: 'plan.quota_gb', label: 'Quota per billing cycle', kind: 'number', unit: 'GB', min: 0, max: 1_000_000, step: 0.5, help: '0 = unlimited (quota alerts off). 1 GB = 1,000,000,000 bytes.' },
      { path: 'plan.cycle_start_day', label: 'Billing cycle starts on day', kind: 'int', min: 1, max: 28 },
    ],
  },
  {
    id: 'telemetry',
    title: 'Telemetry',
    description: 'How often the phone reports (plan 8.2). Shorter intervals use more mobile data.',
    fields: [
      { path: 'telemetry.heartbeat_s', label: 'Heartbeat', kind: 'int', unit: 's', min: 5, max: 600, help: 'Also keeps a free hosting instance awake.' },
      { path: 'telemetry.fast_active_s', label: 'Signal and speed while a Dashboard is open', kind: 'int', unit: 's', min: 1, max: 600 },
      { path: 'telemetry.fast_idle_s', label: 'Signal and speed otherwise', kind: 'int', unit: 's', min: 1, max: 3600 },
      { path: 'telemetry.mirror_s', label: 'Finder mirror', kind: 'int', unit: 's', min: 1, max: 60 },
      { path: 'telemetry.slow_s', label: 'Battery, heat, system and tethering', kind: 'int', unit: 's', min: 5, max: 3600 },
      { path: 'telemetry.internet_check_s', label: 'Internet check interval', kind: 'int', unit: 's', min: 5, max: 3600 },
      { path: 'telemetry.probe_url', label: 'Online check URL (HTTP 204)', kind: 'url' },
      { path: 'telemetry.ping_host', label: 'Ping host', kind: 'host' },
      { path: 'telemetry.dns_host', label: 'DNS check host', kind: 'host' },
      { path: 'telemetry.public_ip_url', label: 'Public IP URL', kind: 'url' },
      { path: 'telemetry.public_ip_every_s', label: 'Check public IP every', kind: 'int', unit: 's', min: 60, max: 86_400 },
    ],
  },
  {
    id: 'finder',
    title: 'Signal Finder',
    description: 'Score weights and survey tests (plan 7.2, 7.3). The weights should add up to 1.',
    fields: [
      { path: 'finder.w_sinr', label: 'SINR weight', kind: 'number', min: 0, max: 1, step: 0.05 },
      { path: 'finder.w_rsrp', label: 'RSRP weight', kind: 'number', min: 0, max: 1, step: 0.05 },
      { path: 'finder.w_rsrq', label: 'RSRQ weight', kind: 'number', min: 0, max: 1, step: 0.05 },
      { path: 'finder.smooth_s', label: 'Smooth the score over', kind: 'int', unit: 's', min: 1, max: 60 },
      { path: 'finder.spot_test_s', label: 'Spot test duration', kind: 'int', unit: 's', min: 5, max: 600 },
      { path: 'finder.speed_runs', label: 'Speed test runs', kind: 'int', min: 1, max: 10 },
      { path: 'finder.speed_bytes', label: 'Download size per run', kind: 'int', unit: 'bytes', min: 100_000, max: 1_000_000_000, step: 1_000_000 },
      { path: 'finder.speed_down_url', label: 'Download URL', kind: 'url', help: '{bytes} is replaced with the size above.' },
      { path: 'finder.speed_up_url', label: 'Upload URL', kind: 'url' },
      { path: 'finder.speak_every_s', label: 'Speak the score every', kind: 'int', unit: 's', min: 1, max: 60 },
    ],
  },
];

export const ALL_FIELDS: FieldDef[] = SETTINGS_SECTIONS.flatMap((s) => s.fields);
export const FIELD_BY_PATH: Record<string, FieldDef> = Object.fromEntries(ALL_FIELDS.map((f) => [f.path, f]));

// --- path helpers -----------------------------------------------------------------------------

export function getPath(obj: unknown, path: string): unknown {
  let cur: unknown = obj;
  for (const k of path.split('.')) {
    if (cur == null || typeof cur !== 'object') return undefined;
    cur = (cur as Record<string, unknown>)[k];
  }
  return cur;
}

/** Immutable set: returns a copy of `obj` with `path` replaced. */
export function setPath<T>(obj: T, path: string, value: unknown): T {
  const [head, ...rest] = path.split('.');
  const base = (obj && typeof obj === 'object' ? obj : {}) as Record<string, unknown>;
  if (!head) return obj;
  return {
    ...base,
    [head]: rest.length ? setPath(base[head], rest.join('.'), value) : value,
  } as T;
}

export function defaultFor(path: string): unknown {
  return getPath(DEFAULT_SETTINGS, path);
}

export function sameValue(a: unknown, b: unknown): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}

// --- validation -------------------------------------------------------------------------------

export function validateField(def: FieldDef, value: unknown): string | null {
  switch (def.kind) {
    case 'bool':
      return typeof value === 'boolean' ? null : 'Choose on or off.';
    case 'int':
    case 'number': {
      if (typeof value !== 'number' || Number.isNaN(value)) return 'Enter a number.';
      if (def.kind === 'int' && !Number.isInteger(value)) return 'Enter a whole number.';
      if (def.min !== undefined && value < def.min) return `Must be at least ${def.min}${def.unit ? ` ${def.unit}` : ''}.`;
      if (def.max !== undefined && value > def.max) return `Must be at most ${def.max}${def.unit ? ` ${def.unit}` : ''}.`;
      return null;
    }
    case 'host':
      if (typeof value !== 'string' || !value.trim()) return 'Required.';
      return value.length <= 253 && /^[A-Za-z0-9.:_-]+$/.test(value) ? null : 'Use a host name or IP address.';
    case 'url': {
      if (typeof value !== 'string' || !value.trim()) return 'Required.';
      try {
        const u = new URL(value.replace(/\{[a-z_]+\}/g, '1'));
        return u.protocol === 'https:' || u.protocol === 'http:' ? null : 'Use an http(s) URL.';
      } catch {
        return 'Enter a valid URL.';
      }
    }
    case 'select':
      return def.options?.some((o) => o.value === value) ? null : 'Choose an option.';
    case 'time':
      return typeof value === 'string' && /^([01]\d|2[0-3]):[0-5]\d$/.test(value) ? null : 'Use HH:MM (24 h).';
    case 'weekday':
      return typeof value === 'number' && Number.isInteger(value) && value >= 0 && value <= 6 ? null : 'Choose a day.';
    case 'int_list': {
      if (!Array.isArray(value) || value.length === 0) return 'Enter at least one value.';
      if (value.length > 10) return 'At most 10 values.';
      for (const v of value) {
        if (typeof v !== 'number' || !Number.isInteger(v)) return 'Use whole numbers separated by commas.';
        if (def.min !== undefined && v < def.min) return `Each value must be at least ${def.min}.`;
        if (def.max !== undefined && v > def.max) return `Each value must be at most ${def.max}.`;
      }
      for (let i = 1; i < value.length; i++) if ((value[i] as number) <= (value[i - 1] as number)) return 'Values must be ascending and unique.';
      return null;
    }
  }
}

export interface ValidationResult {
  errors: Record<string, string>;
  warnings: Record<string, string>;
}

/** Field rules plus the cross-field rules that keep the watchdog ladder and heat rules coherent. */
export function validateSettings(s: Settings): ValidationResult {
  const errors: Record<string, string> = {};
  const warnings: Record<string, string> = {};
  for (const def of ALL_FIELDS) {
    const err = validateField(def, getPath(s, def.path));
    if (err) errors[def.path] = err;
  }
  const w = s.watchdog;
  const rule = (ok: boolean, path: string, msg: string) => {
    if (!ok && !errors[path]) errors[path] = msg;
  };
  rule(w.airplane_after_s > w.internet_fail_s, 'watchdog.airplane_after_s', 'Must be later than the mobile data cycle.');
  rule(w.reboot_after_s > w.airplane_after_s, 'watchdog.reboot_after_s', 'Must be later than the airplane mode cycle.');
  rule(w.temp_warn_c <= w.temp_stop_charge_c, 'watchdog.temp_warn_c', 'Must not be above the stop-charging temperature.');
  rule(w.temp_resume_c <= w.temp_stop_charge_c, 'watchdog.temp_resume_c', 'Must not be above the stop-charging temperature.');
  rule(w.temp_radio_rest_c > w.temp_stop_charge_c, 'watchdog.temp_radio_rest_c', 'Must be above the stop-charging temperature.');
  rule(w.charge_resume_drop_pct < w.charge_limit_pct, 'watchdog.charge_resume_drop_pct', 'Must be smaller than the charge limit.');
  rule(s.telemetry.fast_active_s <= s.telemetry.fast_idle_s, 'telemetry.fast_active_s', 'Must not be longer than the idle interval.');
  rule(s.alerts.offline_after_s > s.telemetry.heartbeat_s, 'alerts.offline_after_s', 'Must be longer than the heartbeat interval.');
  if (s.profile_override && JSON.stringify(s.profile_override).length > 64 * 1024) errors.profile_override = 'Larger than 64 KiB.';
  const sum = s.finder.w_sinr + s.finder.w_rsrp + s.finder.w_rsrq;
  if (!(sum > 0)) rule(false, 'finder.w_sinr', 'At least one weight must be above 0.');
  else if (Math.abs(sum - 1) > 0.001) warnings['finder.w_sinr'] = `Weights add up to ${sum.toFixed(2)}; scores will not be on the 0 to 100 scale.`;
  if (s.commands.revert_after_s < 60) warnings['commands.revert_after_s'] = 'Short timers can undo a change before a slow link confirms it.';
  if (s.watchdog.charge_limit_pct > 80) warnings['watchdog.charge_limit_pct'] = 'Holding a battery above 80 % in a hot box shortens its life and raises the swelling risk.';
  return { errors, warnings };
}

// --- patches ----------------------------------------------------------------------------------

const isPlainObject = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);

/**
 * Top-level settings the Server replaces as a whole on PUT instead of deep-merging (shared
 * mergeSettings). A patch must carry their complete new value, which also lets one PUT remove keys.
 */
export const REPLACED_WHOLE: ReadonlySet<string> = new Set(['profile_override']);

function diffObjects(base: Record<string, unknown>, next: Record<string, unknown>, top: boolean): Record<string, unknown> | null {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(next)) {
    const b = base[k];
    const whole = top && REPLACED_WHOLE.has(k);
    if (!whole && isPlainObject(b) && isPlainObject(v)) {
      const sub = diffObjects(b, v, false);
      if (sub) out[k] = sub;
    } else if (!sameValue(b, v)) {
      out[k] = whole ? (v ?? null) : v;
    }
  }
  return Object.keys(out).length ? out : null;
}

/**
 * Minimal patch turning `base` into `next` for PUT /settings (deep-merged by the Server).
 * Arrays and scalars are replaced whole, and so is every REPLACED_WHOLE key (sent complete, or
 * null to clear it). Returns null when nothing changed.
 */
export function diffSettings(base: unknown, next: unknown): Record<string, unknown> | null {
  if (!isPlainObject(base) || !isPlainObject(next)) return sameValue(base, next) ? null : (next as Record<string, unknown>);
  return diffObjects(base, next, true);
}

function countLeaves(v: unknown): number {
  if (!isPlainObject(v)) return 1;
  let n = 0;
  for (const x of Object.values(v)) n += countLeaves(x);
  return Math.max(1, n);
}

/** Number of leaf values that differ; a REPLACED_WHOLE value counts as one change. */
export function countChanges(patch: Record<string, unknown> | null): number {
  if (!patch) return 0;
  let n = 0;
  for (const [k, v] of Object.entries(patch)) n += REPLACED_WHOLE.has(k) ? 1 : countLeaves(v);
  return n;
}

/** Parses "80, 90, 100" into numbers; returns the raw string's numbers or NaN entries for bad parts. */
export function parseIntList(text: string): number[] {
  return text
    .split(/[,\s]+/)
    .map((p) => p.trim())
    .filter(Boolean)
    .map((p) => (/^-?\d+$/.test(p) ? Number(p) : Number.NaN));
}
