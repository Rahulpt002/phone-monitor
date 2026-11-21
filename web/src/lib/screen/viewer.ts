/**
 * Viewer side of a remote-screen session: the /ws/screen/view socket, H.264 decoding with
 * WebCodecs and drawing onto a canvas. One instance per session; call close() when done.
 */
import type { ScreenControlMsg, ScreenEndReason, ScreenMetaMsg, ScreenStatusMsg } from '@hsm/shared';
import { API_BASE, deriveWsUrl } from '../config';
import { FrameAssembler, avcCodecString, concatBytes, type VideoPacket } from './stream';

export type ViewerPhase = 'connecting' | 'waiting' | 'starting' | 'streaming' | 'reconnecting' | 'ended';

export interface ViewerState {
  phase: ViewerPhase;
  width: number;
  height: number;
  fps: number;
  /** Video bytes received in this session. */
  bytes: number;
  reason: ScreenEndReason | null;
  message: string | null;
}

export interface ViewerOptions {
  sessionId: string;
  token: string;
  canvas: HTMLCanvasElement;
  onState: (s: ViewerState) => void;
}

export function webCodecsSupported(): boolean {
  return typeof window !== 'undefined' && 'VideoDecoder' in window && window.isSecureContext;
}

const CLOSE_ENDED = 4005;
const CLOSE_UNAUTHORIZED = 4001;
const CLOSE_REPLACED = 4002;
/** Reconnect attempts after a dropped socket (the Server keeps the session for 30 s). */
const RECONNECT_DELAYS_MS = [500, 1000, 2000, 4000, 8000, 8000];
/** Decoder queue length above which delta frames are skipped until the next key frame. */
const MAX_DECODE_QUEUE = 8;

export class ScreenViewer {
  private ws: WebSocket | null = null;
  private decoder: VideoDecoder | null = null;
  private codec: string | null = null;
  private config: Uint8Array | null = null;
  private needKey = true;
  private assembler = new FrameAssembler();
  private ctx2d: CanvasRenderingContext2D | null;
  private state: ViewerState = { phase: 'connecting', width: 0, height: 0, fps: 0, bytes: 0, reason: null, message: null };
  private framesThisSecond = 0;
  private fpsTimer: ReturnType<typeof setInterval>;
  private pingTimer: ReturnType<typeof setInterval> | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private attempt = 0;
  private closed = false;
  private paused = false;

  constructor(private opts: ViewerOptions) {
    this.ctx2d = opts.canvas.getContext('2d', { alpha: false });
    this.fpsTimer = setInterval(() => {
      if (this.state.fps !== this.framesThisSecond) this.update({ fps: this.framesThisSecond });
      this.framesThisSecond = 0;
    }, 1000);
    this.connect();
  }

  get current(): ViewerState {
    return this.state;
  }

  send(msg: ScreenControlMsg | { t: 'screen_stop' }): void {
    if (this.ws?.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(msg));
  }

  /** Ends the session on the Server, then closes. */
  stop(): void {
    this.send({ t: 'screen_stop' });
    this.finish('stopped', 'Stopped.');
  }

  /** Page hidden: drop the socket (no video while nobody looks); the Server waits 30 s for us. */
  pause(): void {
    if (this.closed || this.paused) return;
    this.paused = true;
    this.dropSocket();
  }

  resume(): void {
    if (this.closed || !this.paused) return;
    this.paused = false;
    this.attempt = 0;
    this.update({ phase: 'reconnecting' });
    this.connect();
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    clearInterval(this.fpsTimer);
    this.dropSocket();
    this.closeDecoder();
  }

  // --- socket --------------------------------------------------------------------------------

  private connect(): void {
    if (this.closed || this.paused) return;
    const ws = new WebSocket(deriveWsUrl(API_BASE, '/ws/screen/view', window.location));
    ws.binaryType = 'arraybuffer';
    this.ws = ws;
    ws.onopen = () => {
      if (this.ws !== ws) return;
      ws.send(JSON.stringify({ t: 'screen_auth', token: this.opts.token, session_id: this.opts.sessionId }));
      this.pingTimer = setInterval(() => {
        if (ws.readyState === WebSocket.OPEN) ws.send('{"t":"ping"}');
      }, 20_000);
    };
    ws.onmessage = (ev) => {
      if (this.ws !== ws) return;
      if (typeof ev.data === 'string') this.onText(ev.data);
      else this.onBinary(new Uint8Array(ev.data as ArrayBuffer));
    };
    ws.onclose = (ev) => {
      if (this.ws !== ws) return;
      this.clearSocketTimers();
      this.ws = null;
      if (this.closed || this.paused) return;
      if (ev.code === CLOSE_ENDED || ev.code === CLOSE_UNAUTHORIZED || ev.code === CLOSE_REPLACED || this.state.phase === 'ended') {
        if (this.state.phase !== 'ended') {
          this.finish(
            null,
            ev.code === CLOSE_REPLACED
              ? 'The screen was opened in another window.'
              : ev.reason || 'This screen session has ended.',
          );
        }
        return;
      }
      const delay = RECONNECT_DELAYS_MS[this.attempt];
      if (delay === undefined) {
        this.finish(null, 'Lost the connection to the server.');
        return;
      }
      this.attempt++;
      this.update({ phase: 'reconnecting' });
      this.reconnectTimer = setTimeout(() => this.connect(), delay);
    };
  }

