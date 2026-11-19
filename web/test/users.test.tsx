import type { UserInfo } from '@hsm/shared';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { UsersPanel, resetTotpExplanation } from '../src/components/settings/UsersPanel';
import { configureApi } from '../src/lib/api';
import { useSession } from '../src/store/session';
import { byText, click, flush, mockApi, render, typeInto, type FetchCall, type Rendered } from './dom';

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
const pwButton = (name: string) => document.querySelector<HTMLButtonElement>(`button[aria-label="Reset password for ${name}"]`);
const deleteButton = (name: string) => document.querySelector<HTMLButtonElement>(`button[aria-label="Delete ${name}"]`);
const dialog = () => document.querySelector<HTMLElement>('[role="dialog"]');
const writes = (calls: FetchCall[]) => calls.filter((c) => c.method !== 'GET');
const row = (name: string) => byText(document.body, 'tr', new RegExp(`^${name}`));
const reauthInput = () => {
  const label = byText<HTMLLabelElement>(dialog()!, 'label', 'Your password');
  return label ? document.getElementById(label.htmlFor) : null;
};

async function showUsers(route: (c: FetchCall) => { status: number; body?: unknown } | undefined) {
  const calls = mockApi((c) => (c.method === 'GET' && c.path === '/users' ? { status: 200, body: [admin, ravi, viewer] } : route(c)));
  view = await render(
    <MemoryRouter>
      <UsersPanel />
    </MemoryRouter>,
  );
  await flush();
  return calls;
}

async function confirmWith(password: string, button: string) {
  await typeInto(reauthInput(), password);
  await click(byText(dialog()!, 'button', button));
}

describe('Users: Reset 2FA', () => {
  it('is offered only for other users who have two-factor authentication', async () => {
    await showUsers(() => undefined);
    expect(resetButton('ravi')?.disabled).toBe(false);
    expect(resetButton('guest')?.disabled).toBe(true);
    expect(resetButton('guest')?.title).toMatch(/no two-factor authentication to reset/);
  });

  it('asks for your own password, then sends PATCH /users/:id { reset_totp, reauth_password } (F00)', async () => {
    const calls = await showUsers((c) =>
      c.method === 'PATCH' && c.path === '/users/2' ? { status: 200, body: { ...ravi, totp_enabled: false } } : undefined,
    );
    await click(resetButton('ravi'));
    expect(dialog()?.textContent).toMatch(/Reset two-factor authentication for ravi\?/);
    expect(dialog()?.textContent).toMatch(/clears ravi’s authenticator and signs them out on every device/);
    expect(writes(calls)).toEqual([]);
    // The password field is focused first.
    expect(document.activeElement).toBe(reauthInput());

    // Nothing is sent without the password.
    await click(byText(dialog()!, 'button', 'Reset 2FA'));
    expect(writes(calls)).toEqual([]);
    expect(dialog()?.textContent).toMatch(/Enter your password/);

    await confirmWith('my-own-password', 'Reset 2FA');
    expect(writes(calls)).toEqual([{ method: 'PATCH', path: '/users/2', body: { reset_totp: true, reauth_password: 'my-own-password' } }]);
    expect(dialog()).toBeNull();
    expect(row('ravi')?.textContent).toMatch(/Not set up/);
    expect(resetButton('ravi')?.disabled).toBe(true);
  });

  it('keeps the dialog open on a wrong password or another refusal', async () => {
    let answer = { status: 401, body: { error: 'reauth_required', message: 'Password required' } };
    const calls = await showUsers((c) => (c.method === 'PATCH' ? answer : undefined));
    await click(resetButton('ravi'));
    await confirmWith('wrong', 'Reset 2FA');
    expect(writes(calls)).toHaveLength(1);
    expect(dialog()?.textContent).toMatch(/Wrong password/);
    // A wrong re-auth password never ends the session.
    expect(useSession.getState().token).toBe('sess_1');

    answer = { status: 404, body: { error: 'not_found', message: 'User not found' } };
    await confirmWith('right', 'Reset 2FA');
    expect(dialog()?.textContent).toMatch(/User not found/);
    expect(row('ravi')?.textContent).toMatch(/On/);
  });

  it('explains the reset for viewers and admins', () => {
    expect(resetTotpExplanation(viewer)).toMatch(/must set up two-factor authentication again/);
    expect(resetTotpExplanation(ravi)).toMatch(/clears ravi’s authenticator/);
    expect(resetTotpExplanation(ravi)).toMatch(/push notifications/);
  });
});

