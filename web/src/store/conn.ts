/**
 * Dashboard socket (docs/protocol.md section 5): connect, `auth` within 10 s, `subscribe` with the
 * current device and Finder flag, `ping` every 25 s, reconnect with backoff, re-auth and re-subscribe.
 * Every other message goes to the data store reducer.
 *
 * Close codes (protocol section 7): 4001 = the session is gone (sign out), 4004 = the user's role
 * changed (reconnect at once and reload /auth/me), 1013 and everything else = reconnect with
 * backoff, keeping the token. Not opened at all while the user still has to enrol TOTP.
 */
import type { DashboardToServer } from '@hsm/shared';
import { create } from 'zustand';
import { backoffDelay } from '../lib/backoff';
import { dashboardSocketUrl } from '../lib/config';
import { useData } from './data';
import { onLogout, useSession } from './session';
import { useUi } from './ui';

export type ConnStatus = 'idle' | 'connecting' | 'authenticating' | 'open' | 'waiting';

interface ConnState {
  status: ConnStatus;
  attempt: number;
  nextRetryAt: number | null;
  /** When the socket last went down (null while open). */
  downSince: number | null;
  /** Incremented after each successful re-connection so screens refetch history they may have missed. */
  epoch: number;
  /** Finder screen visible -> ask the Server for the 1 s mirror. */
  finder: boolean;
  setFinder: (on: boolean) => void;
  retryNow: () => void;
}

/** Server close codes for the dashboard socket (docs/protocol.md section 7). */
export const CLOSE_UNAUTHORIZED = 4001;
export const CLOSE_ROLE_CHANGED = 4004;

const PING_MS = 25_000;
const STALE_MS = 70_000; // no message at all (not even pong) for this long -> reconnect
const AUTH_TIMEOUT_MS = 15_000;
const ROLE_CHANGE_QUIET_MS = 10_000;

export const useConn = create<ConnState>()((set, get) => ({
  status: 'idle',
  attempt: 0,
  nextRetryAt: null,
  downSince: null,
  epoch: 0,
  finder: false,
  setFinder: (finder) => {
    if (get().finder === finder) return;
    set({ finder });
    sendSubscribe();
  },
  retryNow: () => {
    if (get().status === 'waiting' || get().status === 'idle') connect();
  },
}));

let ws: WebSocket | null = null;
let token: string | null = null;
let stopped = true;
let hadSession = false;
let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
let pingTimer: ReturnType<typeof setInterval> | null = null;
let authTimer: ReturnType<typeof setTimeout> | null = null;
let lastMessageAt = 0;
let lastSubscribe = '';
let lastRoleChangeAt = Number.NEGATIVE_INFINITY;

function clearTimers() {
  if (reconnectTimer) clearTimeout(reconnectTimer);
  if (pingTimer) clearInterval(pingTimer);
  if (authTimer) clearTimeout(authTimer);
  reconnectTimer = pingTimer = authTimer = null;
}

function send(msg: DashboardToServer): boolean {
  if (!ws || ws.readyState !== WebSocket.OPEN) return false;
  try {
    ws.send(JSON.stringify(msg));
    return true;
  } catch {
    return false;
  }
}

function documentVisible(): boolean {
  return typeof document === 'undefined' || document.visibilityState !== 'hidden';
}

/** Sends `subscribe` when it differs from the last one sent on this connection. */
export function sendSubscribe(force = false): void {
  if (useConn.getState().status !== 'open') return;
  const device_id = useUi.getState().deviceId;
  // The mirror costs the phone data every second, so it only runs while the Finder is actually on screen.
  const finder = useConn.getState().finder && documentVisible();
  const key = `${device_id ?? ''}|${finder}`;
  if (!force && key === lastSubscribe) return;
  if (send({ t: 'subscribe', device_id, finder })) lastSubscribe = key;
}

