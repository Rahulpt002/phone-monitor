import {
  METRICS,
  METRIC_BY_KEY,
  PROTOCOL,
  type DashTelemetryMsg,
  type TelemetryHistoryResponse,
  type TelemetryMsg,
  type TelemetryTier,
} from '@hsm/shared';
import type { AppContext } from '../context.js';
import { badRequest } from '../errors.js';
import { iso, isNum, isPlainObject, parseTs, type Plain } from '../util.js';
import type { DeviceRuntime } from './devices.js';
import type { IngestBatch } from './ingest.js';

export const RAW_RETENTION_MS = 7 * 86_400_000;
export const ROLLUP_RETENTION_MS = 365 * 86_400_000;
/** A minute is rolled up this long after it ends, so readings still in flight are included. */
export const ROLLUP_GRACE_MS = 15_000;
export const AUTO_RAW_MAX_RANGE_MS = 6 * 3_600_000;
export const MAX_POINTS = 5000;
const MINUTE = 60_000;
/** Dirty minutes further apart than this are queried separately. */
const CLUSTER_GAP_MS = 60 * MINUTE;
const TIERS: TelemetryTier[] = ['fast', 'slow', 'static'];

for (const m of METRICS) {
  // Paths are interpolated into SQL below; they are constants from the shared contract.
  if (!/^\$[A-Za-z0-9_.[\]]+$/.test(m.path)) throw new Error(`unsafe metric path ${m.path}`);
}

/** Averages only numeric JSON values, so a stray string never counts as 0. */
const avgExpr = (path: string) =>
  `AVG(CASE WHEN json_type(data, '${path}') IN ('integer', 'real') THEN json_extract(data, '${path}') END)`;
const ROLLUP_SELECT = `SELECT (ts / ${MINUTE}) * ${MINUTE} AS minute, COUNT(*) AS n, ${METRICS.map(
  (m, i) => `${avgExpr(m.path)} AS m${i}`,
).join(', ')} FROM telemetry_raw WHERE device_id = ? AND ts >= ? AND ts < ? GROUP BY minute`;

export const minuteOf = (ts: number) => Math.floor(ts / MINUTE) * MINUTE;

/** Time-bucket average down to at most `max` points (nulls ignored; an all-null bucket stays null). */
export function downsample(points: [number, number | null][], from: number, to: number, max = MAX_POINTS): [number, number | null][] {
  if (points.length <= max) return points;
  const span = Math.max(1, to - from);
  const width = span / max;
  const out: [number, number | null][] = [];
  let bucket = -1;
  let bucketTs = 0;
  let sum = 0;
  let count = 0;
  const push = () => {
    if (bucket >= 0) out.push([bucketTs, count ? Math.round((sum / count) * 1000) / 1000 : null]);
  };
  for (const [ts, v] of points) {
    const b = Math.min(max - 1, Math.max(0, Math.floor((ts - from) / width)));
    if (b !== bucket) {
      push();
      bucket = b;
      bucketTs = ts;
      sum = 0;
      count = 0;
    }
    if (v != null) {
      sum += v;
      count++;
    }
  }
  push();
  return out;
}

/**
 * Telemetry storage: one raw row per message (deduplicated on device+tier+ts), a 1-minute
 * average row per device per minute, history queries for charts, and retention.
 */
export class TelemetryService {
  /** Minutes (epoch ms of the minute start) that need a (re)computed 1-minute row, per device. */
  private dirty = new Map<string, Set<number>>();

  constructor(private ctx: AppContext) {}

  plan(batch: IngestBatch, dev: DeviceRuntime, item: Plain): boolean {
    const tier = item.tier as TelemetryTier;
    if (!TIERS.includes(tier)) return false;
    const ts = parseTs(item.ts);
    if (ts == null) return false;
    const data: Plain = {};
    for (const [k, v] of Object.entries(item)) {
      if (k === 't' || k === 'seq' || k === 'ts' || k === 'tier') continue;
      if (isPlainObject(v)) data[k] = v;
    }
    const idx = batch.add({
      sql: 'INSERT OR IGNORE INTO telemetry_raw (device_id, ts, tier, data, received_at) VALUES (?, ?, ?, ?, ?)',
      args: [dev.id, ts, tier, JSON.stringify(data), this.ctx.clock.now()],
    });
    batch.after((results) => {
      if ((results[idx]?.rowsAffected ?? 0) === 0) return; // duplicate re-send
      this.markDirty(dev.id, ts);
      this.onStored(dev, item as unknown as TelemetryMsg, ts, data);
    });
    return true;
  }

  private onStored(dev: DeviceRuntime, msg: TelemetryMsg, ts: number, groups: Plain): void {
    const changed = this.ctx.devices.mergeTelemetry(dev, msg, ts);
    const live = this.ctx.clock.now() - ts <= PROTOCOL.live_window_s * 1000;
    if (!live) return;
    const relay = { ...groups, t: 'telemetry', device_id: dev.id, ts: iso(ts), tier: msg.tier } as DashTelemetryMsg;
    this.ctx.hub.broadcastDevice(dev.id, relay);
    if (changed.length) this.ctx.alerts.evaluateState(dev, changed);
  }

  markDirty(deviceId: string, ts: number): void {
    let set = this.dirty.get(deviceId);
    if (!set) {
      set = new Set();
      this.dirty.set(deviceId, set);
    }
    set.add(minuteOf(ts));
  }

  dirtyCount(): number {
    let n = 0;
    for (const s of this.dirty.values()) n += s.size;
    return n;
  }

