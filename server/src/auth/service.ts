import type { LoginResponse, Role, SessionResponse, TotpSetupResponse, UserInfo } from '@hsm/shared';
import { MIN_PASSWORD_LENGTH } from '../config.js';
import type { AppContext } from '../context.js';
import { ApiHttpError, badRequest, conflict, notFound, unauthorized } from '../errors.js';
import { iso, randomToken, sha256Hex } from '../util.js';
import { LoginGuard } from './lockout.js';
import { burnPasswordCheck, hashPassword, needsRehash, verifyPassword } from './password.js';
import { generateTotpSecret, otpauthUrl, verifyTotp } from './totp.js';

export interface UserRow {
  id: number;
  username: string;
  password_hash: string;
  role: Role;
  totp_secret: string | null;
  totp_pending_secret: string | null;
  totp_last_step: number | null;
  created_at: string;
  updated_at: string;
}

export interface AuthInfo {
  user: UserRow;
  sessionHash: string;
}

interface CachedSession {
  userId: number;
  kind: 'session' | 'pending';
  expiresAt: number;
  lastUsedAt: number;
}

const PENDING_TTL_MS = 5 * 60_000;
/** Known-device token lifetime (F39); every full sign-in replaces the token with a new one. */
export const KNOWN_DEVICE_TTL_MS = 180 * 86_400_000;
/** Newest known devices kept per user. */
const KNOWN_DEVICES_PER_USER = 20;
const LAST_USED_WRITE_MS = 60 * 60_000;
const SESSION_CACHE_MAX = 5000;
export const USERNAME_RE = /^[A-Za-z0-9_.@-]{3,64}$/;

function toUser(r: Record<string, unknown>): UserRow {
  return {
    id: Number(r.id),
    username: String(r.username),
    password_hash: String(r.password_hash),
    role: r.role === 'admin' ? 'admin' : 'viewer',
    totp_secret: (r.totp_secret as string | null) ?? null,
    totp_pending_secret: (r.totp_pending_secret as string | null) ?? null,
    totp_last_step: r.totp_last_step == null ? null : Number(r.totp_last_step),
    created_at: String(r.created_at),
    updated_at: String(r.updated_at),
  };
}

export function checkPasswordStrength(pw: unknown): string {
  if (typeof pw !== 'string') throw badRequest('Password is required');
  if (pw.length < MIN_PASSWORD_LENGTH) throw badRequest(`Password must be at least ${MIN_PASSWORD_LENGTH} characters`);
  if (pw.length > 256) throw badRequest('Password is too long (max 256 characters)');
  return pw;
}

/**
 * Users, sessions and the second factor (plan section 10). Session tokens are opaque random
 * strings; only their SHA-256 is stored. Users are few, so the whole table is cached; sessions
 * are cached after their first lookup because every request on Turso would otherwise pay a
 * network round trip.
 */
export class AuthService {
  readonly guard: LoginGuard;
  private users = new Map<number, UserRow>();
  private sessions = new Map<string, CachedSession>();

  constructor(private ctx: AppContext) {
    this.guard = new LoginGuard(ctx.clock);
  }

  async load(): Promise<void> {
    const rows = await this.ctx.db.all('SELECT * FROM users');
    this.users = new Map(rows.map((r) => [Number(r.id), toUser(r)]));
  }

  userInfo(u: UserRow): UserInfo {
    return { id: u.id, username: u.username, role: u.role, totp_enabled: u.totp_secret != null, created_at: u.created_at };
  }

  /**
   * Plan 10: password plus TOTP for every Dashboard login, whatever the role. A session of a user
   * without TOTP may only enroll (guard.ts `session`); everything else answers
   * 403 totp_enrollment_required.
   */
  needsTotpEnrollment(u: UserRow): boolean {
    return u.totp_secret == null;
  }

  getUser(id: number): UserRow | undefined {
    return this.users.get(id);
  }

  private findByUsername(username: string): UserRow | undefined {
    const lower = username.toLowerCase();
    for (const u of this.users.values()) if (u.username.toLowerCase() === lower) return u;
    return undefined;
  }

