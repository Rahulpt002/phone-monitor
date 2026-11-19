/**
 * Where the Server lives. Empty VITE_API_BASE = same origin (the Server serves this app, or the
 * Vite dev server proxies /api and /ws). Otherwise an absolute URL such as https://hsm.onrender.com.
 */
export function normalizeBase(raw: string | undefined | null): string {
  return (raw ?? '').trim().replace(/\/+$/, '');
}

export const API_BASE = normalizeBase(import.meta.env.VITE_API_BASE as string | undefined);

/** http(s) base -> ws(s) URL for `path`; an empty base derives the socket URL from the page location. */
export function deriveWsUrl(base: string, path: string, loc: Pick<Location, 'protocol' | 'host'>): string {
  if (!base) {
    const proto = loc.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${proto}//${loc.host}${path}`;
  }
  return base.replace(/^http(s?):\/\//i, (_m, s: string) => `ws${s.toLowerCase()}://`) + path;
}

export function dashboardSocketUrl(): string {
  return deriveWsUrl(API_BASE, '/ws/dashboard', window.location);
}

export const APP_VERSION: string = (import.meta.env.VITE_APP_VERSION as string | undefined) ?? '0.1.0';
