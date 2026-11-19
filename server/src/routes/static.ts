import { existsSync } from 'node:fs';
import { basename, join, sep } from 'node:path';
import fastifyStatic from '@fastify/static';
import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import type { AppContext } from '../context.js';

const NO_CACHE_FILES = new Set(['index.html', 'sw.js', 'registerSW.js', 'manifest.webmanifest']);

const pathOf = (req: FastifyRequest) => (req.url.split('?')[0] ?? '/').replace(/\/+$/, '') || '/';
const isApiOrWs = (p: string) => p === '/api' || p.startsWith('/api/') || p === '/ws' || p.startsWith('/ws/');

function notFound(req: FastifyRequest, reply: FastifyReply) {
  return reply.code(404).send({ error: 'not_found', message: `Route ${req.method} ${pathOf(req)} not found` });
}

/**
 * Serves the built Dashboard (web/dist) from the same origin as the API, with an SPA fallback to
 * index.html for client-side routes. Hashed assets are cached forever; the HTML shell and the
 * service worker are revalidated on every load so a deploy reaches clients immediately.
 */
export async function registerStatic(app: FastifyInstance, ctx: AppContext): Promise<boolean> {
  const dist = ctx.config.webDist;
  const hasDist = existsSync(join(dist, 'index.html'));

  if (hasDist) {
    await app.register(fastifyStatic, {
      root: dist,
      prefix: '/',
      wildcard: true,
      index: ['index.html'],
      cacheControl: false,
      etag: true,
      lastModified: true,
      setHeaders: (reply, filePath) => {
        const name = basename(filePath);
        if (NO_CACHE_FILES.has(name) || name.endsWith('.html')) {
          reply.header('cache-control', 'no-cache');
        } else if (filePath.includes(`${sep}assets${sep}`)) {
          reply.header('cache-control', 'public, max-age=31536000, immutable');
        } else {
          reply.header('cache-control', 'public, max-age=3600');
        }
      },
    });
  } else {
    ctx.log.warn({ web_dist: dist }, 'Dashboard build not found; serving the API only');
    app.get('/', async () => ({
      name: 'Hotspot Monitor server',
      version: ctx.config.version,
      message:
        'The Dashboard is not built. Run "npm run build" (or "npm run build -w web") in the repository, or set WEB_DIST to the built web/dist directory, then restart. For development run "npm run dev:web" and open http://localhost:5173.',
      api: '/api',
      health: '/healthz',
    }));
  }

  app.setNotFoundHandler((req, reply) => {
    const p = pathOf(req);
    const isPage = (req.method === 'GET' || req.method === 'HEAD') && !isApiOrWs(p) && !/\.[A-Za-z0-9]+$/.test(p);
    if (hasDist && isPage) {
      reply.header('cache-control', 'no-cache');
      return reply.type('text/html; charset=utf-8').sendFile('index.html');
    }
    return notFound(req, reply);
  });
  return hasDist;
}
