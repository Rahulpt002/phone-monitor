/**
 * Pure state transitions for everything the Dashboard socket and the REST loaders deliver
 * (docs/protocol.md section 5). Kept free of React and zustand so tests can feed it fixtures.
 */
import {
  commandMayMove,
  type AlertRecord,
  type CommandRecord,
  type DeviceSnapshot,
  type DeviceSummary,
  type FinderLive,
  type FinderSpot,
  type Settings,
  type SmsRecord,
  type StoredEvent,
  type TelemetryGroups,
  type TelemetryTier,
} from '@hsm/shared';
import { withoutSecretArgs } from '../lib/commandMeta';

export type DeviceSms = SmsRecord & { device_id: string };

export interface LiveTelemetry extends TelemetryGroups {
  device_id: string;
  ts: string;
  tier: TelemetryTier;
}

export interface DataState {
  devices: DeviceSummary[];
  snapshots: Record<string, DeviceSnapshot>;
  /** Newest first, every device (filter by device_id in views). */
  events: StoredEvent[];
  /** Newest first by created_at. */
  commands: CommandRecord[];
  alerts: AlertRecord[];
  sms: DeviceSms[];
  spots: Record<string, FinderSpot[]>;
  finderLive: Record<string, { live: FinderLive; received_at: number }>;
  settings: Record<string, { settings: Settings; version: number }>;
  /** Last live telemetry message, so open charts can append it. */
  lastTelemetry: LiveTelemetry | null;
}

export const initialData: DataState = {
  devices: [],
  snapshots: {},
  events: [],
  commands: [],
  alerts: [],
  sms: [],
  spots: {},
  finderLive: {},
  settings: {},
  lastTelemetry: null,
};

export const CAPS = { events: 500, commands: 500, alerts: 300, sms: 500 } as const;

const GROUP_KEYS: (keyof TelemetryGroups)[] = [
  'radio',
  'net',
  'traffic',
  'tether',
  'power',
  'heat',
  'system',
  'position',
  'device',
  'capabilities',
];

const ms = (iso: string | null | undefined): number => {
  if (!iso) return Number.NEGATIVE_INFINITY;
  const v = Date.parse(iso);
  return Number.isNaN(v) ? Number.NEGATIVE_INFINITY : v;
};

const isObj = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);

const laterIso = (a: string | null | undefined, b: string | null | undefined): string | null =>
  ms(a) >= ms(b) ? (a ?? b ?? null) : (b ?? null);

/**
 * Upsert by key, keep newest first by `time`, cap the length. `replaces(current, incoming)` decides
 * whether an incoming record may replace the stored one (default: always).
 */
export function mergeById<T>(
  list: T[],
  incoming: T[],
  key: (x: T) => string,
  time: (x: T) => string,
  cap: number,
  replaces?: (current: T, incoming: T) => boolean,
): T[] {
  if (!incoming.length) return list;
  const map = new Map<string, T>();
  for (const x of list) map.set(key(x), x);
  for (const x of incoming) {
    const cur = map.get(key(x));
    if (cur === undefined || !replaces || replaces(cur, x)) map.set(key(x), x);
  }
  return [...map.values()].sort((a, b) => ms(time(b)) - ms(time(a))).slice(0, cap);
}

/**
 * A command record is mutable: an older copy (a late REST response, a delayed broadcast) must not
 * undo a newer status. Uses the Server's own lifecycle table (shared commandMayMove), so a soft
 * 'expired' replaces 'sent', 'acked' and 'revert_armed', and late reports after it still apply.
 * A copy with the same status may refresh the record (re-delivery, output).
 */
const commandNotBehind = (cur: CommandRecord, next: CommandRecord) =>
  next.status === cur.status || commandMayMove(cur, next.status);

/** Secret arguments (CommandSpec.secret_args, the hotspot password) never enter the store. */
function sanitizeCommand(c: CommandRecord): CommandRecord {
  if (!isObj(c.args)) return c;
  const args = withoutSecretArgs(c.name, c.args);
  return args === c.args ? c : { ...c, args };
}

function mergeCommandList(list: CommandRecord[], incoming: CommandRecord[]): CommandRecord[] {
  return mergeById(list, incoming.map(sanitizeCommand), (c) => c.id, (c) => c.created_at, CAPS.commands, commandNotBehind);
}

export function emptySnapshot(device_id: string): DeviceSnapshot {
  return {
    device_id,
    name: device_id,
    online: false,
    last_seen: null,
    paired_at: '',
    info: null,
    connected_since: null,
    state: {},
    state_ts: {},
    settings_version: 0,
    baseline: null,
    outbox: null,
    pending_reverts: [],
  };
}

