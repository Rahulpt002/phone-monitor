import defaultsFixture from '@hsm/shared/fixtures/settings-default.json';
import { DEFAULT_SETTINGS, mergeSettings, type Settings } from '@hsm/shared';
import { describe, expect, it } from 'vitest';
import {
  ALL_FIELDS,
  FIELD_BY_PATH,
  countChanges,
  defaultFor,
  diffSettings,
  getPath,
  parseIntList,
  setPath,
  validateField,
  validateSettings,
} from '../src/lib/settingsSchema';

const clone = (): Settings => JSON.parse(JSON.stringify(DEFAULT_SETTINGS)) as Settings;
const field = (p: string) => FIELD_BY_PATH[p]!;

describe('schema coverage', () => {
  it('has an editor field for every leaf setting except the profile override', () => {
    const leaves: string[] = [];
    const walk = (o: unknown, prefix: string) => {
      if (o && typeof o === 'object' && !Array.isArray(o)) {
        for (const [k, v] of Object.entries(o)) walk(v, prefix ? `${prefix}.${k}` : k);
      } else leaves.push(prefix);
    };
    walk(DEFAULT_SETTINGS, '');
    const covered = new Set(ALL_FIELDS.map((f) => f.path));
    expect(leaves.filter((p) => p !== 'profile_override' && !covered.has(p))).toEqual([]);
  });
  it('agrees with the shared defaults fixture', () => {
    expect(defaultsFixture).toEqual(DEFAULT_SETTINGS);
  });
});

describe('validateField', () => {
  it('enforces min, max and whole numbers', () => {
    const f = field('watchdog.charge_limit_pct');
    expect(validateField(f, 60)).toBeNull();
    expect(validateField(f, 19)).toBe('Must be at least 20 %.');
    expect(validateField(field('watchdog.temp_warn_c'), 30)).toBeNull(); // lowered Phase 4 test thresholds are allowed
    expect(validateField(f, 101)).toBe('Must be at most 100 %.');
    expect(validateField(f, 60.5)).toBe('Enter a whole number.');
    expect(validateField(f, Number.NaN)).toBe('Enter a number.');
  });
  it('accepts decimals for number fields', () => {
    expect(validateField(field('finder.w_sinr'), 0.45)).toBeNull();
    expect(validateField(field('finder.w_sinr'), 1.5)).toMatch(/at most 1/);
  });
  it('validates URLs, including {bytes} placeholders', () => {
    expect(validateField(field('finder.speed_down_url'), 'https://speed.cloudflare.com/__down?bytes={bytes}')).toBeNull();
    expect(validateField(field('telemetry.probe_url'), 'ftp://x')).toBe('Use an http(s) URL.');
    expect(validateField(field('telemetry.probe_url'), 'not a url')).toBe('Enter a valid URL.');
    expect(validateField(field('telemetry.probe_url'), '')).toBe('Required.');
  });
  it('validates time, weekday, select and lists', () => {
    expect(validateField(field('watchdog.weekly_reboot.time'), '04:00')).toBeNull();
    expect(validateField(field('watchdog.weekly_reboot.time'), '24:00')).toBe('Use HH:MM (24 h).');
    expect(validateField(field('watchdog.weekly_reboot.day'), 6)).toBeNull();
    expect(validateField(field('watchdog.weekly_reboot.day'), 7)).toBe('Choose a day.');
    expect(validateField(field('watchdog.usb_function'), 'ncm')).toBeNull();
    expect(validateField(field('watchdog.usb_function'), 'mtp')).toBe('Choose an option.');
    expect(validateField(field('alerts.quota_steps_pct'), [80, 90, 100])).toBeNull();
    expect(validateField(field('alerts.quota_steps_pct'), [90, 80])).toBe('Values must be ascending and unique.');
    expect(validateField(field('alerts.quota_steps_pct'), [80, 1200])).toBe('Each value must be at most 1000.');
    expect(validateField(field('telemetry.ping_host'), '1.1.1.1')).toBeNull();
    expect(validateField(field('telemetry.ping_host'), 'bad host')).toBe('Use a host name or IP address.');
    expect(validateField(field('alerts.quota_steps_pct'), [])).toBe('Enter at least one value.');
    expect(validateField(field('alerts.quota_steps_pct'), parseIntList('80, x'))).toBe('Use whole numbers separated by commas.');
  });
});

