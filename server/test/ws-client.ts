import WebSocket from 'ws';

export type Msg = Record<string, any>;

/** Minimal WebSocket test client that records every message. */
export class Client {
  readonly messages: Msg[] = [];
  closed: { code: number; reason: string } | null = null;
  private listeners: (() => void)[] = [];

  private constructor(readonly ws: WebSocket) {
    ws.on('message', (data) => {
      this.messages.push(JSON.parse(data.toString()) as Msg);
      this.notify();
    });
    ws.on('close', (code, reason) => {
      this.closed = { code, reason: reason.toString() };
      this.notify();
    });
  }

  private notify() {
    for (const l of this.listeners.splice(0)) l();
  }

  static open(url: string, headers: Record<string, string> = {}): Promise<Client> {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(url, { headers });
      const c = new Client(ws);
      ws.once('open', () => resolve(c));
      ws.once('error', reject);
    });
  }

  send(msg: unknown) {
    this.ws.send(typeof msg === 'string' ? msg : JSON.stringify(msg));
  }

  async waitFor(pred: (m: Msg) => boolean, what: string, timeoutMs = 3000): Promise<Msg> {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const found = this.messages.find(pred);
      if (found) return found;
      if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}; got ${JSON.stringify(this.messages.map((m) => m.t))}`);
      await new Promise<void>((r) => {
        this.listeners.push(r);
        setTimeout(r, 50);
      });
    }
  }

  async waitClosed(timeoutMs = 3000) {
    const deadline = Date.now() + timeoutMs;
    while (!this.closed) {
      if (Date.now() > deadline) throw new Error('socket did not close');
      await new Promise((r) => setTimeout(r, 20));
    }
    return this.closed;
  }
}
