import { EventEmitter } from 'node:events';
import { randomBytes, scryptSync } from 'node:crypto';
import type { FastifyRequest } from 'fastify';
import type { WebSocket } from 'ws';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { hashPassword, needsRehash, verifyPassword } from '../src/auth/password.js';
import { totpCode } from '../src/auth/totp.js';
import { Db } from '../src/db/client.js';
import { migrate, SCHEMA_VERSION } from '../src/db/migrations.js';
import type { HsmServer } from '../src/server.js';
import { sha256Hex } from '../src/util.js';
import { DASH_AUTH_TIMEOUT_MS, handleDashboardSocket } from '../src/ws/dashboard.js';
import {
  ADMIN,
  adminWithTotp,
  api,
  asWs,
  createUser,
  FakeClock,
  fakeSocket,
  loginWithTotp,
  makeServer,
  userWithTotp,
} from './helpers.js';

/** A socket stand-in that delivers messages through the same events as `ws`. */
class EmitSocket extends EventEmitter {
  sent: Record<string, unknown>[] = [];
  closed: { code?: number; reason?: string } | null = null;
  readyState = 1;
  bufferedAmount = 0;
  send(data: string) {
    this.sent.push(JSON.parse(data) as Record<string, unknown>);
  }
  close(code?: number, reason?: string) {
    if (this.readyState !== 1) return;
    this.closed = { code, reason };
    this.readyState = 3;
    this.emit('close', code, reason);
  }
  terminate() {
    this.close(1006);
  }
  ping() {}
  deliver(msg: unknown) {
    this.emit('message', Buffer.from(JSON.stringify(msg)), false);
  }
}

function openDash(server: HsmServer): EmitSocket {
  const sock = new EmitSocket();
  handleDashboardSocket(server.ctx, sock as unknown as WebSocket, {} as FastifyRequest);
  return sock;
}

const SUB = (endpoint: string) => ({ endpoint, keys: { p256dh: 'BPk', auth: 'x' } });
const subs = async (server: HsmServer) =>
  (await server.ctx.db.all<{ endpoint: string }>('SELECT endpoint FROM push_subscriptions ORDER BY endpoint')).map((r) => r.endpoint);

