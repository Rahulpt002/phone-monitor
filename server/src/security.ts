import type { FastifyInstance } from 'fastify';
import type { Config } from './config.js';

function wsOrigin(url: string): string | null {
  try {
    const u = new URL(url);
    return `${u.protocol === 'https:' ? 'wss:' : 'ws:'}//${u.host}`;
  } catch {
    return null;
  }
}

/** CSP for the Dashboard SPA: everything from this origin, WebSockets to this host. */
export function spaCsp(config: Config): string {
  const connect = ["'self'"];
  const ws = config.publicUrl ? wsOrigin(config.publicUrl) : null;
  // Older Safari does not treat 'self' as covering ws(s):, so name it when known.
  connect.push(ws ?? 'ws:', ...(ws ? [] : ['wss:']));
  return [
    "default-src 'self'",
    "script-src 'self'",
    // Inline style attributes are used by chart and UI libraries.
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data: blob:",
    "font-src 'self' data:",
    `connect-src ${connect.join(' ')}`,
    "manifest-src 'self'",
    "worker-src 'self'",
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    "frame-ancestors 'none'",
  ].join('; ');
}

const API_CSP = "default-src 'none'; frame-ancestors 'none'";

/** Helmet-like headers, set by hand to avoid another dependency (plan section 10). */
export function registerSecurityHeaders(app: FastifyInstance, config: Config): void {
  const csp = spaCsp(config);
  app.addHook('onSend', async (req, reply, payload) => {
    reply.header('x-content-type-options', 'nosniff');
    reply.header('referrer-policy', 'no-referrer');
    reply.header('x-frame-options', 'DENY');
    reply.header('cross-origin-opener-policy', 'same-origin');
    reply.header('permissions-policy', 'camera=(), microphone=(), geolocation=(), payment=()');
    if (req.protocol === 'https') reply.header('strict-transport-security', 'max-age=31536000; includeSubDomains');
    const type = String(reply.getHeader('content-type') ?? '');
    // The SPA policy also covers scripts: a service worker runs under its script's CSP.
    reply.header('content-security-policy', type.startsWith('application/json') ? API_CSP : csp);
    if (req.url.startsWith('/api/') || req.url === '/healthz') reply.header('cache-control', 'no-store');
    return payload;
  });
}
