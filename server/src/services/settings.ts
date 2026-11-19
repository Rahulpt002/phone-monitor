import {
  ALERT_KINDS,
  DEFAULT_SETTINGS,
  mergeSettings,
  type AgentEvent,
  type AlertKind,
  type Settings,
  type SettingsResponse,
} from '@hsm/shared';
import { z } from 'zod';
import type { AuthInfo, UserRow } from '../auth/service.js';
import type { AppContext } from '../context.js';
import type { Stmt } from '../db/client.js';
import { badRequest, conflict, formatZodError } from '../errors.js';
import { iso, isPlainObject, randomId } from '../util.js';
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
 *
 * Durations (*_s, *_min, *_h), percentages (*_pct), distances (*_m), counts and days are integers
 * (the Agent reads most of them into Kotlin Int/Long fields). Score weights, temperatures (*_c),
 * tilt degrees and quota_gb may be fractional.
 *
 * profile_override is replaced as a whole by a PUT (mergeSettings), so one request can remove
 * keys from it; here it only has to be an object (or null) no larger than 64 KiB.
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
      move_distance_m: int(1, 100_000),
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
  // move_distance_m used to accept fractions; round an old stored value instead of failing the
  // whole object (which would reset every setting to the defaults).
  const dist: unknown = merged.watchdog?.move_distance_m;
  if (typeof dist === 'number' && Number.isFinite(dist) && !Number.isInteger(dist)) {
    merged.watchdog = { ...merged.watchdog, move_distance_m: Math.round(dist) };
  }
  const v = validateSettings(merged);
  return v.ok ? v.settings : structuredClone(DEFAULT_SETTINGS);
}

/** Order-independent comparison of JSON values. */
export function jsonEqual(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  if (Array.isArray(a) || Array.isArray(b)) {
    return Array.isArray(a) && Array.isArray(b) && a.length === b.length && a.every((x, i) => jsonEqual(x, b[i]));
  }
  if (!isPlainObject(a) || !isPlainObject(b)) return false;
  const ka = Object.keys(a).filter((k) => a[k] !== undefined);
  const kb = Object.keys(b).filter((k) => b[k] !== undefined);
  return ka.length === kb.length && ka.every((k) => k in b && jsonEqual(a[k], b[k]));
}

/**
 * Dotted paths whose values differ. Arrays and profile_override (replaced as a whole) count as
 * one value each.
 */
export function changedPaths(before: unknown, after: unknown, prefix = ''): string[] {
  if (prefix !== 'profile_override' && isPlainObject(before) && isPlainObject(after)) {
    const keys = [...new Set([...Object.keys(before), ...Object.keys(after)])].sort();
    return keys.flatMap((k) => changedPaths(before[k], after[k], prefix ? `${prefix}.${k}` : k));
  }
  return jsonEqual(before, after) ? [] : [prefix];
}

/** Who changes the settings through PUT /api/settings (audit and re-authentication). */
export interface SettingsActor {
  user: UserRow;
  /** The request's session, so failed password re-entries count per session (F39). */
  auth?: AuthInfo;
  /** The admin's own password; needed when profile_override changes (it holds root shell templates). */
  password?: unknown;
  ip: string;
}

/**
 * Per-device settings (docs/protocol.md 3.4). The Server owns them: PUT deep-merges, validates,
 * increments the version, persists, pushes to the Agent and broadcasts to Dashboards.
 */
export class SettingsService {
  /** Updates of one device run one after another, each merging over the last committed copy. */
  private chains = new Map<string, Promise<unknown>>();

  constructor(private ctx: AppContext) {}

  get(dev: DeviceRuntime): SettingsResponse {
    return { device_id: dev.id, settings: dev.settings, version: dev.settingsVersion };
  }

  private serialize<T>(deviceId: string, fn: () => Promise<T>): Promise<T> {
    const prev = this.chains.get(deviceId) ?? Promise.resolve();
    const run = prev.then(fn, fn);
    const tail = run.then(
      () => undefined,
      () => undefined,
    );
    this.chains.set(deviceId, tail);
    void tail.then(() => {
      if (this.chains.get(deviceId) === tail) this.chains.delete(deviceId);
    });
    return run;
  }

  /**
   * Deep-merges `patch`, validates, and writes the new settings and version before memory, the
   * Agent and Dashboards see them (F47). With an `actor` (PUT /api/settings), a change to
   * profile_override needs the actor's password, and the change is recorded as a
   * `settings_changed` event (F01).
   */
  async update(dev: DeviceRuntime, patch: unknown, actor?: SettingsActor): Promise<SettingsResponse> {
    if (!isPlainObject(patch)) throw badRequest('settings must be an object');
    return this.serialize(dev.id, async () => {
      const before = dev.settings;
      const v = validateSettings(mergeSettings(before, patch));
      if (!v.ok) throw badRequest(`Invalid settings: ${v.message}`);
      const changed = changedPaths(before, v.settings);
      const overrideChanged = changed.includes('profile_override');
      if (actor && overrideChanged) await this.ctx.auth.reauth(actor.auth ?? actor.user, actor.password, actor.ip);

      const oldVersion = dev.settingsVersion;
      const version = oldVersion + 1;
      const stmts: Stmt[] = [
        {
          sql: 'UPDATE devices SET settings = ?, settings_version = ? WHERE id = ? AND settings_version = ?',
          args: [JSON.stringify(v.settings), version, dev.id, oldVersion],
        },
      ];
      let event: AgentEvent | null = null;
      if (actor) {
        const now = this.ctx.clock.now();
        const list = changed.length ? changed.join(', ') : 'no values changed';
        event = {
          id: randomId('srv_', 8),
          ts: iso(now),
          kind: 'settings_changed',
          severity: overrideChanged ? 'warning' : 'info',
          message: `Settings changed by ${actor.user.username} (version ${version}): ${list}`.slice(0, 2000),
          data: { user: actor.user.username, version, changed, profile_override_changed: overrideChanged },
        };
        // Written only together with the settings it describes.
        stmts.push({
          sql: `INSERT INTO events (device_id, id, ts, kind, severity, message, data, received_at)
                SELECT ?, ?, ?, ?, ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM devices WHERE id = ? AND settings_version = ?)`,
          args: [dev.id, event.id, now, event.kind, event.severity, event.message, JSON.stringify(event.data), now, dev.id, version],
        });
      }
      const results = await this.ctx.db.batch(stmts);
      if ((results[0]?.rowsAffected ?? 0) === 0) {
        throw conflict('The settings were changed or the phone was removed meanwhile; reload and try again');
      }
      dev.settings = v.settings;
      dev.settingsVersion = version;
      this.push(dev);
      if (event) this.ctx.records.broadcastEvent(dev.id, event);
      return this.get(dev);
    });
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
