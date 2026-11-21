import {
  SCREEN,
  SCREEN_FRAME_CONT,
  SCREEN_FRAME_START,
  SCREEN_KEYS,
  SCREEN_QUALITIES,
  SCREEN_QUALITY,
  readScreenPacketHeader,
  utf8Length,
  type AgentEvent,
  type ScreenControlMsg,
  type ScreenEndReason,
  type ScreenMetaMsg,
  type ScreenQuality,
  type ScreenSessionResponse,
  type ScreenStateMsg,
  type ScreenStatusMsg,
} from '@hsm/shared';
import type { RawData, WebSocket } from 'ws';
import { z } from 'zod';
import type { AuthInfo } from '../auth/service.js';
import type { AppContext } from '../context.js';
import { ApiHttpError, conflict, forbidden, notFound, parseOrThrow } from '../errors.js';
import { iso, isPlainObject, randomId, type Plain } from '../util.js';
import { sendJson } from '../ws/hub.js';

const WS_OPEN = 1;
/** Close codes of the relay sockets (docs/protocol.md section 8). */
export const SCREEN_CLOSE = { normal: 1000, unauthorized: 4001, replaced: 4002, protocol: 4003, ended: 4005 } as const;
/** Control messages accepted per viewer per second; the rest are dropped. */
const MAX_CONTROL_PER_S = 200;
/** At most one key-frame request to the Agent per this many ms. */
const KEYFRAME_REQUEST_MS = 2_000;
/** Relay sockets get a ws ping this often; one without a pong by the next ping is terminated. */
const PING_MS = 15_000;
const MAX_BAD_MESSAGES = 10;

export const CreateScreenSessionSchema = z.object({
  device_id: z.string().min(1).max(64),
  quality: z.enum(SCREEN_QUALITIES as [ScreenQuality, ...ScreenQuality[]]).optional(),
  // Optional so a missing value gives 401 reauth_required / 400 for the code, not a schema error.
  password: z.string().max(256).optional(),
  code: z.string().max(16).optional(),
});

const frac = z.number().finite().min(0).max(1);
const ControlSchema = z.discriminatedUnion('t', [
  z.object({
    t: z.literal('touch'),
    action: z.enum(['down', 'move', 'up', 'cancel']),
    pointer: z.number().int().min(0).max(9),
    x: frac,
    y: frac,
  }),
  z.object({
    t: z.literal('scroll'),
    x: frac,
    y: frac,
    dx: z.number().finite().min(-16).max(16),
    dy: z.number().finite().min(-16).max(16),
  }),
  z.object({ t: z.literal('key'), key: z.enum(SCREEN_KEYS as [string, ...string[]]) }),
  z.object({
    t: z.literal('text'),
    text: z
      .string()
      .min(1)
      .refine((s) => utf8Length(s) <= SCREEN.max_text_bytes, `at most ${SCREEN.max_text_bytes} bytes`),
  }),
  z.object({ t: z.literal('panel'), panel: z.enum(['notifications', 'settings', 'collapse']) }),
  z.object({ t: z.literal('keyframe') }),
]);

interface RelaySocket {
  socket: WebSocket;
  awaitingPong: boolean;
  lastPing: number;
  bad: number;
}

export interface ScreenSession {
  id: string;
  deviceId: string;
  userId: number;
  username: string;
  sessionHash: string;
  quality: ScreenQuality;
  createdAt: number;
  expiresAt: number;
  state: 'starting' | 'streaming' | 'ended';
  agent: RelaySocket | null;
  viewer: RelaySocket | null;
  /** When the viewer was last seen connected (createdAt until the first viewer arrives). */
  viewerGoneSince: number | null;
  /** Bytes of video relayed to the viewer. */
  bytes: number;
  /** Video is being dropped until the next config or key frame (new viewer, slow viewer). */
  waitForKey: boolean;
  /** The current packet's continuation chunks are being dropped. */
  skippingPacket: boolean;
  lastKeyRequest: number;
  meta: ScreenMetaMsg | null;
  controlWindowStart: number;
  controlCount: number;
}