  private adminCount(): number {
    let n = 0;
    for (const u of this.users.values()) if (u.role === 'admin') n++;
    return n;
  }

  /** Creates the first admin from ADMIN_USERNAME / ADMIN_PASSWORD when no user exists yet. */
  async bootstrapAdmin(): Promise<void> {
    if (this.users.size > 0) return;
    const { adminUsername, adminPassword } = this.ctx.config;
    if (!adminUsername || !adminPassword) {
      this.ctx.log.warn(
        'No users exist and ADMIN_USERNAME / ADMIN_PASSWORD are not set: nobody can sign in. Set both and restart.',
      );
      return;
    }
    if (!USERNAME_RE.test(adminUsername)) {
      this.ctx.log.error('ADMIN_USERNAME must be 3-64 characters of letters, digits, _ . @ -; first admin not created');
      return;
    }
    if (adminPassword.length < MIN_PASSWORD_LENGTH) {
      this.ctx.log.error(`ADMIN_PASSWORD must be at least ${MIN_PASSWORD_LENGTH} characters; first admin not created`);
      return;
    }
    await this.createUser({ username: adminUsername, password: adminPassword, role: 'admin' });
    this.ctx.log.info({ username: adminUsername }, 'Created the first admin from ADMIN_USERNAME; sign in and enroll TOTP');
  }

  private lockKeys(username: string, ip: string): string[] {
    return [`u:${username.toLowerCase()}`, `ip:${ip}`];
  }

  /**
   * Sign-in lockout keys (F39). Without a known-device token: the username and the IP, so
   * guessing is limited whatever the source. With a valid token for `user` (issued by an earlier
   * full sign-in of this browser): that token and the IP, so failures by anyone else, which lock
   * the username, never lock this browser out; a stolen token still gets only 5 guesses.
   */
  private async signInKeys(
    username: string,
    user: UserRow | undefined,
    ip: string,
    deviceToken: string | undefined,
  ): Promise<{ keys: string[]; deviceHash: string | null }> {
    const deviceHash = await this.knownDevice(user, deviceToken);
    if (deviceHash) return { keys: [`d:${deviceHash}`, `ip:${ip}`], deviceHash };
    return { keys: this.lockKeys(username, ip), deviceHash: null };
  }

  /** The stored hash of `token` when it is a valid known-device token of `user`. */
  private async knownDevice(user: UserRow | undefined, token: string | undefined): Promise<string | null> {
    if (!user || !token || token.length > 200) return null;
    const hash = sha256Hex(token);
    const row = await this.ctx.db.get<{ user_id: number; expires_at: number }>(
      'SELECT user_id, expires_at FROM known_devices WHERE token_hash = ?',
      [hash],
    );
    if (!row || Number(row.user_id) !== user.id || Number(row.expires_at) <= this.ctx.clock.now()) return null;
    return hash;
  }

  /**
   * Issues a new known-device token after a full sign-in, replacing the one presented (if any)
   * and keeping the newest KNOWN_DEVICES_PER_USER per user. Best effort: a failure only means
   * the browser is not exempt next time.
   */
  private async issueDeviceToken(user: UserRow, replaces: string | null): Promise<string | undefined> {
    const token = randomToken();
    const now = this.ctx.clock.now();
    try {
      await this.ctx.db.batch([
        ...(replaces ? [{ sql: 'DELETE FROM known_devices WHERE token_hash = ?', args: [replaces] }] : []),
        { sql: 'DELETE FROM known_devices WHERE user_id = ? AND expires_at <= ?', args: [user.id, now] },
        {
          sql: 'INSERT INTO known_devices (token_hash, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)',
          args: [sha256Hex(token), user.id, now, now + KNOWN_DEVICE_TTL_MS],
        },
        {
          sql: `DELETE FROM known_devices WHERE user_id = ? AND token_hash NOT IN
                  (SELECT token_hash FROM known_devices WHERE user_id = ? ORDER BY created_at DESC LIMIT ?)`,
          args: [user.id, user.id, KNOWN_DEVICES_PER_USER],
        },
      ]);
      return token;
    } catch (err) {
      this.ctx.log.warn({ err, user_id: user.id }, 'failed to store a known-device token');
      return undefined;
    }
  }

