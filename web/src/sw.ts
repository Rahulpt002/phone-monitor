/// <reference lib="webworker" />
/**
 * Service worker (vite-plugin-pwa injectManifest):
 * - precaches the app shell so the installed PWA opens offline,
 * - shows Web Push alerts and opens the matching screen on click,
 * - waits for the page's "Reload" before activating a new version (update prompt).
 */
import { notificationFor, type PushPayload } from './lib/notification';

declare const self: ServiceWorkerGlobalScope & { __WB_MANIFEST: Array<{ url: string; revision: string | null }> };

// Deduplicated: the same file can be listed twice (glob + includeAssets).
const MANIFEST = [...new Map(self.__WB_MANIFEST.map((e) => [e.url, e])).values()];
const CACHE_PREFIX = 'hsm-shell-';

function hash(s: string): string {
  let h = 5381;
  for (let i = 0; i < s.length; i++) h = ((h << 5) + h + s.charCodeAt(i)) | 0;
  return (h >>> 0).toString(36);
}

const CACHE = CACHE_PREFIX + hash(JSON.stringify(MANIFEST));
const scopeUrl = new URL(self.registration.scope);
const toAbs = (u: string) => new URL(u, scopeUrl).href;
const PRECACHED = new Set(MANIFEST.map((e) => toAbs(e.url)));
const INDEX = toAbs('index.html');

self.addEventListener('install', (event) => {
  event.waitUntil(
    (async () => {
      const cache = await caches.open(CACHE);
      await Promise.all(
        MANIFEST.map(async (e) => {
          const res = await fetch(new Request(toAbs(e.url), { cache: 'reload' }));
          if (!res.ok) throw new Error(`precache ${e.url}: HTTP ${res.status}`);
          await cache.put(toAbs(e.url), res);
        }),
      );
    })(),
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    (async () => {
      const keys = await caches.keys();
      await Promise.all(keys.filter((k) => k.startsWith(CACHE_PREFIX) && k !== CACHE).map((k) => caches.delete(k)));
      await self.clients.claim();
    })(),
  );
});

self.addEventListener('message', (event) => {
  const data = event.data as { type?: string } | null;
  if (data?.type === 'SKIP_WAITING') void self.skipWaiting();
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== scopeUrl.origin) return;
  // Live data and auth never come from the cache.
  if (url.pathname.startsWith('/api') || url.pathname.startsWith('/ws') || url.pathname === '/healthz') return;

  if (req.mode === 'navigate') {
    // Network first so a deploy shows up at once; the cached shell keeps the app opening offline.
    event.respondWith(
      (async () => {
        try {
          const ctrl = new AbortController();
          const timer = setTimeout(() => ctrl.abort(), 4000);
          const res = await fetch(req, { signal: ctrl.signal });
          clearTimeout(timer);
          return res;
        } catch {
          const cached = await caches.match(INDEX, { cacheName: CACHE });
          return cached ?? Response.error();
        }
      })(),
    );
    return;
  }

  const abs = url.origin + url.pathname;
  if (PRECACHED.has(abs)) {
    event.respondWith(
      (async () => {
        const cached = await caches.match(abs, { cacheName: CACHE });
        return cached ?? fetch(req);
      })(),
    );
  }
});

// --- push -------------------------------------------------------------------------------------

function parsePayload(event: PushEvent): PushPayload {
  if (!event.data) return {};
  try {
    const v = event.data.json() as unknown;
    return v && typeof v === 'object' ? (v as PushPayload) : { body: String(v) };
  } catch {
    return { body: event.data.text() };
  }
}

self.addEventListener('push', (event) => {
  const { title, options } = notificationFor(parsePayload(event));
  event.waitUntil(self.registration.showNotification(title, options));
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const url = (event.notification.data as { url?: string } | null)?.url ?? '/alerts';
  event.waitUntil(
    (async () => {
      const wins = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
      const win = wins.find((w) => new URL(w.url).origin === scopeUrl.origin);
      if (win) {
        await win.focus();
        // The app's router handles this message (App.tsx); navigate() as a fallback reloads the page.
        win.postMessage({ type: 'navigate', url });
        return;
      }
      await self.clients.openWindow(url);
    })(),
  );
});