export function summaryOf(s: DeviceSnapshot): DeviceSummary {
  return {
    device_id: s.device_id,
    name: s.name,
    online: s.online,
    last_seen: s.last_seen,
    paired_at: s.paired_at,
    info: s.info,
  };
}

function upsertSummary(devices: DeviceSummary[], d: DeviceSummary): DeviceSummary[] {
  const i = devices.findIndex((x) => x.device_id === d.device_id);
  if (i === -1) return [...devices, d];
  const next = devices.slice();
  next[i] = { ...devices[i]!, ...d };
  return next;
}

function patchSummary(devices: DeviceSummary[], id: string, patch: Partial<DeviceSummary>): DeviceSummary[] {
  const i = devices.findIndex((x) => x.device_id === id);
  if (i === -1) return devices;
  const next = devices.slice();
  next[i] = { ...devices[i]!, ...patch };
  return next;
}

/** Applies one telemetry message to a snapshot; older readings never overwrite newer ones. */
export function applyTelemetry(snap: DeviceSnapshot, msg: LiveTelemetry): DeviceSnapshot {
  const state: TelemetryGroups = { ...snap.state };
  const stateTs = { ...snap.state_ts };
  const at = ms(msg.ts);
  let changed = false;
  for (const key of GROUP_KEYS) {
    const value = msg[key];
    if (value === undefined || value === null) continue;
    if (ms(stateTs[key]) > at) continue;
    (state as Record<string, unknown>)[key] = value;
    stateTs[key] = msg.ts;
    changed = true;
  }
  if (!changed) return snap;
  return {
    ...snap,
    state,
    state_ts: stateTs,
    info: msg.device && ms(snap.state_ts.device) <= at ? msg.device : snap.info,
    last_seen: laterIso(snap.last_seen, msg.ts),
  };
}

function withPendingRevert(snap: DeviceSnapshot, cmd: CommandRecord): DeviceSnapshot {
  const others = snap.pending_reverts.filter((c) => c.id !== cmd.id);
  if (cmd.status === 'revert_armed') return { ...snap, pending_reverts: [...others, cmd] };
  return others.length === snap.pending_reverts.length ? snap : { ...snap, pending_reverts: others };
}

function upsertSpot(spots: FinderSpot[], spot: FinderSpot): FinderSpot[] {
  const rest = spots.filter((s) => s.id !== spot.id);
  const normalized = spot.baseline ? rest.map((s) => (s.baseline ? { ...s, baseline: false } : s)) : rest;
  return [...normalized, spot];
}

/**
 * Applies one Server -> Dashboard message. Unknown `t` values and malformed messages are ignored
 * (protocol rule: receivers ignore what they do not understand).
 */