  /**
   * Failure counter for checks made inside a signed-in session (re-authentication, TOTP codes).
   * It is per session, not per username, so failed sign-ins by someone else (F39) cannot lock a
   * signed-in admin out of reboot, shell or unpair, and a stolen session gets 5 guesses per
   * 15 minutes. Without a session (older callers pass only the user) it is per user, still separate
   * from the sign-in keys.
   */
  private sessionKeys(who: AuthInfo | UserRow): string[] {
    return 'sessionHash' in who ? [`s:${who.sessionHash}`] : [`r:${who.id}`];
  }

  private assertNotLocked(keys: string[]): void {
    const ms = this.guard.lockedFor(keys);
    if (ms > 0) {
      const min = Math.ceil(ms / 60_000);
      throw new ApiHttpError(429, 'locked', `Too many failed attempts. Try again in ${min} minute${min === 1 ? '' : 's'}.`);
    }
  }

  /**
   * One password check that counts toward the lockout of `keys`. The attempt is reserved before
   * scrypt runs, so concurrent wrong guesses cannot all pass the lock check first (F19).
   */
  private async checkPassword(keys: string[], password: string, user: UserRow | undefined): Promise<boolean> {
    this.assertNotLocked(keys);
    const release = this.guard.reserve(keys);
    let ok: boolean;
    try {
      ok = user ? await verifyPassword(password, user.password_hash) : await burnPasswordCheck(password);
    } finally {
      release();
    }
    if (!ok) this.guard.fail(keys);
    return ok;
  }

  /** Re-hashes a correct password whose stored hash uses older, cheaper parameters (F41). */
  private async upgradeHash(user: UserRow, password: string): Promise<void> {
    const old = user.password_hash;
    if (!needsRehash(old)) return;
    try {
      const next = await hashPassword(password);
      const r = await this.ctx.db.run('UPDATE users SET password_hash = ? WHERE id = ? AND password_hash = ?', [next, user.id, old]);
      if (r.changes > 0 && user.password_hash === old) user.password_hash = next;
    } catch (err) {
      this.ctx.log.warn({ err, user_id: user.id }, 'failed to upgrade the password hash; keeping the old one');
    }
  }

  async login(
    username: string,
    password: string,
    ip: string,
    userAgent: string | null,
    deviceToken?: string,
  ): Promise<LoginResponse> {
    const user = this.findByUsername(username);
    const { keys, deviceHash } = await this.signInKeys(username, user, ip, deviceToken);
    if (!(await this.checkPassword(keys, password, user)) || !user) {
      throw unauthorized('Wrong username or password');
    }
    await this.upgradeHash(user, password);
    if (user.totp_secret) {
      const pending = randomToken();
      const now = this.ctx.clock.now();
      await this.ctx.db.run(
        'INSERT INTO sessions (token_hash, user_id, kind, created_at, expires_at, ip, user_agent) VALUES (?, ?, ?, ?, ?, ?, ?)',
        [sha256Hex(pending), user.id, 'pending', now, now + PENDING_TTL_MS, ip, userAgent],
      );
      return { totp_required: true, pending_token: pending };
    }
    this.guard.succeed([keys[0] as string]);
    const s = await this.createSession(user, ip, userAgent);
    const device_token = await this.issueDeviceToken(user, deviceHash);
    return { totp_required: false, ...s, ...(device_token ? { device_token } : {}) };
  }

