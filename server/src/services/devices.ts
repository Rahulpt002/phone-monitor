import { randomInt } from 'node:crypto';
import {
  DEFAULT_SETTINGS,
  TIER_GROUPS,
  type DashStatusMsg,
  type DeviceInfo,
  type DeviceSnapshot,
  type DeviceSummary,
  type FinderSpot,
  type PairCodeResponse,
  type PairResponse,
  type Settings,
  type TelemetryGroups,
  type TelemetryMsg,
} from '@hsm/shared';
import { z } from 'zod';
import type { AppContext } from '../context.js';
import type { Stmt } from '../db/client.js';
import { badRequest, notFound, unauthorized } from '../errors.js';
import { iso, isoOrNull, isPlainObject, parseTs, randomId, randomToken, safeJsonParse, sha256Hex } from '../util.js';
import type { AlertState } from './alerts.js';
import { normalizeStoredSettings } from './settings.js';

export type GroupKey = keyof TelemetryGroups;
export const GROUP_KEYS: GroupKey[] = [...new Set(Object.values(TIER_GROUPS).flat())] as GroupKey[];

/** In-memory state of one paired device. The process is the single writer; the DB is the backup. */
export interface DeviceRuntime {
  id: string;
  name: string;
  pairedAt: string;
  lastSeen: number | null;
  connectedSince: number | null;
  info: DeviceInfo | null;
  state: TelemetryGroups;
  stateTs: Partial<Record<GroupKey, number>>;
  settings: Settings;
  settingsVersion: number;
  outbox: number | null;
  offlineAlertOpen: boolean;
  /** last_seen at the moment the offline alert fired. */
  offlineSince: number | null;
  lastBackOnline: number | null;
  alertState: AlertState;
  baseline: FinderSpot | null;
  dirty: boolean;
}

/** 31 characters: A-Z and 2-9 without the lookalikes I, L and O (0 and 1 are not in 2-9). */
export const PAIR_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
export const PAIR_CODE_LENGTH = 8;
export const PAIR_CODE_TTL_MS = 15 * 60_000;

export const DeviceInfoSchema = z.looseObject({
  device_id: z.string().max(64).nullish(),
  manufacturer: z.string().min(1).max(100),
  model: z.string().min(1).max(100),
  device: z.string().max(100),
  android_version: z.string().max(32),
  sdk: z.number().int().min(1).max(1000),
  build_id: z.string().max(200).nullish(),
  agent_version: z.string().max(64),
  agent_version_code: z.number().int().nullish(),
  profile_id: z.string().max(100),
  rooted: z.boolean(),
  root_impl: z.string().max(100).nullish(),
  imei_tail: z.string().max(8).nullish(),
});

function rowToDevice(r: Record<string, unknown>): DeviceRuntime {
  const stateTsIso = safeJsonParse<Record<string, string>>(r.state_ts, {});
  const stateTs: Partial<Record<GroupKey, number>> = {};
  for (const [k, v] of Object.entries(stateTsIso)) {
    const ms = parseTs(v);
    if (ms != null && (GROUP_KEYS as string[]).includes(k)) stateTs[k as GroupKey] = ms;
  }
  return {
    id: String(r.id),
    name: String(r.name),
    pairedAt: String(r.paired_at),
    lastSeen: parseTs(r.last_seen),
    connectedSince: null,
    info: safeJsonParse<DeviceInfo | null>(r.info, null),
    state: safeJsonParse<TelemetryGroups>(r.state, {}),
    stateTs,
    settings: normalizeStoredSettings(safeJsonParse(r.settings, {})),
    settingsVersion: Number(r.settings_version ?? 1),
    outbox: r.outbox == null ? null : Number(r.outbox),
    offlineAlertOpen: Number(r.offline_alert_open ?? 0) === 1,
    offlineSince: parseTs(r.offline_since),
    lastBackOnline: parseTs(r.last_back_online),
    alertState: safeJsonParse<AlertState>(r.alert_state, {}),
    baseline: null,
    dirty: false,
  };
}

export class DeviceService {
  private devices = new Map<string, DeviceRuntime>();
  private byToken = new Map<string, string>();

  constructor(private ctx: AppContext) {}

  async load(): Promise<void> {
    const rows = await this.ctx.db.all('SELECT * FROM devices WHERE revoked_at IS NULL');
    this.devices.clear();
    this.byToken.clear();
    for (const r of rows) {
      const d = rowToDevice(r);
      this.devices.set(d.id, d);
      this.byToken.set(String(r.token_hash), d.id);
    }
  }

  all(): DeviceRuntime[] {
    return [...this.devices.values()].sort((a, b) => a.pairedAt.localeCompare(b.pairedAt));
  }

  get(id: string): DeviceRuntime | undefined {
    return this.devices.get(id);
  }

  byTokenHash(hash: string): DeviceRuntime | undefined {
    const id = this.byToken.get(hash);
    return id ? this.devices.get(id) : undefined;
  }

