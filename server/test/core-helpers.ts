import type { HsmServer } from '../src/server.js';
import { iso } from '../src/util.js';
import { FakeClock, makeServer, T0 } from './helpers.js';

export interface TgSent {
  url: string;
  body: { chat_id: string; text: string; parse_mode?: string };
}

/** Telegram stand-in; `gate` (when set) holds every request until it resolves. */
export function fakeTelegram() {
  const sent: TgSent[] = [];
  const state: { gate: Promise<void> | null } = { gate: null };
  const fetchFn = (async (url: string | URL | Request, init?: RequestInit) => {
    sent.push({ url: String(url), body: JSON.parse(String(init?.body)) });
    if (state.gate) await state.gate;
    return new Response(JSON.stringify({ ok: true }), { status: 200 });
  }) as typeof fetch;
  return { sent, fetchFn, state };
}

/** A server with Telegram configured, started long enough ago that the startup grace is over. */
export async function alertServer(opts: { listen?: boolean } = {}) {
  const tg = fakeTelegram();
  const clock = new FakeClock(T0);
  const { server } = await makeServer({
    clock,
    fetch: tg.fetchFn,
    config: { telegramBotToken: '123:abc', telegramChatId: '42', publicUrl: 'https://hsm.example.com' },
  });
  clock.advance(90_000);
  return { server, clock, tg };
}

export const alertRows = async (server: HsmServer) =>
  server.ctx.db.all<{ kind: string; title: string; message: string; data: string | null; severity: string }>(
    'SELECT kind, title, message, data, severity FROM alerts_sent ORDER BY id',
  );

export const alertKinds = async (server: HsmServer) => (await alertRows(server)).map((r) => r.kind);

export const eventMsg = (
  seq: number,
  id: string,
  kind: string,
  tsMs: number,
  extra: { severity?: string; message?: string; data?: Record<string, unknown> } = {},
) => ({
  t: 'event',
  seq,
  event: { id, ts: iso(tsMs), kind, severity: extra.severity ?? 'info', message: extra.message ?? kind, ...(extra.data ? { data: extra.data } : {}) },
});

/** Lets fire-and-forget work (alerts raised from after-commit steps) finish. */
export const settle = (ms = 20) => new Promise((r) => setTimeout(r, ms));

/** Polls `cond` in real time (for messages travelling over a real socket). */
export async function until(cond: () => boolean, what: string, timeoutMs = 3000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (!cond()) {
    if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}`);
    await new Promise((r) => setTimeout(r, 10));
  }
}