describe('TOTP is required for every role (plan 10, F09/F40)', () => {
  let server: HsmServer;
  let clock: FakeClock;
  afterEach(async () => server?.close());

  it('gives an admin without TOTP only the enrollment endpoints', async () => {
    ({ server, clock } = await makeServer());
    const login = await api(server, 'POST', '/api/auth/login', { body: ADMIN });
    expect(login.body.totp_required).toBe(false);
    const token = login.body.token as string;
    for (const [method, url] of [
      ['GET', '/api/devices'],
      ['GET', '/api/sms'],
      ['GET', '/api/events'],
      ['GET', '/api/users'],
      ['GET', '/api/push/vapid'],
    ] as const) {
      const r = await api(server, method, url, { token });
      expect(r.status, url).toBe(403);
      expect(r.body.error, url).toBe('totp_enrollment_required');
    }
    expect((await api(server, 'GET', '/api/auth/me', { token })).body.totp_enrollment_required).toBe(true);
    expect((await api(server, 'GET', '/api/config', { token })).status).toBe(200);
    const setup = await api(server, 'POST', '/api/auth/totp/setup', { token });
    expect(setup.status).toBe(200);
    const en = await api(server, 'POST', '/api/auth/totp/enable', { token, body: { code: totpCode(setup.body.secret, clock.now()) } });
    expect(en.status).toBe(200);
    expect((await api(server, 'GET', '/api/devices', { token })).status).toBe(200);
    expect((await api(server, 'GET', '/api/auth/me', { token })).body.totp_enrollment_required).toBe(false);
  });

  it('lets a user without TOTP change the password and sign out', async () => {
    ({ server, clock } = await makeServer());
    const token = (await api(server, 'POST', '/api/auth/login', { body: ADMIN })).body.token as string;
    const pw = await api(server, 'POST', '/api/auth/password', { token, body: { current: ADMIN.password, next: 'another-long-password' } });
    expect(pw.status).toBe(200);
    expect((await api(server, 'POST', '/api/auth/logout', { token })).status).toBe(204);
  });

  it('treats a viewer without TOTP the same way', async () => {
    ({ server, clock } = await makeServer());
    const { token } = await adminWithTotp(server, clock);
    const created = await createUser(server, token, { username: 'viewer1', password: 'viewer-password', role: 'viewer' });
    expect(created.status).toBe(201);
    const vlogin = await api(server, 'POST', '/api/auth/login', { body: { username: 'viewer1', password: 'viewer-password' } });
    expect(vlogin.body.totp_required).toBe(false);
    const vtoken = vlogin.body.token as string;
    const me = await api(server, 'GET', '/api/auth/me', { token: vtoken });
    expect(me.body).toMatchObject({ user: { role: 'viewer', totp_enabled: false }, totp_enrollment_required: true });
    for (const url of ['/api/devices', '/api/events', '/api/commands', '/api/alerts', '/api/finder/spots']) {
      const r = await api(server, 'GET', url, { token: vtoken });
      expect(r.status, url).toBe(403);
      expect(r.body.error, url).toBe('totp_enrollment_required');
    }
    const dash = openDash(server);
    dash.deliver({ t: 'auth', token: vtoken });
    await vi.waitFor(() => expect(dash.closed).not.toBeNull());
    expect(dash.sent).toEqual([{ t: 'auth_failed', message: 'totp_enrollment_required' }]);
    expect(dash.closed?.code).toBe(4001);
    // After enrolling, the same session reads.
    const setup = await api(server, 'POST', '/api/auth/totp/setup', { token: vtoken });
    await api(server, 'POST', '/api/auth/totp/enable', { token: vtoken, body: { code: totpCode(setup.body.secret, clock.now()) } });
    expect((await api(server, 'GET', '/api/devices', { token: vtoken })).status).toBe(200);
  });

  it('closes the live sockets of a user who disables TOTP (reconnect, then enroll)', async () => {
    ({ server, clock } = await makeServer());
    const { token, secret } = await adminWithTotp(server, clock);
    const dash = openDash(server);
    dash.deliver({ t: 'auth', token });
    await vi.waitFor(() => expect(dash.sent[0]).toMatchObject({ t: 'auth_ok' }));
    clock.advance(30_000);
    const off = await api(server, 'POST', '/api/auth/totp/disable', {
      token,
      body: { password: ADMIN.password, code: totpCode(secret, clock.now()) },
    });
    expect(off.status).toBe(200);
    expect(dash.closed?.code).toBe(4004);
    expect((await api(server, 'GET', '/api/devices', { token })).body.error).toBe('totp_enrollment_required');
  });
});

describe('user management needs re-authentication and no self-edits (F00)', () => {
  let server: HsmServer;
  let clock: FakeClock;
  afterEach(async () => server?.close());

  it('refuses POST, PATCH and DELETE without the acting admin\'s password', async () => {
    ({ server, clock } = await makeServer());
    const { token } = await adminWithTotp(server, clock);
    const body = { username: 'backdoor', password: 'backdoor-password', role: 'admin' };
    const none = await api(server, 'POST', '/api/users', { token, body });
    expect(none.status).toBe(401);
    expect(none.body.error).toBe('reauth_required');
    const wrong = await api(server, 'POST', '/api/users', { token, body: { ...body, reauth_password: 'not-my-password' } });
    expect(wrong.status).toBe(401);
    expect(wrong.body.error).toBe('reauth_required');
    expect(server.ctx.auth.listUsers()).toHaveLength(1);
    const ok = await api(server, 'POST', '/api/users', { token, body: { ...body, reauth_password: ADMIN.password } });
    expect(ok.status).toBe(201);
    const id = ok.body.id as number;

    const patch = await api(server, 'PATCH', `/api/users/${id}`, { token, body: { role: 'viewer' } });
    expect(patch.status).toBe(401);
    expect(patch.body.error).toBe('reauth_required');
    const del = await api(server, 'DELETE', `/api/users/${id}`, { token });
    expect(del.status).toBe(401);
    expect(del.body.error).toBe('reauth_required');
    expect((await api(server, 'PATCH', `/api/users/${id}`, { token, body: { role: 'viewer', reauth_password: ADMIN.password } })).status).toBe(200);
    expect((await api(server, 'DELETE', `/api/users/${id}`, { token, body: { reauth_password: ADMIN.password } })).status).toBe(204);
  });

  it('refuses changing your own password or TOTP through PATCH /users', async () => {
    ({ server, clock } = await makeServer());
    const { token } = await adminWithTotp(server, clock);
    const me = (await api(server, 'GET', '/api/auth/me', { token })).body.user;
    const pw = await api(server, 'PATCH', `/api/users/${me.id}`, {
      token,
      body: { password: 'attacker-password', reauth_password: ADMIN.password },
    });
    expect(pw.status).toBe(400);
    expect(pw.body.error).toBe('bad_request');
    const reset = await api(server, 'PATCH', `/api/users/${me.id}`, { token, body: { reset_totp: true, reauth_password: ADMIN.password } });
    expect(reset.status).toBe(400);
    // Nothing changed: the old password still signs in, TOTP is still on, the session is alive.
    expect((await api(server, 'POST', '/api/auth/login', { body: ADMIN, ip: '10.0.0.5' })).body.totp_required).toBe(true);
    expect((await api(server, 'GET', '/api/auth/me', { token })).body.user.totp_enabled).toBe(true);
  });
});

