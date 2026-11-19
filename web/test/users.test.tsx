import type { UserInfo } from '@hsm/shared';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { UsersPanel, resetTotpExplanation } from '../src/components/settings/UsersPanel';
import { configureApi } from '../src/lib/api';
import { useSession } from '../src/store/session';
import { byText, click, flush, mockApi, render, type FetchCall, type Rendered } from './dom';

const admin: UserInfo = { id: 1, username: 'admin', role: 'admin', totp_enabled: true, created_at: '2026-11-01T10:00:00Z' };
const ravi: UserInfo = { id: 2, username: 'ravi', role: 'admin', totp_enabled: true, created_at: '2026-11-02T10:00:00Z' };
const viewer: UserInfo = { id: 3, username: 'guest', role: 'viewer', totp_enabled: false, created_at: '2026-11-03T10:00:00Z' };

let view: Rendered | null = null;

beforeEach(() => {
  useSession.setState({ token: 'sess_1', user: admin, totpEnrollmentRequired: false, phase: 'ready' });
});

afterEach(() => {
  view?.unmount();
  view = null;
  document.body.innerHTML = '';
  configureApi({ fetchImpl: (...a) => globalThis.fetch(...a) });
});

const resetButton = (name: string) => document.querySelector<HTMLButtonElement>(`button[aria-label="Reset two-factor authentication for ${name}"]`);
const dialog = () => document.querySelector<HTMLElement>('[role="dialog"]');
const patches = (calls: FetchCall[]) => calls.filter((c) => c.method === 'PATCH');

async function showUsers(route: (c: FetchCall) => { status: number; body?: unknown } | undefined) {
  const calls = mockApi((c) => (c.method === 'GET' && c.path === '/users' ? { status: 200, body: [admin, ravi, viewer] } : route(c)));
  view = await render(<UsersPanel />);
  await flush();
  return calls;
}

describe('Users: Reset 2FA', () => {
  it('is offered only for users who have two-factor authentication', async () => {
    await showUsers(() => undefined);
    expect(resetButton('ravi')?.disabled).toBe(false);
    expect(resetButton('guest')?.disabled).toBe(true);
    expect(resetButton('guest')?.title).toMatch(/no two-factor authentication to reset/);
  });

  it('confirms, then sends PATCH /users/:id { reset_totp: true } and shows 2FA as off', async () => {
    const calls = await showUsers((c) =>
      c.method === 'PATCH' && c.path === '/users/2' ? { status: 200, body: { ...ravi, totp_enabled: false } } : undefined,
    );
    await click(resetButton('ravi'));
    expect(dialog()?.textContent).toMatch(/Reset two-factor authentication for ravi\?/);
    expect(dialog()?.textContent).toMatch(/clears ravi’s authenticator and signs them out on every device/);
    // Nothing is sent before the confirmation; Cancel is focused first for this destructive action.
    expect(patches(calls)).toEqual([]);
    expect(document.activeElement?.textContent).toBe('Cancel');

    await click(byText(dialog()!, 'button', 'Reset 2FA'));
    expect(patches(calls)).toEqual([{ method: 'PATCH', path: '/users/2', body: { reset_totp: true } }]);
    expect(dialog()).toBeNull();
    const row = byText(document.body, 'tr', /^ravi/);
    expect(row?.textContent).toMatch(/Off/);
    expect(resetButton('ravi')?.disabled).toBe(true);
  });

  it('keeps the dialog open and shows the error when the Server refuses', async () => {
    const calls = await showUsers((c) =>
      c.method === 'PATCH' ? { status: 404, body: { error: 'not_found', message: 'User not found' } } : undefined,
    );
    await click(resetButton('ravi'));
    await click(byText(dialog()!, 'button', 'Reset 2FA'));
    expect(patches(calls)).toHaveLength(1);
    expect(dialog()?.textContent).toMatch(/User not found/);
    expect(byText(document.body, 'tr', /^ravi/)?.textContent).toMatch(/On/);
  });

  it('after resetting your own 2FA, reloads the session so enrollment is required again', async () => {
    const calls = await showUsers((c) => {
      if (c.method === 'PATCH' && c.path === '/users/1') return { status: 200, body: { ...admin, totp_enabled: false } };
      if (c.method === 'GET' && c.path === '/auth/me') return { status: 200, body: { user: { ...admin, totp_enabled: false }, totp_enrollment_required: true } };
      return undefined;
    });
    await click(resetButton('admin'));
    expect(dialog()?.textContent).toMatch(/this browser stays signed in/);
    await click(byText(dialog()!, 'button', 'Reset 2FA'));
    expect(calls.some((c) => c.method === 'GET' && c.path === '/auth/me')).toBe(true);
    expect(useSession.getState().totpEnrollmentRequired).toBe(true);
    // Without 2FA the admin can no longer make changes, so the actions are disabled.
    expect(resetButton('ravi')?.disabled).toBe(true);
  });

  it('explains the reset for viewers, admins and yourself', () => {
    expect(resetTotpExplanation(viewer, false)).not.toMatch(/As an admin/);
    expect(resetTotpExplanation(ravi, false)).toMatch(/As an admin they must set it up again/);
    expect(resetTotpExplanation(admin, true)).toMatch(/signs you out on every other device/);
  });
});
