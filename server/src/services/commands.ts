import {
  COMMANDS,
  PROTOCOL,
  type CmdMsg,
  type CommandName,
  type CommandRecord,
  type CommandStatus,
  type RevertConfirm,
  type SendCommandRequest,
} from '@hsm/shared';
import { z } from 'zod';
import type { AuthInfo } from '../auth/service.js';
import type { AppContext } from '../context.js';
import type { Stmt } from '../db/client.js';
import { ApiHttpError, badRequest, conflict, forbidden, notFound, parseOrThrow } from '../errors.js';
import { iso, isNum, parseTs, randomId, safeJsonParse, truncateUtf8, type Plain } from '../util.js';
import type { DeviceRuntime } from './devices.js';
import type { IngestBatch } from './ingest.js';

const int = (min: number, max: number) => z.number().int().min(min).max(max);
const bool = z.boolean();
const pkg = z
  .string()
  .max(255)
  .regex(/^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/, 'must be an Android package name like com.example.app');
const spotId = z.string().min(1).max(100);

/** Server-side argument validation, matching the comments in shared/src/commands.ts. */
export const COMMAND_ARGS: Record<CommandName, z.ZodType<Record<string, unknown>>> = {
  mobile_data: z.object({ enabled: bool }),
  data_cycle: z.object({ off_seconds: int(1, 600).optional() }),
  airplane_mode: z.object({ enabled: bool }),
  airplane_cycle: z.object({ off_seconds: int(1, 600).optional() }),
  usb_tethering: z.object({ enabled: bool, function: z.enum(['rndis', 'ncm']).optional() }),
  hotspot: z.object({
    enabled: bool,
    ssid: z.string().min(1).max(32).optional(),
    password: z.string().min(8).max(63).optional(),
  }),
  wifi: z.object({ enabled: bool }),
  bluetooth: z.object({ enabled: bool }),
  network_mode: z.object({ mode: z.enum(['auto', 'nr_lte', 'lte_only', 'lte_wcdma', 'wcdma_only']) }),
  reboot: z.object({}),
  power_off: z.object({}),
  screen: z.object({ on: bool }),
  brightness: z.object({ level: int(0, 255) }),
  charging: z.object({ enabled: bool }),
  charge_limit: z.object({ percent: int(20, 100) }),
  app_list: z.object({}),
  app_force_stop: z.object({ package: pkg }),
  app_disable: z.object({ package: pkg }),
  app_enable: z.object({ package: pkg }),
  process_list: z.object({}),
  send_sms: z.object({
    to: z.string().regex(/^\+?[0-9A-Za-z*#-]{2,24}$/, 'must be a phone number or short code'),
    body: z.string().min(1).max(1600),
  }),
  ussd: z.object({ code: z.string().regex(/^[*#0-9]{2,40}$/, 'must look like *123#') }),
  shell: z.object({ cmd: z.string().min(1).max(8000), timeout_s: int(1, 600).optional() }),
  refresh: z.object({}),
  capability_probe: z.object({}),
  watchdog_simulate: z.object({ scenario: z.literal('internet_down'), duration_s: int(10, 3600) }),
  speed_test: z.object({ spot_id: spotId.optional() }),
  finder_spot_test: z.object({
    name: z.string().min(1).max(100),
    orientation: z.string().max(50).optional(),
    duration_s: int(5, 600).optional(),
  }),
  finder_set_baseline: z.object({ spot_id: spotId }),
  finder_delete_spot: z.object({ spot_id: spotId }),
};

const SendSchema = z.object({
  device_id: z.string().max(64).optional(),
  name: z.string().max(64),
  args: z.record(z.string(), z.unknown()).optional(),
  confirmed: z.boolean().optional(),
  password: z.string().max(256).optional(),
});

const ACTIVE: CommandStatus[] = ['queued', 'sent', 'acked', 'revert_armed'];
/** 'sent' with no cmd_ack for expiry_s + this long is shown as expired (late reports still apply). */
export const SENT_NO_ACK_MS = 10 * 60_000;
const SOFT_EXPIRED_KEEP_MS = 24 * 3_600_000;
export const COMMAND_RETENTION_MS = 365 * 86_400_000;

/**
 * Which statuses may move to `next` (docs/protocol.md 3.2: a report never moves a command
 * backwards). A command marked expired after it was delivered can still receive its late reports.
 */
export function allowedFrom(next: CommandStatus, delivered: boolean): CommandStatus[] {
  const soft: CommandStatus[] = delivered ? ['expired'] : [];
  switch (next) {
    case 'sent':
      return ['queued'];
    case 'expired':
      return ['queued', 'sent'];
    case 'acked':
    case 'rejected':
      return ['queued', 'sent', ...soft];
    case 'done':
    case 'failed':
    case 'revert_armed':
      return ['queued', 'sent', 'acked', ...soft];
    case 'kept':
    case 'reverted':
      return ['queued', 'sent', 'acked', 'done', 'failed', 'revert_armed', ...soft];
    default:
      return [];
  }
}

export function redactCommandForViewer(rec: CommandRecord): CommandRecord {
  const spec = COMMANDS[rec.name];
  if (rec.name === 'shell' || spec?.group === 'messaging') return { ...rec, args: {}, output: null };
  return rec;
}

interface Tracked {
  rec: CommandRecord;
  createdMs: number;
  revertAfterS: number | null;
}

function rowToRecord(r: Plain): CommandRecord {
  return {
    id: String(r.id),
    device_id: String(r.device_id),
    name: String(r.name) as CommandName,
    args: safeJsonParse<Record<string, unknown>>(r.args, {}),
    source: String(r.source) as CommandRecord['source'],
    user: (r.user as string | null) ?? null,
    status: String(r.status) as CommandStatus,
    created_at: String(r.created_at),
    delivered_at: (r.delivered_at as string | null) ?? null,
    acked_at: (r.acked_at as string | null) ?? null,
    finished_at: (r.finished_at as string | null) ?? null,
    ok: r.ok == null ? null : Number(r.ok) === 1,
    output: (r.output as string | null) ?? null,
    error: (r.error as string | null) ?? null,
    duration_ms: r.duration_ms == null ? null : Number(r.duration_ms),
    revert_confirm: (r.revert_confirm as RevertConfirm | null) ?? null,
    revert_deadline: (r.revert_deadline as string | null) ?? null,
  };
}

/**
 * Command pipeline (plan 6.3/6.4, docs/protocol.md 3.5): validate, audit, queue, deliver,
 * follow the Agent's reports, expire what was never delivered.
 */
export class CommandService {
  /** Commands that can still change (plus recently soft-expired ones). */
  private active = new Map<string, Tracked>();

  constructor(private ctx: AppContext) {}

  async load(): Promise<void> {
    const since = this.ctx.clock.now() - SOFT_EXPIRED_KEEP_MS;
    const rows = await this.ctx.db.all(
      `SELECT * FROM commands WHERE status IN ('queued', 'sent', 'acked', 'revert_armed')
         OR (status = 'expired' AND delivered_at IS NOT NULL AND created_ms > ?)`,
      [since],
    );
    this.active.clear();
    for (const r of rows) {
      this.active.set(String(r.id), {
        rec: rowToRecord(r),
        createdMs: Number(r.created_ms),
        revertAfterS: r.revert_after_s == null ? null : Number(r.revert_after_s),
      });
    }
  }

  get(id: string): CommandRecord | undefined {
    return this.active.get(id)?.rec;
  }

  pendingReverts(deviceId: string): CommandRecord[] {
    return [...this.active.values()]
      .filter((t) => t.rec.device_id === deviceId && t.rec.status === 'revert_armed')
      .map((t) => t.rec);
  }

  private updateStmt(rec: CommandRecord, from: CommandStatus[]): Stmt {
    return {
      sql: `UPDATE commands SET status = ?, delivered_at = ?, acked_at = ?, finished_at = ?, ok = ?, output = ?, error = ?,
              duration_ms = ?, revert_deadline = ?, updated_ms = ?
            WHERE id = ? AND status IN (${from.map(() => '?').join(', ')})`,
      args: [
        rec.status,
        rec.delivered_at ?? null,
        rec.acked_at ?? null,
        rec.finished_at ?? null,
        rec.ok == null ? null : rec.ok ? 1 : 0,
        rec.output ?? null,
        rec.error ?? null,
        rec.duration_ms ?? null,
        rec.revert_deadline ?? null,
        this.ctx.clock.now(),
        rec.id,
        ...from,
      ],
    };
  }

  private broadcast(rec: CommandRecord): void {
    this.ctx.hub.broadcastDevice(
      rec.device_id,
      { t: 'command', command: rec },
      { viewerMsg: { t: 'command', command: redactCommandForViewer(rec) } },
    );
  }

  /** Applies a committed change to memory and tells the Dashboards. */
  private commit(rec: CommandRecord): void {
    const t = this.active.get(rec.id);
    const keep = ACTIVE.includes(rec.status) || (rec.status === 'expired' && rec.delivered_at != null);
    if (t && keep) t.rec = rec;
    else if (t) this.active.delete(rec.id);
    this.broadcast(rec);
  }

  /** Memory first, DB write in the background (guarded so it can never move a row backwards). */
  private transitionNow(t: Tracked, next: CommandRecord): void {
    const from = allowedFrom(next.status, t.rec.delivered_at != null);
    if (!from.includes(t.rec.status)) return;
    this.ctx.db
      .batch([this.updateStmt(next, from)])
      .catch((err) => this.ctx.log.error({ err, command_id: next.id }, 'failed to persist command status'));
    this.commit(next);
  }

  async create(auth: AuthInfo, body: unknown, ip: string): Promise<CommandRecord> {
    const req = parseOrThrow(SendSchema, body, 'command') as SendCommandRequest;
    const user = auth.user;
    // Viewers never send commands (plan section 9); the route guard already enforces admin + TOTP.
    if (user.role !== 'admin') throw forbidden('Viewers cannot send commands');
    const spec = COMMANDS[req.name as CommandName];
    if (!spec) throw badRequest(`Unknown command "${req.name}"`);
    if (spec.admin_only && user.role !== 'admin') throw forbidden('Only admins can send this command');
    const dev = this.ctx.devices.resolve(req.device_id);
    if (spec.confirm && req.confirmed !== true) {
      throw new ApiHttpError(400, 'confirmation_required', `"${spec.label}" needs a second confirmation`);
    }
    if (spec.reauth) await this.ctx.auth.reauth(user, req.password, ip);
    if (spec.name === 'power_off' && !dev.settings.commands.allow_power_off) {
      throw forbidden('Power off is blocked: a phone that is off needs a hand to switch it on (settings.commands.allow_power_off)');
    }
    const args = parseOrThrow(COMMAND_ARGS[spec.name], req.args ?? {}, `arguments for ${spec.name}`);

    const now = this.ctx.clock.now();
    const revertConfirm = spec.revertFor(args);
    const revertAfterS = revertConfirm ? dev.settings.commands.revert_after_s : null;
    const rec: CommandRecord = {
      id: randomId('c_', 8),
      device_id: dev.id,
      name: spec.name,
      args,
      source: 'dashboard',
      user: user.username,
      status: 'queued',
      created_at: iso(now),
      delivered_at: null,
      acked_at: null,
      finished_at: null,
      ok: null,
      output: null,
      error: null,
      duration_ms: null,
      revert_confirm: revertConfirm,
      revert_deadline: null,
    };
    await this.ctx.db.run(
      `INSERT INTO commands (id, device_id, name, args, source, user, status, created_at, created_ms, revert_after_s,
         revert_confirm, updated_ms)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      [rec.id, dev.id, rec.name, JSON.stringify(args), rec.source, rec.user, rec.status, rec.created_at, now, revertAfterS, revertConfirm, now],
    );
    const tracked: Tracked = { rec, createdMs: now, revertAfterS };
    this.active.set(rec.id, tracked);
    this.broadcast(rec);
    this.ctx.log.info({ command_id: rec.id, name: rec.name, user: rec.user, device_id: dev.id }, 'command queued');

    if (spec.name === 'charge_limit' && typeof args.percent === 'number') {
      // The limit lives in settings too, so the watchdog keeps it after a restart (commands.ts).
      await this.ctx.settings.update(dev, { watchdog: { charge_limit_pct: args.percent } });
    }
    this.deliver(tracked);
    return this.active.get(rec.id)?.rec ?? rec;
  }

  private deliver(t: Tracked): boolean {
    const rec = t.rec;
    const msg: CmdMsg = {
      t: 'cmd',
      id: rec.id,
      name: rec.name,
      args: rec.args,
      sent_at: rec.created_at,
      user: rec.user,
      revert_after_s: t.revertAfterS,
      revert_confirm: rec.revert_confirm ?? null,
    };
    if (!this.ctx.hub.sendToAgent(rec.device_id, msg)) return false;
    this.transitionNow(t, { ...rec, status: 'sent', delivered_at: iso(this.ctx.clock.now()) });
    return true;
  }

  /** On Agent (re)connect: expire stale queued commands, send the rest oldest first. */
  async deliverQueued(deviceId: string): Promise<void> {
    await this.expireDue();
    const queued = [...this.active.values()]
      .filter((t) => t.rec.device_id === deviceId && t.rec.status === 'queued')
      .sort((a, b) => a.createdMs - b.createdMs);
    for (const t of queued) if (!this.deliver(t)) break;
  }

  /** Runs every few seconds. */
  async expireDue(): Promise<number> {
    const now = this.ctx.clock.now();
    const stmts: Stmt[] = [];
    const changed: CommandRecord[] = [];
    for (const t of [...this.active.values()]) {
      const rec = t.rec;
      const dev = this.ctx.devices.get(rec.device_id);
      const expiryMs = (dev?.settings.commands.expiry_s ?? 60) * 1000;
      let next: CommandRecord | null = null;
      if (rec.status === 'queued' && now - t.createdMs >= expiryMs) {
        next = { ...rec, status: 'expired', finished_at: iso(now), ok: false, error: `Not delivered within ${expiryMs / 1000} s (phone offline)` };
      } else if (rec.status === 'sent') {
        const delivered = parseTs(rec.delivered_at) ?? t.createdMs;
        if (now - delivered >= expiryMs + SENT_NO_ACK_MS) {
          next = { ...rec, status: 'expired', finished_at: iso(now), ok: false, error: 'No acknowledgement from the Agent' };
        }
      } else if (rec.status === 'expired' && now - t.createdMs > SOFT_EXPIRED_KEEP_MS) {
        this.active.delete(rec.id);
      }
      if (next) {
        stmts.push(this.updateStmt(next, allowedFrom('expired', rec.delivered_at != null)));
        changed.push(next);
      }
    }
    if (stmts.length === 0) return 0;
    await this.ctx.db.batch(stmts);
    for (const rec of changed) this.commit(rec);
    return changed.length;
  }

  async expireAllForDevice(deviceId: string, reason: string): Promise<void> {
    const now = this.ctx.clock.now();
    const stmts: Stmt[] = [];
    for (const t of [...this.active.values()]) {
      if (t.rec.device_id !== deviceId) continue;
      if (t.rec.status === 'queued') {
        const next: CommandRecord = { ...t.rec, status: 'expired', finished_at: iso(now), ok: false, error: reason };
        stmts.push(this.updateStmt(next, ['queued']));
      }
      this.active.delete(t.rec.id);
    }
    await this.ctx.db.batch(stmts);
  }

  /** cmd_ack / cmd_result / cmd_revert from the Agent (buffered, possibly repeated). */
  planReport(batch: IngestBatch, dev: DeviceRuntime, item: Plain): boolean {
    const id = item.id;
    if (typeof id !== 'string' || !id) return false;
    const cur = batch.commands.get(id) ?? this.active.get(id)?.rec;
    // Unknown or already final: nothing to change, but the report is still acknowledged.
    if (!cur || cur.device_id !== dev.id) return true;
    const ts = iso(parseTs(item.ts) ?? this.ctx.clock.now());
    let next: CommandRecord;
    switch (item.t) {
      case 'cmd_ack': {
        const accepted = item.accepted === true;
        next = accepted
          ? { ...cur, status: 'acked', acked_at: ts }
          : {
              ...cur,
              status: 'rejected',
              acked_at: ts,
              finished_at: ts,
              ok: false,
              error: typeof item.reason === 'string' ? item.reason.slice(0, 64) : 'rejected',
            };
        break;
      }
      case 'cmd_result': {
        const ok = item.ok === true;
        const deadline = parseTs(item.revert_deadline);
        next = {
          ...cur,
          status: !ok ? 'failed' : deadline != null ? 'revert_armed' : 'done',
          ok,
          acked_at: cur.acked_at ?? ts,
          finished_at: ts,
          output: typeof item.output === 'string' ? truncateUtf8(item.output, PROTOCOL.max_output_bytes) : '',
          error: typeof item.error === 'string' && item.error ? item.error.slice(0, 2000) : null,
          duration_ms: isNum(item.duration_ms) ? Math.round(item.duration_ms) : null,
          revert_deadline: ok && deadline != null ? iso(deadline) : null,
        };
        break;
      }
      case 'cmd_revert': {
        if (item.state !== 'kept' && item.state !== 'reverted') return false;
        const detail = typeof item.detail === 'string' && item.detail ? item.detail.slice(0, 500) : null;
        next = {
          ...cur,
          status: item.state,
          error: item.state === 'reverted' ? (detail ?? 'Auto-reverted by the Agent') : cur.error ?? null,
        };
        break;
      }
      default:
        return false;
    }
    const from = allowedFrom(next.status, cur.delivered_at != null);
    if (!from.includes(cur.status)) return true;
    batch.commands.set(id, next);
    batch.add(this.updateStmt(next, from));
    batch.after(() => this.commit(next));
    return true;
  }

  /** POST /commands/:id/keep: confirm a `dashboard` auto-revert (protocol 3.5). */
  async keep(id: string): Promise<CommandRecord> {
    const t = this.active.get(id);
    if (!t) {
      const row = await this.ctx.db.get('SELECT status FROM commands WHERE id = ?', [id]);
      if (!row) throw notFound('Command not found');
      throw conflict(`Command is ${String(row.status)}, not waiting for confirmation`);
    }
    if (t.rec.status !== 'revert_armed' || t.rec.revert_confirm !== 'dashboard') {
      throw conflict(`Command is ${t.rec.status}, not waiting for a Keep confirmation`);
    }
    if (!this.ctx.hub.sendToAgent(t.rec.device_id, { t: 'cmd_keep', id })) {
      throw new ApiHttpError(409, 'device_offline', 'The phone is offline; it will revert the change at the deadline');
    }
    return t.rec;
  }

  async list(dev: DeviceRuntime, limit: number, before: number | null, viewer: boolean): Promise<CommandRecord[]> {
    const rows = await this.ctx.db.all(
      `SELECT * FROM commands WHERE device_id = ? ${before != null ? 'AND created_ms < ?' : ''} ORDER BY created_ms DESC LIMIT ?`,
      before != null ? [dev.id, before, limit] : [dev.id, limit],
    );
    return rows.map((r) => {
      // Memory is ahead of the background status writes.
      const rec = this.active.get(String(r.id))?.rec ?? rowToRecord(r);
      return viewer ? redactCommandForViewer(rec) : rec;
    });
  }

  async retention(now: number): Promise<void> {
    await this.ctx.db.run('DELETE FROM commands WHERE created_ms < ?', [now - COMMAND_RETENTION_MS]);
  }
}