  /**
   * Computes 1-minute rows for every dirty minute that has ended (plus the grace period), or for
   * all dirty minutes when `force` (shutdown). Backlog uploads re-dirty old minutes, which are
   * then recomputed from all their raw rows.
   */
  async rollupDirty(force = false): Promise<number> {
    const cutoff = force ? Number.POSITIVE_INFINITY : this.ctx.clock.now() - MINUTE - ROLLUP_GRACE_MS;
    let written = 0;
    for (const [deviceId, set] of [...this.dirty]) {
      const ready = [...set].filter((m) => m <= cutoff).sort((a, b) => a - b);
      if (ready.length === 0) continue;
      for (const m of ready) set.delete(m);
      try {
        written += await this.rollupMinutes(deviceId, ready);
      } catch (err) {
        for (const m of ready) set.add(m);
        throw err;
      }
      if (set.size === 0 && this.dirty.get(deviceId) === set) this.dirty.delete(deviceId);
    }
    return written;
  }

  private async rollupMinutes(deviceId: string, minutes: number[]): Promise<number> {
    const wanted = new Set(minutes);
    const clusters: [number, number][] = [];
    for (const m of minutes) {
      const last = clusters[clusters.length - 1];
      if (last && m - last[1] <= CLUSTER_GAP_MS) last[1] = m;
      else clusters.push([m, m]);
    }
    const upserts: { sql: string; args: (string | number)[] }[] = [];
    for (const [start, end] of clusters) {
      const rows = await this.ctx.db.all(ROLLUP_SELECT, [deviceId, start, end + MINUTE]);
      for (const r of rows) {
        const minute = Number(r.minute);
        if (!wanted.has(minute)) continue;
        const data: Record<string, number> = {};
        METRICS.forEach((m, i) => {
          const v = r[`m${i}`];
          if (isNum(v)) data[m.key] = Math.round(v * 10_000) / 10_000;
        });
        upserts.push({
          sql: `INSERT INTO telemetry_1m (device_id, minute, n, data) VALUES (?, ?, ?, ?)
                ON CONFLICT (device_id, minute) DO UPDATE SET n = excluded.n, data = excluded.data`,
          args: [deviceId, minute, Number(r.n), JSON.stringify(data)],
        });
      }
    }
    await this.ctx.db.batch(upserts);
    return upserts.length;
  }

  /**
   * After a crash the in-memory dirty set is gone; re-dirty recent minutes whose 1-minute row is
   * missing or was computed from a different number of raw rows.
   */
  async catchUp(windowMs = 6 * 3_600_000): Promise<number> {
    const since = minuteOf(this.ctx.clock.now() - windowMs);
    let marked = 0;
    for (const dev of this.ctx.devices.all()) {
      const raw = await this.ctx.db.all<{ minute: number; n: number }>(
        `SELECT (ts / ${MINUTE}) * ${MINUTE} AS minute, COUNT(*) AS n FROM telemetry_raw
         WHERE device_id = ? AND ts >= ? GROUP BY minute`,
        [dev.id, since],
      );
      if (raw.length === 0) continue;
      const rolled = await this.ctx.db.all<{ minute: number; n: number }>(
        'SELECT minute, n FROM telemetry_1m WHERE device_id = ? AND minute >= ?',
        [dev.id, since],
      );
      const have = new Map(rolled.map((r) => [Number(r.minute), Number(r.n)]));
      for (const r of raw) {
        if (have.get(Number(r.minute)) !== Number(r.n)) {
          this.markDirty(dev.id, Number(r.minute));
          marked++;
        }
      }
    }
    return marked;
  }

  async history(
    dev: DeviceRuntime,
    metricKey: string,
    from: number,
    to: number,
    res: 'auto' | 'raw' | '1m',
  ): Promise<TelemetryHistoryResponse> {
    const metric = METRIC_BY_KEY[metricKey];
    if (!metric) throw badRequest(`Unknown metric "${metricKey}"`);
    if (!(to > from)) throw badRequest('"to" must be after "from"');
    const useRaw = res === 'raw' || (res === 'auto' && to - from <= AUTO_RAW_MAX_RANGE_MS);
    let points: [number, number | null][];
    if (useRaw) {
      const rows = await this.ctx.db.all<{ ts: number; v: unknown }>(
        `SELECT ts, CASE WHEN json_type(data, ?) IN ('integer', 'real') THEN json_extract(data, ?) END AS v
         FROM telemetry_raw WHERE device_id = ? AND tier = ? AND ts >= ? AND ts <= ? ORDER BY ts LIMIT 500000`,
        [metric.path, metric.path, dev.id, metric.tier, from, to],
      );
      points = rows.map((r) => [Number(r.ts), isNum(r.v) ? r.v : null]);
    } else {
      const keyPath = `$."${metric.key}"`;
      const rows = await this.ctx.db.all<{ ts: number; v: unknown }>(
        `SELECT minute AS ts, json_extract(data, ?) AS v FROM telemetry_1m
         WHERE device_id = ? AND minute >= ? AND minute <= ? ORDER BY minute LIMIT 600000`,
        [keyPath, dev.id, minuteOf(from), to],
      );
      points = rows.map((r) => [Number(r.ts), isNum(r.v) ? r.v : null]);
    }
    return { metric: metric.key, res: useRaw ? 'raw' : '1m', points: downsample(points, from, to) };
  }

  async retention(now: number): Promise<void> {
    const ids = (await this.ctx.db.all<{ id: string }>('SELECT id FROM devices')).map((r) => String(r.id));
    const stmts = ids.flatMap((id) => [
      { sql: 'DELETE FROM telemetry_raw WHERE device_id = ? AND ts < ?', args: [id, now - RAW_RETENTION_MS] },
      { sql: 'DELETE FROM telemetry_1m WHERE device_id = ? AND minute < ?', args: [id, now - ROLLUP_RETENTION_MS] },
    ]);
    await this.ctx.db.batch(stmts);
  }
}
