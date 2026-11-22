import { createSign } from 'node:crypto';
import { z } from 'zod';
import type { AppContext } from '../context.js';
import { parseOrThrow } from '../errors.js';
import type { PushPayload } from './push.js';
import type { FetchFn } from './telegram.js';

/**
 * Firebase Cloud Messaging (HTTP v1) for the native Android app. Configured with the service
 * account key of the Firebase project in FIREBASE_SERVICE_ACCOUNT (the JSON file, raw or base64).
 * Tokens are stored per signed-in session, like Web Push subscriptions, so signing out or a
 * revoked session stops delivery.
 */

interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
  token_uri?: string;
}

export const FcmRegisterSchema = z.object({
  token: z.string().min(20).max(4096),
  platform: z.string().max(20).optional(),
});
export const FcmUnregisterSchema = z.object({ token: z.string().min(1).max(4096) });

const SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';
const b64url = (b: Buffer | string) => Buffer.from(b).toString('base64url');

export function parseServiceAccount(raw: string | null): ServiceAccount | null {
  if (!raw || !raw.trim()) return null;
  const text = raw.trim().startsWith('{') ? raw.trim() : Buffer.from(raw.trim(), 'base64').toString('utf8');
  try {
    const j = JSON.parse(text) as Partial<ServiceAccount>;
    if (typeof j.project_id !== 'string' || typeof j.client_email !== 'string' || typeof j.private_key !== 'string') return null;
    return { project_id: j.project_id, client_email: j.client_email, private_key: j.private_key, token_uri: j.token_uri };
  } catch {
    return null;
  }
}

export class FcmService {
  private account: ServiceAccount | null;
  private accessToken: string | null = null;
  private accessTokenExp = 0;

  constructor(
    private ctx: AppContext,
    private fetchFn: FetchFn = fetch,
  ) {
    this.account = parseServiceAccount(ctx.config.firebaseServiceAccount);
    if (ctx.config.firebaseServiceAccount && !this.account) {
      ctx.log.error('FIREBASE_SERVICE_ACCOUNT is set but is not a valid service account key; FCM push is off');
    }
  }

  get configured(): boolean {
    return this.account !== null;
  }

  async register(userId: number, sessionHash: string | null, body: unknown): Promise<void> {
    const b = parseOrThrow(FcmRegisterSchema, body, 'FCM registration');
    await this.ctx.db.run(
      `INSERT INTO fcm_tokens (token, user_id, session_hash, platform, created_at) VALUES (?, ?, ?, ?, ?)
       ON CONFLICT(token) DO UPDATE SET user_id = excluded.user_id, session_hash = excluded.session_hash,
         platform = excluded.platform, created_at = excluded.created_at`,
      [b.token, userId, sessionHash, b.platform ?? 'android', this.ctx.clock.now()],
    );
  }

  async unregister(userId: number, body: unknown): Promise<void> {
    const b = parseOrThrow(FcmUnregisterSchema, body, 'FCM unregistration');
    await this.ctx.db.run('DELETE FROM fcm_tokens WHERE token = ? AND user_id = ?', [b.token, userId]);
  }

  /** OAuth access token for the FCM scope, from a self-signed JWT (cached until shortly before expiry). */
  private async token(): Promise<string> {
    const now = Math.floor(this.ctx.clock.now() / 1000);
    if (this.accessToken && now < this.accessTokenExp - 60) return this.accessToken;
    const a = this.account as ServiceAccount;
    const aud = a.token_uri ?? 'https://oauth2.googleapis.com/token';
    const header = b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
    const claims = b64url(JSON.stringify({ iss: a.client_email, scope: SCOPE, aud, iat: now, exp: now + 3600 }));
    const signature = createSign('RSA-SHA256').update(`${header}.${claims}`).sign(a.private_key).toString('base64url');
    const res = await this.fetchFn(aud, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: `${header}.${claims}.${signature}` }),
    });
    if (!res.ok) throw new Error(`FCM auth failed: HTTP ${res.status}`);
    const j = (await res.json()) as { access_token?: string; expires_in?: number };
    if (!j.access_token) throw new Error('FCM auth failed: no access_token');
    this.accessToken = j.access_token;
    this.accessTokenExp = now + (j.expires_in ?? 3600);
    return this.accessToken;
  }

  /**
   * Sends to every token whose user has TOTP and whose session is still valid; viewers get
   * `viewerPayload` (null = skip them). Tokens FCM reports as unregistered are deleted.
   * Returns how many deliveries succeeded.
   */
  async sendAll(payload: PushPayload, viewerPayload: PushPayload | null, alertId: number | null = null): Promise<number> {
    if (!this.account) return 0;
    const rows = await this.ctx.db.all<{ token: string; role: string | null }>(
      `SELECT t.token, u.role FROM fcm_tokens t JOIN users u ON u.id = t.user_id
       WHERE u.totp_secret IS NOT NULL
         AND (t.session_hash IS NULL OR EXISTS (
           SELECT 1 FROM sessions x WHERE x.token_hash = t.session_hash AND x.kind = 'session' AND x.expires_at > ?))`,
      [this.ctx.clock.now()],
    );
    if (rows.length === 0) return 0;
    let access: string;
    try {
      access = await this.token();
    } catch (err) {
      this.ctx.log.warn({ err: (err as Error).message }, 'FCM: could not get an access token');
      return 0;
    }
    const url = `https://fcm.googleapis.com/v1/projects/${this.account.project_id}/messages:send`;
    const gone: string[] = [];
    let delivered = 0;
    await Promise.all(
      rows.map(async (r) => {
        const p = r.role === 'admin' ? payload : viewerPayload;
        if (!p) return;
        const data: Record<string, string> = {
          title: p.title,
          body: p.body,
          url: p.url,
          kind: p.kind,
          severity: p.severity,
          tag: p.tag,
          ts: p.ts,
          ...(p.device_id ? { device_id: p.device_id } : {}),
          ...(alertId != null ? { alert_id: String(alertId) } : {}),
        };
        try {
          const res = await this.fetchFn(url, {
            method: 'POST',
            headers: { authorization: `Bearer ${access}`, 'content-type': 'application/json' },
            body: JSON.stringify({
              message: {
                token: r.token,
                data,
                android: { priority: p.severity === 'info' ? 'NORMAL' : 'HIGH', ttl: '86400s', collapse_key: p.tag.slice(0, 100) },
              },
            }),
          });
          if (res.ok) {
            delivered++;
            return;
          }
          const text = await res.text().catch(() => '');
          if (res.status === 404 || (res.status === 400 && /UNREGISTERED|INVALID_ARGUMENT/.test(text) && /registration token/i.test(text)) || /UNREGISTERED/.test(text)) {
            gone.push(r.token);
          } else {
            this.ctx.log.warn({ status: res.status, body: text.slice(0, 300) }, 'FCM delivery failed');
          }
        } catch (err) {
          this.ctx.log.warn({ err: (err as Error).message }, 'FCM delivery failed');
        }
      }),
    );
    if (gone.length) {
      await this.ctx.db.batch(gone.map((t) => ({ sql: 'DELETE FROM fcm_tokens WHERE token = ?', args: [t] })));
      this.ctx.log.info({ removed: gone.length }, 'removed unregistered FCM tokens');
    }
    return delivered;
  }
}