  /** The device named by `device_id`, or the first paired one when omitted (api.ts header). */
  resolve(deviceId?: string | null): DeviceRuntime {
    if (deviceId) {
      const d = this.devices.get(deviceId);
      if (!d) throw notFound(`Device ${deviceId} not found`);
      return d;
    }
    const first = this.all()[0];
    if (!first) throw notFound('No phone is paired yet');
    return first;
  }

  isOnline(dev: DeviceRuntime): boolean {
    if (!this.ctx.hub.agentConnected(dev.id) || dev.lastSeen == null) return false;
    return this.ctx.clock.now() - dev.lastSeen <= dev.settings.alerts.offline_after_s * 1000;
  }

  summary(dev: DeviceRuntime): DeviceSummary {
    return {
      device_id: dev.id,
      name: dev.name,
      online: this.isOnline(dev),
      last_seen: isoOrNull(dev.lastSeen),
      paired_at: dev.pairedAt,
      info: dev.info,
    };
  }

  snapshot(dev: DeviceRuntime): DeviceSnapshot {
    const stateTs: Partial<Record<GroupKey, string>> = {};
    for (const [k, v] of Object.entries(dev.stateTs)) if (v != null) stateTs[k as GroupKey] = iso(v);
    return {
      ...this.summary(dev),
      connected_since: this.ctx.hub.agentConnected(dev.id) ? isoOrNull(dev.connectedSince) : null,
      state: dev.state,
      state_ts: stateTs,
      settings_version: dev.settingsVersion,
      baseline: dev.baseline,
      outbox: dev.outbox,
      pending_reverts: this.ctx.commands.pendingReverts(dev.id),
    };
  }

  status(dev: DeviceRuntime): DashStatusMsg {
    return {
      t: 'status',
      device_id: dev.id,
      online: this.isOnline(dev),
      last_seen: isoOrNull(dev.lastSeen),
      connected_since: this.ctx.hub.agentConnected(dev.id) ? isoOrNull(dev.connectedSince) : null,
    };
  }

  broadcastStatus(dev: DeviceRuntime): void {
    this.ctx.hub.broadcastDevice(dev.id, this.status(dev));
  }

  // --- pairing (docs/protocol.md section 2) ---------------------------------------------------

  async createPairCode(name: string | null, createdBy: string, baseUrl: string): Promise<PairCodeResponse> {
    const now = this.ctx.clock.now();
    for (let attempt = 0; attempt < 5; attempt++) {
      let code = '';
      for (let i = 0; i < PAIR_CODE_LENGTH; i++) code += PAIR_ALPHABET[randomInt(PAIR_ALPHABET.length)];
      const r = await this.ctx.db.run(
        'INSERT OR IGNORE INTO pair_codes (code, name, created_by, created_at, expires_at) VALUES (?, ?, ?, ?, ?)',
        [code, name, createdBy, now, now + PAIR_CODE_TTL_MS],
      );
      if (r.changes !== 1) continue;
      const deepLink = `hsm://pair?server=${encodeURIComponent(baseUrl)}&code=${code}`;
      return {
        code,
        expires_at: iso(now + PAIR_CODE_TTL_MS),
        deep_link: deepLink,
        // Double quotes on the host keep the single quotes for the phone's shell, which would
        // otherwise split the link at '&'.
        adb_command: `adb shell "am start -a android.intent.action.VIEW -d '${deepLink}'"`,
      };
    }
    throw new Error('could not allocate a unique pairing code');
  }

  async pair(rawCode: string, info: DeviceInfo, baseUrl: string): Promise<PairResponse> {
    const code = rawCode.toUpperCase().replace(/[\s-]/g, '');
    if (code.length !== PAIR_CODE_LENGTH || [...code].some((c) => !PAIR_ALPHABET.includes(c))) {
      throw badRequest('Pairing code must be 8 characters');
    }
    const now = this.ctx.clock.now();
    const id = randomId('d_', 6);
    // Claim the code atomically: only one request can flip used_at from NULL.
    const claimed = await this.ctx.db.all<{ name: string | null }>(
      'UPDATE pair_codes SET used_at = ?, device_id = ? WHERE code = ? AND used_at IS NULL AND expires_at > ? RETURNING name',
      [now, id, code, now],
    );
    const claim = claimed[0];
    if (!claim) throw unauthorized('Pairing code is wrong, expired or already used');

    const token = randomToken(32);
    const tokenHash = sha256Hex(token);
    const name = (claim.name && claim.name.trim()) || `${info.manufacturer} ${info.model}`.slice(0, 80);
    const settings = structuredClone(DEFAULT_SETTINGS);
    const storedInfo: DeviceInfo = { ...info, device_id: id };
    await this.ctx.db.run(
      `INSERT INTO devices (id, name, token_hash, paired_at, info, settings, settings_version)
       VALUES (?, ?, ?, ?, ?, ?, 1)`,
      [id, name, tokenHash, iso(now), JSON.stringify(storedInfo), JSON.stringify(settings)],
    );
    const dev: DeviceRuntime = {
      id,
      name,
      pairedAt: iso(now),
      lastSeen: null,
      connectedSince: null,
      info: storedInfo,
      state: {},
      stateTs: {},
      settings,
      settingsVersion: 1,
      outbox: null,
      offlineAlertOpen: false,
      offlineSince: null,
      lastBackOnline: null,
      alertState: {},
      baseline: null,
      dirty: false,
    };
    this.devices.set(id, dev);
    this.byToken.set(tokenHash, id);
    this.ctx.log.info({ device_id: id, model: info.model }, 'phone paired');
    return {
      device_id: id,
      device_token: token,
      ws_url: `${baseUrl.replace(/^http/i, 'ws')}/ws/agent`,
      server_url: baseUrl,
    };
  }