describe('push subscriptions follow the session that created them (F04)', () => {
  let server: HsmServer;
  let clock: FakeClock;
  afterEach(async () => server?.close());

  it('stores the session and deletes it on logout, password change, reset_totp and expiry', async () => {
    ({ server, clock } = await makeServer({ config: { sessionTtlDays: 1 } }));
    const push = server.ctx.push;
    const admin = await adminWithTotp(server, clock);
    const viewer = await userWithTotp(server, clock, admin.token, { username: 'viewer1', password: 'viewer-password', role: 'viewer' });
    const viewer2 = await loginWithTotp(server, clock, { username: 'viewer1', password: 'viewer-password' }, viewer.secret);
    await push.subscribe(viewer.id, SUB('https://push.example.com/v1'), null, sha256Hex(viewer.token));
    await push.subscribe(viewer.id, SUB('https://push.example.com/v2'), null, sha256Hex(viewer2));
    const row = await server.ctx.db.get<{ session_hash: string }>('SELECT session_hash FROM push_subscriptions WHERE endpoint = ?', [
      'https://push.example.com/v1',
    ]);
    expect(row?.session_hash).toBe(sha256Hex(viewer.token));

    // Logout removes only that session's subscription.
    expect((await api(server, 'POST', '/api/auth/logout', { token: viewer2 })).status).toBe(204);
    expect(await subs(server)).toEqual(['https://push.example.com/v1']);

    // A password change keeps the current session's subscription and drops the others.
    const viewer3 = await loginWithTotp(server, clock, { username: 'viewer1', password: 'viewer-password' }, viewer.secret);
    await push.subscribe(viewer.id, SUB('https://push.example.com/v3'), null, sha256Hex(viewer3));
    const pw = await api(server, 'POST', '/api/auth/password', {
      token: viewer.token,
      body: { current: 'viewer-password', next: 'viewer-password-2' },
    });
    expect(pw.status).toBe(200);
    expect(await subs(server)).toEqual(['https://push.example.com/v1']);

    // reset_totp (lost phone) removes every subscription of that user.
    const reset = await api(server, 'PATCH', `/api/users/${viewer.id}`, {
      token: admin.token,
      body: { reset_totp: true, reauth_password: ADMIN.password },
    });
    expect(reset.status).toBe(200);
    expect(await subs(server)).toEqual([]);

    // Expired sessions lose theirs at the next cleanup, and get nothing in the meantime.
    const adminSub = SUB('https://push.example.com/a1');
    await push.subscribe(1, adminSub, null, sha256Hex(admin.token));
    clock.advance(86_400_000 + 1);
    await server.ctx.auth.cleanup();
    expect(await subs(server)).toEqual([]);
  });

  it('POST /api/push/subscribe records the requesting session, so logout removes it', async () => {
    ({ server, clock } = await makeServer());
    const admin = await adminWithTotp(server, clock);
    const r = await api(server, 'POST', '/api/push/subscribe', { token: admin.token, body: { subscription: SUB('https://push.example.com/h1') } });
    expect(r.status).toBe(204);
    const row = await server.ctx.db.get<{ session_hash: string | null }>('SELECT session_hash FROM push_subscriptions WHERE endpoint = ?', [
      'https://push.example.com/h1',
    ]);
    expect(row?.session_hash).toBe(sha256Hex(admin.token));
    // Another session's logout leaves it alone; its own logout removes it.
    const other = await loginWithTotp(server, clock, ADMIN, admin.secret);
    expect((await api(server, 'POST', '/api/auth/logout', { token: other })).status).toBe(204);
    expect(await subs(server)).toEqual(['https://push.example.com/h1']);
    expect((await api(server, 'POST', '/api/auth/logout', { token: admin.token })).status).toBe(204);
    expect(await subs(server)).toEqual([]);
  });

  it('drops all of a user\'s subscriptions when an admin sets a new password for them', async () => {
    ({ server, clock } = await makeServer());
    const admin = await adminWithTotp(server, clock);
    const viewer = await userWithTotp(server, clock, admin.token, { username: 'viewer1', password: 'viewer-password', role: 'viewer' });
    await server.ctx.push.subscribe(viewer.id, SUB('https://push.example.com/v1'), null, sha256Hex(viewer.token));
    await server.ctx.push.subscribe(viewer.id, SUB('https://push.example.com/legacy'), null, null);
    const r = await api(server, 'PATCH', `/api/users/${viewer.id}`, {
      token: admin.token,
      body: { password: 'new-viewer-password', reauth_password: ADMIN.password },
    });
    expect(r.status).toBe(200);
    expect(await subs(server)).toEqual([]);
  });

  it('does not push to signed-out sessions or to users without TOTP', async () => {
    const pushed: string[] = [];
    ({ server, clock } = await makeServer({
      pushSender: async (sub) => {
        pushed.push(sub.endpoint);
        return { statusCode: 201 };
      },
    }));
    const admin = await adminWithTotp(server, clock);
    await server.ctx.push.subscribe(1, SUB('https://push.example.com/live'), null, sha256Hex(admin.token));
    await server.ctx.push.subscribe(1, SUB('https://push.example.com/dead'), null, sha256Hex('no-such-session'));
    const payload = { title: 't', body: 'b', tag: 'x', kind: 'server_test', severity: 'info', device_id: null, ts: '', url: '/' } as const;
    expect(await server.ctx.push.sendAll(payload, payload)).toBe(1);
    expect(pushed).toEqual(['https://push.example.com/live']);
  });

  it('upgrades an existing database: the column is added and old rows survive', async () => {
    const db = await Db.open(':memory:', null);
    try {
      expect(await migrate(db, 1)).toEqual([1]);
      await db.run(
        "INSERT INTO push_subscriptions (endpoint, user_id, p256dh, auth, created_at) VALUES ('https://push.example.com/old', 1, 'k', 'a', '2026-01-01')",
      );
      expect(await migrate(db)).toEqual(Array.from({ length: SCHEMA_VERSION - 1 }, (_, i) => i + 2));
      const rows = await db.all<{ endpoint: string; session_hash: string | null }>('SELECT endpoint, session_hash FROM push_subscriptions');
      expect(rows).toEqual([{ endpoint: 'https://push.example.com/old', session_hash: null }]);
      expect(await migrate(db)).toEqual([]);
    } finally {
      db.close();
    }
  });

  it('scrubs hotspot passwords stored before secret_args were blanked, except where the Agent may still need them', async () => {
    const db = await Db.open(':memory:', null);
    try {
      await migrate(db, 2);
      const add = (id: string, name: string, status: string, args: Record<string, unknown>) =>
        db.run(
          `INSERT INTO commands (id, device_id, name, args, source, user, status, created_at, created_ms, updated_ms)
           VALUES (?, 'd_1', ?, ?, 'dashboard', 'admin', ?, '2026-01-01T00:00:00.000Z', 1, 1)`,
          [id, name, JSON.stringify(args), status],
        );
      const pw = { enabled: true, ssid: 'Pole', password: 'OldSecret123' };
      for (const st of ['done', 'failed', 'rejected', 'expired', 'acked', 'revert_armed', 'kept', 'reverted']) await add(`c_${st}`, 'hotspot', st, pw);
      await add('c_queued', 'hotspot', 'queued', pw);
      await add('c_sent', 'hotspot', 'sent', pw);
      await add('c_other', 'shell', 'done', { cmd: 'echo password', password: 'not-a-secret-arg' });
      expect(await migrate(db)).toEqual(Array.from({ length: SCHEMA_VERSION - 2 }, (_, i) => i + 3));
      const rows = new Map(
        (await db.all<{ id: string; args: string }>('SELECT id, args FROM commands')).map((r) => [r.id, JSON.parse(r.args)]),
      );
      for (const st of ['done', 'failed', 'rejected', 'expired', 'acked', 'revert_armed', 'kept', 'reverted']) {
        expect(rows.get(`c_${st}`)).toEqual({ enabled: true, ssid: 'Pole' });
      }
      expect(rows.get('c_queued')).toEqual(pw);
      expect(rows.get('c_sent')).toEqual(pw);
      expect(rows.get('c_other')).toEqual({ cmd: 'echo password', password: 'not-a-secret-arg' });
    } finally {
      db.close();
    }
  });
});

