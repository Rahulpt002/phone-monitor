import type { LoginResponse, Role, SessionResponse, TotpSetupResponse, UserInfo } from '@hsm/shared';
import { MIN_PASSWORD_LENGTH } from '../config.js';
import type { AppContext } from '../context.js';
import { ApiHttpError, badRequest, conflict, notFound, unauthorized } from '../errors.js';
import { iso, randomToken, sha256Hex } from '../util.js';
import { LoginGuard } from './lockout.js';
import { burnPasswordCheck, hashPassword, verifyPassword } from './password.js';
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

  needsTotpEnrollment(u: UserRow): boolean {
    return u.role === 'admin' && u.totp_secret == null;
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

  private assertNotLocked(keys: string[]): void {
    const ms = this.guard.lockedFor(keys);
    if (ms > 0) {
      const min = Math.ceil(ms / 60_000);
      throw new ApiHttpError(429, 'locked', `Too many failed attempts. Try again in ${min} minute${min === 1 ? '' : 's'}.`);
    }
  }

  async login(username: string, password: string, ip: string, userAgent: string | null): Promise<LoginResponse> {
    const keys = this.lockKeys(username, ip);
    this.assertNotLocked(keys);
    const user = this.findByUsername(username);
    const ok = user ? await verifyPassword(password, user.password_hash) : await burnPasswordCheck(password);
    if (!user || !ok) {
      this.guard.fail(keys);
      throw unauthorized('Wrong username or password');
    }
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
    return { totp_required: false, ...s };
  }

  async loginTotp(pendingToken: string, code: string, ip: string, userAgent: string | null): Promise<SessionResponse> {
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
    const keys = this.lockKeys(user.username, ip);
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
    return this.createSession(user, ip, userAgent);
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

  async logout(sessionHash: string): Promise<void> {
    this.sessions.delete(sessionHash);
    await this.ctx.db.run('DELETE FROM sessions WHERE token_hash = ?', [sessionHash]);
    this.ctx.hub.closeDashboards((c) => c.sessionHash === sessionHash, 'signed out');
  }

  /** Revokes every session of a user except `exceptHash`, and closes their live sockets. */
  async revokeUserSessions(userId: number, exceptHash: string | null = null): Promise<void> {
    for (const [h, s] of this.sessions) if (s.userId === userId && h !== exceptHash) this.sessions.delete(h);
    if (exceptHash) {
      await this.ctx.db.run('DELETE FROM sessions WHERE user_id = ? AND token_hash <> ?', [userId, exceptHash]);
    } else {
      await this.ctx.db.run('DELETE FROM sessions WHERE user_id = ?', [userId]);
    }
    this.ctx.hub.closeDashboards((c) => c.user?.id === userId && c.sessionHash !== exceptHash, 'session revoked');
  }

  /** Re-authentication for sensitive actions (plan section 10). Failures count toward lockout. */
  async reauth(user: UserRow, password: unknown, ip: string): Promise<void> {
    if (typeof password !== 'string' || password.length === 0) {
      throw new ApiHttpError(401, 'reauth_required', 'Enter your password to confirm this action');
    }
    const keys = this.lockKeys(user.username, ip);
    this.assertNotLocked(keys);
    if (!(await verifyPassword(password, user.password_hash))) {
      this.guard.fail(keys);
      throw new ApiHttpError(401, 'reauth_required', 'Wrong password');
    }
  }

  async totpSetup(user: UserRow): Promise<TotpSetupResponse> {
    if (user.totp_secret) throw conflict('TOTP is already enabled. Disable it first to enroll a new authenticator.');
    const secret = generateTotpSecret();
    const now = iso(this.ctx.clock.now());
    await this.ctx.db.run('UPDATE users SET totp_pending_secret = ?, updated_at = ? WHERE id = ?', [secret, now, user.id]);
    user.totp_pending_secret = secret;
    return { secret, otpauth_url: otpauthUrl(secret, user.username) };
  }

  async totpEnable(user: UserRow, code: string, ip: string): Promise<void> {
    if (user.totp_secret) throw conflict('TOTP is already enabled');
    if (!user.totp_pending_secret) throw badRequest('Start with POST /api/auth/totp/setup');
    const keys = this.lockKeys(user.username, ip);
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

  async totpDisable(user: UserRow, password: unknown, code: unknown, ip: string): Promise<void> {
    if (!user.totp_secret) throw conflict('TOTP is not enabled');
    await this.reauth(user, password, ip);
    const keys = this.lockKeys(user.username, ip);
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
  }

  async changePassword(user: UserRow, sessionHash: string, current: unknown, next: unknown, ip: string): Promise<void> {
    await this.reauth(user, current, ip);
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

  async updateUser(
    actor: AuthInfo,
    id: number,
    patch: { role?: Role; password?: string; reset_totp?: boolean },
  ): Promise<UserInfo> {
    const user = this.users.get(id);
    if (!user) throw notFound('User not found');
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
      await this.revokeUserSessions(id, id === actor.user.id ? actor.sessionHash : null);
    } else if (patch.role) {
      // Live dashboard sockets carry the role they authenticated with; make them re-auth.
      this.ctx.hub.closeDashboards((c) => c.user?.id === id, 'role changed');
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
      { sql: 'DELETE FROM users WHERE id = ?', args: [id] },
    ]);
    this.users.delete(id);
    for (const [h, s] of this.sessions) if (s.userId === id) this.sessions.delete(h);
    this.ctx.hub.closeDashboards((c) => c.user?.id === id, 'user deleted');
  }

  /** Hourly housekeeping. */
  async cleanup(): Promise<void> {
    const now = this.ctx.clock.now();
    await this.ctx.db.run('DELETE FROM sessions WHERE expires_at < ?', [now]);
    for (const [h, s] of this.sessions) if (s.expiresAt <= now) this.sessions.delete(h);
    this.guard.prune();
  }
}
