import type { ResultSet } from '@libsql/client';
import type { CommandRecord } from '@hsm/shared';
import type { AppContext } from '../context.js';
import type { Stmt } from '../db/client.js';
import { isPlainObject, type Plain } from '../util.js';
import type { DeviceRuntime } from './devices.js';

export type AfterCommit = (results: ResultSet[]) => void | Promise<void>;

interface Mark {
  stmts: number;
  afters: number;
  commands: Map<string, CommandRecord>;
}

/**
 * One storage transaction for a group of buffered Agent messages (a single message or a `batch`
 * of up to 200). Services add statements and an after-commit step; everything is written in one
 * round trip, and in-memory state changes only after the write succeeded.
 */
export class IngestBatch {
  readonly stmts: Stmt[] = [];
  readonly afters: AfterCommit[] = [];
  /** Command records as they will be once this batch commits (later items see earlier changes). */
  commands = new Map<string, CommandRecord>();

  add(stmt: Stmt): number {
    this.stmts.push(stmt);
    return this.stmts.length - 1;
  }

  after(fn: AfterCommit): void {
    this.afters.push(fn);
  }

  /** Remembers the current size so a failed item can be taken out again. */
  mark(): Mark {
    return { stmts: this.stmts.length, afters: this.afters.length, commands: new Map(this.commands) };
  }

  rollback(m: Mark): void {
    this.stmts.length = m.stmts;
    this.afters.length = m.afters;
    this.commands = m.commands;
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

const seqOf = (item: Plain): number | null =>
  typeof item.seq === 'number' && Number.isInteger(item.seq) && item.seq > 0 ? item.seq : null;

/**
 * Adds one item's statements to the batch. Returns false when the item is dropped (malformed,
 * semantically invalid, or its planner threw); a dropped item leaves nothing behind in the batch.
 * Items that are valid but change nothing (an unknown command id, a report that would move a
 * command backwards, an unknown type) return true without statements.
 */
function planItem(ctx: AppContext, batch: IngestBatch, dev: DeviceRuntime, item: Plain): boolean {
  const mark = batch.mark();
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
    ok = false;
  }
  if (!ok) batch.rollback(mark);
  return ok;
}

async function commit(ctx: AppContext, dev: DeviceRuntime, batch: IngestBatch): Promise<void> {
  const results = await ctx.db.batch(batch.stmts);
  for (const fn of batch.afters) {
    try {
      await fn(results);
    } catch (err) {
      ctx.log.error({ err, device_id: dev.id }, 'post-store step failed');
    }
  }
}

/**
 * Stores buffered messages idempotently and returns the seq to acknowledge (docs/protocol.md
 * 3.2). Every processed item is acknowledged, including the ones that are dropped: malformed or
 * semantically invalid items, reports for unknown commands, and items the database refuses.
 * Otherwise the Agent would resend them forever and its outbox would never drain.
 *
 * The group is written in one transaction. If that fails, the items are written one by one so a
 * single item the database rejects cannot block the rest; an item is dropped only while the
 * database still answers (a failed `SELECT 1` means an outage, so nothing is acknowledged and the
 * Agent resends the whole group later, which is safe because storage is idempotent).
 */
export async function storeBuffered(ctx: AppContext, dev: DeviceRuntime, items: unknown[]): Promise<StoreResult> {
  let ackSeq: number | null = null;
  let skipped = 0;
  const valid: Plain[] = [];
  for (const item of items) {
    if (!isPlainObject(item)) {
      skipped++;
      continue;
    }
    const seq = seqOf(item);
    if (seq !== null) ackSeq = ackSeq === null ? seq : Math.max(ackSeq, seq);
    valid.push(item);
  }

  const batch = new IngestBatch();
  let planSkipped = 0;
  for (const item of valid) if (!planItem(ctx, batch, dev, item)) planSkipped++;
  try {
    await commit(ctx, dev, batch);
    return { ackSeq, stored: items.length - skipped - planSkipped, skipped: skipped + planSkipped };
  } catch (err) {
    ctx.log.warn({ err, device_id: dev.id, items: valid.length }, 'group write failed; storing items one by one');
  }

  // Nothing of the group was written (one transaction) and no after-commit step ran, so every
  // item is planned again on its own, in order, against the committed in-memory state.
  for (const item of valid) {
    let stored = false;
    for (let attempt = 0; attempt < 2 && !stored; attempt++) {
      const one = new IngestBatch();
      if (!planItem(ctx, one, dev, item)) break;
      try {
        await commit(ctx, dev, one);
        stored = true;
      } catch (err) {
        if (!(await ctx.db.ping())) throw err; // database unreachable: acknowledge nothing
        if (attempt === 1) {
          ctx.log.error({ err, device_id: dev.id, t: item.t, seq: item.seq }, 'database rejected a buffered message; dropped');
        }
      }
    }
    if (!stored) skipped++;
  }
  return { ackSeq, stored: items.length - skipped, skipped };
}