  private onText(data: string): void {
    let msg: { t?: string } & Record<string, unknown>;
    try {
      msg = JSON.parse(data) as typeof msg;
    } catch {
      return;
    }
    switch (msg.t) {
      case 'screen_status': {
        const s = msg as unknown as ScreenStatusMsg;
        this.attempt = 0;
        if (s.state === 'ended') this.finish(s.reason ?? null, s.message ?? 'The screen session ended.');
        else this.update({ phase: s.state });
        return;
      }
      case 'screen_meta': {
        const m = msg as unknown as ScreenMetaMsg;
        this.update({ width: m.width, height: m.height });
        return;
      }
      case 'auth_failed':
        this.finish(null, typeof msg.message === 'string' ? msg.message : 'Not allowed.');
        return;
      default:
    }
  }

  private onBinary(frame: Uint8Array): void {
    this.state.bytes += frame.length;
    const packet = this.assembler.push(frame);
    if (packet) this.onPacket(packet);
  }

  // --- decoding ------------------------------------------------------------------------------

  private onPacket(p: VideoPacket): void {
    if (p.config) {
      this.config = p.data;
      const codec = avcCodecString(p.data);
      if (codec && (codec !== this.codec || !this.decoder || this.decoder.state !== 'configured')) {
        this.configure(codec);
      }
      return;
    }
    const dec = this.decoder;
    if (!dec || dec.state !== 'configured') return;
    if (!p.key) {
      if (this.needKey) return;
      if (dec.decodeQueueSize > MAX_DECODE_QUEUE) {
        // Decoding cannot keep up: skip to the next key frame instead of falling behind.
        this.needKey = true;
        this.send({ t: 'keyframe' });
        return;
      }
    }
    const data = p.key && this.config ? concatBytes(this.config, p.data) : p.data;
    try {
      dec.decode(new EncodedVideoChunk({ type: p.key ? 'key' : 'delta', timestamp: p.ptsUs, data }));
      if (p.key) this.needKey = false;
    } catch {
      this.recover();
    }
  }

  private configure(codec: string): void {
    this.closeDecoder();
    const dec = new VideoDecoder({
      output: (frame) => this.draw(frame),
      error: () => this.recover(),
    });
    try {
      dec.configure({ codec, optimizeForLatency: true, hardwareAcceleration: 'no-preference' });
    } catch {
      dec.close();
      this.update({ message: `This browser cannot decode the phone's video (${codec}).` });
      return;
    }
    this.decoder = dec;
    this.codec = codec;
    this.needKey = true;
  }

  private recover(): void {
    if (this.closed) return;
    const codec = this.codec;
    this.closeDecoder();
    if (codec) this.configure(codec);
    this.send({ t: 'keyframe' });
  }

  private draw(frame: VideoFrame): void {
    try {
      const c = this.opts.canvas;
      const w = frame.displayWidth;
      const h = frame.displayHeight;
      if (c.width !== w || c.height !== h) {
        c.width = w;
        c.height = h;
        if (this.state.width !== w || this.state.height !== h) this.update({ width: w, height: h });
      }
      this.ctx2d?.drawImage(frame, 0, 0, w, h);
      this.framesThisSecond++;
      if (this.state.phase !== 'streaming' && this.state.phase !== 'ended') this.update({ phase: 'streaming' });
    } finally {
      frame.close();
    }
  }

  private closeDecoder(): void {
    const d = this.decoder;
    this.decoder = null;
    if (d && d.state !== 'closed') {
      try {
        d.close();
      } catch {
        // already closed
      }
    }
  }

  // --- state ---------------------------------------------------------------------------------

  private finish(reason: ScreenEndReason | null, message: string): void {
    this.update({ phase: 'ended', reason, message });
    this.close();
  }

  private update(patch: Partial<ViewerState>): void {
    this.state = { ...this.state, ...patch };
    this.opts.onState(this.state);
  }

  private dropSocket(): void {
    this.clearSocketTimers();
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
    const ws = this.ws;
    this.ws = null;
    if (ws && ws.readyState <= WebSocket.OPEN) ws.close(1000, 'viewer closed');
  }

  private clearSocketTimers(): void {
    if (this.pingTimer) clearInterval(this.pingTimer);
    this.pingTimer = null;
  }
}