function connect() {
  if (stopped || !token) return;
  clearTimers();
  if (ws) {
    const old = ws;
    ws = null;
    old.onopen = old.onmessage = old.onclose = old.onerror = null;
    try {
      old.close(1000);
    } catch {
      /* already closed */
    }
  }
  lastSubscribe = '';
  useConn.setState({ status: 'connecting', nextRetryAt: null });

  let sock: WebSocket;
  try {
    sock = new WebSocket(dashboardSocketUrl());
  } catch {
    scheduleReconnect();
    return;
  }
  ws = sock;

  sock.onopen = () => {
    if (ws !== sock) return;
    useConn.setState({ status: 'authenticating' });
    lastMessageAt = Date.now();
    send({ t: 'auth', token: token ?? '' });
    authTimer = setTimeout(() => {
      if (ws === sock && useConn.getState().status === 'authenticating') sock.close(1000, 'auth timeout');
    }, AUTH_TIMEOUT_MS);
  };

  sock.onmessage = (ev) => {
    if (ws !== sock) return;
    lastMessageAt = Date.now();
    let msg: unknown;
    try {
      msg = JSON.parse(typeof ev.data === 'string' ? ev.data : '');
    } catch {
      return;
    }
    if (!msg || typeof msg !== 'object') return;
    const t = (msg as { t?: unknown }).t;
    if (t === 'auth_ok') onAuthOk();
    else if (t === 'auth_failed') onAuthFailed((msg as { message?: unknown }).message);
    else if (t === 'pong') return;
    else useData.getState().apply(msg);
  };

  sock.onclose = (ev) => {
    if (ws !== sock) return;
    ws = null;
    clearTimers();
    if (ev.code === CLOSE_UNAUTHORIZED) {
      onAuthFailed(ev.reason);
      return;
    }
    if (ev.code === CLOSE_ROLE_CHANGED) {
      onRoleChanged();
      return;
    }
    scheduleReconnect();
  };

  sock.onerror = () => {
    /* a close event always follows; reconnect is handled there */
  };
}

function onAuthOk() {
  if (authTimer) clearTimeout(authTimer);
  authTimer = null;
  const reconnected = hadSession;
  hadSession = true;
  useConn.setState((s) => ({
    status: 'open',
    attempt: 0,
    downSince: null,
    nextRetryAt: null,
    epoch: reconnected ? s.epoch + 1 : s.epoch,
  }));
  sendSubscribe(true);
  pingTimer = setInterval(() => {
    if (Date.now() - lastMessageAt > STALE_MS) {
      try {
        ws?.close(1000, 'stale');
      } catch {
        /* ignore */
      }
      return;
    }
    send({ t: 'ping' });
  }, PING_MS);
}

function onAuthFailed(message?: unknown) {
  stopSocket();
  const session = useSession.getState();
  if (!session.token) return;
  // A valid session without TOTP: enrollment, not sign-out (the app then shows /enroll).
  if (message === 'totp_enrollment_required') session.setTotpEnrollmentRequired(true);
  else session.logout('Your session has expired. Please sign in again.', false);
}

/**
 * 4004: same session, new role. Reconnect at once (auth_ok carries the new role) and reload
 * /auth/me. A second 4004 right after the first falls back to the backoff, so it cannot spin.
 */
function onRoleChanged() {
  void useSession.getState().refreshMe();
  const now = Date.now();
  const repeated = now - lastRoleChangeAt < ROLE_CHANGE_QUIET_MS;
  lastRoleChangeAt = now;
  if (stopped || repeated) {
    scheduleReconnect();
    return;
  }
  connect();
}

function scheduleReconnect() {
  if (stopped) {
    useConn.setState({ status: 'idle' });
    return;
  }
  const attempt = useConn.getState().attempt + 1;
  const delay = backoffDelay(attempt);
  useConn.setState((s) => ({
    status: 'waiting',
    attempt,
    nextRetryAt: Date.now() + delay,
    downSince: s.downSince ?? Date.now(),
  }));
  reconnectTimer = setTimeout(connect, delay);
}

export function startSocket(sessionToken: string): void {
  // The Server refuses the socket until TOTP is enrolled (plan 10).
  if (useSession.getState().totpEnrollmentRequired) return;
  if (!stopped && token === sessionToken && ws) return;
  token = sessionToken;
  stopped = false;
  hadSession = false;
  useConn.setState({ attempt: 0, downSince: null });
  connect();
}

export function stopSocket(): void {
  stopped = true;
  token = null;
  clearTimers();
  if (ws) {
    const old = ws;
    ws = null;
    old.onopen = old.onmessage = old.onclose = old.onerror = null;
    try {
      old.close(1000);
    } catch {
      /* ignore */
    }
  }
  lastSubscribe = '';
  useConn.setState({ status: 'idle', nextRetryAt: null, downSince: null, attempt: 0 });
}

// Wire-up that lives for the whole page.
if (typeof window !== 'undefined') {
  useUi.subscribe((s, prev) => {
    if (s.deviceId !== prev.deviceId) sendSubscribe();
  });
  window.addEventListener('online', () => {
    const st = useConn.getState().status;
    if (st === 'waiting') connect();
  });
  document.addEventListener('visibilitychange', () => {
    const st = useConn.getState().status;
    if (documentVisible() && st === 'waiting') connect();
    sendSubscribe();
  });
  onLogout(() => {
    stopSocket();
    useData.getState().reset();
  });
  // Enrollment became required (a 403 or auth_failed): close the socket and drop device data.
  useSession.subscribe((s, prev) => {
    if (s.totpEnrollmentRequired && !prev.totpEnrollmentRequired) {
      stopSocket();
      useData.getState().reset();
    }
  });
}
