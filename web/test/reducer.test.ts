import agentToServer from '@hsm/shared/fixtures/agent-to-server.json';
import serverToAgent from '@hsm/shared/fixtures/server-to-agent.json';
import type { AlertRecord, CommandRecord, DeviceSnapshot, FinderSpot, Settings } from '@hsm/shared';
import { describe, expect, it } from 'vitest';
import {
  applyServerMessage,
  emptySnapshot,
  initialData,
  lastCommandByName,
  mergeCommands,
  pendingReverts,
  setSnapshot,
  setSpots,
  type DataState,
} from '../src/store/reducer';

const DEV = 'd_3f9c2a';
type Msg = Record<string, unknown> & { t: string };
const agent = agentToServer as Msg[];
const toAgent = serverToAgent as Msg[];

/** The Server relays Agent messages to Dashboards with the device_id added (protocol.ts Dash* types). */
const relay = (m: Msg) => ({ ...m, device_id: DEV });
const find = (t: string, i = 0) => agent.filter((m) => m.t === t)[i]!;
const fast = find('telemetry', 0);
const slow = find('telemetry', 1);
const staticTier = find('telemetry', 2);

function snapshot(over: Partial<DeviceSnapshot> = {}): DeviceSnapshot {
  return { ...emptySnapshot(DEV), name: 'Roof phone', online: true, paired_at: '2026-11-01T00:00:00.000Z', ...over };
}

function withSnapshot(): DataState {
  return applyServerMessage(initialData, { t: 'snapshot', device: snapshot() });
}

function command(over: Partial<CommandRecord>): CommandRecord {
  return {
    id: 'c_1',
    device_id: DEV,
    name: 'mobile_data',
    args: { enabled: false },
    source: 'dashboard',
    user: 'admin',
    status: 'queued',
    created_at: '2026-11-19T10:20:00.000Z',
    ...over,
  };
}

describe('snapshot', () => {
  it('stores the device and its summary', () => {
    const s = withSnapshot();
    expect(s.snapshots[DEV]?.name).toBe('Roof phone');
    expect(s.devices).toEqual([expect.objectContaining({ device_id: DEV, name: 'Roof phone', online: true })]);
  });
  it('adds pending reverts to the command list', () => {
    const armed = command({ status: 'revert_armed', revert_deadline: '2026-11-19T10:22:00.000Z' });
    const s = applyServerMessage(initialData, { t: 'snapshot', device: snapshot({ pending_reverts: [armed] }) });
    expect(s.commands.map((c) => c.id)).toEqual(['c_1']);
    expect(pendingReverts(s, DEV).map((c) => c.id)).toEqual(['c_1']);
  });
});

describe('telemetry (fixtures)', () => {
  it('merges the fast tier groups', () => {
    const s = applyServerMessage(withSnapshot(), relay(fast));
    const snap = s.snapshots[DEV]!;
    expect(snap.state.radio).toEqual(fast.radio);
    expect(snap.state.net).toEqual(fast.net);
    expect(snap.state_ts.radio).toBe(fast.ts);
    expect(s.lastTelemetry?.tier).toBe('fast');
  });
  it('merges the slow and static tiers, and static sets the device info', () => {
    let s = withSnapshot();
    for (const m of [fast, slow, staticTier]) s = applyServerMessage(s, relay(m));
    const snap = s.snapshots[DEV]!;
    expect(snap.state.power?.percent).toBe(58);
    expect(snap.state.heat?.battery_c).toBe(36.5);
    expect(snap.state.tether?.tether_ip).toBe('192.168.42.129');
    expect(snap.state.capabilities?.network_mode?.supported).toBe(false);
    expect(snap.info?.model).toBe('SM-G991B');
    expect(s.devices[0]?.info?.model).toBe('SM-G991B');
  });
  it('never lets an older reading overwrite a newer one', () => {
    let s = applyServerMessage(withSnapshot(), relay({ ...fast, ts: '2026-11-19T10:16:00.000Z' }));
    s = applyServerMessage(s, relay({ ...fast, ts: '2026-11-19T10:15:00.000Z', radio: { ...(fast.radio as object), rsrp: -120 } }));
    expect(s.snapshots[DEV]!.state.radio?.rsrp).toBe(-94);
    expect(s.snapshots[DEV]!.state_ts.radio).toBe('2026-11-19T10:16:00.000Z');
  });
  it('creates a skeleton snapshot for a device it has not seen', () => {
    const s = applyServerMessage(initialData, relay(fast));
    expect(s.snapshots[DEV]?.state.radio?.pci).toBe(211);
  });
  it('advances last_seen', () => {
    const s = applyServerMessage(applyServerMessage(initialData, { t: 'snapshot', device: snapshot({ last_seen: '2026-11-19T10:00:00.000Z' }) }), relay(fast));
    expect(s.snapshots[DEV]!.last_seen).toBe(fast.ts);
  });
});

