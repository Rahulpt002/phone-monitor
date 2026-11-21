/**
 * Pure helpers for the remote screen (shared/src/screen.ts "Binary video frames"): joining split
 * packets, the H.264 codec string for WebCodecs, and pointer-to-video coordinate mapping.
 */
import { SCREEN_FRAME_CONT, SCREEN_FRAME_START, SCREEN_HEADER_BYTES, readScreenPacketHeader } from '@hsm/shared';

export interface VideoPacket {
  config: boolean;
  key: boolean;
  ptsUs: number;
  data: Uint8Array;
}

/** Reassembles START/CONT frames into whole packets. A frame out of sequence drops the packet. */
export class FrameAssembler {
  private buf: Uint8Array | null = null;
  private filled = 0;
  private head: { config: boolean; key: boolean; ptsUs: number } | null = null;

  push(frame: Uint8Array): VideoPacket | null {
    if (frame.length === 0) return null;
    if (frame[0] === SCREEN_FRAME_START) {
      const h = readScreenPacketHeader(frame);
      if (!h) return this.reset();
      this.head = { config: h.config, key: h.key, ptsUs: h.pts_us };
      this.buf = new Uint8Array(h.size);
      this.filled = 0;
      return this.append(frame.subarray(1 + SCREEN_HEADER_BYTES));
    }
    if (frame[0] === SCREEN_FRAME_CONT) {
      if (!this.buf) return null; // continuation of a packet we never started
      return this.append(frame.subarray(1));
    }
    return null;
  }

  private append(chunk: Uint8Array): VideoPacket | null {
    if (!this.buf || !this.head) return null;
    if (this.filled + chunk.length > this.buf.length) return this.reset();
    this.buf.set(chunk, this.filled);
    this.filled += chunk.length;
    if (this.filled < this.buf.length) return null;
    const packet: VideoPacket = { ...this.head, data: this.buf };
    this.buf = null;
    this.head = null;
    this.filled = 0;
    return packet;
  }

  private reset(): null {
    this.buf = null;
    this.head = null;
    this.filled = 0;
    return null;
  }
}

/** `avc1.PPCCLL` from the SPS in an Annex B config packet, or null when there is no SPS. */
export function avcCodecString(annexB: Uint8Array): string | null {
  for (let i = 0; i + 4 < annexB.length; i++) {
    // Start code 00 00 01 (a 4-byte 00 00 00 01 contains it too).
    if (annexB[i] !== 0 || annexB[i + 1] !== 0 || annexB[i + 2] !== 1) continue;
    const nal = annexB[i + 3]!;
    if ((nal & 0x1f) === 7 && i + 6 < annexB.length) {
      const hex = (b: number) => b.toString(16).padStart(2, '0');
      return `avc1.${hex(annexB[i + 4]!)}${hex(annexB[i + 5]!)}${hex(annexB[i + 6]!)}`;
    }
  }
  return null;
}

export function concatBytes(a: Uint8Array, b: Uint8Array): Uint8Array {
  const out = new Uint8Array(a.length + b.length);
  out.set(a, 0);
  out.set(b, a.length);
  return out;
}

/**
 * Maps a pointer position to fractions (0..1) of the video, for an element of `box` size showing
 * a `videoW` x `videoH` frame with object-fit: contain. Null outside the picture (letterbox bars).
 */
export function pointerToVideo(
  px: number,
  py: number,
  box: { left: number; top: number; width: number; height: number },
  videoW: number,
  videoH: number,
): { x: number; y: number } | null {
  if (videoW <= 0 || videoH <= 0 || box.width <= 0 || box.height <= 0) return null;
  const scale = Math.min(box.width / videoW, box.height / videoH);
  const w = videoW * scale;
  const h = videoH * scale;
  const ox = box.left + (box.width - w) / 2;
  const oy = box.top + (box.height - h) / 2;
  const x = (px - ox) / w;
  const y = (py - oy) / h;
  if (x < 0 || x > 1 || y < 0 || y > 1) return null;
  return { x, y };
}

/** Upper bound of data per hour at a peak bit rate (a still screen uses far less). */
export function megabytesPerHour(bitRate: number): number {
  return Math.round((bitRate * 3600) / 8 / 1_000_000 / 10) * 10;
}
