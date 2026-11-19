/**
 * Plan 10 / F09: password plus TOTP for every Dashboard login. A user without TOTP (admin or
 * viewer) only sees the enrollment page: no dashboard socket, no device data, until enrolled.
 */
import type { DeviceSummary, UserInfo } from '@hsm/shared';
import { MemoryRouter } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../src/App';
import { configureApi } from '../src/lib/api';
import { stopSocket } from '../src/store/conn';
import { useData } from '../src/store/data';
import { useSession } from '../src/store/session';
import { useUi } from '../src/store/ui';
import { FakeWebSocket, byText, click, flush, mockApi, render, typeInto, waitFor, type FetchCall, type Rendered } from './dom';

const viewer: UserInfo = { id: 2, username: 'guest', role: 'viewer', totp_enabled: false, created_at: '2026-11-01T10:00:00Z' };
const admin: UserInfo = { id: 1, username: 'admin', role: 'admin', totp_enabled: false, created_at: '2026-11-01T10:00:00Z' };
const phone: DeviceSummary = { device_id: 'dev_1', name: 'Roof', online: true, last_seen: null, paired_at: '2026-11-19T09:00:00Z', info: null };

let view: Rendered | null = null;

beforeAll(() => {
  vi.stubGlobal('WebSocket', FakeWebSocket);
});
afterAll(() => {
  vi.unstubAllGlobals();
});

beforeEach(() => {
  FakeWebSocket.instances = [];
  useData.getState().reset();
  useUi.setState({ deviceId: null });
});

afterEach(() => {
  view?.unmount();
  view = null;
  document.body.innerHTML = '';
  stopSocket();
  useData.getState().reset();
  useUi.setState({ deviceId: null });
  configureApi({ fetchImpl: (...a) => globalThis.fetch(...a) });
});

async function showApp(path: string) {
  view = await render(
    <MemoryRouter initialEntries={[path]}>
      <AppRoutes />
    </MemoryRouter>,
  );
  await flush(6);
}

const deviceDataCalls = (calls: FetchCall[]) => calls.filter((c) => !c.path.startsWith('/auth/') && c.path !== '/config');

describe('TOTP enrollment gate', () => {
  for (const user of [viewer, admin]) {
    it(`sends a ${user.role} without TOTP to /enroll and opens nothing else`, async () => {
      useSession.setState({ token: 'sess_1', user, totpEnrollmentRequired: true, phase: 'ready' });
      const calls = mockApi(() => ({ status: 403, body: { error: 'totp_enrollment_required', message: 'Set up two-factor authentication first.' } }));
      await showApp('/power');
      await waitFor(() => expect(document.body.textContent).toMatch(/Start setup/));
      expect(document.body.textContent).toMatch(/Set up two-factor authentication/);
      expect(document.body.textContent).not.toMatch(/Later/);
      expect(FakeWebSocket.instances).toHaveLength(0);
      expect(deviceDataCalls(calls)).toEqual([]);
      // Signing out stays possible.
      expect(byText(document.body, 'button', 'Sign out')).toBeDefined();
    });
  }

  it('opens the app (socket and device data) once enrollment is done, on the page asked for', async () => {
    useSession.setState({ token: 'sess_1', user: viewer, totpEnrollmentRequired: true, phase: 'ready' });
    let enrolled = false;
    const calls = mockApi((c) => {
      if (c.method === 'POST' && c.path === '/auth/totp/setup') return { status: 200, body: { secret: 'JBSWY3DPEHPK3PXP', otpauth_url: 'otpauth://totp/HSM:guest?secret=JBSWY3DPEHPK3PXP' } };
      if (c.method === 'POST' && c.path === '/auth/totp/enable') {
        enrolled = true;
        return { status: 200, body: { ok: true } };
      }
      if (c.method === 'GET' && c.path === '/auth/me') return { status: 200, body: { user: { ...viewer, totp_enabled: enrolled }, totp_enrollment_required: !enrolled } };
      if (!enrolled) return { status: 403, body: { error: 'totp_enrollment_required', message: 'Set up two-factor authentication first.' } };
      if (c.method === 'GET' && c.path === '/devices') return { status: 200, body: [phone] };
      return undefined;
    });
    await showApp('/alerts');
    await waitFor(() => expect(byText(document.body, 'button', 'Start setup')).toBeDefined());
    await click(byText(document.body, 'button', 'Start setup'));
    await typeInto(document.querySelector('input[autocomplete="one-time-code"]'), '123456');
    await click(byText(document.body, 'button', 'Verify and turn on'));
    await waitFor(() => expect(calls.some((c) => c.method === 'GET' && c.path === '/devices')).toBe(true));

    expect(useSession.getState().totpEnrollmentRequired).toBe(false);
    expect(FakeWebSocket.instances.length).toBeGreaterThan(0);
    expect(calls.some((c) => c.method === 'GET' && c.path === '/devices')).toBe(true);
    // No request went out for device data while enrollment was pending.
    const enableAt = calls.findIndex((c) => c.path === '/auth/totp/enable');
    expect(deviceDataCalls(calls.slice(0, enableAt))).toEqual([]);
  });

  it('sends a signed-in user whose session needs enrollment from /login to /enroll', async () => {
    useSession.setState({ token: 'sess_1', user: viewer, totpEnrollmentRequired: true, phase: 'ready' });
    mockApi(() => undefined);
    await showApp('/login');
    await waitFor(() => expect(document.body.textContent).toMatch(/Start setup/));
    expect(FakeWebSocket.instances).toHaveLength(0);
  });

  it('leaves /enroll for the app when the user already has TOTP', async () => {
    useSession.setState({ token: 'sess_1', user: { ...viewer, totp_enabled: true }, totpEnrollmentRequired: false, phase: 'ready' });
    mockApi((c) => (c.path === '/devices' ? { status: 200, body: [] } : undefined));
    await showApp('/enroll');
    expect(document.body.textContent).not.toMatch(/Start setup/);
    expect(FakeWebSocket.instances.length).toBeGreaterThan(0);
  });
});
