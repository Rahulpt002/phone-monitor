import { describe, expect, it } from 'vitest';
import type { BatchMsg, BufferedMsg } from '@hsm/shared';
import { Outbox, type OutboxInput } from '../src/outbox.js';

const fast = (i: number): OutboxInput => ({ t: 'telemetry', ts: new Date(1_700_000_000_000 + i * 1000).toISOString(), tier: 'fast', net: { online: true } });
const event = (i: number): OutboxInput => ({
  t: 'event',
  event: { id: `e_${i}`, ts: new Date().toISOString(), kind: 'connectivity', severity: 'info', message: `#${i}` },
});

describe('Outbox', () => {
  it('assigns strictly increasing seq, continuing after a persisted last seq', () => {
    const ob = new Outbox({ lastSeq: 41 });
    const a = ob.push(fast(1));
    const b = ob.push(event(2));
    expect([a.seq, b.seq]).toEqual([42, 43]);
    expect(Object.keys(a).slice(0, 2)).toEqual(['t', 'seq']);
    expect(ob.lastSeq).toBe(43);
  });

  it('sends a lone message as itself and a backlog as one batch, in seq order', () => {
    const ob = new Outbox();
    ob.push(fast(1));
    const single = ob.takeFrames();
    expect(single).toHaveLength(1);
    expect(single[0]?.t).toBe('telemetry');
    expect(ob.takeFrames()).toEqual([]);

    ob.push(fast(2));
    ob.push(event(3));
    ob.push(fast(4));
    const frames = ob.takeFrames();
    expect(frames).toHaveLength(1);
    const batch = frames[0] as BatchMsg;
    expect(batch.t).toBe('batch');
    expect(batch.items.map((m) => m.seq)).toEqual([2, 3, 4]);
  });

  it('deletes everything up to the acked seq and re-sends the rest after a reconnect', () => {
    const ob = new Outbox();
    for (let i = 0; i < 5; i++) ob.push(fast(i));
    ob.takeFrames();
    expect(ob.ack(3)).toBe(3);
    expect(ob.size).toBe(2);
    expect(ob.takeFrames()).toEqual([]); // 4 and 5 are in flight on this connection
    ob.resetSent();
    const frames = ob.takeFrames();
    expect((frames[0] as BatchMsg).items.map((m) => m.seq)).toEqual([4, 5]);
    expect(ob.ack(5)).toBe(2);
    expect(ob.size).toBe(0);
  });

  it('never deletes rows that were not sent on this connection', () => {
    const ob = new Outbox();
    ob.push(fast(1));
    ob.push(fast(2));
    expect(ob.ack(10)).toBe(0);
    ob.takeFrames();
    expect(ob.ack(10)).toBe(2);
  });

  it('splits a backlog into batches of at most 200 and waits for acks beyond the in-flight window', () => {
    const ob = new Outbox();
    for (let i = 0; i < 450; i++) ob.push(fast(i));
    const first = ob.takeFrames() as BatchMsg[];
    expect(first.map((f) => f.items.length)).toEqual([200, 200]);
    expect(ob.takeFrames()).toEqual([]);
    ob.ack(200);
    const next = ob.takeFrames() as BatchMsg[];
    expect(next.map((f) => f.items.length)).toEqual([50]);
    expect(next[0]?.items[0]?.seq).toBe(401);
  });

  it('keeps frames under the byte budget', () => {
    const ob = new Outbox({ maxFrameBytes: 600 });
    for (let i = 0; i < 10; i++) ob.push(fast(i));
    const frames = ob.takeFrames();
    expect(frames.length).toBeGreaterThan(1);
    for (const f of frames) expect(Buffer.byteLength(JSON.stringify(f))).toBeLessThanOrEqual(600 + 200);
    const seqs = frames.flatMap((f) => (f.t === 'batch' ? f.items.map((m) => m.seq) : [(f as BufferedMsg).seq]));
    expect(seqs).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
  });

  it('drops the oldest fast telemetry first when full, never events', () => {
    const ob = new Outbox({ cap: 4 });
    ob.push(event(1));
    ob.push(fast(2));
    ob.push(fast(3));
    ob.push(event(4));
    ob.push(event(5)); // over the cap: drops seq 2
    expect(ob.peek().map((m) => m.seq)).toEqual([1, 3, 4, 5]);
    ob.push(event(6)); // drops seq 3
    ob.push(event(7)); // nothing droppable left: events are kept
    expect(ob.peek().map((m) => m.seq)).toEqual([1, 4, 5, 6, 7]);
    expect(ob.dropped).toBe(2);
  });

  it('restores persisted rows in seq order', () => {
    const items = [
      { t: 'event', seq: 9, event: { id: 'e_9', ts: '', kind: 'boot', severity: 'info', message: '' } },
      { t: 'event', seq: 7, event: { id: 'e_7', ts: '', kind: 'boot', severity: 'info', message: '' } },
    ] as BufferedMsg[];
    const ob = new Outbox({ items, lastSeq: 3 });
    expect(ob.peek().map((m) => m.seq)).toEqual([7, 9]);
    expect(ob.push(fast(1)).seq).toBe(10);
  });
});