const END_MESSAGES: Record<ScreenEndReason, string> = {
  stopped: 'Stopped.',
  replaced: 'Another screen session for this phone started.',
  max_duration: `Sessions last at most ${SCREEN.max_duration_s / 60} minutes. Start a new one to continue.`,
  viewer_gone: 'The viewer disconnected.',
  agent_timeout: 'The phone did not start streaming in time.',
  agent_gone: 'The phone stopped streaming.',
  agent_failed: 'The phone could not start the screen stream.',
  session_ended: 'Your sign-in session ended.',
  unpaired: 'The phone was unpaired.',
  server_shutdown: 'The server is restarting. Start the screen again in a minute.',
};

/**
 * Remote screen sessions (shared/src/screen.ts). Memory only: a server restart ends every
 * session (the Agent stops when its relay socket closes). Start and end are stored as
 * `screen_session` events for the audit trail.
 */
export class ScreenService {
  private sessions = new Map<string, ScreenSession>();
  private byDevice = new Map<string, string>();

  constructor(private ctx: AppContext) {}

  get(id: string): ScreenSession | undefined {
    return this.sessions.get(id);
  }

  forDevice(deviceId: string): ScreenSession | undefined {
    const id = this.byDevice.get(deviceId);
    return id ? this.sessions.get(id) : undefined;
  }

  // --- REST ----------------------------------------------------------------------------------

  async create(auth: AuthInfo, body: unknown): Promise<ScreenSessionResponse> {
    if (auth.user.role !== 'admin') throw forbidden('Only admins can view and control the phone screen');
    const b = parseOrThrow(CreateScreenSessionSchema, body ?? {}, 'request');
    const dev = this.ctx.devices.get(b.device_id);
    if (!dev) throw notFound(`Device ${b.device_id} not found`);
    // Checked before the password and code, so an offline phone does not burn a TOTP code.
    if (!this.ctx.hub.agentConnected(dev.id)) {
      throw new ApiHttpError(409, 'device_offline', 'The phone is offline. The screen needs a live connection.');
    }
    const cap = dev.state.capabilities?.screen_mirror;
    if (!cap?.supported) {
      throw conflict(
        cap
          ? `This phone cannot stream its screen: ${cap.note ?? 'not supported'}`
          : 'This phone has not reported remote-screen support. Update the Agent app to the latest version.',
      );
    }
    await this.ctx.auth.reauthWithCode(auth, b.password, b.code);

    const old = this.forDevice(dev.id);
    if (old) await this.end(old, 'replaced');

    const now = this.ctx.clock.now();
    const quality = b.quality ?? 'standard';
    const s: ScreenSession = {
      id: randomId('scr_', 12),
      deviceId: dev.id,
      userId: auth.user.id,
      username: auth.user.username,
      sessionHash: auth.sessionHash,
      quality,
      createdAt: now,
      expiresAt: now + SCREEN.max_duration_s * 1000,
      state: 'starting',
      agent: null,
      viewer: null,
      viewerGoneSince: now,
      bytes: 0,
      waitForKey: true,
      skippingPacket: false,
      lastKeyRequest: 0,
      meta: null,
      controlWindowStart: now,
      controlCount: 0,
    };
    this.sessions.set(s.id, s);
    this.byDevice.set(dev.id, s.id);
    this.sendStart(s);
    await this.audit(s, 'started');
    this.ctx.log.info({ device_id: dev.id, session: s.id, user: s.username, quality }, 'screen session started');
    return {
      session_id: s.id,
      device_id: dev.id,
      quality,
      created_at: iso(s.createdAt),
      expires_at: iso(s.expiresAt),
      ws_path: '/ws/screen/view',
    };
  }