  async loginTotp(
    pendingToken: string,
    code: string,
    ip: string,
    userAgent: string | null,
    deviceToken?: string,
  ): Promise<SessionResponse> {
    const hash = sha256Hex(pendingToken);
    const now = this.ctx.clock.now();
    const row = await this.ctx.db.get<{ user_id: number; expires_at: number }>(
      "SELECT user_id, expires_at FROM sessions WHERE token_hash = ? AND kind = 'pending'",
      [hash],
    );
    const user = row ? this.users.get(Number(row.user_id)) : undefined;
    if (!row || !user || Number(row.expires_at) <= now || !user.totp_secret) {
      throw unauthorized('The sign-in step expired. Enter your password again.');
    }
    const { keys, deviceHash } = await this.signInKeys(user.username, user, ip, deviceToken);
    this.assertNotLocked(keys);
    const step = verifyTotp(user.totp_secret, code, now, user.totp_last_step);
    if (step === null) {
      this.guard.fail(keys);
      throw unauthorized('Wrong or already used code');
    }
    user.totp_last_step = step;
    await this.ctx.db.batch([
      { sql: 'UPDATE users SET totp_last_step = ? WHERE id = ?', args: [step, user.id] },
      { sql: 'DELETE FROM sessions WHERE token_hash = ?', args: [hash] },
    ]);
    this.guard.succeed([keys[0] as string]);
    const session = await this.createSession(user, ip, userAgent);
    const device_token = await this.issueDeviceToken(user, deviceHash);
    return device_token ? { ...session, device_token } : session;
  }

  private async createSession(user: UserRow, ip: string, userAgent: string | null): Promise<SessionResponse> {
    const token = randomToken();
    const hash = sha256Hex(token);
    const now = this.ctx.clock.now();
    const expiresAt = now + this.ctx.config.sessionTtlDays * 86_400_000;
    await this.ctx.db.run(
      'INSERT INTO sessions (token_hash, user_id, kind, created_at, expires_at, last_used_at, ip, user_agent) VALUES (?, ?, ?, ?, ?, ?, ?, ?)',
      [hash, user.id, 'session', now, expiresAt, now, ip, userAgent?.slice(0, 300) ?? null],
    );
    this.cacheSession(hash, { userId: user.id, kind: 'session', expiresAt, lastUsedAt: now });
    return { token, user: this.userInfo(user), expires_at: iso(expiresAt) };
  }

  private cacheSession(hash: string, s: CachedSession): void {
    if (this.sessions.size >= SESSION_CACHE_MAX) {
      const first = this.sessions.keys().next();
      if (!first.done) this.sessions.delete(first.value);
    }
    this.sessions.set(hash, s);
  }

  /** Resolves a full session token (not a pending one). */
  async authenticate(token: string | null | undefined): Promise<AuthInfo | null> {
    if (!token || token.length > 200) return null;
    const hash = sha256Hex(token);
    const now = this.ctx.clock.now();
    let s = this.sessions.get(hash);
    if (!s) {
      const row = await this.ctx.db.get<Record<string, unknown>>(
        'SELECT user_id, kind, expires_at, last_used_at FROM sessions WHERE token_hash = ?',
        [hash],
      );
      if (!row) return null;
      s = {
        userId: Number(row.user_id),
        kind: row.kind === 'pending' ? 'pending' : 'session',
        expiresAt: Number(row.expires_at),
        lastUsedAt: Number(row.last_used_at ?? 0),
      };
      this.cacheSession(hash, s);
    }
    if (s.kind !== 'session') return null;
    if (s.expiresAt <= now) {
      this.sessions.delete(hash);
      return null;
    }
    const user = this.users.get(s.userId);
    if (!user) return null;
    if (now - s.lastUsedAt > LAST_USED_WRITE_MS) {
      s.lastUsedAt = now;
      this.ctx.db
        .run('UPDATE sessions SET last_used_at = ? WHERE token_hash = ?', [now, hash])
        .catch((err) => this.ctx.log.warn({ err }, 'failed to update session last_used_at'));
    }
    return { user, sessionHash: hash };
  }

  /** For open Dashboard sockets: is the session they authenticated with still valid? */
  sessionAlive(sessionHash: string): boolean {
    const s = this.sessions.get(sessionHash);
    return !!s && s.kind === 'session' && s.expiresAt > this.ctx.clock.now() && this.users.has(s.userId);
  }

