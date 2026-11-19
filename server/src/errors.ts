import type { ApiErrorCode } from '@hsm/shared';
import type { z } from 'zod';

/** An error that maps 1:1 to an HTTP response `{ error, message }` (shared/src/api.ts ApiError). */
export class ApiHttpError extends Error {
  constructor(
    readonly statusCode: number,
    readonly code: ApiErrorCode,
    message: string,
  ) {
    super(message);
    this.name = 'ApiHttpError';
  }
}

export const badRequest = (message: string) => new ApiHttpError(400, 'bad_request', message);
export const unauthorized = (message = 'Not signed in or the session has expired') =>
  new ApiHttpError(401, 'unauthorized', message);
export const forbidden = (message = 'Your role does not allow this') => new ApiHttpError(403, 'forbidden', message);
export const notFound = (message = 'Not found') => new ApiHttpError(404, 'not_found', message);
export const conflict = (message: string) => new ApiHttpError(409, 'conflict', message);

export function formatZodError(err: z.ZodError): string {
  return err.issues
    .slice(0, 8)
    .map((i) => (i.path.length ? `${i.path.join('.')}: ${i.message}` : i.message))
    .join('; ');
}

/** Validates with zod and throws a 400 bad_request listing the problems. */
export function parseOrThrow<S extends z.ZodType>(schema: S, data: unknown, what = 'request'): z.output<S> {
  const r = schema.safeParse(data);
  if (!r.success) throw badRequest(`Invalid ${what}: ${formatZodError(r.error)}`);
  return r.data;
}