describe('events, status, alerts, sms', () => {
  it('adds events once, newest first', () => {
    const ev = find('event');
    let s = applyServerMessage(initialData, relay(ev));
    s = applyServerMessage(s, relay(ev));
    s = applyServerMessage(s, { t: 'event', device_id: DEV, event: { id: 'e_old', ts: '2026-11-19T09:00:00.000Z', kind: 'boot', severity: 'info', message: 'booted' } });
    expect(s.events.map((e) => e.id)).toEqual(['e_7Hk2pQ', 'e_old']);
    expect(s.events[0]!.device_id).toBe(DEV);
  });
  it('applies status to the snapshot and the device list', () => {
    const s = applyServerMessage(withSnapshot(), { t: 'status', device_id: DEV, online: false, last_seen: '2026-11-19T10:30:00.000Z', connected_since: null });
    expect(s.snapshots[DEV]!.online).toBe(false);
    expect(s.snapshots[DEV]!.last_seen).toBe('2026-11-19T10:30:00.000Z');
    expect(s.devices[0]!.online).toBe(false);
  });
  it('dedupes alerts by id', () => {
    const alert: AlertRecord = { id: 7, device_id: DEV, kind: 'offline', severity: 'critical', title: 'Phone offline', message: 'No heartbeat for 3 min', ts: '2026-11-19T10:40:00.000Z', channels: ['telegram'] };
    let s = applyServerMessage(initialData, { t: 'alert', alert });
    s = applyServerMessage(s, { t: 'alert', alert: { ...alert, channels: ['telegram', 'push'] } });
    expect(s.alerts).toHaveLength(1);
    expect(s.alerts[0]!.channels).toEqual(['telegram', 'push']);
  });
  it('stores sms with the device id', () => {
    const s = applyServerMessage(initialData, relay(find('sms')));
    expect(s.sms).toEqual([expect.objectContaining({ id: 'm_x1', peer: 'JX-JIOINF', device_id: DEV })]);
  });
});

describe('commands and auto-revert', () => {
  it('tracks a revert from armed to kept', () => {
    let s = withSnapshot();
    s = applyServerMessage(s, { t: 'command', command: command({ status: 'queued' }) });
    expect(pendingReverts(s, DEV)).toHaveLength(0);
    s = applyServerMessage(s, { t: 'command', command: command({ status: 'revert_armed', revert_confirm: 'agent_link', revert_deadline: '2026-11-19T10:22:00.000Z' }) });
    expect(s.snapshots[DEV]!.pending_reverts.map((c) => c.id)).toEqual(['c_1']);
    expect(pendingReverts(s, DEV)).toHaveLength(1);
    s = applyServerMessage(s, { t: 'command', command: command({ status: 'kept' }) });
    expect(s.snapshots[DEV]!.pending_reverts).toHaveLength(0);
    expect(pendingReverts(s, DEV)).toHaveLength(0);
    expect(s.commands).toHaveLength(1);
    expect(s.commands[0]!.status).toBe('kept');
  });
  it('finds the last result per command name', () => {
    const s = mergeCommands(initialData, [
      command({ id: 'c_a', name: 'reboot', created_at: '2026-11-19T09:00:00.000Z', status: 'done' }),
      command({ id: 'c_b', name: 'reboot', created_at: '2026-11-19T11:00:00.000Z', status: 'rejected' }),
      command({ id: 'c_c', name: 'wifi', device_id: 'd_other' }),
    ]);
    const last = lastCommandByName(s.commands, DEV);
    expect(last.get('reboot')?.id).toBe('c_b');
    expect(last.has('wifi')).toBe(false);
  });
});