describe('validateSettings', () => {
  it('accepts the defaults without errors or warnings', () => {
    expect(validateSettings(DEFAULT_SETTINGS)).toEqual({ errors: {}, warnings: {} });
  });
  it('keeps the watchdog ladder in order', () => {
    const s = clone();
    s.watchdog.airplane_after_s = 60;
    expect(validateSettings(s).errors['watchdog.airplane_after_s']).toMatch(/later than the mobile data cycle/);
  });
  it('keeps the heat rules coherent', () => {
    const s = clone();
    s.watchdog.temp_resume_c = 46;
    expect(validateSettings(s).errors['watchdog.temp_resume_c']).toMatch(/not be above the stop-charging/);
    s.watchdog.temp_resume_c = 45;
    expect(validateSettings(s).errors['watchdog.temp_resume_c']).toBeUndefined();
  });
  it('warns when the finder weights do not add up to 1', () => {
    const s = clone();
    s.finder.w_rsrq = 0.5;
    const r = validateSettings(s);
    expect(r.errors).toEqual({});
    expect(r.warnings['finder.w_sinr']).toMatch(/add up to 1.30/);
  });
  it('reports a field error before a cross-field one', () => {
    const s = clone();
    s.watchdog.reboot_after_s = 20;
    expect(validateSettings(s).errors['watchdog.reboot_after_s']).toBe('Must be at least 30 s.');
  });
  it('mirrors the Server rule that offline detection outlasts the heartbeat', () => {
    const s = clone();
    s.alerts.offline_after_s = 30;
    expect(validateSettings(s).errors['alerts.offline_after_s']).toMatch(/longer than the heartbeat/);
  });
});

describe('patches for PUT /settings', () => {
  it('returns null when nothing changed', () => {
    expect(diffSettings(DEFAULT_SETTINGS, clone())).toBeNull();
  });
  it('sends only changed leaves, nested', () => {
    const s = setPath(setPath(clone(), 'watchdog.weekly_reboot.enabled', true), 'plan.quota_gb', 50);
    const patch = diffSettings(DEFAULT_SETTINGS, s);
    expect(patch).toEqual({ watchdog: { weekly_reboot: { enabled: true } }, plan: { quota_gb: 50 } });
    expect(countChanges(patch)).toBe(2);
    expect(mergeSettings(DEFAULT_SETTINGS, patch)).toEqual(s);
  });
  it('replaces arrays whole', () => {
    const s = setPath(clone(), 'alerts.quota_steps_pct', [50, 100]);
    expect(diffSettings(DEFAULT_SETTINGS, s)).toEqual({ alerts: { quota_steps_pct: [50, 100] } });
  });
  it('setPath does not mutate and defaultFor reads the shipped defaults', () => {
    const s = clone();
    const next = setPath(s, 'alerts.enabled.hot', false);
    expect(getPath(s, 'alerts.enabled.hot')).toBe(true);
    expect(getPath(next, 'alerts.enabled.hot')).toBe(false);
    expect(defaultFor('commands.revert_after_s')).toBe(120);
  });
  it('sends the profile override whole, so one PUT can remove keys from it', () => {
    const base = setPath(clone(), 'profile_override', { usb_function: 'ncm', thermal: { cpu: ['cpu'], battery: ['battery'] } });
    const removed = setPath(base, 'profile_override', { thermal: { cpu: ['cpu'] } });
    const patch = diffSettings(base, removed);
    expect(patch).toEqual({ profile_override: { thermal: { cpu: ['cpu'] } } });
    expect(mergeSettings(base, patch)).toEqual(removed);

    // Changing one nested key still sends the complete override, so the other keys survive.
    const edited = setPath(base, 'profile_override.thermal.cpu', ['cpu', 'cpu-1']);
    const editPatch = diffSettings(base, edited);
    expect(editPatch).toEqual({ profile_override: { usb_function: 'ncm', thermal: { cpu: ['cpu', 'cpu-1'], battery: ['battery'] } } });
    expect(mergeSettings(base, editPatch)).toEqual(edited);
  });
  it('clears the override with null and leaves it out when unchanged', () => {
    const base = setPath(clone(), 'profile_override', { usb_function: 'ncm' });
    expect(diffSettings(base, setPath(base, 'profile_override', null))).toEqual({ profile_override: null });
    expect(mergeSettings(base, { profile_override: null }).profile_override).toBeNull();
    const other = setPath(base, 'plan.quota_gb', 10);
    expect(diffSettings(base, other)).toEqual({ plan: { quota_gb: 10 } });
  });
  it('counts a changed override as one change', () => {
    const base = setPath(clone(), 'profile_override', { a: 1, b: { c: 2, d: 3 } });
    const next = setPath(setPath(base, 'profile_override.b.c', 5), 'plan.quota_gb', 10);
    expect(countChanges(diffSettings(base, next))).toBe(2);
  });
});
