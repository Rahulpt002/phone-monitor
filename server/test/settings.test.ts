import { DEFAULT_SETTINGS } from '@hsm/shared';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { DeviceRuntime } from '../src/services/devices.js';
import { normalizeStoredSettings, validateSettings } from '../src/services/settings.js';
import type { HsmServer } from '../src/server.js';
import {
  ADMIN,
  adminWithTotp,
  api,
  connectFakeAgent,
  connectFakeDashboard,
  type FakeClock,
  fixture,
  makeServer,
  msgsOfType,
  pairDevice,
} from './helpers.js';

describe('settings validation', () => {
  it('accepts the defaults and the shared fixture', () => {
    expect(validateSettings(DEFAULT_SETTINGS).ok).toBe(true);
    expect(validateSettings(fixture('settings-default.json')).ok).toBe(true);
  });
  it('rejects bad values, unknown keys and inconsistent thresholds', () => {
    const bad = structuredClone(DEFAULT_SETTINGS) as unknown as Record<string, any>;
    bad.watchdog.charge_limit_pct = 5;
    expect(validateSettings(bad)).toMatchObject({ ok: false, message: expect.stringContaining('watchdog.charge_limit_pct') });
    const unknown = { ...structuredClone(DEFAULT_SETTINGS), extra: 1 };
    expect(validateSettings(unknown).ok).toBe(false);
    const cross = structuredClone(DEFAULT_SETTINGS);
    cross.alerts.offline_after_s = 30;
    expect(validateSettings(cross)).toMatchObject({ ok: false, message: expect.stringContaining('heartbeat') });
  });

  // Every numeric setting is listed in exactly one of these, so a new field forces a decision.
  const INTEGER_PATHS = [
    'telemetry.heartbeat_s', 'telemetry.fast_active_s', 'telemetry.fast_idle_s', 'telemetry.mirror_s', 'telemetry.slow_s',
    'telemetry.internet_check_s', 'telemetry.public_ip_every_s',
    'watchdog.internet_fail_s', 'watchdog.data_off_s', 'watchdog.airplane_after_s', 'watchdog.airplane_off_s',
    'watchdog.reboot_after_s', 'watchdog.reboot_retry_s', 'watchdog.tether_fix_after_s', 'watchdog.charge_limit_pct',
    'watchdog.charge_resume_drop_pct', 'watchdog.radio_rest_s', 'watchdog.weak_power_min', 'watchdog.move_distance_m',
    'watchdog.max_reboots', 'watchdog.reboot_window_h', 'watchdog.weekly_reboot.day',
    'commands.revert_after_s', 'commands.expiry_s',
    'alerts.offline_after_s', 'alerts.low_battery_pct', 'alerts.signal_drop_pct', 'alerts.signal_drop_min',
    'alerts.cell_change_min', 'alerts.tether_down_min', 'alerts.quota_steps_pct.0', 'alerts.cooldown_min',
    'plan.cycle_start_day',
    'finder.smooth_s', 'finder.spot_test_s', 'finder.speed_runs', 'finder.speed_bytes', 'finder.speak_every_s',
  ];
  const FRACTIONAL_PATHS = [
    'watchdog.temp_warn_c', 'watchdog.temp_stop_charge_c', 'watchdog.temp_resume_c', 'watchdog.temp_radio_rest_c',
    'watchdog.move_tilt_deg', 'plan.quota_gb', 'finder.w_sinr', 'finder.w_rsrp', 'finder.w_rsrq',
  ];

  /** Paths of numeric values; an array counts once, as its element 0. */
  const numericLeaves = (v: unknown, prefix = ''): string[] =>
    typeof v === 'number'
      ? [prefix]
      : Array.isArray(v)
        ? numericLeaves(v[0], `${prefix}.0`)
        : v !== null && typeof v === 'object'
          ? Object.entries(v).flatMap(([k, x]) => numericLeaves(x, prefix ? `${prefix}.${k}` : k))
          : [];
  const withValue = (path: string, f: (old: number) => number) => {
    const s = structuredClone(DEFAULT_SETTINGS) as unknown as Record<string, any>;
    const keys = path.split('.');
    const last = keys.pop()!;
    const parent = keys.reduce((o, k) => o[k], s);
    parent[last] = f(parent[last] as number);
    return s;
  };

  it('classifies every numeric setting as integer or fractional', () => {
    expect([...INTEGER_PATHS, ...FRACTIONAL_PATHS].sort()).toEqual(numericLeaves(DEFAULT_SETTINGS).sort());
  });

  it.each(INTEGER_PATHS)('requires an integer for %s', (path) => {
    expect(validateSettings(withValue(path, (v) => v)).ok).toBe(true);
    const r = validateSettings(withValue(path, (v) => v + 0.5));
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.message).toContain(path.replace(/\.0$/, ''));
  });

  it.each(FRACTIONAL_PATHS)('accepts a fraction for %s', (path) => {
    expect(validateSettings(withValue(path, (v) => (v < 1 ? v / 2 : v + 0.25)))).toMatchObject({ ok: true });
  });

  it('rounds an old fractional move_distance_m instead of resetting stored settings', () => {
    const s = normalizeStoredSettings({ watchdog: { move_distance_m: 120.6 }, plan: { quota_gb: 5 } });
    expect(s.watchdog.move_distance_m).toBe(121);
    expect(s.plan.quota_gb).toBe(5);
    expect(DEFAULT_SETTINGS.watchdog.move_distance_m).toBe(100);
  });
});