describe('finder', () => {
  const spot = (find('finder_spot').spot as FinderSpot);
  it('stores the live mirror reading with the receive time', () => {
    const s = applyServerMessage(initialData, relay(find('finder_live')), 1234);
    expect(s.finderLive[DEV]).toEqual({ live: (find('finder_live').live as object), received_at: 1234 });
  });
  it('upserts spots and moves the baseline flag', () => {
    let s = withSnapshot();
    s = applyServerMessage(s, { t: 'finder_spot', device_id: DEV, spot: { ...spot, id: 's_a', baseline: true } });
    s = applyServerMessage(s, { t: 'finder_spot', device_id: DEV, spot: { ...spot, id: 's_b', baseline: true } });
    const list = s.spots[DEV]!;
    expect(list.find((x) => x.id === 's_a')?.baseline).toBe(false);
    expect(list.find((x) => x.id === 's_b')?.baseline).toBe(true);
    expect(s.snapshots[DEV]!.baseline?.id).toBe('s_b');
  });
  it('removes a deleted spot and clears it as baseline', () => {
    let s = setSpots(withSnapshot(), DEV, [{ ...spot, id: 's_old' }], 's_old');
    expect(s.snapshots[DEV]!.baseline?.id).toBe('s_old');
    s = applyServerMessage(s, { t: 'finder_spot_deleted', device_id: DEV, spot_id: 's_old' });
    expect(s.spots[DEV]).toEqual([]);
    expect(s.snapshots[DEV]!.baseline).toBeNull();
  });
});

describe('settings', () => {
  const settingsMsg = toAgent.find((m) => m.t === 'settings')!;
  it('applies newer versions only', () => {
    let s = applyServerMessage(withSnapshot(), { ...settingsMsg, device_id: DEV });
    expect(s.settings[DEV]?.version).toBe(4);
    expect(s.snapshots[DEV]!.settings_version).toBe(4);
    const older = { ...(settingsMsg.settings as Settings), plan: { quota_gb: 99, cycle_start_day: 1 } };
    s = applyServerMessage(s, { t: 'settings', device_id: DEV, settings: older, version: 3 });
    expect(s.settings[DEV]?.settings.plan.quota_gb).toBe(0);
  });
});

describe('robustness', () => {
  it('ignores unknown message types and malformed messages', () => {
    const s = withSnapshot();
    expect(applyServerMessage(s, { t: 'something_new', x: 1 })).toBe(s);
    expect(applyServerMessage(s, null)).toBe(s);
    expect(applyServerMessage(s, 'text')).toBe(s);
    expect(applyServerMessage(s, { t: 'telemetry' })).toBe(s);
    expect(applyServerMessage(s, { t: 'command', command: 'nope' })).toBe(s);
    expect(applyServerMessage(s, { t: 'pong' })).toBe(s);
  });
  it('keeps socket groups that are newer than a REST snapshot', () => {
    let s = applyServerMessage(withSnapshot(), relay({ ...fast, ts: '2026-11-19T10:20:00.000Z' }));
    s = setSnapshot(s, snapshot({ state: { radio: { net_type: 'LTE', rsrp: -110 } }, state_ts: { radio: '2026-11-19T10:00:00.000Z' } }));
    expect(s.snapshots[DEV]!.state.radio?.rsrp).toBe(-94);
  });
});
