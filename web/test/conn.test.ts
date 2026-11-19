import type { UserInfo } from '@hsm/shared';
import { act } from 'react';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { configureApi } from '../src/lib/api';
import { startSocket, stopSocket, useConn } from '../src/store/conn';
import { useData } from '../src/store/data';
import { useSession } from '../src/store/session';
import { FakeWebSocket, flush, mockApi } from './dom';

const admin: UserInfo = { id: 1, username: 'admin', role: 'admin', totp_enabled: true, created_at: '2026-11-01T10:00:00Z' };

beforeAll(() => {
  vi.stubGlobal('WebSocket', FakeWebSocket);
});
afterAll(() => {
  vi.unstubAllGlobals();
});

beforeEach(() => {
  FakeWebSocket.instances = [];
  useSession.setState({ token: 'sess_1', user: admin, totpEnrollmentRequired: false, phase: 'ready', notice: null });
});

afterEach(() => {
  stopSocket();
  vi.useRealTimers();
  useData.getState().reset();
  configureApi({ fetchImpl: (...a) => globalThis.fetch(...a) });
});

/** Opens the socket and completes auth like the Server does. */
function connected(): FakeWebSocket {
  startSocket('sess_1');
  const sock = FakeWebSocket.latest()!;
  sock.serverOpen();
  expect(sock.sent[0]).toEqual({ t: 'auth', token: 'sess_1' });
  sock.serverSend({ t: 'auth_ok', user: admin });
  expect(useConn.getState().status).toBe('open');
  return sock;
}

describe('dashboard socket close codes', () => {
  it('4004 (role changed): reconnects, re-authenticates and reloads /auth/me without signing out (F44)', async () => {
    const calls = mockApi((c) =>
      c.method === 'GET' && c.path === '/auth/me'
        ? { status: 200, body: { user: { ...admin, role: 'viewer' }, totp_enrollment_required: false } }
        : undefined,
    );
    const first = connected();
    const epoch = useConn.getState().epoch;
    await act(async () => first.serverClose(4004, 'role changed'));
    await flush();

    expect(useSession.getState().token).toBe('sess_1');
    expect(useSession.getState().notice).toBeNull();
    expect(calls.filter((c) => c.path === '/auth/me')).toHaveLength(1);
    expect(useSession.getState().user?.role).toBe('viewer');

    const second = FakeWebSocket.latest()!;
    expect(second).not.toBe(first);
    second.serverOpen();
    expect(second.sent[0]).toEqual({ t: 'auth', token: 'sess_1' });
    second.serverSend({ t: 'auth_ok', user: { ...admin, role: 'viewer' } });
    expect(useConn.getState().status).toBe('open');
    // A re-connection: screens refetch what they may have missed.
    expect(useConn.getState().epoch).toBe(epoch + 1);

    // Another 4004 straight away does not spin: it waits for the backoff.
    await act(async () => second.serverClose(4004, 'role changed'));
    await flush();
    expect(FakeWebSocket.instances).toHaveLength(2);
    expect(useConn.getState().status).toBe('waiting');
    expect(useSession.getState().token).toBe('sess_1');
  });

  it('1013 (session lookup failed): keeps the token and retries with backoff (F43)', () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] });
    const first = connected();
    first.serverClose(1013, 'try again later');

    expect(useSession.getState().token).toBe('sess_1');
    expect(useSession.getState().phase).toBe('ready');
    expect(useConn.getState().status).toBe('waiting');
    expect(useConn.getState().nextRetryAt).not.toBeNull();
    expect(FakeWebSocket.instances).toHaveLength(1);

    vi.advanceTimersByTime(30_000);
    expect(FakeWebSocket.instances).toHaveLength(2);
    // The lookup failed before auth_ok as well: still no sign-out.
    FakeWebSocket.latest()!.serverOpen();
    FakeWebSocket.latest()!.serverClose(1013);
    expect(useSession.getState().token).toBe('sess_1');
    expect(useConn.getState().attempt).toBe(2);
  });

  it('auth_failed totp_enrollment_required: stops and asks for enrollment instead of signing out', () => {
    startSocket('sess_1');
    const sock = FakeWebSocket.latest()!;
    sock.serverOpen();
    sock.serverSend({ t: 'auth_failed', message: 'totp_enrollment_required' });
    sock.serverClose(4001, 'totp_enrollment_required');

    expect(useSession.getState().token).toBe('sess_1');
    expect(useSession.getState().totpEnrollmentRequired).toBe(true);
    expect(useConn.getState().status).toBe('idle');
    expect(FakeWebSocket.instances).toHaveLength(1);
  });

  it('4001 (bad or revoked session) still signs out', () => {
    const sock = connected();
    sock.serverClose(4001, 'session revoked');
    expect(useSession.getState().token).toBeNull();
    expect(useSession.getState().notice).toMatch(/session has expired/);
    expect(useConn.getState().status).toBe('idle');
  });

  it('closes the socket and clears device data when enrollment becomes required', () => {
    connected();
    useData.getState().setDevices([{ device_id: 'dev_1', name: 'Roof', online: true, last_seen: null, paired_at: '', info: null }]);
    useSession.getState().setTotpEnrollmentRequired(true);
    expect(useConn.getState().status).toBe('idle');
    expect(useData.getState().devices).toEqual([]);
    // It does not come back by itself.
    startSocket('sess_1');
    expect(FakeWebSocket.instances).toHaveLength(1);
  });
});