  /**
   * Signs one session out. Its push subscriptions go with it (F04), and so do subscriptions of
   * that user stored without a session (from before sessions were recorded): those cannot be told
   * apart, and a signed-in browser registers its subscription again on its next load.
   */
  async logout(auth: AuthInfo): Promise<void> {
    const { sessionHash } = auth;
    this.sessions.delete(sessionHash);
    await this.ctx.db.batch([
      { sql: 'DELETE FROM sessions WHERE token_hash = ?', args: [sessionHash] },
      {
        sql: 'DELETE FROM push_subscriptions WHERE session_hash = ? OR (user_id = ? AND session_hash IS NULL)',
        args: [sessionHash, auth.user.id],
      },
    ]);
    this.ctx.hub.closeDashboards((c) => c.sessionHash === sessionHash, 'signed out');
  }

  /**
   * Revokes every session of a user except `exceptHash`, deletes the push subscriptions of the
   * revoked sessions (and those stored without a session), and closes their live sockets.
   */
  async revokeUserSessions(userId: number, exceptHash: string | null = null): Promise<void> {
    for (const [h, s] of this.sessions) if (s.userId === userId && h !== exceptHash) this.sessions.delete(h);
    // Known-device tokens go too: after a password change or reset, no browser keeps its exemption
    // from the username lock until it signs in again (F39).
    if (exceptHash) {
      await this.ctx.db.batch([
        { sql: 'DELETE FROM known_devices WHERE user_id = ?', args: [userId] },
        { sql: 'DELETE FROM sessions WHERE user_id = ? AND token_hash <> ?', args: [userId, exceptHash] },
        {
          sql: 'DELETE FROM push_subscriptions WHERE user_id = ? AND (session_hash IS NULL OR session_hash <> ?)',
          args: [userId, exceptHash],
        },
      ]);
    } else {
      await this.ctx.db.batch([
        { sql: 'DELETE FROM known_devices WHERE user_id = ?', args: [userId] },
        { sql: 'DELETE FROM sessions WHERE user_id = ?', args: [userId] },
        { sql: 'DELETE FROM push_subscriptions WHERE user_id = ?', args: [userId] },
      ]);
    }
    this.ctx.hub.closeDashboards((c) => c.user?.id === userId && c.sessionHash !== exceptHash, 'session revoked');
  }

  /**
   * Re-authentication for sensitive actions (plan section 10). Pass the AuthInfo of the request:
   * failures then count per session (sessionKeys), never toward the sign-in lock of the username.
   */
  async reauth(who: AuthInfo | UserRow, password: unknown, _ip?: string): Promise<void> {
    const user = 'sessionHash' in who ? who.user : who;
    if (typeof password !== 'string' || password.length === 0) {
      throw new ApiHttpError(401, 'reauth_required', 'Enter your password to confirm this action');
    }
    const keys = this.sessionKeys(who);
    if (!(await this.checkPassword(keys, password, user))) {
      throw new ApiHttpError(401, 'reauth_required', 'Wrong password');
    }
    this.guard.succeed(keys);
  }

  async totpSetup(user: UserRow): Promise<TotpSetupResponse> {
    if (user.totp_secret) throw conflict('TOTP is already enabled. Disable it first to enroll a new authenticator.');
    const secret = generateTotpSecret();
    const now = iso(this.ctx.clock.now());
    await this.ctx.db.run('UPDATE users SET totp_pending_secret = ?, updated_at = ? WHERE id = ?', [secret, now, user.id]);
    user.totp_pending_secret = secret;
    return { secret, otpauth_url: otpauthUrl(secret, user.username) };
  }

