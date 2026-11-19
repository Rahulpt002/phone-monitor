import { DEFAULT_SETTINGS } from '@hsm/shared';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { DeviceRuntime } from '../src/services/devices.js';
import { validateSettings } from '../src/services/settings.js';
import type { HsmServer } from '../src/server.js';
import {
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

  it('stores and clears a device profile override', async () => {
    const r = await api(server, 'PUT', '/api/settings', {
      token,
      body: { settings: { profile_override: { charge_control: [{ path: '/sys/x', on: '1', off: '0' }] } } },
    });
    expect(r.body.settings.profile_override).toEqual({ charge_control: [{ path: '/sys/x', on: '1', off: '0' }] });
    const c = await api(server, 'PUT', '/api/settings', { token, body: { settings: { profile_override: null } } });
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
