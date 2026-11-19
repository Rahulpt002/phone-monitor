import {
  ALERT_KINDS,
  DEFAULT_SETTINGS,
  mergeSettings,
  type AlertKind,
  type Settings,
  type SettingsResponse,
} from '@hsm/shared';
import { z } from 'zod';
import type { AppContext } from '../context.js';
import { badRequest, formatZodError } from '../errors.js';
import { isPlainObject } from '../util.js';
import type { DeviceRuntime } from './devices.js';

const int = (min: number, max: number) => z.number().int().min(min).max(max);
const num = (min: number, max: number) => z.number().min(min).max(max);
const httpUrl = z
  .string()
  .max(2000)
  .refine((u) => /^https?:\/\/\S+$/i.test(u), { message: 'must be an http(s) URL' });
const host = z.string().min(1).max(253).regex(/^[A-Za-z0-9.:_-]+$/, 'must be a host name or IP address');

const alertEnabledShape = Object.fromEntries(ALERT_KINDS.map((k) => [k, z.boolean()])) as Record<AlertKind, z.ZodBoolean>;

/**
 * Full validation of the Settings shape (shared/src/settings.ts). Bounds keep a typo from making
 * the phone misbehave (for example a 0 s heartbeat) while still allowing low test thresholds
 * (plan Phase 4: "lowered test thresholds trigger the heat and power rules").
 */
export const SettingsSchema = z
  .strictObject({
    telemetry: z.strictObject({
      heartbeat_s: int(5, 600),
      fast_active_s: int(1, 600),
      fast_idle_s: int(1, 3600),
      mirror_s: int(1, 60),
      slow_s: int(5, 3600),
      internet_check_s: int(5, 3600),
      probe_url: httpUrl,
      ping_host: host,
      dns_host: host,
      public_ip_url: httpUrl,
      public_ip_every_s: int(60, 86_400),
    }),
    watchdog: z.strictObject({
      enabled: z.boolean(),
      internet_fail_s: int(10, 86_400),
      data_off_s: int(1, 600),
      airplane_after_s: int(10, 86_400),
      airplane_off_s: int(1, 600),
      reboot_after_s: int(30, 7 * 86_400),
      reboot_retry_s: int(60, 7 * 86_400),
      tether_fix: z.boolean(),
      tether_fix_after_s: int(1, 3600),
      usb_function: z.enum(['rndis', 'ncm']),
      charge_limit_pct: int(20, 100),
      charge_resume_drop_pct: int(1, 50),
      temp_warn_c: num(0, 90),
      temp_stop_charge_c: num(0, 90),
      temp_resume_c: num(0, 90),
      temp_radio_rest_c: num(0, 100),
      radio_rest_s: int(10, 86_400),
      weak_power_min: int(1, 1440),
      move_tilt_deg: num(1, 180),
      move_distance_m: num(1, 100_000),
      max_reboots: int(0, 50),
      reboot_window_h: int(1, 168),
      weekly_reboot: z.strictObject({
        enabled: z.boolean(),
        day: int(0, 6),
        time: z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/, 'must be HH:MM'),
      }),
    }),
    commands: z.strictObject({
      revert_after_s: int(30, 3600),
      expiry_s: int(10, 3600),
      allow_power_off: z.boolean(),
    }),
    alerts: z.strictObject({
      offline_after_s: int(30, 86_400),
      low_battery_pct: int(0, 100),
      signal_drop_pct: int(1, 100),
      signal_drop_min: int(1, 1440),
      cell_change_min: int(1, 1440),
      tether_down_min: int(1, 1440),
      quota_steps_pct: z.array(int(1, 1000)).max(10),
      cooldown_min: int(0, 10_080),
      enabled: z.strictObject(alertEnabledShape),
      channels: z.strictObject({ telegram: z.boolean(), push: z.boolean() }),
    }),
    plan: z.strictObject({
      quota_gb: num(0, 1_000_000),
      cycle_start_day: int(1, 28),
    }),
    finder: z.strictObject({
      w_sinr: num(0, 1),
      w_rsrp: num(0, 1),
      w_rsrq: num(0, 1),
      smooth_s: int(1, 60),
      spot_test_s: int(5, 600),
      speed_runs: int(1, 10),
      speed_down_url: httpUrl,
      speed_up_url: httpUrl,
      speed_bytes: int(100_000, 1_000_000_000),
      speak_every_s: int(1, 60),
    }),
    profile_override: z.record(z.string(), z.unknown()).nullable(),
  })
  .superRefine((s, ctx) => {
    if (s.alerts.offline_after_s <= s.telemetry.heartbeat_s) {
      ctx.addIssue({
        code: 'custom',
        path: ['alerts', 'offline_after_s'],
        message: 'must be longer than telemetry.heartbeat_s',
      });
    }
    if (s.watchdog.temp_resume_c > s.watchdog.temp_stop_charge_c) {
      ctx.addIssue({
        code: 'custom',
        path: ['watchdog', 'temp_resume_c'],
        message: 'must not be above temp_stop_charge_c',
      });
    }
    if (s.profile_override && JSON.stringify(s.profile_override).length > 64 * 1024) {
      ctx.addIssue({ code: 'custom', path: ['profile_override'], message: 'is larger than 64 KiB' });
    }
  });