  async totpEnable(auth: AuthInfo, code: string): Promise<void> {
    const { user } = auth;
    if (user.totp_secret) throw conflict('TOTP is already enabled');
    if (!user.totp_pending_secret) throw badRequest('Start with POST /api/auth/totp/setup');
    const keys = this.sessionKeys(auth);
    this.assertNotLocked(keys);
    const step = verifyTotp(user.totp_pending_secret, code, this.ctx.clock.now(), null);
    if (step === null) {
      this.guard.fail(keys);
      throw badRequest('Wrong code. Check the time on your phone and try again.');
    }
    const now = iso(this.ctx.clock.now());
    await this.ctx.db.run(
      'UPDATE users SET totp_secret = ?, totp_pending_secret = NULL, totp_last_step = ?, updated_at = ? WHERE id = ?',
      [user.totp_pending_secret, step, now, user.id],
    );
    user.totp_secret = user.totp_pending_secret;
    user.totp_pending_secret = null;
    user.totp_last_step = step;
    user.updated_at = now;
  }

  /**
   * Removes the user's authenticator (to enroll a new one). The session stays valid but, like any
   * session without TOTP, can only enroll again; live sockets are closed with 4004 so the Dashboard
   * reconnects and refetches /auth/me.
   */
  async totpDisable(auth: AuthInfo, password: unknown, code: unknown): Promise<void> {
    const { user } = auth;
    if (!user.totp_secret) throw conflict('TOTP is not enabled');
    await this.reauth(auth, password);
    const keys = this.sessionKeys(auth);
    this.assertNotLocked(keys);
    const step = typeof code === 'string' ? verifyTotp(user.totp_secret, code, this.ctx.clock.now(), user.totp_last_step) : null;
    if (step === null) {
      this.guard.fail(keys);
      throw badRequest('Wrong or already used code');
    }
    const now = iso(this.ctx.clock.now());
    await this.ctx.db.run(
      'UPDATE users SET totp_secret = NULL, totp_pending_secret = NULL, totp_last_step = NULL, updated_at = ? WHERE id = ?',
      [now, user.id],
    );
    user.totp_secret = null;
    user.totp_pending_secret = null;
    user.totp_last_step = null;
    user.updated_at = now;
    this.ctx.hub.closeDashboards((c) => c.user?.id === user.id, 'totp disabled', 4004);
  }

  async changePassword(user: UserRow, sessionHash: string, current: unknown, next: unknown, ip: string): Promise<void> {
    await this.reauth({ user, sessionHash }, current, ip);
    const pw = checkPasswordStrength(next);
    const hash = await hashPassword(pw);
    const now = iso(this.ctx.clock.now());
    await this.ctx.db.run('UPDATE users SET password_hash = ?, updated_at = ? WHERE id = ?', [hash, now, user.id]);
    user.password_hash = hash;
    await this.revokeUserSessions(user.id, sessionHash);
  }

  listUsers(): UserInfo[] {
    return [...this.users.values()].sort((a, b) => a.id - b.id).map((u) => this.userInfo(u));
  }

  async createUser(input: { username: string; password: string; role: Role }): Promise<UserInfo> {
    if (!USERNAME_RE.test(input.username)) {
      throw badRequest('Username must be 3-64 characters: letters, digits, _ . @ -');
    }
    checkPasswordStrength(input.password);
    if (this.findByUsername(input.username)) throw conflict('A user with that name already exists');
    const now = iso(this.ctx.clock.now());
    const hash = await hashPassword(input.password);
    const r = await this.ctx.db.run(
      'INSERT INTO users (username, password_hash, role, created_at, updated_at) VALUES (?, ?, ?, ?, ?)',
      [input.username, hash, input.role, now, now],
    );
    const id = Number(r.lastId);
    const user: UserRow = {
      id,
      username: input.username,
      password_hash: hash,
      role: input.role,
      totp_secret: null,
      totp_pending_secret: null,
      totp_last_step: null,
      created_at: now,
      updated_at: now,
    };
    this.users.set(id, user);
    return this.userInfo(user);
  }

