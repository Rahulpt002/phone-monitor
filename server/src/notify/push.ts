import { isIP } from 'node:net';
import webpush from 'web-push';
import { z } from 'zod';
import type { AlertKind, AlertSeverity } from '@hsm/shared';
import type { AppContext } from '../context.js';
import { badRequest } from '../errors.js';
import { iso } from '../util.js';

/**
 * Payload of every Web Push message. The Dashboard's service worker shows `title` / `body`,
 * groups by `tag` and opens `url` on click.
 */
export interface PushPayload {
  title: string;
  body: string;
  tag: string;
  kind: AlertKind;
  severity: AlertSeverity;
  device_id: string | null;
  ts: string;
  url: string;
}

export type PushSender = (
  subscription: webpush.PushSubscription,
  payload: string,
  options: webpush.RequestOptions,
) => Promise<{ statusCode: number }>;

export const PushSubscriptionSchema = z.object({
  endpoint: z
    .string()
    .max(2000)
    .refine((v) => {
      try {
        const u = new URL(v);
        // Push services are public HTTPS hosts; refusing IP literals and localhost keeps this
        // endpoint from being used to make the Server call internal addresses.
        return u.protocol === 'https:' && !isIP(u.hostname.replace(/^\[|\]$/g, '')) && u.hostname !== 'localhost';
      } catch {
        return false;
      }
    }, 'must be an https push service URL'),
  expirationTime: z.number().nullish(),
  keys: z.object({ p256dh: z.string().min(1).max(200), auth: z.string().min(1).max(100) }),
});

const KV_KEY = 'vapid_keys';

export class PushService {
  private publicKey: string | null = null;
  private privateKey: string | null = null;
  private subject = 'mailto:admin@example.com';

  constructor(
    private ctx: AppContext,
    private sender: PushSender = (sub, payload, opts) => webpush.sendNotification(sub, payload, opts),
  ) {}

  /** Uses VAPID keys from the environment, else the pair stored in the DB, else generates one. */
  async init(): Promise<void> {
    const { vapidPublicKey, vapidPrivateKey, vapidSubject, publicUrl } = this.ctx.config;
    if (vapidSubject) this.subject = vapidSubject;
    else if (publicUrl?.startsWith('https://')) this.subject = publicUrl;
    if (vapidPublicKey && vapidPrivateKey) {
      this.publicKey = vapidPublicKey;
      this.privateKey = vapidPrivateKey;
      return;
    }
    if (vapidPublicKey || vapidPrivateKey) {
      this.ctx.log.warn('Only one of VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY is set; using the stored or generated pair instead');
    }
    const row = await this.ctx.db.get<{ value: string }>('SELECT value FROM kv WHERE key = ?', [KV_KEY]);
    if (row) {
      try {
        const v = JSON.parse(row.value) as { publicKey: string; privateKey: string };
        this.publicKey = v.publicKey;
        this.privateKey = v.privateKey;
        return;
      } catch {
        this.ctx.log.warn('Stored VAPID keys are unreadable; generating a new pair');
      }
    }
    const keys = webpush.generateVAPIDKeys();
    await this.ctx.db.run(
      'INSERT INTO kv (key, value, updated_at) VALUES (?, ?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at',
      [KV_KEY, JSON.stringify(keys), iso(this.ctx.clock.now())],
    );
    this.publicKey = keys.publicKey;
    this.privateKey = keys.privateKey;
    this.ctx.log.info('Generated VAPID keys for Web Push and stored them in the database');
  }

  get configured(): boolean {
    return !!this.publicKey && !!this.privateKey;
  }

  get vapidPublicKey(): string | null {
    return this.publicKey;
  }

  async subscribe(userId: number, body: unknown, userAgent: string | null): Promise<void> {
    const parsed = PushSubscriptionSchema.safeParse(body);
    if (!parsed.success) throw badRequest('Invalid push subscription');
    const s = parsed.data;
    await this.ctx.db.run(
      `INSERT INTO push_subscriptions (endpoint, user_id, p256dh, auth, created_at, user_agent) VALUES (?, ?, ?, ?, ?, ?)
       ON CONFLICT (endpoint) DO UPDATE SET user_id = excluded.user_id, p256dh = excluded.p256dh, auth = excluded.auth`,
      [s.endpoint, userId, s.keys.p256dh, s.keys.auth, iso(this.ctx.clock.now()), userAgent?.slice(0, 300) ?? null],
    );
  }

  async unsubscribe(endpoint: string): Promise<void> {
    await this.ctx.db.run('DELETE FROM push_subscriptions WHERE endpoint = ?', [endpoint]);
  }

  /**
   * Sends to every stored subscription. Viewers get `viewerPayload` (null = skip them).
   * Subscriptions the push service reports as gone (404/410) are deleted.
   * Returns how many deliveries succeeded.
   */
  async sendAll(payload: PushPayload, viewerPayload: PushPayload | null): Promise<number> {
    if (!this.configured) return 0;
    const subs = await this.ctx.db.all<{ endpoint: string; p256dh: string; auth: string; role: string | null }>(
      `SELECT s.endpoint, s.p256dh, s.auth, u.role FROM push_subscriptions s JOIN users u ON u.id = s.user_id`,
    );
    if (subs.length === 0) return 0;
    const options: webpush.RequestOptions = {
      vapidDetails: { subject: this.subject, publicKey: this.publicKey as string, privateKey: this.privateKey as string },
      TTL: 24 * 3600,
      urgency: payload.severity === 'critical' ? 'high' : 'normal',
      timeout: 10_000,
    };
    const gone: string[] = [];
    let delivered = 0;
    await Promise.all(
      subs.map(async (s) => {
        const p = s.role === 'admin' ? payload : viewerPayload;
        if (!p) return;
        try {
          await this.sender({ endpoint: s.endpoint, keys: { p256dh: s.p256dh, auth: s.auth } }, JSON.stringify(p), options);
          delivered++;
        } catch (err) {
          const status = (err as { statusCode?: number }).statusCode;
          if (status === 404 || status === 410) gone.push(s.endpoint);
          else this.ctx.log.warn({ status, message: (err as Error).message }, 'Web Push delivery failed');
        }
      }),
    );
    if (gone.length) {
      await this.ctx.db.batch(gone.map((e) => ({ sql: 'DELETE FROM push_subscriptions WHERE endpoint = ?', args: [e] })));
      this.ctx.log.info({ removed: gone.length }, 'removed expired push subscriptions');
    }
    return delivered;
  }
}
