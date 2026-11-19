import WebSocket from 'ws';
import { AssertionError, sleep } from './util.mjs';

/**
 * A Dashboard socket (`WS /ws/dashboard`, docs/protocol.md 5): auth, subscribe, and a log of every
 * message received with its arrival time, so scenarios can wait for "a message like X after mark M".
 */
export class DashClient {
  constructor(wsUrl, label = 'dash') {
    this.wsUrl = wsUrl;
    this.label = label;
    this.msgs = [];
    this.ws = null;
    this.user = null;
    /** The `auth_failed` message when the Server refused the session (e.g. totp_enrollment_required). */
    this.authFailed = null;
    this.closed = null;
    this.pingTimer = null;
  }

  async connect(token, timeout = 10_000) {
    const ws = new WebSocket(this.wsUrl);
    this.ws = ws;
    ws.on('message', (data) => {
      let msg;
      try {
        msg = JSON.parse(data.toString());
      } catch {
        msg = { t: '__unparsable__', raw: data.toString().slice(0, 200) };
      }
      this.msgs.push({ at: Date.now(), msg });
    });
    ws.on('close', (code, reason) => {
      this.closed = { code, reason: reason.toString() };
      if (this.pingTimer) clearInterval(this.pingTimer);
    });
    await new Promise((resolve, reject) => {
      const t = setTimeout(() => reject(new Error(`${this.label}: socket did not open`)), timeout);
      ws.once('open', () => {
        clearTimeout(t);
        resolve();
      });
      ws.once('error', (err) => {
        clearTimeout(t);
        reject(err);
      });
    });
    this.send({ t: 'auth', token });
    const ok = await this.waitFor((m) => m.t === 'auth_ok' || m.t === 'auth_failed', { timeout, label: 'auth reply' });
    if (ok.t !== 'auth_ok') {
      this.authFailed = ok;
      throw new AssertionError(`${this.label}: auth failed: ${ok.message}`);
    }
    this.user = ok.user;
    // Protocol 5.5: the client pings every 25 s.
    this.pingTimer = setInterval(() => this.send({ t: 'ping' }), 25_000);
    this.pingTimer.unref();
    return ok;
  }

  send(msg) {
    if (this.ws?.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(msg));
  }

  subscribe(deviceId, finder = false) {
    this.send({ t: 'subscribe', device_id: deviceId, finder });
  }

  /** Index to pass as `since` so only later messages count. */
  mark() {
    return this.msgs.length;
  }

  find(pred, since = 0) {
    for (let i = since; i < this.msgs.length; i++) if (pred(this.msgs[i].msg)) return this.msgs[i].msg;
    return null;
  }

  filter(pred, since = 0) {
    return this.msgs.slice(since).filter((e) => pred(e.msg));
  }

  async waitFor(pred, { since = 0, timeout = 10_000, label = 'message' } = {}) {
    const deadline = Date.now() + timeout;
    let i = since;
    for (;;) {
      for (; i < this.msgs.length; i++) if (pred(this.msgs[i].msg)) return this.msgs[i].msg;
      if (Date.now() > deadline) {
        const seen = [...new Set(this.msgs.slice(since).map((e) => e.msg.t))].join(', ') || 'nothing';
        throw new AssertionError(`${this.label}: no ${label} within ${Math.round(timeout / 1000)} s (received: ${seen})`);
      }
      if (this.closed) throw new AssertionError(`${this.label}: socket closed (${this.closed.code} ${this.closed.reason}) while waiting for ${label}`);
      await sleep(50);
    }
  }

  close() {
    if (this.pingTimer) clearInterval(this.pingTimer);
    try {
      this.ws?.close(1000, 'done');
    } catch {
      // ignore
    }
  }
}
