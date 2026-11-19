import type { FastifyInstance } from 'fastify';
import type { UserInfo } from '@hsm/shared';
import { z } from 'zod';
import { authOf, type Guards } from '../auth/guard.js';
import type { AppContext } from '../context.js';
import { badRequest, parseOrThrow } from '../errors.js';

const Role = z.enum(['admin', 'viewer']);
/**
 * Every write carries `reauth_password`, the acting admin's own password (shared/src/api.ts
 * CreateUserRequest / UpdateUserRequest / DeleteUserRequest). It is optional here so that a missing
 * one answers 401 reauth_required (from AuthService.reauth) rather than 400.
 */
const Reauth = z.string().max(256).optional();
const CreateSchema = z.object({ username: z.string().max(64), password: z.string().max(256), role: Role, reauth_password: Reauth });
const PatchSchema = z.object({
  role: Role.optional(),
  password: z.string().max(256).optional(),
  /** Clears the user's authenticator, revokes their sessions and push subscriptions (lost phone). */
  reset_totp: z.boolean().optional(),
  reauth_password: Reauth,
});
const DeleteSchema = z.object({ reauth_password: Reauth });

function idParam(params: unknown): number {
  const id = Number((params as { id?: string }).id);
  if (!Number.isInteger(id) || id <= 0) throw badRequest('Invalid user id');
  return id;
}

export function registerUserRoutes(app: FastifyInstance, ctx: AppContext, g: Guards): void {
  app.get('/users', { preHandler: g.admin }, async (): Promise<UserInfo[]> => ctx.auth.listUsers());

  app.post('/users', { preHandler: g.adminWrite }, async (req, reply) => {
    const b = parseOrThrow(CreateSchema, req.body, 'user');
    const a = authOf(req);
    await ctx.auth.reauth(a, b.reauth_password, req.ip);
    const user = await ctx.auth.createUser({ username: b.username.trim(), password: b.password, role: b.role });
    ctx.log.info({ by: a.user.username, username: user.username, role: user.role }, 'user created');
    return reply.code(201).send(user);
  });

  app.patch('/users/:id', { preHandler: g.adminWrite }, async (req): Promise<UserInfo> => {
    const { reauth_password, ...patch } = parseOrThrow(PatchSchema, req.body ?? {}, 'user update');
    const a = authOf(req);
    const id = idParam(req.params);
    await ctx.auth.reauth(a, reauth_password, req.ip);
    const user = await ctx.auth.updateUser(a, id, patch);
    ctx.log.info(
      { by: a.user.username, user_id: user.id, role: patch.role, password: patch.password !== undefined, reset_totp: patch.reset_totp },
      'user updated',
    );
    return user;
  });

  app.delete('/users/:id', { preHandler: g.adminWrite }, async (req, reply) => {
    const b = parseOrThrow(DeleteSchema, req.body ?? {}, 'request');
    const a = authOf(req);
    const id = idParam(req.params);
    await ctx.auth.reauth(a, b.reauth_password, req.ip);
    await ctx.auth.deleteUser(a, id);
    ctx.log.info({ by: a.user.username, user_id: id }, 'user deleted');
    return reply.code(204).send();
  });
}
