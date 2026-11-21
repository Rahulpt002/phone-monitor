import type { FastifyInstance } from 'fastify';
import { authOf, type Guards } from '../auth/guard.js';
import type { AppContext } from '../context.js';
import { RATE_AUTH } from './helpers.js';

/** Remote screen sessions (shared/src/api.ts, docs/protocol.md section 8). Admins only. */
export function registerScreenRoutes(app: FastifyInstance, ctx: AppContext, g: Guards): void {
  // Rate-limited like sign-in: it checks a password and a TOTP code.
  app.post('/screen/sessions', { preHandler: g.admin, config: { rateLimit: RATE_AUTH } }, async (req, reply) => {
    const res = await ctx.screen.create(authOf(req), req.body);
    return reply.code(201).send(res);
  });

  app.delete('/screen/sessions/:id', { preHandler: g.admin }, async (req, reply) => {
    const id = String((req.params as { id?: string }).id ?? '');
    await ctx.screen.stop(authOf(req), id);
    return reply.code(204).send();
  });
}