describe('PUT /api/settings', () => {
  let server: HsmServer;
  let clock: FakeClock;
  let token: string;
  let dev: DeviceRuntime;

  beforeEach(async () => {
    ({ server, clock } = await makeServer());
    ({ token } = await adminWithTotp(server, clock));
    ({ dev } = await pairDevice(server));
  });
  afterEach(async () => server.close());

  it('deep-merges, bumps the version, pushes to the Agent and broadcasts', async () => {
    const agent = connectFakeAgent(server, dev);
    const dash = connectFakeDashboard(server, 'viewer', { deviceId: dev.id, finder: false });
    const before = await api(server, 'GET', '/api/settings', { token });
    expect(before.body).toMatchObject({ device_id: dev.id, version: 1 });

    const r = await api(server, 'PUT', '/api/settings', {
      token,
      body: { settings: { watchdog: { charge_limit_pct: 55 }, alerts: { quota_steps_pct: [50, 75] }, telemetry: { fast_idle_s: 120 } } },
    });
    expect(r.status).toBe(200);
    expect(r.body.version).toBe(2);
    expect(r.body.settings.watchdog.charge_limit_pct).toBe(55);
    expect(r.body.settings.watchdog.temp_warn_c).toBe(40);
    expect(r.body.settings.alerts.quota_steps_pct).toEqual([50, 75]);

    expect(msgsOfType(agent, 'settings')).toEqual([{ t: 'settings', settings: r.body.settings, version: 2 }]);
    expect(msgsOfType(dash.sock, 'settings')[0]).toMatchObject({ device_id: dev.id, version: 2 });
    // fast_idle_s changed and the dashboard watches: fast_active_s applies.
    expect(msgsOfType(agent, 'rates').at(-1)).toMatchObject({ fast_s: 5 });

    const stored = await server.ctx.db.get<{ settings: string; settings_version: number }>(
      'SELECT settings, settings_version FROM devices WHERE id = ?',
      [dev.id],
    );
    expect(Number(stored?.settings_version)).toBe(2);
    expect(JSON.parse(stored!.settings).watchdog.charge_limit_pct).toBe(55);
  });

  it('rejects invalid settings without changing the version', async () => {
    const r = await api(server, 'PUT', '/api/settings', { token, body: { settings: { commands: { expiry_s: -1 } } } });
    expect(r.status).toBe(400);
    expect(r.body.error).toBe('bad_request');
    const typo = await api(server, 'PUT', '/api/settings', { token, body: { settings: { watchdgo: {} } } });
    expect(typo.status).toBe(400);
    expect(dev.settingsVersion).toBe(1);
  });

  it('rejects fractional integer settings and accepts fractional temperatures', async () => {
    for (const settings of [
      { watchdog: { move_distance_m: 150.5 } },
      { telemetry: { heartbeat_s: 29.5 } },
      { alerts: { low_battery_pct: 19.9 } },
      { watchdog: { weak_power_min: 30.1 } },
      { watchdog: { reboot_window_h: 6.5 } },
      { alerts: { quota_steps_pct: [80, 90.5] } },
    ]) {
      const r = await api(server, 'PUT', '/api/settings', { token, body: { settings } });
      expect(r.status, JSON.stringify(settings)).toBe(400);
      expect(r.body.message).toMatch(/integer|int/i);
    }
    expect(dev.settingsVersion).toBe(1);
    const ok = await api(server, 'PUT', '/api/settings', {
      token,
      body: { settings: { watchdog: { move_distance_m: 150, temp_warn_c: 39.5 }, plan: { quota_gb: 1.5 } } },
    });
    expect(ok.status).toBe(200);
    expect(ok.body).toMatchObject({ version: 2, settings: { watchdog: { move_distance_m: 150, temp_warn_c: 39.5 }, plan: { quota_gb: 1.5 } } });
  });

  it('replaces profile_override in one PUT, so a key can be removed with one version bump', async () => {
    const agent = connectFakeAgent(server, dev);
    const full = {
      charge_control: [{ path: '/sys/x', on: '1', off: '0' }],
      thermal: { cpu: ['cpu-0'], modem: ['modem'] },
      commands: { hotspot: 'cmd wifi start-softap {ssid} wpa2 {password}' },
    };
    const first = await api(server, 'PUT', '/api/settings', { token, body: { settings: { profile_override: full }, password: ADMIN.password } });
    expect(first.body).toMatchObject({ version: 2, settings: { profile_override: full } });

    // Remove `commands` and the nested `thermal.modem` in a single request.
    const smaller = { charge_control: full.charge_control, thermal: { cpu: ['cpu-0'] } };
    const second = await api(server, 'PUT', '/api/settings', { token, body: { settings: { profile_override: smaller }, password: ADMIN.password } });
    expect(second.status).toBe(200);
    expect(second.body.version).toBe(3);
    expect(second.body.settings.profile_override).toEqual(smaller);

    const pushes = msgsOfType(agent, 'settings');
    expect(pushes.map((m) => m.version)).toEqual([2, 3]);
    expect((pushes[1]!.settings as { profile_override: unknown }).profile_override).toEqual(smaller);
    const stored = await server.ctx.db.get<{ settings: string; settings_version: number }>(
      'SELECT settings, settings_version FROM devices WHERE id = ?',
      [dev.id],
    );
    expect(Number(stored?.settings_version)).toBe(3);
    expect(JSON.parse(stored!.settings).profile_override).toEqual(smaller);

    // A PUT that does not mention profile_override keeps it.
    const other = await api(server, 'PUT', '/api/settings', { token, body: { settings: { plan: { quota_gb: 10 } } } });
    expect(other.body).toMatchObject({ version: 4, settings: { profile_override: smaller } });
  });

  it('stores and clears a device profile override', async () => {
    const r = await api(server, 'PUT', '/api/settings', {
      token,
      body: { settings: { profile_override: { charge_control: [{ path: '/sys/x', on: '1', off: '0' }] } }, password: ADMIN.password },
    });
    expect(r.body.settings.profile_override).toEqual({ charge_control: [{ path: '/sys/x', on: '1', off: '0' }] });
    const c = await api(server, 'PUT', '/api/settings', { token, body: { settings: { profile_override: null }, password: ADMIN.password } });
    expect(c.body.settings.profile_override).toBeNull();
    expect(c.body.version).toBe(3);
  });

  it('persists settings so a restart (spin-down) reloads them', async () => {
    await api(server, 'PUT', '/api/settings', { token, body: { settings: { plan: { quota_gb: 50 } } } });
    const reloaded = await server.ctx.devices.load().then(() => server.ctx.devices.get(dev.id));
    expect(reloaded?.settings.plan.quota_gb).toBe(50);
    expect(reloaded?.settingsVersion).toBe(2);
  });
});