  /** DELETE /screen/sessions/:id by an admin. */
  async stop(auth: AuthInfo, id: string): Promise<void> {
    if (auth.user.role !== 'admin') throw forbidden();
    const s = this.sessions.get(id);
    if (!s) return; // already over: idempotent
    await this.end(s, 'stopped');
  }

  // --- Agent link ----------------------------------------------------------------------------

  private sendStart(s: ScreenSession): void {
    const q = SCREEN_QUALITY[s.quality];
    this.ctx.hub.sendToAgent(s.deviceId, {
      t: 'screen_start',
      session_id: s.id,
      quality: s.quality,
      max_size: q.max_size,
      bit_rate: q.bit_rate,
      max_fps: q.max_fps,
      max_duration_s: Math.max(1, Math.round((s.expiresAt - this.ctx.clock.now()) / 1000)),
      user: s.username,
    });
  }

  /** The Agent's main link (re)connected: repeat `screen_start` for a session still waiting for it. */
  onAgentLinkUp(deviceId: string): void {
    const s = this.forDevice(deviceId);
    if (s && s.state === 'starting' && !s.agent) this.sendStart(s);
  }

  /** `screen_state` from /ws/agent. */
  async onAgentState(deviceId: string, msg: Plain): Promise<void> {
    if (typeof msg.session_id !== 'string') return;
    const s = this.sessions.get(msg.session_id);
    const state = msg.state as ScreenStateMsg['state'];
    if (!s || s.deviceId !== deviceId) {
      // A session the Server no longer knows (restart, ended meanwhile): tell the Agent to stop.
      if (state === 'starting' || state === 'streaming') {
        this.ctx.hub.sendToAgent(deviceId, { t: 'screen_stop', session_id: msg.session_id, reason: 'stopped' });
      }
      return;
    }
    if (state === 'failed') {
      const err = typeof msg.error === 'string' ? msg.error.slice(0, 300) : null;
      await this.end(s, 'agent_failed', err ? `The phone could not start the screen stream: ${err}` : undefined);
    } else if (state === 'ended') {
      await this.end(s, 'agent_gone');
    } else if (state === 'streaming' && s.state === 'starting') {
      s.state = 'streaming';
      this.status(s, 'streaming');
    }
  }

  // --- relay sockets -------------------------------------------------------------------------

  /** WS /ws/screen/agent?session=<id>, device token in the Authorization header. */
  attachAgent(deviceId: string, sessionId: string, socket: WebSocket): void {
    const s = this.sessions.get(sessionId);
    if (!s || s.deviceId !== deviceId || s.state === 'ended') {
      socket.close(SCREEN_CLOSE.ended, 'no such screen session');
      return;
    }
    if (s.agent) s.agent.socket.close(SCREEN_CLOSE.replaced, 'replaced');
    const relay = this.relay(socket);
    s.agent = relay;
    s.waitForKey = true;
    socket.on('message', (data: RawData, isBinary: boolean) => {
      if (s.agent !== relay || s.state === 'ended') return;
      if (isBinary) this.onVideo(s, toBuffer(data));
      else this.onAgentText(s, relay, data);
    });
    socket.on('close', () => {
      if (s.agent !== relay) return;
      s.agent = null;
      if (s.state !== 'ended') void this.end(s, 'agent_gone');
    });
    this.status(s, s.state === 'streaming' ? 'streaming' : 'starting');
  }

