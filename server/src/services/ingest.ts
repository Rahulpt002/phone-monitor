import type { ResultSet } from '@libsql/client';
import type { CommandRecord } from '@hsm/shared';
import type { AppContext } from '../context.js';
import type { Stmt } from '../db/client.js';
import { isPlainObject } from '../util.js';
import type { DeviceRuntime } from './devices.js';

export type AfterCommit = (results: ResultSet[]) => void | Promise<void>;

/**
 * One storage transaction for a group of buffered Agent messages (a single message or a `batch`
 * of up to 200). Services add statements and an after-commit step; everything is written in one
 * round trip, and in-memory state changes only after the write succeeded.
 */
export class IngestBatch {
  readonly stmts: Stmt[] = [];
  readonly afters: AfterCommit[] = [];
  /** Command records as they will be once this batch commits (later items see earlier changes). */
  readonly commands = new Map<string, CommandRecord>();

  add(stmt: Stmt): number {
    this.stmts.push(stmt);
    return this.stmts.length - 1;
  }

  after(fn: AfterCommit): void {
    this.afters.push(fn);
  }
}

const BUFFERED = new Set([
  'telemetry',
  'event',
  'finder_spot',
  'finder_spot_deleted',
  'sms',
  'cmd_ack',
  'cmd_result',
  'cmd_revert',
]);

export const isBufferedType = (t: unknown): boolean => typeof t === 'string' && BUFFERED.has(t);

export interface StoreResult {
  /** Highest seq stored (or skipped as unstorable); null when no item carried a seq. */
  ackSeq: number | null;
  stored: number;
  skipped: number;
}

/**
 * Stores buffered messages idempotently and returns the seq to acknowledge. Items that can never
 * be stored (malformed) are skipped but still acknowledged, otherwise the Agent would resend them
 * forever and its outbox would never drain. If the database write fails, nothing is acknowledged
 * and the Agent resends the whole group later.
 */
export async function storeBuffered(ctx: AppContext, dev: DeviceRuntime, items: unknown[]): Promise<StoreResult> {
  const batch = new IngestBatch();
  let ackSeq: number | null = null;
  let skipped = 0;
  for (const item of items) {
    if (!isPlainObject(item)) {
      skipped++;
      continue;
    }
    const seq = typeof item.seq === 'number' && Number.isInteger(item.seq) && item.seq > 0 ? item.seq : null;
    if (seq !== null) ackSeq = ackSeq === null ? seq : Math.max(ackSeq, seq);
    let ok = false;
    try {
      switch (item.t) {
        case 'telemetry':
          ok = ctx.telemetry.plan(batch, dev, item);
          break;
        case 'event':
          ok = ctx.records.planEvent(batch, dev, item);
          break;
        case 'sms':
          ok = ctx.records.planSms(batch, dev, item);
          break;
        case 'finder_spot':
          ok = ctx.finder.planUpsert(batch, dev, item);
          break;
        case 'finder_spot_deleted':
          ok = ctx.finder.planDelete(batch, dev, item);
          break;
        case 'cmd_ack':
        case 'cmd_result':
        case 'cmd_revert':
          ok = ctx.commands.planReport(batch, dev, item);
          break;
        default:
          // Unknown types inside a batch are ignored (forward compatibility), but acknowledged.
          ok = true;
      }
    } catch (err) {
      ctx.log.warn({ err, t: item.t, device_id: dev.id }, 'failed to plan buffered message');
    }
    if (!ok) skipped++;
  }

  const results = await ctx.db.batch(batch.stmts);
  for (const fn of batch.afters) {
    try {
      await fn(results);
    } catch (err) {
      ctx.log.error({ err, device_id: dev.id }, 'post-store step failed');
    }
  }
  return { ackSeq, stored: items.length - skipped, skipped };
}
