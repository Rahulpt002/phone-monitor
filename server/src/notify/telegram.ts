import type { FastifyBaseLogger } from 'fastify';
import { sleep } from '../util.js';

export type FetchFn = typeof fetch;

export const escapeHtml = (s: string): string =>
  s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

const TIMEOUT_MS = 10_000;
const MAX_TEXT = 4000; // Telegram's limit is 4096 characters

/**
 * Telegram Bot API sender (plan 2.2 "Alerts"). The bot token is part of the request URL, so the
 * URL is never logged.
 */
export class TelegramNotifier {
  constructor(
    private botToken: string | null,
    private chatId: string | null,
    private log: FastifyBaseLogger,
    private fetchFn: FetchFn = fetch,
  ) {}

  get configured(): boolean {
    return !!this.botToken && !!this.chatId;
  }

  /** Sends an HTML-formatted message. Returns true when Telegram accepted it. */
  async send(html: string): Promise<boolean> {
    if (!this.configured) return false;
    const url = `https://api.telegram.org/bot${this.botToken}/sendMessage`;
    const body = JSON.stringify({
      chat_id: this.chatId,
      text: html.length > MAX_TEXT ? `${html.slice(0, MAX_TEXT)}…` : html,
      parse_mode: 'HTML',
      disable_web_page_preview: true,
    });
    for (let attempt = 1; attempt <= 2; attempt++) {
      try {
        const res = await this.fetchFn(url, {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body,
          signal: AbortSignal.timeout(TIMEOUT_MS),
        });
        if (res.ok) return true;
        const payload = (await res.json().catch(() => null)) as { description?: string; parameters?: { retry_after?: number } } | null;
        const retryAfter = payload?.parameters?.retry_after;
        this.log.warn({ status: res.status, description: payload?.description }, 'Telegram rejected the alert');
        const retryable = res.status === 429 || res.status >= 500;
        if (!retryable || attempt === 2) return false;
        await sleep(Math.min(10, retryAfter ?? 2) * 1000);
      } catch (err) {
        this.log.warn({ err: { name: (err as Error).name, message: (err as Error).message } }, 'Telegram request failed');
        if (attempt === 2) return false;
        await sleep(2000);
      }
    }
    return false;
  }
}
