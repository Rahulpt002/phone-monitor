import { afterEach, describe, expect, it } from 'vitest';
import { hashPassword, verifyPassword } from '../src/auth/password.js';
import { base32Decode, base32Encode, hotp, totpCode, verifyTotp } from '../src/auth/totp.js';
import { LOCK_MS } from '../src/auth/lockout.js';
import type { HsmServer } from '../src/server.js';
import { ADMIN, adminWithTotp, api, createUser, FakeClock, makeServer, T0, userWithTotp } from './helpers.js';

describe('scrypt password hashing', () => {
  it('verifies the right password and rejects others', async () => {
    const h = await hashPassword('a long passphrase');
    expect(h).toMatch(/^scrypt\$32768\$8\$3\$[A-Za-z0-9+/=]+\$[A-Za-z0-9+/=]+$/);
    expect(await verifyPassword('a long passphrase', h)).toBe(true);
    expect(await verifyPassword('a long passphrasE', h)).toBe(false);
    expect(await verifyPassword('x', 'garbage')).toBe(false);
  });
  it('uses a random salt', async () => {
    expect(await hashPassword('same password')).not.toBe(await hashPassword('same password'));
  });
});

describe('TOTP (RFC 6238, SHA1)', () => {
  // RFC 6238 Appendix B secret "12345678901234567890"; 6-digit codes are the last 6 digits.
  const secret = base32Encode(Buffer.from('12345678901234567890', 'ascii'));
  const vectors: [number, string][] = [
    [59, '287082'],
    [1111111109, '081804'],
    [1111111111, '050471'],
    [1234567890, '005924'],
    [2000000000, '279037'],
    [20000000000, '353130'],
  ];

  it('encodes the RFC secret as expected', () => {
    expect(secret).toBe('GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ');
    expect(base32Decode(secret).toString('ascii')).toBe('12345678901234567890');
  });

  it.each(vectors)('T=%i -> %s', (t, code) => {
    expect(totpCode(secret, t * 1000)).toBe(code);
  });

  it('matches the 8-digit RFC value', () => {
    expect(hotp(Buffer.from('12345678901234567890'), Math.floor(59 / 30), 8)).toBe('94287082');
  });

  it('accepts +/-1 step and rejects replays', () => {
    const t = 1111111111 * 1000;
    const prev = totpCode(secret, t - 30_000);
    const step = verifyTotp(secret, prev, t, null);
    expect(step).toBe(Math.floor(t / 30_000) - 1);
    expect(verifyTotp(secret, prev, t, step)).toBeNull();
    expect(verifyTotp(secret, totpCode(secret, t - 90_000), t, null)).toBeNull();
    expect(verifyTotp(secret, '12345', t, null)).toBeNull();
  });
});

