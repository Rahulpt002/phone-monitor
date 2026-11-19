import type { FastifyInstance } from 'fastify';
import type { LoginResponse, MeResponse, SessionResponse, TotpSetupResponse } from '@hsm/shared';
import { z } from 'zod';
import { authOf, type Guards } from '../auth/guard.js';
import type { AppContext } from '../context.js';
import { parseOrThrow } from '../errors.js';
import { RATE_AUTH, userAgent } from './helpers.js';

const LoginSchema = z.object({ username: z.string().min(1).max(64), password: z.string().min(1).max(256) });
const TotpLoginSchema = z.object({ pending_token: z.string().min(1).max(200), code: z.string().min(1).max(16) });
const CodeSchema = z.object({ code: z.string().min(1).max(16) });
const DisableSchema = z.object({ password: z.string().max(256), code: z.string().max(16) });
const PasswordSchema = z.object({ current: z.string().max(256), next: z.string().max(256) });

export function registerAuthRoutes(app: FastifyInstance, ctx: AppContext, g: Guards): void {
  app.post('/auth/login', { config: { rateLimit: RATE_AUTH } }, async (req): Promise<LoginResponse> => {
    const b = parseOrThrow(LoginSchema, req.body, 'login');
    return ctx.auth.login(b.username.trim(), b.password, req.ip, userAgent(req));
  });

  app.post('/auth/totp', { config: { rateLimit: RATE_AUTH } }, async (req): Promise<SessionResponse> => {
    const b = parseOrThrow(TotpLoginSchema, req.body, 'request');
    return ctx.auth.loginTotp(b.pending_token, b.code, req.ip, userAgent(req));
  });

  app.get('/auth/me', { preHandler: g.user }, async (req): Promise<MeResponse> => {
    const { user } = authOf(req);
    return { user: ctx.auth.userInfo(user), totp_enrollment_required: ctx.auth.needsTotpEnrollment(user) };
  });

  app.post('/auth/logout', { preHandler: g.user }, async (req, reply) => {
    await ctx.auth.logout(authOf(req).sessionHash);
    return reply.code(204).send();
  });

  app.post('/auth/totp/setup', { preHandler: g.user }, async (req): Promise<TotpSetupResponse> => {
    return ctx.auth.totpSetup(authOf(req).user);
  });

  app.post('/auth/totp/enable', { preHandler: g.user, config: { rateLimit: RATE_AUTH } }, async (req) => {
    const b = parseOrThrow(CodeSchema, req.body, 'request');
    await ctx.auth.totpEnable(authOf(req).user, b.code, req.ip);
    return { ok: true };
  });

  app.post('/auth/totp/disable', { preHandler: g.user, config: { rateLimit: RATE_AUTH } }, async (req) => {
    const b = parseOrThrow(DisableSchema, req.body, 'request');
    await ctx.auth.totpDisable(authOf(req).user, b.password, b.code, req.ip);
    return { ok: true };
  });

  app.post('/auth/password', { preHandler: g.user, config: { rateLimit: RATE_AUTH } }, async (req) => {
    const b = parseOrThrow(PasswordSchema, req.body, 'request');
    const a = authOf(req);
    await ctx.auth.changePassword(a.user, a.sessionHash, b.current, b.next, req.ip);
    return { ok: true };
  });
}
