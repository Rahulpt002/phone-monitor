import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiRequestError, api, buildUrl, configureApi, errorMessage, isApiError, request } from '../src/lib/api';
import { deriveWsUrl, normalizeBase } from '../src/lib/config';

type FetchArgs = [string, RequestInit];

function jsonResponse(status: number, body: unknown): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

let calls: FetchArgs[];
let onUnauthorized: ReturnType<typeof vi.fn<() => void>>;
let onTotpEnrollmentRequired: ReturnType<typeof vi.fn<() => void>>;
let token: string | null;

function useFetch(impl: (url: string, init: RequestInit) => Promise<Response>) {
  configureApi({
    fetchImpl: ((url: string, init: RequestInit) => {
      calls.push([url, init]);
      return impl(url, init);
    }) as typeof fetch,
  });
}

beforeEach(() => {
  calls = [];
  token = 'sess_123';
  onUnauthorized = vi.fn<() => void>();
  onTotpEnrollmentRequired = vi.fn<() => void>();
  configureApi({ base: 'https://hsm.example.com', getToken: () => token, onUnauthorized, onTotpEnrollmentRequired, timeoutMs: 1000 });
});

afterEach(() => {
  configureApi({ fetchImpl: (...a) => globalThis.fetch(...a) });
});

describe('request', () => {
  it('sends the bearer token and JSON body under /api', async () => {
    useFetch(async () => jsonResponse(201, { id: 'c_1' }));
    const out = await api.sendCommand({ device_id: 'd_1', name: 'reboot', args: {}, confirmed: true, password: 'pw' });
    expect(out).toEqual({ id: 'c_1' });
    const [url, init] = calls[0]!;
    expect(url).toBe('https://hsm.example.com/api/commands');
    expect(init.method).toBe('POST');
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer sess_123');
    expect(JSON.parse(init.body as string)).toEqual({ device_id: 'd_1', name: 'reboot', args: {}, confirmed: true, password: 'pw' });
  });

  it('builds query strings without empty values', async () => {
    useFetch(async () => jsonResponse(200, []));
    await api.events({ device_id: 'd_1', kind: '', limit: 100, before: undefined });
    expect(calls[0]![0]).toBe('https://hsm.example.com/api/events?device_id=d_1&limit=100');
    expect(buildUrl('', '/x', { a: null, b: 0, c: false })).toBe('/api/x?b=0&c=false');
  });

  it('returns undefined for 204 No Content', async () => {
    useFetch(async () => new Response(null, { status: 204 }));
    await expect(api.deleteUser(3, { reauth_password: 'pw' })).resolves.toBeUndefined();
    expect(calls[0]![1].method).toBe('DELETE');
    expect(JSON.parse(calls[0]![1].body as string)).toEqual({ reauth_password: 'pw' });
  });

  it('maps a 401 unauthorized to a logout', async () => {
    useFetch(async () => jsonResponse(401, { error: 'unauthorized', message: 'session expired' }));
    const err = await api.devices().catch((e: unknown) => e);
    expect(isApiError(err, 'unauthorized')).toBe(true);
    expect((err as ApiRequestError).status).toBe(401);
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });

  it('does not log out on a wrong re-auth password', async () => {
    useFetch(async () => jsonResponse(401, { error: 'reauth_required', message: 'wrong password' }));
    const err = await api.sendCommand({ name: 'shell', args: { cmd: 'id' }, confirmed: true, password: 'bad' }).catch((e: unknown) => e);
    expect(isApiError(err, 'reauth_required')).toBe(true);
    expect(errorMessage(err)).toBe('wrong password');
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it('does not log out when a public call (login) fails', async () => {
    token = null;
    useFetch(async () => jsonResponse(401, { error: 'unauthorized', message: 'bad credentials' }));
    await expect(api.login({ username: 'a', password: 'b' })).rejects.toMatchObject({ code: 'unauthorized' });
    expect((calls[0]![1].headers as Record<string, string>).Authorization).toBeUndefined();
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it('keeps the server error code and message', async () => {
    useFetch(async () => jsonResponse(403, { error: 'totp_enrollment_required', message: 'enable TOTP first' }));
    await expect(api.putSettings({ device_id: 'd_1', settings: { plan: { quota_gb: 5 } } })).rejects.toMatchObject({
      status: 403,
      code: 'totp_enrollment_required',
      message: 'enable TOTP first',
    });
    // The session needs TOTP enrollment (plan 10): the app switches to the setup page, no sign-out.
    expect(onTotpEnrollmentRequired).toHaveBeenCalledTimes(1);
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it('sends an explicit session token, and its answers do not touch the current session', async () => {
    useFetch(async () => jsonResponse(401, { error: 'unauthorized', message: 'session expired' }));
    await expect(api.logout('sess_old')).rejects.toMatchObject({ code: 'unauthorized' });
    expect((calls[0]![1].headers as Record<string, string>).Authorization).toBe('Bearer sess_old');
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it('falls back to the HTTP status when the body is not JSON', async () => {
    useFetch(async () => new Response('<html>Bad gateway</html>', { status: 502 }));
    await expect(api.devices()).rejects.toMatchObject({ status: 502, code: 'internal' });
    useFetch(async () => new Response('', { status: 429 }));
    await expect(api.devices()).rejects.toMatchObject({ status: 429, code: 'rate_limited' });
  });

  it('reports network failures', async () => {
    useFetch(async () => {
      throw new TypeError('Failed to fetch');
    });
    await expect(api.devices()).rejects.toMatchObject({ status: 0, code: 'network' });
  });

  it('times out slow requests', async () => {
    useFetch(
      (_url, init) =>
        new Promise((_resolve, reject) => {
          init.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')));
        }),
    );
    await expect(request('GET', '/devices', { timeoutMs: 20 })).rejects.toMatchObject({ code: 'timeout' });
  });

  it('passes caller aborts through untouched', async () => {
    useFetch(
      (_url, init) =>
        new Promise((_resolve, reject) => {
          init.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')));
        }),
    );
    const ctrl = new AbortController();
    const p = api.devices(ctrl.signal);
    ctrl.abort();
    const err = await p.catch((e: unknown) => e);
    expect(err).toBeInstanceOf(DOMException);
    expect((err as DOMException).name).toBe('AbortError');
  });

  it('rejects an unreadable success body', async () => {
    useFetch(async () => new Response('not json', { status: 200 }));
    await expect(api.devices()).rejects.toMatchObject({ code: 'invalid_response' });
  });
});

describe('config', () => {
  it('normalizes the API base', () => {
    expect(normalizeBase(' https://hsm.onrender.com/ ')).toBe('https://hsm.onrender.com');
    expect(normalizeBase(undefined)).toBe('');
  });
  it('derives the socket URL from the base or the page', () => {
    expect(deriveWsUrl('https://hsm.onrender.com', '/ws/dashboard', { protocol: 'http:', host: 'x' })).toBe('wss://hsm.onrender.com/ws/dashboard');
    expect(deriveWsUrl('http://localhost:8080', '/ws/dashboard', { protocol: 'http:', host: 'x' })).toBe('ws://localhost:8080/ws/dashboard');
    expect(deriveWsUrl('', '/ws/dashboard', { protocol: 'https:', host: 'hsm.example.com' })).toBe('wss://hsm.example.com/ws/dashboard');
    expect(deriveWsUrl('', '/ws/dashboard', { protocol: 'http:', host: 'localhost:5173' })).toBe('ws://localhost:5173/ws/dashboard');
  });
});
