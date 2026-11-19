import type { FastifyInstance } from 'fastify';
import type { UserInfo } from '@hsm/shared';
import { z } from 'zod';
import { authOf, type Guards } from '../auth/guard.js';
import type { AppContext } from '../context.js';
import { badRequest, parseOrThrow } from '../errors.js';

const Role = z.enum(['admin', 'viewer']);
const CreateSchema = z.object({ username: z.string().max(64), password: z.string().max(256), role: Role });
const PatchSchema = z.object({
  role: Role.optional(),
  password: z.string().max(256).optional(),
  /** Clears the user's authenticator and revokes their sessions (lost phone); see api.ts. */
  reset_totp: z.boolean().optional(),
});

function idParam(params: unknown): number {
  const id = Number((params as { id?: string }).id);
  if (!Number.isInteger(id) || id <= 0) throw badRequest('Invalid user id');
  return id;
}

export function registerUserRoutes(app: FastifyInstance, ctx: AppContext, g: Guards): void {
  app.get('/users', { preHandler: g.admin }, async (): Promise<UserInfo[]> => ctx.auth.listUsers());

  app.post('/users', { preHandler: g.adminWrite }, async (req, reply) => {
    const b = parseOrThrow(CreateSchema, req.body, 'user');
    const user = await ctx.auth.createUser({ username: b.username.trim(), password: b.password, role: b.role });
    ctx.log.info({ by: authOf(req).user.username, username: user.username, role: user.role }, 'user created');
    return reply.code(201).send(user);
  });

  app.patch('/users/:id', { preHandler: g.adminWrite }, async (req): Promise<UserInfo> => {
    const b = parseOrThrow(PatchSchema, req.body ?? {}, 'user update');
    const a = authOf(req);
    const user = await ctx.auth.updateUser(a, idParam(req.params), b);
    ctx.log.info({ by: a.user.username, user_id: user.id, role: b.role, password: b.password !== undefined, reset_totp: b.reset_totp }, 'user updated');
    return user;
  });

  app.delete('/users/:id', { preHandler: g.adminWrite }, async (req, reply) => {
    const a = authOf(req);
    const id = idParam(req.params);
    await ctx.auth.deleteUser(a, id);
    ctx.log.info({ by: a.user.username, user_id: id }, 'user deleted');
    return reply.code(204).send();
  });
}
