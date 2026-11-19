import { PROTOCOL, type AgentToServer, type BatchMsg, type BufferedMsg } from '@hsm/shared';

type DistributiveOmit<T, K extends PropertyKey> = T extends unknown ? Omit<T, K> : never;

/** A buffered message before the outbox assigns its `seq`. */
export type OutboxInput = DistributiveOmit<BufferedMsg, 'seq'>;

export interface OutboxOptions {
  /** Row cap (docs/protocol.md 3.2 rule 5). */
  cap?: number;
  /** Items per `batch` frame. */
  maxBatch?: number;
  /** Sent-but-unacked items allowed before the sender waits for an `ack`. */
  maxInflight?: number;
  /** Frame size budget; stays under PROTOCOL.max_message_bytes. */
  maxFrameBytes?: number;
  /** Continue numbering after this seq (persisted across runs). */
  lastSeq?: number;
  /** Restored unacked rows (--persist-outbox). */
  items?: BufferedMsg[];
}

/**
 * The Agent's outbox (docs/protocol.md 3.2): every buffered message gets the next `seq`, the
 * single sender drains in seq order, a backlog leaves in `batch` frames of at most 200 items,
 * an `ack {seq}` deletes everything up to seq, and unacked rows are re-sent after a reconnect.
 */
export class Outbox {
  private items: BufferedMsg[] = [];
  private seqCounter: number;
  /** Highest seq written to the current socket. */
  private sentUpTo = 0;
  private readonly cap: number;
  private readonly maxBatch: number;
  private readonly maxInflight: number;
  private readonly maxFrameBytes: number;
  /** Rows dropped because the cap was reached. */
  dropped = 0;

  constructor(opts: OutboxOptions = {}) {
    this.cap = opts.cap ?? 50_000;
    this.maxBatch = Math.max(1, Math.min(opts.maxBatch ?? PROTOCOL.max_batch_items, PROTOCOL.max_batch_items));
    this.maxInflight = Math.max(this.maxBatch, opts.maxInflight ?? this.maxBatch * 2);
    this.maxFrameBytes = Math.min(opts.maxFrameBytes ?? 480 * 1024, PROTOCOL.max_message_bytes);
    const restored = (opts.items ?? []).filter((m) => typeof m?.seq === 'number').sort((a, b) => a.seq - b.seq);
    this.items = restored;
    const maxRestored = restored.length ? (restored[restored.length - 1] as BufferedMsg).seq : 0;
    this.seqCounter = Math.max(opts.lastSeq ?? 0, maxRestored);
  }

  get size(): number {
    return this.items.length;
  }

  get lastSeq(): number {
    return this.seqCounter;
  }

  /** Items not yet written to the current socket. */
  get unsent(): number {
    return this.items.filter((m) => m.seq > this.sentUpTo).length;
  }

  /** Snapshot of the rows in seq order (for persistence and tests). */
  peek(): readonly BufferedMsg[] {
    return this.items;
  }

  push(input: OutboxInput): BufferedMsg {
    const seq = ++this.seqCounter;
    // `t` first, then `seq`, then the rest: matches the fixtures' key order.
    const { t, ...rest } = input;
    const msg = { t, seq, ...rest } as BufferedMsg;
    this.items.push(msg);
    if (this.items.length > this.cap) this.enforceCap();
    return msg;
  }

  /** Server stored everything up to `seq`. Returns how many rows were deleted. */
  ack(seq: number): number {
    if (!Number.isFinite(seq)) return 0;
    // Never delete what this connection has not sent: a bogus ack must not lose data.
    const upTo = Math.min(seq, this.sentUpTo);
    let n = 0;
    while (n < this.items.length && (this.items[n] as BufferedMsg).seq <= upTo) n++;
    this.items.splice(0, n);
    return n;
  }

  /** New connection: everything still in the outbox is sent again. */
  resetSent(): void {
    this.sentUpTo = 0;
  }

  /**
   * Frames to write now, in seq order: a single message when only one is waiting, otherwise
   * `batch` frames. Stops when `maxInflight` items await an ack.
   */
  takeFrames(): AgentToServer[] {
    const frames: AgentToServer[] = [];
    let idx = this.items.findIndex((m) => m.seq > this.sentUpTo);
    if (idx < 0) return frames;
    let inflight = idx;
    while (idx < this.items.length && inflight < this.maxInflight) {
      const chunk: BufferedMsg[] = [];
      let bytes = 32;
      while (idx < this.items.length && chunk.length < this.maxBatch && inflight + chunk.length < this.maxInflight) {
        const item = this.items[idx] as BufferedMsg;
        const size = Buffer.byteLength(JSON.stringify(item), 'utf8') + 1;
        if (chunk.length > 0 && bytes + size > this.maxFrameBytes) break;
        chunk.push(item);
        bytes += size;
        idx++;
      }
      if (chunk.length === 0) break;
      const batch: BatchMsg = { t: 'batch', items: chunk };
      frames.push(chunk.length === 1 ? (chunk[0] as BufferedMsg) : batch);
      inflight += chunk.length;
      this.sentUpTo = (chunk[chunk.length - 1] as BufferedMsg).seq;
    }
    return frames;
  }

  /** Over the cap: drop the oldest fast telemetry first, then other telemetry; never events or reports. */
  private enforceCap(): void {
    while (this.items.length > this.cap) {
      let idx = this.items.findIndex((m) => m.t === 'telemetry' && m.tier === 'fast');
      if (idx < 0) idx = this.items.findIndex((m) => m.t === 'telemetry');
      if (idx < 0) return;
      this.items.splice(idx, 1);
      this.dropped++;
    }
  }
}
