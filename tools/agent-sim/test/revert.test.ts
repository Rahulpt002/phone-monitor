import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { REVERT_SETTLE_S } from '@hsm/shared';
import { RevertManager, type ArmedRevert, type RevertHost } from '../src/revert.js';

interface Recorder {
  host: RevertHost;
  probes: string[];
  undone: string[];
  reports: { id: string; state: string; detail: string; quiet: boolean }[];
  persisted: ArmedRevert[][];
  connected: { value: boolean };
}

function recorder(): Recorder {
  const r: Recorder = {
    probes: [],
    undone: [],
    reports: [],
    persisted: [],
    connected: { value: true },
    host: undefined as unknown as RevertHost,
  };
  let n = 0;
  r.host = {
    now: () => Date.now(),
    isConnected: () => r.connected.value,
    newProbeId: () => `p_${++n}`,
    sendProbe: (id) => r.probes.push(id),
    undo: (a) => r.undone.push(a.command_id),
    report: (a, state, detail, quiet) => r.reports.push({ id: a.command_id, state, detail, quiet }),
    persist: (list) => r.persisted.push(list),
  };
  return r;
}

function armed(id: string, confirm: 'agent_link' | 'dashboard', afterS = 120, name: ArmedRevert['name'] = 'mobile_data'): ArmedRevert {
  const now = Date.now();
  return { command_id: id, name, undo: { name, args: { enabled: true } }, confirm, applied_at: now, deadline: now + afterS * 1000 };
}

describe('RevertManager (plan 6.4 rule 1)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('agent_link: the first probe after the settle time keeps the change', async () => {
    const rec = recorder();
    const m = new RevertManager(rec.host);
    m.arm(armed('c_1', 'agent_link'));
    await vi.advanceTimersByTimeAsync(REVERT_SETTLE_S * 1000 - 1);
    expect(rec.probes).toEqual([]);
    await vi.advanceTimersByTimeAsync(1);
    expect(rec.probes).toEqual(['p_1']);
    expect(m.onProbeAck('p_1')).toBe(true);
    expect(rec.reports).toEqual([{ id: 'c_1', state: 'kept', detail: expect.stringContaining('round-trip'), quiet: false }]);
    expect(rec.undone).toEqual([]);
    expect(m.size).toBe(0);
    await vi.advanceTimersByTimeAsync(200_000);
    expect(rec.reports).toHaveLength(1);
  });

  it('agent_link: keeps probing every 10 s while connected', async () => {
    const rec = recorder();
    const m = new RevertManager(rec.host);
    m.arm(armed('c_1', 'agent_link'));
    await vi.advanceTimersByTimeAsync(31_000);
    expect(rec.probes).toEqual(['p_1', 'p_2', 'p_3']);
    expect(m.onProbeAck('p_3')).toBe(true);
  });

  it('agent_link: with the link down nothing confirms and the change reverts at the deadline', async () => {
    const rec = recorder();
    rec.connected.value = false;
    const m = new RevertManager(rec.host);
    m.arm(armed('c_1', 'agent_link', 120));
    await vi.advanceTimersByTimeAsync(119_999);
    expect(rec.undone).toEqual([]);
    await vi.advanceTimersByTimeAsync(1);
    expect(rec.probes).toEqual([]);
    expect(rec.undone).toEqual(['c_1']);
    expect(rec.reports[0]).toMatchObject({ id: 'c_1', state: 'reverted', quiet: false });
    expect(rec.persisted.at(-1)).toEqual([]);
  });

  it('agent_link: a reconnect before the settle time does not probe; after it, it probes once', async () => {
    const rec = recorder();
    const m = new RevertManager(rec.host);
    m.arm(armed('c_1', 'agent_link'));
    m.onConnected();
    expect(rec.probes).toEqual([]);
    rec.connected.value = false;
    await vi.advanceTimersByTimeAsync(15_000);
    expect(rec.probes).toEqual([]);
    rec.connected.value = true;
    m.onConnected();
    expect(rec.probes).toEqual(['p_1']);
    expect(m.onProbeAck('p_unknown')).toBe(false);
    expect(m.onProbeAck('p_1')).toBe(true);
  });

  it('dashboard: only cmd_keep keeps it; probes are never sent', async () => {
    const rec = recorder();
    const m = new RevertManager(rec.host);
    m.arm(armed('c_t', 'dashboard', 120, 'usb_tethering'));
    await vi.advanceTimersByTimeAsync(60_000);
    expect(rec.probes).toEqual([]);
    expect(m.onKeep('c_t')).toBe(true);
    expect(rec.reports).toEqual([{ id: 'c_t', state: 'kept', detail: 'kept from the Dashboard', quiet: false }]);
    expect(m.onKeep('c_t')).toBe(false);
  });

  it('dashboard: without cmd_keep the change reverts at the deadline', async () => {
    const rec = recorder();
    const m = new RevertManager(rec.host);
    m.arm(armed('c_t', 'dashboard', 30, 'usb_tethering'));
    await vi.advanceTimersByTimeAsync(30_000);
    expect(rec.undone).toEqual(['c_t']);
    expect(rec.reports[0]?.state).toBe('reverted');
  });

  it('restore: a persisted revert past its deadline is reverted at once, others are re-armed', async () => {
    const rec = recorder();
    const m = new RevertManager(rec.host);
    const old = { ...armed('c_old', 'agent_link'), applied_at: Date.now() - 300_000, deadline: Date.now() - 180_000 };
    const fresh = armed('c_new', 'dashboard', 60);
    m.restore([old, fresh]);
    expect(rec.undone).toEqual(['c_old']);
    expect(rec.reports[0]).toMatchObject({ id: 'c_old', state: 'reverted', detail: expect.stringContaining('not running') });
    expect(m.list().map((r) => r.command_id)).toEqual(['c_new']);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(rec.undone).toEqual(['c_old', 'c_new']);
  });

  it('suspend/resume: a deadline that passes while suspended reverts on resume', async () => {
    const rec = recorder();
    const m = new RevertManager(rec.host);
    m.arm(armed('c_1', 'agent_link', 20));
    m.suspend();
    await vi.advanceTimersByTimeAsync(25_000);
    expect(rec.undone).toEqual([]);
    m.resume();
    expect(rec.undone).toEqual(['c_1']);
  });

  it('supersede: a restoring command reports the earlier change as reverted, quietly and without undo', () => {
    const rec = recorder();
    const m = new RevertManager(rec.host);
    m.arm(armed('c_off', 'agent_link'));
    m.supersede('data', 'c_on', false);
    expect(rec.reports).toEqual([{ id: 'c_off', state: 'reverted', detail: 'superseded by c_on', quiet: true }]);
    expect(rec.undone).toEqual([]);
    m.arm(armed('c_off2', 'agent_link'));
    m.supersede('airplane', 'c_x', false);
    expect(m.size).toBe(1);
  });
});