  /** WS /ws/screen/view: the first message must be `screen_auth`. */
  attachViewer(socket: WebSocket): void {
    const relay = this.relay(socket);
    let session: ScreenSession | null = null;
    let authing = false;
    const authTimer = setTimeout(() => {
      if (!session) socket.close(SCREEN_CLOSE.unauthorized, 'auth timeout');
    }, 10_000);
    authTimer.unref();

    const fail = (message: string, code: number = SCREEN_CLOSE.unauthorized) => {
      sendJson(socket, { t: 'auth_failed', message });
      socket.close(code, message.slice(0, 100));
    };
    const bad = (reason: string) => {
      relay.bad++;
      if (relay.bad > MAX_BAD_MESSAGES) socket.close(SCREEN_CLOSE.protocol, 'protocol error');
      else this.ctx.log.debug({ reason }, 'bad screen viewer message');
    };

    socket.on('message', (data: RawData, isBinary: boolean) => {
      if (isBinary) return bad('binary frames are not accepted from viewers');
      let msg: unknown;
      try {
        msg = JSON.parse(data.toString());
      } catch {
        return bad('invalid JSON');
      }
      if (!isPlainObject(msg) || typeof msg.t !== 'string') return bad('expected {t}');

      if (!session) {
        if (msg.t !== 'screen_auth' || authing) return bad('authenticate first');
        authing = true;
        void this.authViewer(msg).then(
          (r) => {
            clearTimeout(authTimer);
            if (socket.readyState !== WS_OPEN) return;
            if (typeof r === 'string') return fail(r, r.includes('ended') ? SCREEN_CLOSE.ended : SCREEN_CLOSE.unauthorized);
            session = r;
            if (r.viewer) r.viewer.socket.close(SCREEN_CLOSE.replaced, 'opened in another window');
            r.viewer = relay;
            r.viewerGoneSince = null;
            r.waitForKey = true;
            r.skippingPacket = false;
            this.status(r, r.state === 'streaming' ? 'streaming' : r.agent ? 'starting' : 'waiting');
            if (r.meta) sendJson(socket, r.meta);
            // A new viewer has no decoder state: ask for a fresh config + key frame.
            this.requestKeyframe(r, true);
          },
          () => {
            clearTimeout(authTimer);
            // Session lookup failed (database trouble): try again later.
            socket.close(1013, 'try again later');
          },
        );
        return;
      }

      const s = session;
      if (s.viewer !== relay || s.state === 'ended') return;
      switch (msg.t) {
        case 'ping':
          sendJson(socket, { t: 'pong' });
          return;
        case 'screen_stop':
          void this.end(s, 'stopped');
          return;
        default: {
          const parsed = ControlSchema.safeParse(msg);
          if (!parsed.success) return bad(`invalid control message ${msg.t}`);
          const now = this.ctx.clock.now();
          if (now - s.controlWindowStart >= 1000) {
            s.controlWindowStart = now;
            s.controlCount = 0;
          }
          if (++s.controlCount > MAX_CONTROL_PER_S) return;
          if (parsed.data.t === 'keyframe') this.requestKeyframe(s, false);
          else this.toAgent(s, parsed.data as ScreenControlMsg);
        }
      }
    });
    socket.on('close', () => {
      clearTimeout(authTimer);
      const s = session;
      if (!s || s.viewer !== relay) return;
      s.viewer = null;
      s.viewerGoneSince = this.ctx.clock.now();
    });
  }

  /** Returns the session, or a reason string for auth_failed. */
  private async authViewer(msg: Plain): Promise<ScreenSession | string> {
    if (typeof msg.token !== 'string' || typeof msg.session_id !== 'string') return 'token and session_id are required';
    const info = await this.ctx.auth.authenticate(msg.token);
    if (!info) return 'invalid or expired sign-in session';
    if (this.ctx.auth.needsTotpEnrollment(info.user)) return 'totp_enrollment_required';
    if (info.user.role !== 'admin') return 'only admins can view the phone screen';
    const s = this.sessions.get(msg.session_id);
    if (!s || s.state === 'ended') return 'this screen session has ended';
    // Bound to the sign-in session that passed the password and code check.
    if (s.sessionHash !== info.sessionHash) return 'this screen session belongs to another sign-in';
    return s;
  }

  private relay(socket: WebSocket): RelaySocket {
    const r: RelaySocket = { socket, awaitingPong: false, lastPing: this.ctx.clock.now(), bad: 0 };
    socket.on('pong', () => {
      r.awaitingPong = false;
    });
    return r;
  }

