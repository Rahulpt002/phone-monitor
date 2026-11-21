import { SCREEN_FRAME_CONT, SCREEN_FRAME_START } from '@hsm/shared';
import { describe, expect, it } from 'vitest';
import { FrameAssembler, avcCodecString, megabytesPerHour, pointerToVideo } from '../src/lib/screen/stream';
import { NAV, bottomTabs, navFor } from '../src/components/layout/nav';

function start(flags: { config?: boolean; key?: boolean }, pts: number, size: number, chunk: number[]): Uint8Array {
  const b = new Uint8Array(13 + chunk.length);
  b[0] = SCREEN_FRAME_START;
  const v = new DataView(b.buffer);
  let pf = BigInt(pts);
  if (flags.config) pf |= 1n << 62n;
  if (flags.key) pf |= 1n << 61n;
  v.setBigUint64(1, pf, false);
  v.setUint32(9, size, false);
  b.set(chunk, 13);
  return b;
}
const cont = (chunk: number[]) => new Uint8Array([SCREEN_FRAME_CONT, ...chunk]);

describe('remote screen stream', () => {
  it('joins split packets and keeps the flags', () => {
    const a = new FrameAssembler();
    expect(a.push(start({ key: true }, 777, 5, [1, 2]))).toBeNull();
    expect(a.push(cont([3, 4]))).toBeNull();
    const p = a.push(cont([5]));
    expect(p).toMatchObject({ key: true, config: false, ptsUs: 777 });
    expect([...p!.data]).toEqual([1, 2, 3, 4, 5]);

    const single = a.push(start({ config: true }, 0, 2, [9, 8]));
    expect(single).toMatchObject({ config: true, key: false });
    expect([...single!.data]).toEqual([9, 8]);
  });

  it('drops a continuation without a start, and an overlong packet', () => {
    const a = new FrameAssembler();
    expect(a.push(cont([1, 2]))).toBeNull();
    expect(a.push(start({}, 1, 2, [1]))).toBeNull();
    expect(a.push(cont([2, 3, 4]))).toBeNull(); // more than announced: dropped
    expect(a.push(cont([5]))).toBeNull(); // nothing pending any more
    expect(a.push(new Uint8Array([7, 1, 2]))).toBeNull(); // unknown kind
  });

  it('reads the codec string from the SPS', () => {
    // 00 00 00 01 | 67 (SPS) 64 00 1f ... | 00 00 00 01 | 68 (PPS)
    const config = new Uint8Array([0, 0, 0, 1, 0x67, 0x64, 0x00, 0x1f, 0xac, 0, 0, 0, 1, 0x68, 0xee]);
    expect(avcCodecString(config)).toBe('avc1.64001f');
    expect(avcCodecString(new Uint8Array([0, 0, 1, 0x68, 1, 2, 3]))).toBeNull();
  });

  it('maps pointers into the letterboxed picture', () => {
    const box = { left: 10, top: 20, width: 200, height: 200 };
    // 100x200 video in a 200x200 box: 100 px wide picture centred, bars of 50 px each side.
    expect(pointerToVideo(110, 120, box, 100, 200)).toEqual({ x: 0.5, y: 0.5 });
    expect(pointerToVideo(60, 20, box, 100, 200)).toEqual({ x: 0, y: 0 });
    expect(pointerToVideo(30, 120, box, 100, 200)).toBeNull(); // on the left bar
    expect(pointerToVideo(110, 120, box, 0, 0)).toBeNull();
  });

  it('estimates data per hour', () => {
    expect(megabytesPerHour(800_000)).toBe(360);
    expect(megabytesPerHour(2_000_000)).toBe(900);
  });

  it('is in the admin navigation only', () => {
    expect(NAV.find((n) => n.to === '/screen')?.adminOnly).toBe(true);
    expect(navFor(false).some((n) => n.to === '/screen')).toBe(false);
    expect(bottomTabs(true).map((n) => n.to)).toContain('/screen');
    expect(bottomTabs(false).map((n) => n.to)).not.toContain('/screen');
  });
});