/** Validates a complete Settings object; returns the problems as one message, or null. */
export function validateSettings(s: unknown): { ok: true; settings: Settings } | { ok: false; message: string } {
  const r = SettingsSchema.safeParse(s);
  if (!r.success) return { ok: false, message: formatZodError(r.error) };
  return { ok: true, settings: r.data as Settings };
}

/**
 * Loads stored settings defensively: merged over the defaults so a settings object written by an
 * older version (missing newer keys) still validates.
 */
export function normalizeStoredSettings(raw: unknown): Settings {
  const merged = mergeSettings(DEFAULT_SETTINGS, isPlainObject(raw) ? raw : {});
  const v = validateSettings(merged);
  return v.ok ? v.settings : structuredClone(DEFAULT_SETTINGS);
}

/**
 * Per-device settings (docs/protocol.md 3.4). The Server owns them: PUT deep-merges, validates,
 * increments the version, persists, pushes to the Agent and broadcasts to Dashboards.
 */
export class SettingsService {
  constructor(private ctx: AppContext) {}

  get(dev: DeviceRuntime): SettingsResponse {
    return { device_id: dev.id, settings: dev.settings, version: dev.settingsVersion };
  }

  async update(dev: DeviceRuntime, patch: unknown): Promise<SettingsResponse> {
    if (!isPlainObject(patch)) throw badRequest('settings must be an object');
    const merged = mergeSettings(dev.settings, patch);
    const v = validateSettings(merged);
    if (!v.ok) throw badRequest(`Invalid settings: ${v.message}`);
    // Update memory first so concurrent requests merge over the newest copy.
    dev.settings = v.settings;
    dev.settingsVersion += 1;
    const version = dev.settingsVersion;
    await this.ctx.db.run('UPDATE devices SET settings = ?, settings_version = ? WHERE id = ?', [
      JSON.stringify(v.settings),
      version,
      dev.id,
    ]);
    this.push(dev);
    return this.get(dev);
  }

  /** Sends the current settings to the Agent (if connected) and every watching Dashboard. */
  push(dev: DeviceRuntime): void {
    this.ctx.hub.sendToAgent(dev.id, { t: 'settings', settings: dev.settings, version: dev.settingsVersion });
    this.ctx.hub.broadcastDevice(dev.id, {
      t: 'settings',
      device_id: dev.id,
      settings: dev.settings,
      version: dev.settingsVersion,
    });
    // fast_active_s / fast_idle_s may have changed.
    this.ctx.hub.refreshRates(dev.id);
  }
}