describe('lockout (F19, F39)', () => {
  let server: HsmServer;
  let clock: FakeClock;
  afterEach(async () => server?.close());

  it('evaluates at most 5 concurrent wrong passwords for one username', async () => {
    ({ server, clock } = await makeServer());
    const results = await Promise.all(
      Array.from({ length: 20 }, (_, i) =>
        api(server, 'POST', '/api/auth/login', { body: { username: 'admin', password: `wrong-password-${i}` }, ip: `10.1.0.${i}` }),
      ),
    );
    const evaluated = results.filter((r) => r.status === 401).length;
    const locked = results.filter((r) => r.status === 429).length;
    expect(evaluated).toBeLessThanOrEqual(5);
    expect(evaluated + locked).toBe(20);
    expect((await api(server, 'POST', '/api/auth/login', { body: ADMIN, ip: '10.2.0.1' })).status).toBe(429);
  });

  it('does not let failed logins block a signed-in admin\'s re-authentication', async () => {
    ({ server, clock } = await makeServer());
    const { token } = await adminWithTotp(server, clock);
    for (let i = 0; i < 5; i++) {
      await api(server, 'POST', '/api/auth/login', { body: { username: 'admin', password: 'wrong-password' }, ip: `10.3.0.${i}` });
    }
    expect((await api(server, 'POST', '/api/auth/login', { body: ADMIN, ip: '10.3.1.1' })).status).toBe(429);
    const pw = await api(server, 'POST', '/api/auth/password', { token, body: { current: ADMIN.password, next: 'another-long-password' } });
    expect(pw.status).toBe(200);
    const r = await createUser(server, token, { username: 'viewer1', password: 'viewer-password', role: 'viewer' }, 'another-long-password');
    expect(r.status).toBe(201);
  });

  it('counts re-authentication failures per session', async () => {
    ({ server, clock } = await makeServer());
    const { token, secret } = await adminWithTotp(server, clock);
    const other = await loginWithTotp(server, clock, ADMIN, secret);
    const body = { username: 'viewer1', password: 'viewer-password', role: 'viewer' as const };
    for (let i = 0; i < 5; i++) {
      const r = await api(server, 'POST', '/api/users', { token, body: { ...body, reauth_password: 'wrong-password' } });
      expect(r.status).toBe(401);
    }
    const locked = await api(server, 'POST', '/api/users', { token, body: { ...body, reauth_password: ADMIN.password } });
    expect(locked.status).toBe(429);
    expect(locked.body.error).toBe('locked');
    // The other session and the sign-in itself are unaffected.
    expect((await createUser(server, other, body)).status).toBe(201);
    expect((await api(server, 'POST', '/api/auth/login', { body: ADMIN, ip: '10.4.0.1' })).body.totp_required).toBe(true);
  });

  /** Full two-step sign-in presenting `device_token`; returns the session and the rotated device token. */
  async function signInWithDevice(deviceToken: string | undefined, ip: string, secret: string) {
    clock.advance(30_000);
    const step1 = await api(server, 'POST', '/api/auth/login', { body: { ...ADMIN, device_token: deviceToken }, ip });
    if (step1.status !== 200) return { status: step1.status, body: step1.body, token: null, device: null };
    const step2 = await api(server, 'POST', '/api/auth/totp', {
      body: { pending_token: step1.body.pending_token, code: totpCode(secret, clock.now()), device_token: deviceToken },
      ip,
    });
    return { status: step2.status, body: step2.body, token: step2.body.token as string, device: step2.body.device_token as string };
  }

  it('lets a browser that signed in before sign in while strangers have locked the username (F39)', async () => {
    ({ server, clock } = await makeServer());
    const { secret } = await adminWithTotp(server, clock);
    const first = await signInWithDevice(undefined, '10.5.0.1', secret);
    expect(first.status).toBe(200);
    expect(first.device).toMatch(/^[A-Za-z0-9_-]{20,}$/);
    // Five wrong passwords from elsewhere lock the username for everyone else ...
    for (let i = 0; i < 5; i++) {
      await api(server, 'POST', '/api/auth/login', { body: { username: 'admin', password: 'wrong-password' }, ip: `10.6.0.${i}` });
    }
    expect((await api(server, 'POST', '/api/auth/login', { body: ADMIN, ip: '10.6.1.1' })).status).toBe(429);
    // ... but not for the known browser, from any network; its token is rotated on every sign-in.
    const again = await signInWithDevice(first.device!, '10.7.0.1', secret);
    expect(again.status).toBe(200);
    expect(again.device).toBeTruthy();
    expect(again.device).not.toBe(first.device);
    // The old token was replaced: it is just an unknown token now.
    expect((await api(server, 'POST', '/api/auth/login', { body: { ...ADMIN, device_token: first.device }, ip: '10.7.0.2' })).status).toBe(429);
    // A token for another user, or a made-up one, gets no exemption.
    expect((await api(server, 'POST', '/api/auth/login', { body: { ...ADMIN, device_token: 'x'.repeat(43) }, ip: '10.7.0.3' })).status).toBe(429);
  });

  it('locks a known device after its own 5 failures, without touching the username lock', async () => {
    ({ server, clock } = await makeServer());
    const { secret } = await adminWithTotp(server, clock);
    const { device } = await signInWithDevice(undefined, '10.8.0.1', secret);
    for (let i = 0; i < 5; i++) {
      const r = await api(server, 'POST', '/api/auth/login', { body: { username: 'admin', password: 'wrong-password', device_token: device }, ip: `10.8.1.${i}` });
      expect(r.status).toBe(401);
    }
    expect((await api(server, 'POST', '/api/auth/login', { body: { ...ADMIN, device_token: device }, ip: '10.8.2.1' })).status).toBe(429);
    expect((await api(server, 'POST', '/api/auth/login', { body: ADMIN, ip: '10.8.2.2' })).body.totp_required).toBe(true);
  });

  it('forgets known devices when the password changes or sessions are revoked', async () => {
    ({ server, clock } = await makeServer());
    const { secret } = await adminWithTotp(server, clock);
    const { token, device } = await signInWithDevice(undefined, '10.9.0.1', secret);
    const pw = await api(server, 'POST', '/api/auth/password', { token: token!, body: { current: ADMIN.password, next: 'another-long-password' } });
    expect(pw.status).toBe(200);
    for (let i = 0; i < 5; i++) {
      await api(server, 'POST', '/api/auth/login', { body: { username: 'admin', password: 'wrong-password' }, ip: `10.9.1.${i}` });
    }
    const r = await api(server, 'POST', '/api/auth/login', { body: { username: 'admin', password: 'another-long-password', device_token: device }, ip: '10.9.2.1' });
    expect(r.status).toBe(429);
  });
});

