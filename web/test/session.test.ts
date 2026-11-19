import type { UserInfo } from '@hsm/shared';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { configureApi } from '../src/lib/api';
import { useSession } from '../src/store/session';
import { flush, mockApi } from './dom';

const admin: UserInfo = { id: 1, username: 'admin', role: 'admin', totp_enabled: true, created_at: '2026-11-01T10:00:00Z' };
const viewer: UserInfo = { id: 2, username: 'guest', role: 'viewer', totp_enabled: false, created_at: '2026-11-01T10:00:00Z' };

const unsubscribe = vi.fn(async () => true);

function installPush(subscribed: boolean) {
  const sub = { endpoint: 'https://push.example/abc', unsubscribe };
  vi.stubGlobal('PushManager', function PushManager() {});
  vi.stubGlobal('Notification', { permission: 'granted' });
  Object.defineProperty(navigator, 'serviceWorker', {
    configurable: true,
    value: {
      ready: new Promise(() => undefined),
      getRegistration: async () => ({ pushManager: { getSubscription: async () => (subscribed ? sub : null) } }),
    },
  });
}

beforeEach(() => {
  useSession.setState({ token: 'sess_1', user: admin, totpEnrollmentRequired: false, phase: 'ready', notice: null });
  unsubscribe.mockClear();
});

afterEach(() => {
  vi.unstubAllGlobals();
  delete (navigator as { serviceWorker?: unknown }).serviceWorker;
  configureApi({ fetchImpl: (...a) => globalThis.fetch(...a) });
});

describe('sign out', () => {
  it('removes this browser’s push subscription with the session token, then ends the session (F04)', async () => {
    installPush(true);
    const calls = mockApi(() => ({ status: 204 }));
    useSession.getState().logout();
    // The app signs out at once; the Server calls finish in the background.
    expect(useSession.getState().token).toBeNull();
    expect(useSession.getState().phase).toBe('anonymous');
    await flush();
    expect(calls.map((c) => `${c.method} ${c.path}`)).toEqual(['POST /push/unsubscribe', 'POST /auth/logout']);
    expect(calls[0]!.body).toEqual({ endpoint: 'https://push.example/abc' });
    expect(calls.map((c) => c.auth)).toEqual(['Bearer sess_1', 'Bearer sess_1']);
    expect(unsubscribe).toHaveBeenCalledTimes(1);
  });

  it('still ends the session when push is off, unsupported or the unsubscribe fails', async () => {
    installPush(false);
    let calls = mockApi(() => ({ status: 204 }));
    useSession.getState().logout();
    await flush();
    expect(calls.map((c) => c.path)).toEqual(['/auth/logout']);

    vi.unstubAllGlobals();
    delete (navigator as { serviceWorker?: unknown }).serviceWorker;
    useSession.setState({ token: 'sess_2', user: admin, phase: 'ready' });
    calls = mockApi(() => ({ status: 204 }));
    useSession.getState().logout();
    await flush();
    expect(calls.map((c) => c.path)).toEqual(['/auth/logout']);
    expect(calls[0]!.auth).toBe('Bearer sess_2');

    installPush(true);
    useSession.setState({ token: 'sess_3', user: admin, phase: 'ready' });
    calls = mockApi((c) => (c.path === '/push/unsubscribe' ? { status: 500, body: { error: 'internal', message: 'boom' } } : { status: 204 }));
    useSession.getState().logout();
    await flush();
    expect(calls.map((c) => c.path)).toEqual(['/push/unsubscribe', '/auth/logout']);
  });

  it('signing out before TOTP enrollment only unsubscribes the browser (the Server would answer 403)', async () => {
    installPush(true);
    useSession.setState({ totpEnrollmentRequired: true });
    const calls = mockApi(() => ({ status: 204 }));
    useSession.getState().logout();
    await flush();
    expect(calls.map((c) => c.path)).toEqual(['/auth/logout']);
    expect(unsubscribe).toHaveBeenCalledTimes(1);
  });

  it('does not call the Server after an involuntary sign-out (the session is already gone)', async () => {
    installPush(true);
    const calls = mockApi(() => ({ status: 204 }));
    useSession.getState().logout('Your session has expired. Please sign in again.', false);
    await flush();
    expect(calls).toEqual([]);
    expect(useSession.getState().notice).toMatch(/expired/);
  });
});

describe('two-factor enrollment (plan 10, F09)', () => {
  it('is required for every role without TOTP, viewers included', async () => {
    const calls = mockApi((c) =>
      c.path === '/auth/me' ? { status: 200, body: { user: viewer, totp_enrollment_required: true } } : undefined,
    );
    useSession.setState({ token: null, user: null, phase: 'anonymous', totpEnrollmentRequired: false });
    await useSession.getState().setSession('sess_v', viewer);
    expect(calls.map((c) => c.path)).toEqual(['/auth/me']);
    expect(useSession.getState().totpEnrollmentRequired).toBe(true);
  });

  it('turns on when any request answers 403 totp_enrollment_required', async () => {
    const { api } = await import('../src/lib/api');
    mockApi(() => ({ status: 403, body: { error: 'totp_enrollment_required', message: 'Set up two-factor authentication first.' } }));
    await expect(api.devices()).rejects.toMatchObject({ code: 'totp_enrollment_required' });
    expect(useSession.getState().totpEnrollmentRequired).toBe(true);
    expect(useSession.getState().token).toBe('sess_1');
  });
});
