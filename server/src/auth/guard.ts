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
  /** Any signed-in user. */
  user: Guard;
  /** Admins; reads are allowed without TOTP. */
  admin: Guard;
  /** Admins with TOTP enrolled: every write (commands, settings, pairing, users). */
  adminWrite: Guard;
}

export function makeGuards(ctx: AppContext): Guards {
  const user: Guard = async (req) => {
    const info = await ctx.auth.authenticate(parseBearer(req.headers.authorization));
    if (!info) throw unauthorized();
    req.auth = info;
  };
  const admin: Guard = async (req) => {
    await user(req);
    if (req.auth?.user.role !== 'admin') throw forbidden('Only admins can do this');
  };
  const adminWrite: Guard = async (req) => {
    await admin(req);
    if (req.auth && ctx.auth.needsTotpEnrollment(req.auth.user)) {
      throw new ApiHttpError(
        403,
        'totp_enrollment_required',
        'Set up an authenticator app (TOTP) before making changes',
      );
    }
  };
  return { user, admin, adminWrite };
}

export function authOf(req: FastifyRequest): AuthInfo {
  if (!req.auth) throw unauthorized();
  return req.auth;
}