describe('password hashing cost (F41)', () => {
  let server: HsmServer;
  let clock: FakeClock;
  afterEach(async () => server?.close());

  it('hashes with N=2^15, r=8, p=3', async () => {
    const h = await hashPassword('a long passphrase');
    expect(h).toMatch(/^scrypt\$32768\$8\$3\$/);
    expect(needsRehash(h)).toBe(false);
    expect(await verifyPassword('a long passphrase', h)).toBe(true);
  });

  it('re-hashes an older, cheaper hash on the next successful sign-in', async () => {
    ({ server, clock } = await makeServer());
    const salt = randomBytes(16);
    const key = scryptSync(ADMIN.password.normalize('NFKC'), salt, 64, { N: 16384, r: 8, p: 1 });
    const old = `scrypt$16384$8$1$${salt.toString('base64')}$${key.toString('base64')}`;
    expect(needsRehash(old)).toBe(true);
    await server.ctx.db.run('UPDATE users SET password_hash = ? WHERE id = 1', [old]);
    await server.ctx.auth.load();
    expect((await api(server, 'POST', '/api/auth/login', { body: { ...ADMIN, password: 'wrong-password' } })).status).toBe(401);
    expect((await server.ctx.db.get<{ password_hash: string }>('SELECT password_hash FROM users WHERE id = 1'))?.password_hash).toBe(old);
    expect((await api(server, 'POST', '/api/auth/login', { body: ADMIN })).status).toBe(200);
    const stored = (await server.ctx.db.get<{ password_hash: string }>('SELECT password_hash FROM users WHERE id = 1'))?.password_hash;
    expect(stored).toMatch(/^scrypt\$32768\$8\$3\$/);
    expect(server.ctx.auth.getUser(1)?.password_hash).toBe(stored);
    expect(await verifyPassword(ADMIN.password, stored as string)).toBe(true);
  });
});

