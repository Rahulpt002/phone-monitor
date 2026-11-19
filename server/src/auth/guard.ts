import type { FastifyRequest } from 'fastify';
import type { AppContext } from '../context.js';
import { ApiHttpError, forbidden, unauthorized } from '../errors.js';
import { parseBearer } from '../util.js';
import type { AuthInfo } from './service.js';

declare module 'fastify' {
  interface FastifyRequest {
    auth: AuthInfo | null;
  }
}

export type Guard = (req: FastifyRequest) => Promise<void>;

export interface Guards {
  /**
   * Any valid session, second factor enrolled or not. Only for the enrollment allow-list
   * (shared/src/api.ts MeResponse): GET /auth/me, POST /auth/logout, /auth/password,
   * /auth/totp/setup and /auth/totp/enable.
   */
  session: Guard;
  /** Any signed-in user with TOTP enrolled (plan 10: password plus TOTP for every role). */
  user: Guard;
  /** Admins (TOTP enrolled). */
  admin: Guard;
  /** Admin writes (commands, settings, pairing, users). Same rule as `admin` since TOTP is required for everyone. */
  adminWrite: Guard;
}

export const totpEnrollmentRequired = () =>
  new ApiHttpError(403, 'totp_enrollment_required', 'Set up an authenticator app (TOTP) to continue');

export function makeGuards(ctx: AppContext): Guards {
  const session: Guard = async (req) => {
    const info = await ctx.auth.authenticate(parseBearer(req.headers.authorization));
    if (!info) throw unauthorized();
    req.auth = info;
  };
  const user: Guard = async (req) => {
    await session(req);
    if (req.auth && ctx.auth.needsTotpEnrollment(req.auth.user)) throw totpEnrollmentRequired();
  };
  const admin: Guard = async (req) => {
    await user(req);
    if (req.auth?.user.role !== 'admin') throw forbidden('Only admins can do this');
  };
  return { session, user, admin, adminWrite: admin };
}

export function authOf(req: FastifyRequest): AuthInfo {
  if (!req.auth) throw unauthorized();
  return req.auth;
}
