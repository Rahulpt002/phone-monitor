import type { AgentEvent, EventKind, Severity, SmsRecord, StoredEvent } from '@hsm/shared';
import type { AppContext } from '../context.js';
import { iso, isPlainObject, parseTs, safeJsonParse, type Plain } from '../util.js';
import type { DeviceRuntime } from './devices.js';
import type { IngestBatch } from './ingest.js';

const SEVERITIES: Severity[] = ['info', 'warning', 'critical'];
export const RECORD_RETENTION_MS = 365 * 86_400_000;

/** Viewers never read SMS (plan section 9): strip the text from SMS events. */
export function redactEventForViewer<T extends AgentEvent>(ev: T): T {
  if (ev.kind !== 'sms_received') return ev;
  return { ...ev, message: 'SMS received', data: {} };
}

function rowToEvent(r: Record<string, unknown>): StoredEvent {
  const data = safeJsonParse<Record<string, unknown> | null>(r.data, null);
  return {
    id: String(r.id),
    device_id: String(r.device_id),
    ts: iso(Number(r.ts)),
    kind: String(r.kind) as EventKind,
    severity: String(r.severity) as Severity,
    message: String(r.message),
    ...(data ? { data } : {}),
  };
}

/** Agent events (timeline) and SMS (Messages screen). */
export class RecordService {
  constructor(private ctx: AppContext) {}

