import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  COMMANDS, DEFAULT_SETTINGS, mergeSettings, rankSpots, signalScore, gradeRsrp, gradeSinr, gradeRsrq,
  type FinderSpot,
} from '../src/index.js';

const fx = (n: string) =>
  JSON.parse(readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', 'fixtures', n), 'utf8'));

describe('signalScore (plan 7.2)', () => {
  it('hits the anchor points', () => {
    expect(signalScore(-80, 25, -8)).toBe(100);
    expect(signalScore(-120, -5, -20)).toBe(0);
  });
  it('matches the fixture cases used by the Kotlin Agent', () => {
    for (const c of fx('score-cases.json')) expect(signalScore(c.rsrp, c.sinr, c.rsrq)).toBe(c.score);
  });
  it('returns null without RSRP', () => expect(signalScore(null, 10, -10)).toBeNull());
});

describe('grades (plan 7.1)', () => {
  it('uses the plan thresholds', () => {
    expect(gradeRsrp(-79)).toBe('excellent');
    expect(gradeRsrp(-85)).toBe('good');
    expect(gradeRsrp(-95)).toBe('fair');
    expect(gradeRsrp(-101)).toBe('poor');
    expect(gradeSinr(21)).toBe('excellent');
    expect(gradeSinr(-1)).toBe('poor');
    expect(gradeRsrq(-9)).toBe('excellent');
    expect(gradeRsrq(-21)).toBe('poor');
  });
});

describe('commands (plan 6.4)', () => {
  it('arms revert for link-cutting commands only', () => {
    expect(COMMANDS.mobile_data.revertFor({ enabled: false })).toBe('agent_link');
    expect(COMMANDS.mobile_data.revertFor({ enabled: true })).toBeNull();
    expect(COMMANDS.airplane_mode.revertFor({ enabled: true })).toBe('agent_link');
    expect(COMMANDS.usb_tethering.revertFor({ enabled: false })).toBe('dashboard');
    expect(COMMANDS.network_mode.revertFor({ mode: 'lte_only' })).toBe('agent_link');
  });
  it('requires confirmation and re-auth for reboot and shell', () => {
    expect(COMMANDS.reboot.confirm && COMMANDS.reboot.reauth).toBe(true);
    expect(COMMANDS.shell.confirm && COMMANDS.shell.reauth && COMMANDS.shell.admin_only).toBe(true);
  });
});

describe('settings', () => {
  it('fixture equals the defaults', () => expect(fx('settings-default.json')).toEqual(DEFAULT_SETTINGS));
  it('deep-merges partial patches', () => {
    const s = mergeSettings(DEFAULT_SETTINGS, { watchdog: { charge_limit_pct: 55 }, alerts: { quota_steps_pct: [50] } });
    expect(s.watchdog.charge_limit_pct).toBe(55);
    expect(s.watchdog.temp_warn_c).toBe(40);
    expect(s.alerts.quota_steps_pct).toEqual([50]);
    expect(DEFAULT_SETTINGS.watchdog.charge_limit_pct).toBe(60);
  });
  it('replaces profile_override as a whole', () => {
    const a = mergeSettings(DEFAULT_SETTINGS, { profile_override: { charge: { paths: ['x'] }, notes: 'n' } });
    const b = mergeSettings(a, { profile_override: { charge: { paths: ['y'] } } });
    expect(b.profile_override).toEqual({ charge: { paths: ['y'] } });
    expect(mergeSettings(b, { watchdog: { enabled: false } }).profile_override).toEqual({ charge: { paths: ['y'] } });
    expect(mergeSettings(b, { profile_override: null }).profile_override).toBeNull();
  });
});

describe('ranking (plan 7.3)', () => {
  const mk = (id: string, score: number, down: number | null): FinderSpot => ({
    id, name: id, created_at: '', duration_s: 30, serving: { net_type: 'LTE' }, cell_switches: 0,
    metrics: { score: { avg: score, min: score, max: score, std: 0, samples: 1 }, rsrp: { avg: -90, min: -90, max: -90, std: 0, samples: 1 } },
    speed: down == null ? [] : [{ at: '', peak_hour: false, runs: [], down_mbps: down, up_mbps: null, ping_ms: null, jitter_ms: null }],
    baseline: false,
  });
  it('sorts by speed first, then score', () => {
    const r = rankSpots([mk('a', 90, null), mk('b', 50, 30), mk('c', 60, 40), mk('d', 95, null)]);
    expect(r.map((s) => s.id)).toEqual(['c', 'b', 'd', 'a']);
  });
});

describe('fixtures', () => {
  it('every message has a type', () => {
    for (const f of ['agent-to-server.json', 'server-to-agent.json']) for (const m of fx(f)) expect(typeof m.t).toBe('string');
  });
});