  /**
   * PATCH /users/:id (the route re-authenticates the acting admin first). Your own password and
   * authenticator are changed only through /auth/password and /auth/totp/disable, which need the
   * current password (and code): a stolen session must not be able to take the account over (F00).
   */
  async updateUser(
    actor: AuthInfo,
    id: number,
    patch: { role?: Role; password?: string; reset_totp?: boolean },
  ): Promise<UserInfo> {
    const user = this.users.get(id);
    if (!user) throw notFound('User not found');
    if (id === actor.user.id && patch.password !== undefined) {
      throw badRequest('Change your own password under Account (it asks for the current one)');
    }
    if (id === actor.user.id && patch.reset_totp) {
      throw badRequest('Reset your own authenticator under Account (it asks for your password and a code)');
    }
    if (patch.role && patch.role !== user.role && user.role === 'admin' && this.adminCount() <= 1) {
      throw conflict('Cannot demote the last admin');
    }
    const sets: string[] = [];
    const args: (string | number | null)[] = [];
    let newHash: string | null = null;
    if (patch.password !== undefined) {
      newHash = await hashPassword(checkPasswordStrength(patch.password));
      sets.push('password_hash = ?');
      args.push(newHash);
    }
    if (patch.role) {
      sets.push('role = ?');
      args.push(patch.role);
    }
    if (patch.reset_totp) {
      sets.push('totp_secret = NULL', 'totp_pending_secret = NULL', 'totp_last_step = NULL');
    }
    if (sets.length === 0) return this.userInfo(user);
    const now = iso(this.ctx.clock.now());
    sets.push('updated_at = ?');
    args.push(now, id);
    await this.ctx.db.run(`UPDATE users SET ${sets.join(', ')} WHERE id = ?`, args);
    if (newHash) user.password_hash = newHash;
    if (patch.role) user.role = patch.role;
    if (patch.reset_totp) {
      user.totp_secret = null;
      user.totp_pending_secret = null;
      user.totp_last_step = null;
    }
    user.updated_at = now;
    if (newHash || patch.reset_totp) {
      // Lost phone or forgotten password: every session and every push subscription goes (F04).
      await this.revokeUserSessions(id);
    } else if (patch.role) {
      // Live dashboard sockets carry the role they authenticated with. 4004: the session is still
      // valid; the Dashboard reconnects, re-authenticates and refetches /auth/me (protocol 7).
      this.ctx.hub.closeDashboards((c) => c.user?.id === id, 'role changed', 4004);
    }
    return this.userInfo(user);
  }

  async deleteUser(actor: AuthInfo, id: number): Promise<void> {
    const user = this.users.get(id);
    if (!user) throw notFound('User not found');
    if (id === actor.user.id) throw conflict('You cannot delete yourself');
    if (user.role === 'admin' && this.adminCount() <= 1) throw conflict('Cannot delete the last admin');
    await this.ctx.db.batch([
      { sql: 'DELETE FROM sessions WHERE user_id = ?', args: [id] },
      { sql: 'DELETE FROM push_subscriptions WHERE user_id = ?', args: [id] },
      { sql: 'DELETE FROM known_devices WHERE user_id = ?', args: [id] },
      { sql: 'DELETE FROM users WHERE id = ?', args: [id] },
    ]);
    this.users.delete(id);
    for (const [h, s] of this.sessions) if (s.userId === id) this.sessions.delete(h);
    this.ctx.hub.closeDashboards((c) => c.user?.id === id, 'user deleted');
  }

  /** Hourly housekeeping. */
  async cleanup(): Promise<void> {
    const now = this.ctx.clock.now();
    await this.ctx.db.batch([
      { sql: 'DELETE FROM sessions WHERE expires_at < ?', args: [now] },
      { sql: 'DELETE FROM known_devices WHERE expires_at <= ?', args: [now] },
      // Subscriptions of sessions that expired or were deleted (F04).
      {
        sql: 'DELETE FROM push_subscriptions WHERE session_hash IS NOT NULL AND session_hash NOT IN (SELECT token_hash FROM sessions)',
      },
    ]);
    for (const [h, s] of this.sessions) if (s.expiresAt <= now) this.sessions.delete(h);
    this.guard.prune();
  }
}