  private onAgentText(s: ScreenSession, relay: RelaySocket, data: RawData): void {
    let msg: unknown;
    try {
      msg = JSON.parse(data.toString());
    } catch {
      msg = null;
    }
    if (!isPlainObject(msg) || typeof msg.t !== 'string') {
      if (++relay.bad > MAX_BAD_MESSAGES) relay.socket.close(SCREEN_CLOSE.protocol, 'protocol error');
      return;
    }
    if (msg.t === 'screen_meta') {
      const w = Number(msg.width);
      const h = Number(msg.height);
      if (!Number.isInteger(w) || !Number.isInteger(h) || w <= 0 || h <= 0 || w > 8192 || h > 8192) return;
      s.meta = {
        t: 'screen_meta',
        codec: 'h264',
        width: w,
        height: h,
        device_name: typeof msg.device_name === 'string' ? msg.device_name.slice(0, 64) : null,
      };
      if (s.viewer) sendJson(s.viewer.socket, s.meta);
    }
    // Other types are ignored (forward compatibility).
  }

  /** Binary video frame from the Agent: forwarded unchanged, or dropped for a slow viewer. */
  private onVideo(s: ScreenSession, frame: Buffer): void {
    const v = s.viewer;
    if (!v || v.socket.readyState !== WS_OPEN) return;
    const kind = frame[0];
    if (kind === SCREEN_FRAME_START) {
      const hdr = readScreenPacketHeader(frame);
      if (!hdr) return;
      if (s.waitForKey) {
        if (!hdr.config && !hdr.key) {
          s.skippingPacket = true;
          return;
        }
        s.waitForKey = false;
      }
      if (v.socket.bufferedAmount > SCREEN.max_viewer_buffered_bytes) {
        // The viewer's connection cannot keep up: skip ahead to the next key frame.
        s.skippingPacket = true;
        s.waitForKey = true;
        this.requestKeyframe(s, false);
        return;
      }
      s.skippingPacket = false;
    } else if (kind === SCREEN_FRAME_CONT) {
      if (s.skippingPacket) return;
    } else {
      return;
    }
    s.bytes += frame.length;
    v.socket.send(frame, { binary: true });
  }

  private requestKeyframe(s: ScreenSession, force: boolean): void {
    const now = this.ctx.clock.now();
    if (!force && now - s.lastKeyRequest < KEYFRAME_REQUEST_MS) return;
    s.lastKeyRequest = now;
    this.toAgent(s, { t: 'keyframe' });
  }

  private toAgent(s: ScreenSession, msg: ScreenControlMsg): void {
    if (s.agent) sendJson(s.agent.socket, msg);
  }

  private status(s: ScreenSession, state: ScreenStatusMsg['state'], reason?: ScreenEndReason, message?: string): void {
    if (!s.viewer) return;
    const msg: ScreenStatusMsg = { t: 'screen_status', state, reason: reason ?? null, message: message ?? null };
    sendJson(s.viewer.socket, msg);
  }

  // --- ending --------------------------------------------------------------------------------

  async end(s: ScreenSession, reason: ScreenEndReason, message?: string): Promise<void> {
    if (s.state === 'ended') return;
    s.state = 'ended';
    this.sessions.delete(s.id);
    if (this.byDevice.get(s.deviceId) === s.id) this.byDevice.delete(s.deviceId);
    const text = message ?? END_MESSAGES[reason];
    if (s.viewer) {
      this.status(s, 'ended', reason, text);
      s.viewer.socket.close(SCREEN_CLOSE.normal, 'screen session ended');
      s.viewer = null;
    }
    if (s.agent) {
      s.agent.socket.close(SCREEN_CLOSE.normal, 'screen session ended');
      s.agent = null;
    }
    this.ctx.hub.sendToAgent(s.deviceId, { t: 'screen_stop', session_id: s.id, reason });
    this.ctx.log.info(
      { device_id: s.deviceId, session: s.id, reason, bytes: s.bytes, seconds: Math.round((this.ctx.clock.now() - s.createdAt) / 1000) },
      'screen session ended',
    );
    if (reason !== 'server_shutdown') await this.audit(s, 'ended', reason);
  }