  planEvent(batch: IngestBatch, dev: DeviceRuntime, item: Plain): boolean {
    const e = item.event;
    if (!isPlainObject(e)) return false;
    const id = typeof e.id === 'string' ? e.id : '';
    const ts = parseTs(e.ts);
    if (!id || id.length > 100 || ts == null || typeof e.kind !== 'string' || e.kind.length > 64) return false;
    const event: AgentEvent = {
      id,
      ts: iso(ts),
      kind: e.kind as EventKind,
      severity: SEVERITIES.includes(e.severity as Severity) ? (e.severity as Severity) : 'info',
      message: typeof e.message === 'string' ? e.message.slice(0, 2000) : '',
      ...(isPlainObject(e.data) ? { data: e.data } : {}),
    };
    const idx = batch.add({
      sql: `INSERT OR IGNORE INTO events (device_id, id, ts, kind, severity, message, data, received_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
      args: [
        dev.id,
        id,
        ts,
        event.kind,
        event.severity,
        event.message,
        event.data ? JSON.stringify(event.data) : null,
        this.ctx.clock.now(),
      ],
    });
    batch.after((results) => {
      if ((results[idx]?.rowsAffected ?? 0) === 0) {
        // Already stored. Usually a resend whose alert already went out, but it may be the resend of
        // a group whose commit response was lost before this step ran (F46): the alert service
        // checks whether this event's alert exists.
        this.ctx.alerts.onDuplicateEvent(dev, event, ts);
        return;
      }
      this.broadcastEvent(dev.id, event);
      this.ctx.alerts.onEvent(dev, event, ts);
    });
    return true;
  }

  broadcastEvent(deviceId: string, event: AgentEvent): void {
    this.ctx.hub.broadcastDevice(
      deviceId,
      { t: 'event', device_id: deviceId, event },
      { viewerMsg: { t: 'event', device_id: deviceId, event: redactEventForViewer(event) } },
    );
  }

  planSms(batch: IngestBatch, dev: DeviceRuntime, item: Plain): boolean {
    const s = item.sms;
    if (!isPlainObject(s)) return false;
    const at = parseTs(s.at);
    if (typeof s.id !== 'string' || !s.id || s.id.length > 100 || at == null) return false;
    const sms: SmsRecord = {
      id: s.id,
      direction: s.direction === 'out' ? 'out' : 'in',
      peer: typeof s.peer === 'string' ? s.peer.slice(0, 64) : '',
      body: typeof s.body === 'string' ? s.body.slice(0, 5000) : '',
      at: iso(at),
    };
    const idx = batch.add({
      sql: `INSERT OR IGNORE INTO sms (device_id, id, direction, peer, body, at, received_at) VALUES (?, ?, ?, ?, ?, ?, ?)`,
      args: [dev.id, sms.id, sms.direction, sms.peer, sms.body, at, this.ctx.clock.now()],
    });
    batch.after((results) => {
      if ((results[idx]?.rowsAffected ?? 0) === 0) return;
      this.ctx.hub.broadcastDevice(dev.id, { t: 'sms', device_id: dev.id, sms }, { adminOnly: true });
    });
    return true;
  }

  async listEvents(
    dev: DeviceRuntime,
    q: { from?: number | null; to?: number | null; kinds?: string[]; limit: number; before?: number | null },
  ): Promise<StoredEvent[]> {
    const where = ['device_id = ?'];
    const args: (string | number)[] = [dev.id];
    if (q.from != null) {
      where.push('ts >= ?');
      args.push(q.from);
    }
    if (q.to != null) {
      where.push('ts <= ?');
      args.push(q.to);
    }
    if (q.before != null) {
      where.push('ts < ?');
      args.push(q.before);
    }
    if (q.kinds && q.kinds.length) {
      where.push(`kind IN (${q.kinds.map(() => '?').join(', ')})`);
      args.push(...q.kinds);
    }
    args.push(q.limit);
    const rows = await this.ctx.db.all(
      `SELECT * FROM events WHERE ${where.join(' AND ')} ORDER BY ts DESC, received_at DESC LIMIT ?`,
      args,
    );
    return rows.map(rowToEvent);
  }

  /** Events kinds in a time window (phone time). */
  async eventsBetween(deviceId: string, from: number, to: number, kinds: string[], limit = 20): Promise<StoredEvent[]> {
    const rows = await this.ctx.db.all(
      `SELECT * FROM events WHERE device_id = ? AND ts >= ? AND ts <= ? AND kind IN (${kinds.map(() => '?').join(', ')})
       ORDER BY ts ASC LIMIT ?`,
      [deviceId, from, to, ...kinds, limit],
    );
    return rows.map(rowToEvent);
  }

  /**
   * Reason events for the "back online" alert (docs/protocol.md 6): only events that reached the
   * Server after the reconnect (`received_at`), so events delivered live before the outage are never
   * named. Their phone time must fall in the outage; kinds in `lookbackKinds` (things that can
   * cause a blackout, such as a watchdog reboot) may be up to `lookbackMs` older. The newest `limit`
   * are returned, oldest first.
   */
  async backOnlineReasons(
    deviceId: string,
    q: { receivedFrom: number; offlineFrom: number; kinds: readonly string[]; lookbackKinds: readonly string[]; lookbackMs: number; limit: number },
  ): Promise<StoredEvent[]> {
    const ph = (a: readonly unknown[]) => a.map(() => '?').join(', ');
    const rows = await this.ctx.db.all(
      `SELECT * FROM events
       WHERE device_id = ? AND received_at >= ? AND kind IN (${ph(q.kinds)})
         AND ts >= CASE WHEN kind IN (${ph(q.lookbackKinds)}) THEN ? ELSE ? END
       ORDER BY ts DESC, received_at DESC LIMIT ?`,
      [deviceId, q.receivedFrom, ...q.kinds, ...q.lookbackKinds, q.offlineFrom - q.lookbackMs, q.offlineFrom, q.limit],
    );
    return rows.map(rowToEvent).reverse();
  }

  async listSms(dev: DeviceRuntime, limit: number, before: number | null): Promise<SmsRecord[]> {
    const rows = await this.ctx.db.all(
      `SELECT id, direction, peer, body, at FROM sms WHERE device_id = ? ${before != null ? 'AND at < ?' : ''}
       ORDER BY at DESC LIMIT ?`,
      before != null ? [dev.id, before, limit] : [dev.id, limit],
    );
    return rows.map((r) => ({
      id: String(r.id),
      direction: r.direction === 'out' ? 'out' : 'in',
      peer: String(r.peer),
      body: String(r.body),
      at: iso(Number(r.at)),
    }));
  }

  async retention(now: number): Promise<void> {
    const ids = (await this.ctx.db.all<{ id: string }>('SELECT id FROM devices')).map((r) => String(r.id));
    const cutoff = now - RECORD_RETENTION_MS;
    await this.ctx.db.batch(
      ids.flatMap((id) => [
        { sql: 'DELETE FROM events WHERE device_id = ? AND ts < ?', args: [id, cutoff] },
        { sql: 'DELETE FROM sms WHERE device_id = ? AND at < ?', args: [id, cutoff] },
      ]),
    );
  }
}
