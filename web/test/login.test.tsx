/**
 * F39: the sign-in form sends this browser's known-device token for that username and keeps the
 * new one the Server returns, so strangers' failed attempts on the username do not lock it out.
 */
import type { UserInfo } from '@hsm/shared';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { configureApi } from '../src/lib/api';
import { deviceTokenFor, rememberDeviceToken } from '../src/lib/knownDevice';
import { KEYS } from '../src/lib/storage';
import { LoginScreen } from '../src/screens/Login';
import { useSession } from '../src/store/session';
import { byText, click, flush, mockApi, render, typeInto, waitFor, type Rendered } from './dom';

const admin: UserInfo = { id: 1, username: 'admin', role: 'admin', totp_enabled: true, created_at: '2026-11-01T10:00:00Z' };
let view: Rendered | null = null;

beforeEach(() => {
  localStorage.clear();
  useSession.setState({ token: null, user: null, totpEnrollmentRequired: false, phase: 'ready', notice: null });
});
afterEach(() => {
  view?.unmount();
  view = null;
  document.body.innerHTML = '';
  localStorage.clear();
  configureApi({ fetchImpl: (...a) => globalThis.fetch(...a) });
});

describe('known-device token', () => {
  it('is kept per username, newest replacing the old one', () => {
    expect(deviceTokenFor('admin')).toBeUndefined();
    rememberDeviceToken('Admin', 'tok_a');
    rememberDeviceToken('guest', 'tok_g');
    rememberDeviceToken('admin ', 'tok_a2');
    expect(deviceTokenFor('ADMIN')).toBe('tok_a2');
    expect(deviceTokenFor('guest')).toBe('tok_g');
    rememberDeviceToken('admin', undefined);
    expect(deviceTokenFor('admin')).toBe('tok_a2');
    localStorage.setItem(KEYS.knownDevices, '{not json');
    expect(deviceTokenFor('admin')).toBeUndefined();
  });

  it('is sent with both sign-in steps and replaced by the one the Server returns', async () => {
    rememberDeviceToken('admin', 'tok_old');
    const calls = mockApi((c) => {
      if (c.path === '/auth/login') return { status: 200, body: { totp_required: true, pending_token: 'pend_1' } };
      if (c.path === '/auth/totp') return { status: 200, body: { token: 'sess_1', user: admin, expires_at: '2026-12-19T10:00:00Z', device_token: 'tok_new' } };
      if (c.path === '/auth/me') return { status: 200, body: { user: admin, totp_enrollment_required: false } };
      return undefined;
    });
    view = await render(
      <MemoryRouter>
        <LoginScreen />
      </MemoryRouter>,
    );
    await typeInto(document.querySelector('input[autocomplete="username"]'), 'admin');
    await typeInto(document.querySelector('input[autocomplete="current-password"]'), 'correct horse battery');
    await click(byText(document.body, 'button', 'Sign in'));
    await waitFor(() => expect(document.querySelector('input[autocomplete="one-time-code"]')).not.toBeNull());
    await typeInto(document.querySelector('input[autocomplete="one-time-code"]'), '123456');
    await click(byText(document.body, 'button', 'Verify'));
    await flush(4);
    expect(calls.find((c) => c.path === '/auth/login')?.body).toMatchObject({ username: 'admin', device_token: 'tok_old' });
    expect(calls.find((c) => c.path === '/auth/totp')?.body).toMatchObject({ pending_token: 'pend_1', code: '123456', device_token: 'tok_old' });
    expect(deviceTokenFor('admin')).toBe('tok_new');
    expect(useSession.getState().token).toBe('sess_1');
  });
});
