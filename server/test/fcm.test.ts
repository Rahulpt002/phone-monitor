import { createVerify, generateKeyPairSync } from 'node:crypto';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { HsmServer } from '../src/server.js';
import { parseServiceAccount } from '../src/notify/fcm.js';
import { adminWithTotp, api, FakeClock, makeServer, T0 } from './helpers.js';

const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const ACCOUNT = {
  type: 'service_account',
  project_id: 'hsm-test',
  client_email: 'fcm@hsm-test.iam.gserviceaccount.com',
  private_key: privateKey.export({ type: 'pkcs8', format: 'pem' }).toString(),
};

interface Call { url: string; init: RequestInit }

describe('FCM push (native app)', () => {
  let server: HsmServer;
  let clock: FakeClock;
  let calls: Call[];
  let gone: Set<string>;

  const fakeFetch = (async (url: string | URL, init: RequestInit = {}) => {
    const u = String(url);
    calls.push({ url: u, init });
    if (u.includes('oauth2.googleapis.com')) {
      return new Response(JSON.stringify({ access_token: 'at-1', expires_in: 3600 }), { status: 200 });
    }
    const body = JSON.parse(String(init.body)) as { message: { token: string } };
    if (gone.has(body.message.token)) {
      return new Response(JSON.stringify({ error: { status: 'NOT_FOUND', details: [{ errorCode: 'UNREGISTERED' }] } }), { status: 404 });
    }
    return new Response(JSON.stringify({ name: 'projects/hsm-test/messages/1' }), { status: 200 });
  }) as typeof fetch;

  beforeEach(async () => {
    clock = new FakeClock(T0);
    calls = [];
    gone = new Set();
    ({ server } = await makeServer({ clock, fetch: fakeFetch, config: { firebaseServiceAccount: JSON.stringify(ACCOUNT) } }));
  });

  afterEach(async () => {
    await server.close();
  });

  it('accepts raw and base64 service account keys', () => {
    expect(parseServiceAccount(JSON.stringify(ACCOUNT))?.project_id).toBe('hsm-test');
    expect(parseServiceAccount(Buffer.from(JSON.stringify(ACCOUNT)).toString('base64'))?.client_email).toBe(ACCOUNT.client_email);
    expect(parseServiceAccount('{"nope":1}')).toBeNull();
    expect(parseServiceAccount(null)).toBeNull();
  });

  it('registers a token for the session and sends alerts to it', async () => {
    const admin = await adminWithTotp(server, clock);
    const cfg = await api(server, 'GET', '/api/config');
    expect(cfg.body.fcm_configured).toBe(true);

    const token = 'fcm-token-'.padEnd(40, 'x');
    expect((await api(server, 'POST', '/api/push/fcm/register', { token: admin.token, body: { token } })).status).toBe(204);

    await server.ctx.alerts.test('admin');
    const auth = calls.find((c) => c.url.includes('oauth2.googleapis.com'));
    expect(auth).toBeTruthy();
    // The JWT assertion is signed with the service account key.
    const assertion = new URLSearchParams(String(auth!.init.body)).get('assertion')!;
    const [h, c, sig] = assertion.split('.');
    expect(createVerify('RSA-SHA256').update(`${h}.${c}`).verify(publicKey, Buffer.from(sig!, 'base64url'))).toBe(true);
    expect(JSON.parse(Buffer.from(c!, 'base64url').toString()).scope).toContain('firebase.messaging');

    const send = calls.find((x) => x.url === 'https://fcm.googleapis.com/v1/projects/hsm-test/messages:send');
    expect(send).toBeTruthy();
    expect((send!.init.headers as Record<string, string>).authorization).toBe('Bearer at-1');
    const msg = JSON.parse(String(send!.init.body)).message;
    expect(msg.token).toBe(token);
    expect(msg.data.title).toBeTruthy();
    expect(typeof msg.data.url).toBe('string');

    const alerts = await api(server, 'GET', '/api/alerts', { token: admin.token });
    expect(alerts.body[0].channels).toContain('push');
  });

  it('drops tokens FCM reports as unregistered, and on sign-out', async () => {
    const admin = await adminWithTotp(server, clock);
    const dead = 'dead-token-'.padEnd(40, 'y');
    const live = 'live-token-'.padEnd(40, 'z');
    await api(server, 'POST', '/api/push/fcm/register', { token: admin.token, body: { token: dead } });
    await api(server, 'POST', '/api/push/fcm/register', { token: admin.token, body: { token: live } });
    gone.add(dead);
    await server.ctx.alerts.test('admin');
    const rows = await server.ctx.db.all<{ token: string }>('SELECT token FROM fcm_tokens ORDER BY token');
    expect(rows.map((r) => r.token)).toEqual([live]);

    await api(server, 'POST', '/api/auth/logout', { token: admin.token });
    expect(await server.ctx.db.all('SELECT token FROM fcm_tokens')).toEqual([]);
  });

  it('needs a signed-in user and a sensible token', async () => {
    expect((await api(server, 'POST', '/api/push/fcm/register', { body: { token: 'x'.repeat(40) } })).status).toBe(401);
    const admin = await adminWithTotp(server, clock);
    expect((await api(server, 'POST', '/api/push/fcm/register', { token: admin.token, body: { token: 'short' } })).status).toBe(400);
  });
});