describe('dashboard socket close codes (F43, F44)', () => {
  let server: HsmServer;
  let clock: FakeClock;
  afterEach(async () => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    await server?.close();
  });

  it('closes with 1013, not auth_failed, when the session lookup throws', async () => {
    ({ server, clock } = await makeServer());
    vi.spyOn(server.ctx.auth, 'authenticate').mockRejectedValue(new Error('database unreachable'));
    const dash = openDash(server);
    dash.deliver({ t: 'auth', token: 'any-token' });
    await vi.waitFor(() => expect(dash.closed).not.toBeNull());
    expect(dash.closed?.code).toBe(1013);
    expect(dash.sent.filter((m) => m.t === 'auth_failed')).toEqual([]);
  });

  it('closes with 1013 when the session lookup outlasts the auth timeout', async () => {
    ({ server, clock } = await makeServer());
    vi.spyOn(server.ctx.auth, 'authenticate').mockReturnValue(new Promise(() => {}));
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    const dash = openDash(server);
    dash.deliver({ t: 'auth', token: 'any-token' });
    await vi.advanceTimersByTimeAsync(DASH_AUTH_TIMEOUT_MS + 10);
    expect(dash.closed?.code).toBe(1013);
    expect(dash.sent.filter((m) => m.t === 'auth_failed')).toEqual([]);
    expect(server.ctx.hub.dashboards.size).toBe(0);
  });

  it('still answers auth_failed + 4001 for a session that does not exist', async () => {
    ({ server, clock } = await makeServer());
    const dash = openDash(server);
    dash.deliver({ t: 'auth', token: 'no-such-token' });
    await vi.waitFor(() => expect(dash.closed).not.toBeNull());
    expect(dash.closed?.code).toBe(4001);
    expect(dash.sent).toEqual([{ t: 'auth_failed', message: 'invalid or expired session' }]);
  });

  it('closes a user\'s sockets with 4004 on a role change and keeps the session', async () => {
    ({ server, clock } = await makeServer());
    const admin = await adminWithTotp(server, clock);
    const viewer = await userWithTotp(server, clock, admin.token, { username: 'viewer1', password: 'viewer-password', role: 'viewer' });
    const dash = openDash(server);
    dash.deliver({ t: 'auth', token: viewer.token });
    await vi.waitFor(() => expect(dash.sent[0]).toMatchObject({ t: 'auth_ok', user: { role: 'viewer' } }));
    const adminDash = fakeSocket();
    const adminConn = server.ctx.hub.addDashboard(asWs(adminDash));
    adminConn.user = server.ctx.auth.listUsers()[0] ?? null;
    const r = await api(server, 'PATCH', `/api/users/${viewer.id}`, {
      token: admin.token,
      body: { role: 'admin', reauth_password: ADMIN.password },
    });
    expect(r.status).toBe(200);
    expect(dash.closed?.code).toBe(4004);
    expect(adminDash.closed).toBeNull();
    const me = await api(server, 'GET', '/api/auth/me', { token: viewer.token });
    expect(me.status).toBe(200);
    expect(me.body.user.role).toBe('admin');
    const again = openDash(server);
    again.deliver({ t: 'auth', token: viewer.token });
    await vi.waitFor(() => expect(again.sent[0]).toMatchObject({ t: 'auth_ok', user: { role: 'admin' } }));
  });
});