  async onUnpaired(deviceId: string): Promise<void> {
    const s = this.forDevice(deviceId);
    if (s) await this.end(s, 'unpaired');
  }

  /** Every 5 s (Jobs): time limits, missing viewer or Agent, ended sign-in sessions, liveness. */
  async tick(): Promise<void> {
    const now = this.ctx.clock.now();
    for (const s of [...this.sessions.values()]) {
      const user = this.ctx.auth.getUser(s.userId);
      if (!user || user.role !== 'admin' || !this.ctx.auth.sessionAlive(s.sessionHash)) {
        await this.end(s, 'session_ended');
      } else if (now >= s.expiresAt) {
        await this.end(s, 'max_duration');
      } else if (!s.agent && now - s.createdAt > SCREEN.agent_connect_timeout_s * 1000) {
        await this.end(s, 'agent_timeout');
      } else if (!s.viewer && s.viewerGoneSince !== null && now - s.viewerGoneSince > SCREEN.viewer_grace_s * 1000) {
        await this.end(s, 'viewer_gone');
      } else {
        for (const r of [s.agent, s.viewer]) {
          if (!r || r.socket.readyState !== WS_OPEN || now - r.lastPing < PING_MS) continue;
          if (r.awaitingPong) {
            r.socket.terminate();
            continue;
          }
          r.awaitingPong = true;
          r.lastPing = now;
          r.socket.ping();
        }
      }
    }
  }

  /** Server shutdown: end everything without writing audit rows (the database may be closing). */
  closeAll(): void {
    for (const s of [...this.sessions.values()]) void this.end(s, 'server_shutdown');
  }

  private async audit(s: ScreenSession, state: 'started' | 'ended', reason?: ScreenEndReason): Promise<void> {
    const now = this.ctx.clock.now();
    const durationS = Math.round((now - s.createdAt) / 1000);
    const q = SCREEN_QUALITY[s.quality].label.toLowerCase();
    const event: AgentEvent = {
      id: randomId('srv_', 8),
      ts: iso(now),
      kind: 'screen_session',
      severity: state === 'started' ? 'warning' : 'info',
      message:
        state === 'started'
          ? `Remote screen started by ${s.username} (${q} quality)`
          : `Remote screen ended after ${formatMinutes(durationS)} (${END_MESSAGES[reason ?? 'stopped'].replace(/\.$/, '').toLowerCase()}), ${formatMb(s.bytes)} of video`,
      data: {
        user: s.username,
        session_id: s.id,
        state,
        quality: s.quality,
        ...(state === 'ended' ? { reason, duration_s: durationS, bytes: s.bytes } : {}),
      },
    };
    try {
      await this.ctx.db.run(
        `INSERT INTO events (device_id, id, ts, kind, severity, message, data, received_at)
         SELECT ?, ?, ?, ?, ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM devices WHERE id = ?)`,
        [s.deviceId, event.id, now, event.kind, event.severity, event.message, JSON.stringify(event.data), now, s.deviceId],
      );
      this.ctx.records.broadcastEvent(s.deviceId, event);
    } catch (err) {
      this.ctx.log.error({ err, session: s.id }, 'failed to store screen session event');
    }
  }
}

function toBuffer(data: RawData): Buffer {
  if (Buffer.isBuffer(data)) return data;
  if (Array.isArray(data)) return Buffer.concat(data);
  return Buffer.from(data as ArrayBuffer);
}

function formatMinutes(s: number): string {
  if (s < 60) return `${s} s`;
  const m = Math.floor(s / 60);
  const r = s % 60;
  return r ? `${m} min ${r} s` : `${m} min`;
}

function formatMb(bytes: number): string {
  return bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.round(bytes / 1024)} KB`;
}