describe('Users: your own row', () => {
  it('has no password reset, Reset 2FA or delete; it points to My account instead (F00)', async () => {
    await showUsers(() => undefined);
    expect(pwButton('admin')).toBeNull();
    expect(resetButton('admin')).toBeNull();
    expect(deleteButton('admin')?.disabled).toBe(true);
    const link = [...(row('admin')?.querySelectorAll('a') ?? [])].find((a) => /My account/.test(a.textContent ?? ''));
    expect(link?.getAttribute('href')).toBe('/settings?tab=account');
    expect(document.querySelector<HTMLSelectElement>('#role-1')?.disabled).toBe(true);
  });
});

describe('Users: every change needs your password (F00)', () => {
  it('adds a user after confirming with your password', async () => {
    const created: UserInfo = { id: 4, username: 'asha', role: 'admin', totp_enabled: false, created_at: '2026-11-19T10:00:00Z' };
    const calls = await showUsers((c) => (c.method === 'POST' && c.path === '/users' ? { status: 201, body: created } : undefined));
    const form = byText(document.body, 'form', /Add user/)!;
    const inputs = form.querySelectorAll('input');
    await typeInto(inputs[0], 'asha');
    await typeInto(inputs[1], 'a-long-password');
    const role = form.querySelector('select')!;
    role.value = 'admin';
    role.dispatchEvent(new Event('change', { bubbles: true }));
    await click(byText(form, 'button', 'Add user'));
    expect(writes(calls)).toEqual([]);
    expect(dialog()?.textContent).toMatch(/Add asha as an admin\?/);

    await confirmWith('my-own-password', 'Add user');
    expect(writes(calls)).toEqual([
      { method: 'POST', path: '/users', body: { username: 'asha', password: 'a-long-password', role: 'admin', reauth_password: 'my-own-password' } },
    ]);
    expect(dialog()).toBeNull();
    expect(row('asha')).toBeDefined();
  });

  it('changes a role only after confirming with your password', async () => {
    const calls = await showUsers((c) => (c.method === 'PATCH' && c.path === '/users/3' ? { status: 200, body: { ...viewer, role: 'admin' } } : undefined));
    const select = document.querySelector<HTMLSelectElement>('#role-3')!;
    select.value = 'admin';
    select.dispatchEvent(new Event('change', { bubbles: true }));
    await flush();
    expect(dialog()?.textContent).toMatch(/Make guest an admin\?/);
    expect(writes(calls)).toEqual([]);

    await confirmWith('my-own-password', 'Change role');
    expect(writes(calls)).toEqual([{ method: 'PATCH', path: '/users/3', body: { role: 'admin', reauth_password: 'my-own-password' } }]);
    expect(document.querySelector<HTMLSelectElement>('#role-3')?.value).toBe('admin');
  });

  it('leaves the role unchanged when the confirmation is cancelled', async () => {
    const calls = await showUsers(() => undefined);
    const select = document.querySelector<HTMLSelectElement>('#role-3')!;
    select.value = 'admin';
    select.dispatchEvent(new Event('change', { bubbles: true }));
    await flush();
    await click(byText(dialog()!, 'button', 'Cancel'));
    expect(writes(calls)).toEqual([]);
    expect(document.querySelector<HTMLSelectElement>('#role-3')?.value).toBe('viewer');
  });

  it('resets another user’s password with your password', async () => {
    const calls = await showUsers((c) => (c.method === 'PATCH' && c.path === '/users/3' ? { status: 200, body: viewer } : undefined));
    await click(pwButton('guest'));
    const newPw = document.getElementById(byText<HTMLLabelElement>(dialog()!, 'label', 'New password for guest')!.htmlFor);
    expect(document.activeElement).toBe(newPw);
    await typeInto(newPw, 'short');
    await confirmWith('my-own-password', 'Set password');
    expect(writes(calls)).toEqual([]);
    expect(dialog()?.textContent).toMatch(/At least 10 characters/);

    await typeInto(newPw, 'a-new-long-password');
    await confirmWith('my-own-password', 'Set password');
    expect(writes(calls)).toEqual([
      { method: 'PATCH', path: '/users/3', body: { password: 'a-new-long-password', reauth_password: 'my-own-password' } },
    ]);
    expect(dialog()).toBeNull();
  });

  it('deletes a user with DELETE /users/:id { reauth_password }', async () => {
    const calls = await showUsers((c) => (c.method === 'DELETE' && c.path === '/users/3' ? { status: 204 } : undefined));
    await click(deleteButton('guest'));
    expect(dialog()?.textContent).toMatch(/Delete guest\?/);
    await confirmWith('my-own-password', 'Delete user');
    expect(writes(calls)).toEqual([{ method: 'DELETE', path: '/users/3', body: { reauth_password: 'my-own-password' } }]);
    expect(row('guest')).toBeUndefined();
  });
});