export function applyServerMessage(state: DataState, raw: unknown, now: number = Date.now()): DataState {
  if (!isObj(raw) || typeof raw.t !== 'string') return state;
  const msg = raw as Record<string, unknown> & { t: string };

  switch (msg.t) {
    case 'snapshot': {
      const device = msg.device as DeviceSnapshot | undefined;
      if (!isObj(device) || typeof device.device_id !== 'string') return state;
      const snap: DeviceSnapshot = {
        ...emptySnapshot(device.device_id),
        ...device,
        state: device.state ?? {},
        state_ts: device.state_ts ?? {},
        pending_reverts: Array.isArray(device.pending_reverts) ? device.pending_reverts.map(sanitizeCommand) : [],
      };
      let spots = state.spots;
      if (snap.baseline) {
        const list = spots[snap.device_id];
        if (list) spots = { ...spots, [snap.device_id]: upsertSpot(list, snap.baseline) };
      }
      return {
        ...state,
        snapshots: { ...state.snapshots, [snap.device_id]: snap },
        devices: upsertSummary(state.devices, summaryOf(snap)),
        commands: mergeCommandList(state.commands, snap.pending_reverts),
        spots,
      };
    }

    case 'telemetry': {
      if (typeof msg.device_id !== 'string' || typeof msg.ts !== 'string') return state;
      const tele = msg as unknown as LiveTelemetry;
      const prev = state.snapshots[tele.device_id] ?? emptySnapshot(tele.device_id);
      const next = applyTelemetry(prev, tele);
      return {
        ...state,
        snapshots: next === prev && state.snapshots[tele.device_id] ? state.snapshots : { ...state.snapshots, [tele.device_id]: next },
        devices: next.info !== prev.info ? patchSummary(state.devices, tele.device_id, { info: next.info }) : state.devices,
        lastTelemetry: tele,
      };
    }

    case 'event': {
      const event = msg.event as StoredEvent | undefined;
      if (!isObj(event) || typeof event.id !== 'string' || typeof msg.device_id !== 'string') return state;
      const stored: StoredEvent = { ...event, device_id: msg.device_id };
      return { ...state, events: mergeById(state.events, [stored], (e) => e.id, (e) => e.ts, CAPS.events) };
    }

    case 'status': {
      if (typeof msg.device_id !== 'string') return state;
      const patch = {
        online: Boolean(msg.online),
        last_seen: (msg.last_seen as string | null) ?? null,
        connected_since: (msg.connected_since as string | null) ?? null,
      };
      const snap = state.snapshots[msg.device_id];
      return {
        ...state,
        snapshots: snap
          ? {
              ...state.snapshots,
              [msg.device_id]: { ...snap, ...patch, last_seen: laterIso(snap.last_seen, patch.last_seen) },
            }
          : state.snapshots,
        devices: patchSummary(state.devices, msg.device_id, { online: patch.online, last_seen: patch.last_seen }),
      };
    }

    case 'command': {
      const cmd = msg.command as CommandRecord | undefined;
      if (!isObj(cmd) || typeof cmd.id !== 'string') return state;
      const commands = mergeCommandList(state.commands, [cmd]);
      // The record now stored: a delayed older status does not re-arm a finished revert.
      const current = commands.find((c) => c.id === cmd.id) ?? sanitizeCommand(cmd);
      const snap = state.snapshots[cmd.device_id];
      return {
        ...state,
        commands,
        snapshots: snap ? { ...state.snapshots, [cmd.device_id]: withPendingRevert(snap, current) } : state.snapshots,
      };
    }

    case 'finder_live': {
      const live = msg.live as FinderLive | undefined;
      if (!isObj(live) || typeof msg.device_id !== 'string') return state;
      return { ...state, finderLive: { ...state.finderLive, [msg.device_id]: { live, received_at: now } } };
    }

    case 'finder_spot': {
      const spot = msg.spot as FinderSpot | undefined;
      if (!isObj(spot) || typeof spot.id !== 'string' || typeof msg.device_id !== 'string') return state;
      const id = msg.device_id;
      const snap = state.snapshots[id];
      let snapshots = state.snapshots;
      if (snap) {
        if (spot.baseline) snapshots = { ...snapshots, [id]: { ...snap, baseline: spot } };
        else if (snap.baseline?.id === spot.id) snapshots = { ...snapshots, [id]: { ...snap, baseline: null } };
      }
      return { ...state, spots: { ...state.spots, [id]: upsertSpot(state.spots[id] ?? [], spot) }, snapshots };
    }

    case 'finder_spot_deleted': {
      if (typeof msg.device_id !== 'string' || typeof msg.spot_id !== 'string') return state;
      const id = msg.device_id;
      const snap = state.snapshots[id];
      return {
        ...state,
        spots: { ...state.spots, [id]: (state.spots[id] ?? []).filter((s) => s.id !== msg.spot_id) },
        snapshots:
          snap && snap.baseline?.id === msg.spot_id ? { ...state.snapshots, [id]: { ...snap, baseline: null } } : state.snapshots,
      };
    }

    case 'alert': {
      const alert = msg.alert as AlertRecord | undefined;
      if (!isObj(alert) || alert.id == null) return state;
      return { ...state, alerts: mergeById(state.alerts, [alert], (a) => String(a.id), (a) => a.ts, CAPS.alerts) };
    }

    case 'sms': {
      const sms = msg.sms as SmsRecord | undefined;
      if (!isObj(sms) || typeof sms.id !== 'string' || typeof msg.device_id !== 'string') return state;
      const rec: DeviceSms = { ...sms, device_id: msg.device_id };
      return { ...state, sms: mergeById(state.sms, [rec], (m) => m.id, (m) => m.at, CAPS.sms) };
    }

    case 'settings': {
      if (typeof msg.device_id !== 'string' || !isObj(msg.settings) || typeof msg.version !== 'number') return state;
      return applySettings(state, msg.device_id, msg.settings as unknown as Settings, msg.version);
    }

    default:
      return state;
  }
}

export function applySettings(state: DataState, deviceId: string, settings: Settings, version: number): DataState {
  const cur = state.settings[deviceId];
  if (cur && cur.version > version) return state;
  const snap = state.snapshots[deviceId];
  return {
    ...state,
    settings: { ...state.settings, [deviceId]: { settings, version } },
    snapshots: snap ? { ...state.snapshots, [deviceId]: { ...snap, settings_version: version } } : state.snapshots,
  };
}

