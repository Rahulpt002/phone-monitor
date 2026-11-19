import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { HsmServer } from '../src/server.js';
import { makeServer } from './helpers.js';

describe('static Dashboard, security headers and CORS', () => {
  let server: HsmServer;
  let dist: string;

  beforeAll(async () => {
    dist = mkdtempSync(join(tmpdir(), 'hsm-web-'));
    mkdirSync(join(dist, 'assets'));
    writeFileSync(join(dist, 'index.html'), '<!doctype html><title>HSM</title>');
    writeFileSync(join(dist, 'sw.js'), 'self.addEventListener("fetch", () => {});');
    writeFileSync(join(dist, 'assets', 'app-3f9c2a.js'), 'console.log(1)');
    ({ server } = await makeServer({
      config: { webDist: dist, trustProxy: true, corsOrigins: ['https://hsm.vercel.app'], publicUrl: 'https://hsm.example.com' },
    }));
  });

  afterAll(async () => {
    await server.close();
    rmSync(dist, { recursive: true, force: true });
  });

  const get = (url: string, headers: Record<string, string> = {}) => server.app.inject({ method: 'GET', url, headers });

  it('serves index.html without caching and with the SPA CSP', async () => {
    const r = await get('/');
    expect(r.statusCode).toBe(200);
    expect(r.body).toContain('<title>HSM</title>');
    expect(r.headers['cache-control']).toBe('no-cache');
    expect(r.headers['content-security-policy']).toContain("connect-src 'self' wss://hsm.example.com");
    expect(r.headers['x-content-type-options']).toBe('nosniff');
    expect(r.headers['referrer-policy']).toBe('no-referrer');
  });

  it('caches hashed assets forever and revalidates the service worker', async () => {
    expect((await get('/assets/app-3f9c2a.js')).headers['cache-control']).toBe('public, max-age=31536000, immutable');
    expect((await get('/sw.js')).headers['cache-control']).toBe('no-cache');
  });

  it('falls back to index.html for client routes only', async () => {
    const page = await get('/settings/users');
    expect(page.statusCode).toBe(200);
    expect(page.body).toContain('<title>HSM</title>');
    const missingAsset = await get('/assets/missing.js');
    expect(missingAsset.statusCode).toBe(404);
    const api = await get('/api/nothing-here');
    expect(api.statusCode).toBe(404);
    expect(JSON.parse(api.body)).toMatchObject({ error: 'not_found' });
  });

  it('sets HSTS only behind https', async () => {
    expect((await get('/healthz', { 'x-forwarded-proto': 'https' })).headers['strict-transport-security']).toContain('max-age=');
    expect((await get('/healthz')).headers['strict-transport-security']).toBeUndefined();
  });

  it('allows CORS only for CORS_ORIGINS', async () => {
    const ok = await get('/api/config', { origin: 'https://hsm.vercel.app' });
    expect(ok.headers['access-control-allow-origin']).toBe('https://hsm.vercel.app');
    const other = await get('/api/config', { origin: 'https://evil.example' });
    expect(other.headers['access-control-allow-origin']).toBeUndefined();
    const pre = await server.app.inject({
      method: 'OPTIONS',
      url: '/api/auth/login',
      headers: { origin: 'https://hsm.vercel.app', 'access-control-request-method': 'POST', 'access-control-request-headers': 'authorization,content-type' },
    });
    expect(pre.statusCode).toBe(204);
    expect(String(pre.headers['access-control-allow-headers']).toLowerCase()).toContain('authorization');
  });

  it('rejects oversized and malformed bodies with ApiError JSON', async () => {
    const big = await server.app.inject({
      method: 'POST',
      url: '/api/auth/login',
      headers: { 'content-type': 'application/json' },
      payload: JSON.stringify({ username: 'a', password: 'x'.repeat(300_000) }),
    });
    expect(big.statusCode).toBe(413);
    expect(JSON.parse(big.body).error).toBe('bad_request');
    const bad = await server.app.inject({
      method: 'POST',
      url: '/api/auth/login',
      headers: { 'content-type': 'application/json' },
      payload: '{nope',
    });
    expect(bad.statusCode).toBe(400);
    expect(JSON.parse(bad.body).error).toBe('bad_request');
  });

  it('rate-limits login attempts per IP', async () => {
    let last = 0;
    for (let i = 0; i < 12; i++) {
      const r = await server.app.inject({
        method: 'POST',
        url: '/api/auth/login',
        headers: { 'content-type': 'application/json' },
        payload: JSON.stringify({ username: `nobody${i}`, password: 'x' }),
        remoteAddress: '192.0.2.50',
      });
      last = r.statusCode;
      if (r.statusCode === 429) {
        expect(['rate_limited', 'locked']).toContain(JSON.parse(r.body).error);
      }
    }
    expect(last).toBe(429);
  });
});