describe('auth API', () => {
  let server: HsmServer;
  let clock: FakeClock;
  afterEach(async () => server?.close());

  it('bootstraps the admin and requires TOTP enrollment before anything else', async () => {
    ({ server, clock } = await makeServer());
    const login = await api(server, 'POST', '/api/auth/login', { body: ADMIN });
    expect(login.status).toBe(200);
    expect(login.body.totp_required).toBe(false);
    const token = login.body.token;
    const me = await api(server, 'GET', '/api/auth/me', { token });
    expect(me.body).toMatchObject({ user: { username: 'admin', role: 'admin', totp_enabled: false }, totp_enrollment_required: true });
    // Neither reads (plan 10: password plus TOTP for every login)...
    const read = await api(server, 'GET', '/api/devices', { token });
    expect(read.status).toBe(403);
    expect(read.body.error).toBe('totp_enrollment_required');
    // ...nor writes.
    const write = await api(server, 'POST', '/api/pair/codes', { token, body: {} });
    expect(write.status).toBe(403);
    expect(write.body.error).toBe('totp_enrollment_required');
    // Enrollment endpoints work.
    const setup = await api(server, 'POST', '/api/auth/totp/setup', { token });
    expect(setup.body.otpauth_url).toContain('otpauth://totp/');
    const bad = await api(server, 'POST', '/api/auth/totp/enable', { token, body: { code: '000000' } });
    expect(bad.status).toBe(400);
    const ok = await api(server, 'POST', '/api/auth/totp/enable', { token, body: { code: totpCode(setup.body.secret, clock.now()) } });
    expect(ok.status).toBe(200);
    const pair = await api(server, 'POST', '/api/pair/codes', { token });
    expect(pair.status).toBe(201);
    expect(pair.body.code).toMatch(/^[A-HJKMNP-Z2-9]{8}$/);
    expect(pair.body.deep_link).toContain(`code=${pair.body.code}`);
  });

  it('runs the two-step login and rejects a replayed code', async () => {
    ({ server, clock } = await makeServer());
    const { secret } = await adminWithTotp(server, clock);
    clock.advance(30_000);
    const step1 = await api(server, 'POST', '/api/auth/login', { body: ADMIN });
    expect(step1.body.totp_required).toBe(true);
    const code = totpCode(secret, clock.now());
    const step2 = await api(server, 'POST', '/api/auth/totp', { body: { pending_token: step1.body.pending_token, code } });
    expect(step2.status).toBe(200);
    expect(step2.body.user.totp_enabled).toBe(true);
    // Same code again on a fresh login is a replay.
    const again = await api(server, 'POST', '/api/auth/login', { body: ADMIN });
    const replay = await api(server, 'POST', '/api/auth/totp', { body: { pending_token: again.body.pending_token, code } });
    expect(replay.status).toBe(401);
    // The pending token expires after 5 minutes.
    clock.advance(6 * 60_000);
    const late = await api(server, 'POST', '/api/auth/totp', {
      body: { pending_token: again.body.pending_token, code: totpCode(secret, clock.now()) },
    });
    expect(late.status).toBe(401);
  });

  it('locks a username after 5 failures for 15 minutes', async () => {
    ({ server, clock } = await makeServer());
    for (let i = 0; i < 5; i++) {
      const r = await api(server, 'POST', '/api/auth/login', { body: { username: 'admin', password: 'wrong-password' }, ip: `10.0.0.${i}` });
      expect(r.status).toBe(401);
    }
    const locked = await api(server, 'POST', '/api/auth/login', { body: ADMIN, ip: '10.0.0.99' });
    expect(locked.status).toBe(429);
    expect(locked.body.error).toBe('locked');
    clock.advance(LOCK_MS + 1000);
    const ok = await api(server, 'POST', '/api/auth/login', { body: ADMIN, ip: '10.0.0.99' });
    expect(ok.status).toBe(200);
  });

  it('locks an IP after 5 failures across usernames', async () => {
    ({ server, clock } = await makeServer());
    for (let i = 0; i < 5; i++) {
      await api(server, 'POST', '/api/auth/login', { body: { username: `user${i}`, password: 'nope-nope-nope' }, ip: '10.9.9.9' });
    }
    const r = await api(server, 'POST', '/api/auth/login', { body: ADMIN, ip: '10.9.9.9' });
    expect(r.status).toBe(429);
    const other = await api(server, 'POST', '/api/auth/login', { body: ADMIN, ip: '10.9.9.10' });
    expect(other.status).toBe(200);
  });

  it('manages users with role rules', async () => {
    ({ server, clock } = await makeServer());
    const { token } = await adminWithTotp(server, clock);
    const reauth_password = ADMIN.password;
    const short = await createUser(server, token, { username: 'viewer1', password: 'short', role: 'viewer' });
    expect(short.status).toBe(400);
    const vuser = await userWithTotp(server, clock, token, { username: 'viewer1', password: 'viewer-password', role: 'viewer' });
    const dup = await createUser(server, token, { username: 'VIEWER1', password: 'viewer-password', role: 'viewer' });
    expect(dup.status).toBe(409);

    const vtoken = vuser.token;
    expect((await api(server, 'GET', '/api/devices', { token: vtoken })).status).toBe(200);
    expect((await api(server, 'GET', '/api/users', { token: vtoken })).status).toBe(403);
    expect((await api(server, 'GET', '/api/sms', { token: vtoken })).status).toBe(403);
    const cmd = await api(server, 'POST', '/api/commands', { token: vtoken, body: { name: 'refresh' } });
    expect(cmd.status).toBe(403);
    expect(cmd.body.error).toBe('forbidden');

    const me = (await api(server, 'GET', '/api/auth/me', { token })).body.user;
    expect((await api(server, 'DELETE', `/api/users/${me.id}`, { token, body: { reauth_password } })).status).toBe(409);
    const demote = await api(server, 'PATCH', `/api/users/${me.id}`, { token, body: { role: 'viewer', reauth_password } });
    expect(demote.status).toBe(409);
    expect((await api(server, 'DELETE', `/api/users/${vuser.id}`, { token, body: { reauth_password } })).status).toBe(204);
    // Deleting the user revoked the session.
    expect((await api(server, 'GET', '/api/auth/me', { token: vtoken })).status).toBe(401);
  });

  it('logs out and changes passwords', async () => {
    ({ server, clock } = await makeServer());
    const login = await api(server, 'POST', '/api/auth/login', { body: ADMIN });
    const token = login.body.token;
    const wrong = await api(server, 'POST', '/api/auth/password', { token, body: { current: 'not-it-at-all', next: 'a-new-password' } });
    expect(wrong.status).toBe(401);
    expect(wrong.body.error).toBe('reauth_required');
    const ok = await api(server, 'POST', '/api/auth/password', { token, body: { current: ADMIN.password, next: 'a-new-password' } });
    expect(ok.status).toBe(200);
    expect((await api(server, 'POST', '/api/auth/login', { body: ADMIN })).status).toBe(401);
    expect((await api(server, 'POST', '/api/auth/logout', { token })).status).toBe(204);
    expect((await api(server, 'GET', '/api/auth/me', { token })).status).toBe(401);
  });

  it('creates no admin without ADMIN_USERNAME / ADMIN_PASSWORD', async () => {
    ({ server, clock } = await makeServer({ config: { adminUsername: null, adminPassword: null } }));
    expect(server.ctx.auth.listUsers()).toHaveLength(0);
    expect((await api(server, 'POST', '/api/auth/login', { body: ADMIN })).status).toBe(401);
  });

  it('bootstraps only while the users table is empty', async () => {
    ({ server, clock } = await makeServer());
    expect(server.ctx.auth.listUsers()).toHaveLength(1);
    await server.ctx.auth.bootstrapAdmin();
    expect(server.ctx.auth.listUsers()).toHaveLength(1);
  });

  it('expires sessions after SESSION_TTL_DAYS', async () => {
    ({ server, clock } = await makeServer({ config: { sessionTtlDays: 1 } }));
    const token = (await api(server, 'POST', '/api/auth/login', { body: ADMIN })).body.token;
    expect((await api(server, 'GET', '/api/auth/me', { token })).status).toBe(200);
    clock.advance(86_400_000 + 1);
    expect((await api(server, 'GET', '/api/auth/me', { token })).status).toBe(401);
  });

  it('serves health and public config, and 404s unknown API routes as JSON', async () => {
    ({ server, clock } = await makeServer({ clock: new FakeClock(T0) }));
    const h = await api(server, 'GET', '/healthz');
    expect(h.body).toMatchObject({ ok: true, version: 'test', db: 'ok' });
    const c = await api(server, 'GET', '/api/config');
    expect(c.body).toEqual({ version: 'test', telegram_configured: false, push_configured: true });
    const nf = await api(server, 'GET', '/api/nope');
    expect(nf.status).toBe(404);
    expect(nf.body.error).toBe('not_found');
    const unauth = await api(server, 'GET', '/api/device');
    expect(unauth.status).toBe(401);
    expect(unauth.body).toEqual({ error: 'unauthorized', message: expect.any(String) });
  });
});