  async rename(dev: DeviceRuntime, name: string): Promise<DeviceSummary> {
    const clean = name.trim();
    if (!clean || clean.length > 80) throw badRequest('Name must be 1-80 characters');
    await this.ctx.db.run('UPDATE devices SET name = ? WHERE id = ?', [clean, dev.id]);
    dev.name = clean;
    return this.summary(dev);
  }

  /**
   * Revokes the device token. The row is kept (soft delete) so stored history ages out through
   * normal retention instead of a mass delete; the device disappears from every list.
   */
  async unpair(dev: DeviceRuntime): Promise<void> {
    const now = this.ctx.clock.now();
    await this.ctx.db.run('UPDATE devices SET revoked_at = ?, token_hash = ? WHERE id = ?', [
      iso(now),
      `revoked:${dev.id}:${randomToken(8)}`,
      dev.id,
    ]);
    for (const [h, id] of this.byToken) if (id === dev.id) this.byToken.delete(h);
    this.devices.delete(dev.id);
    this.ctx.hub.closeAgent(dev.id, 4001, 'unpaired', { t: 'error', code: 'unauthorized', message: 'device unpaired' });
    await this.ctx.commands.expireAllForDevice(dev.id, 'device unpaired');
    this.ctx.hub.broadcastDevice(dev.id, {
      t: 'status',
      device_id: dev.id,
      online: false,
      last_seen: isoOrNull(dev.lastSeen),
      connected_since: null,
    });
    this.ctx.log.info({ device_id: dev.id }, 'phone unpaired');
  }

  // --- live state -----------------------------------------------------------------------------

  touch(dev: DeviceRuntime): void {
    dev.lastSeen = this.ctx.clock.now();
    dev.dirty = true;
  }

  /**
   * Merges the groups of a stored telemetry message into the current state, but only groups whose
   * stored reading is older, so a backlog upload never overwrites fresher values (protocol 3.2).
   * Returns the groups that changed.
   */
  mergeTelemetry(dev: DeviceRuntime, msg: TelemetryMsg, tsMs: number): GroupKey[] {
    const changed: GroupKey[] = [];
    const m = msg as unknown as Record<string, unknown>;
    for (const g of GROUP_KEYS) {
      const v = m[g];
      if (!isPlainObject(v)) continue;
      const prev = dev.stateTs[g];
      if (prev != null && prev > tsMs) continue;
      (dev.state as Record<string, unknown>)[g] = v;
      dev.stateTs[g] = tsMs;
      changed.push(g);
    }
    if (changed.includes('device') && dev.state.device) {
      dev.info = { ...dev.state.device, device_id: dev.id };
    }
    if (changed.length) dev.dirty = true;
    return changed;
  }

  flushStmt(dev: DeviceRuntime): Stmt {
    const stateTs: Record<string, string> = {};
    for (const [k, v] of Object.entries(dev.stateTs)) if (v != null) stateTs[k] = iso(v);
    return {
      sql: `UPDATE devices SET last_seen = ?, connected_since = ?, info = ?, state = ?, state_ts = ?, outbox = ?,
              alert_state = ?, offline_alert_open = ?, offline_since = ?, last_back_online = ?
            WHERE id = ?`,
      args: [
        isoOrNull(dev.lastSeen),
        isoOrNull(dev.connectedSince),
        dev.info ? JSON.stringify(dev.info) : null,
        JSON.stringify(dev.state),
        JSON.stringify(stateTs),
        dev.outbox,
        JSON.stringify(dev.alertState),
        dev.offlineAlertOpen ? 1 : 0,
        isoOrNull(dev.offlineSince),
        isoOrNull(dev.lastBackOnline),
        dev.id,
      ],
    };
  }

  /** Persists every changed device in one round trip (every 30 s and at shutdown). */
  async flush(): Promise<void> {
    const dirty = this.all().filter((d) => d.dirty);
    if (dirty.length === 0) return;
    for (const d of dirty) d.dirty = false;
    try {
      await this.ctx.db.batch(dirty.map((d) => this.flushStmt(d)));
    } catch (err) {
      for (const d of dirty) d.dirty = true;
      throw err;
    }
  }

  /** Immediate write for state that must survive a crash (offline alert open/closed). */
  async persistNow(dev: DeviceRuntime): Promise<void> {
    dev.dirty = false;
    await this.ctx.db.batch([this.flushStmt(dev)]);
  }
}