// --- REST loader merges -----------------------------------------------------------------------

export function setDevices(state: DataState, devices: DeviceSummary[]): DataState {
  const known = new Set(devices.map((d) => d.device_id));
  const snapshots: Record<string, DeviceSnapshot> = {};
  for (const [id, s] of Object.entries(state.snapshots)) if (known.has(id)) snapshots[id] = s;
  return { ...state, devices, snapshots };
}

/**
 * When the connection state in a snapshot last changed: the start of the connection while online,
 * the last contact (about when it dropped) while offline.
 */
const linkChangedAt = (s: Pick<DeviceSnapshot, 'online' | 'connected_since' | 'last_seen'>): number =>
  s.online ? ms(s.connected_since) : ms(s.last_seen);

export function setSnapshot(state: DataState, snap: DeviceSnapshot): DataState {
  const cur = state.snapshots[snap.device_id];
  // Keep any group the socket delivered after this REST response was produced.
  let merged = snap;
  if (cur) {
    const state2 = { ...snap.state };
    const ts2 = { ...snap.state_ts };
    for (const key of GROUP_KEYS) {
      if (ms(cur.state_ts[key]) > ms(snap.state_ts[key]) && cur.state[key] !== undefined) {
        (state2 as Record<string, unknown>)[key] = cur.state[key];
        ts2[key] = cur.state_ts[key];
      }
    }
    // Same for the online state: a live `status` newer than this response wins.
    const link = linkChangedAt(cur) > linkChangedAt(snap) ? { online: cur.online, connected_since: cur.connected_since } : {};
    merged = { ...snap, ...link, state: state2, state_ts: ts2, last_seen: laterIso(snap.last_seen, cur.last_seen) };
  }
  return applyServerMessage(
    { ...state, snapshots: { ...state.snapshots, [snap.device_id]: merged } },
    { t: 'snapshot', device: merged },
  );
}

export function mergeCommands(state: DataState, list: CommandRecord[]): DataState {
  return { ...state, commands: mergeCommandList(state.commands, list) };
}

export function mergeEvents(state: DataState, list: StoredEvent[]): DataState {
  return { ...state, events: mergeById(state.events, list, (e) => e.id, (e) => e.ts, CAPS.events) };
}

export function mergeAlerts(state: DataState, list: AlertRecord[]): DataState {
  return { ...state, alerts: mergeById(state.alerts, list, (a) => String(a.id), (a) => a.ts, CAPS.alerts) };
}

export function mergeSms(state: DataState, deviceId: string, list: SmsRecord[]): DataState {
  const recs = list.map((m) => ({ ...m, device_id: deviceId }));
  return { ...state, sms: mergeById(state.sms, recs, (m) => m.id, (m) => m.at, CAPS.sms) };
}

export function setSpots(state: DataState, deviceId: string, spots: FinderSpot[], baselineId: string | null): DataState {
  const normalized = spots.map((s) => ({ ...s, baseline: baselineId ? s.id === baselineId : s.baseline }));
  const baseline = normalized.find((s) => s.baseline) ?? null;
  const snap = state.snapshots[deviceId];
  return {
    ...state,
    spots: { ...state.spots, [deviceId]: normalized },
    snapshots: snap ? { ...state.snapshots, [deviceId]: { ...snap, baseline } } : state.snapshots,
  };
}

// --- selectors --------------------------------------------------------------------------------

/** Newest record per command name for a device ("last result" on each control). */
export function lastCommandByName(commands: CommandRecord[], deviceId: string | null): Map<string, CommandRecord> {
  const out = new Map<string, CommandRecord>();
  for (const c of commands) {
    if (deviceId && c.device_id !== deviceId) continue;
    if (!out.has(c.name)) out.set(c.name, c);
  }
  return out;
}

/** Commands armed for auto-revert, from both the snapshot and live command updates. */
export function pendingReverts(state: DataState, deviceId: string | null): CommandRecord[] {
  if (!deviceId) return [];
  const map = new Map<string, CommandRecord>();
  for (const c of state.snapshots[deviceId]?.pending_reverts ?? []) map.set(c.id, c);
  for (const c of state.commands) {
    if (c.device_id !== deviceId) continue;
    if (c.status === 'revert_armed') map.set(c.id, c);
    else map.delete(c.id);
  }
  return [...map.values()].sort((a, b) => ms(a.revert_deadline) - ms(b.revert_deadline));
}
