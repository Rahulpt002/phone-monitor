import { DEFAULT_SETTINGS, mergeSettings, type Settings, type UserInfo } from '@hsm/shared';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { SettingsEditor } from '../src/components/settings/SettingsEditor';
import { configureApi } from '../src/lib/api';
import { useData } from '../src/store/data';
import { useSession } from '../src/store/session';
import { useUi } from '../src/store/ui';
import { byText, click, flush, mockApi, render, typeInto, type FetchCall, type Rendered } from './dom';

const admin: UserInfo = { id: 1, username: 'admin', role: 'admin', totp_enabled: true, created_at: '2026-11-01T10:00:00Z' };
const DEVICE = 'dev_1';
const stored: Settings = {
  ...structuredClone(DEFAULT_SETTINGS),
  profile_override: { usb_function: 'ncm', thermal: { cpu: ['cpu-0'], battery: ['battery'] } },
};

let view: Rendered | null = null;

beforeEach(() => {
  useSession.setState({ token: 'sess_1', user: admin, totpEnrollmentRequired: false, phase: 'ready' });
  useData.getState().reset();
  useUi.setState({ deviceId: DEVICE });
  useData.getState().setSettings(DEVICE, stored, 7);
});

afterEach(() => {
  view?.unmount();
  view = null;
  document.body.innerHTML = '';
  useUi.setState({ deviceId: null });
  useData.getState().reset();
  configureApi({ fetchImpl: (...a) => globalThis.fetch(...a) });
});

const overrideBox = () => document.querySelector<HTMLTextAreaElement>('#section-profile textarea');
const dialog = () => document.querySelector<HTMLElement>('[role="dialog"]');
const puts = (calls: FetchCall[]) => calls.filter((c) => c.method === 'PUT');
const inputFor = (label: string) => {
  const l = byText<HTMLLabelElement>(document.body, 'label', label);
  return l ? document.getElementById(l.htmlFor) : null;
};

/** Saves, confirming the profile override change with the admin's own password. */
async function saveWithPassword(password = 'my-own-password') {
  await click(byText(document.body, 'button', 'Save'));
  expect(dialog()?.textContent).toMatch(/Confirm the device profile change/);
  const label = byText<HTMLLabelElement>(dialog()!, 'label', 'Your password')!;
  await typeInto(document.getElementById(label.htmlFor), password);
  await click(byText(dialog()!, 'button', 'Confirm and save'));
  await flush();
}

/** Answers PUT /settings the way the Server does (shared mergeSettings, password for profile_override). */
function serverLike(password = 'my-own-password'): FetchCall[] {
  let current = stored;
  let version = 7;
  return mockApi((c) => {
    if (c.method !== 'PUT' || c.path !== '/settings') return undefined;
    const body = c.body as { device_id: string; settings: Record<string, unknown>; password?: string };
    if ('profile_override' in body.settings && body.password !== password) {
      return { status: 401, body: { error: 'reauth_required', message: 'Password required to change the device profile' } };
    }
    current = mergeSettings(current, body.settings);
    version += 1;
    return { status: 200, body: { device_id: body.device_id, settings: current, version } };
  });
}

describe('SettingsEditor save', () => {
  it('removes a key from the profile override with one PUT carrying the whole override', async () => {
    const calls = serverLike();
    view = await render(<SettingsEditor />);
    await typeInto(overrideBox(), JSON.stringify({ thermal: { cpu: ['cpu-0'] } }, null, 2));
    expect(document.body.textContent).toMatch(/1 unsaved change/);

    await saveWithPassword();
    expect(puts(calls)).toEqual([
      { method: 'PUT', path: '/settings', body: { device_id: DEVICE, settings: { profile_override: { thermal: { cpu: ['cpu-0'] } } }, password: 'my-own-password' } },
    ]);
    expect(dialog()).toBeNull();
    expect(useData.getState().settings[DEVICE]?.settings.profile_override).toEqual({ thermal: { cpu: ['cpu-0'] } });
    expect(useData.getState().settings[DEVICE]?.version).toBe(8);
    expect(document.body.textContent).toMatch(/No changes · version 8/);
  });

  it('sends a threshold change and an override edit together in one PUT, keeping the other override keys', async () => {
    const calls = serverLike();
    view = await render(<SettingsEditor />);
    await typeInto(overrideBox(), JSON.stringify({ usb_function: 'rndis', thermal: { cpu: ['cpu-0'], battery: ['battery'] } }));
    await typeInto(inputFor('Stop charging at'), '55');
    await saveWithPassword();
    expect(puts(calls)).toHaveLength(1);
    const sent = (puts(calls)[0]!.body as { settings: Partial<Settings> }).settings;
    expect(sent).toEqual({
      watchdog: { charge_limit_pct: 55 },
      profile_override: { usb_function: 'rndis', thermal: { cpu: ['cpu-0'], battery: ['battery'] } },
    });
  });

  it('clears the override with null when the box is emptied', async () => {
    const calls = serverLike();
    view = await render(<SettingsEditor />);
    await typeInto(overrideBox(), '');
    await saveWithPassword();
    expect(puts(calls).map((c) => c.body)).toEqual([{ device_id: DEVICE, settings: { profile_override: null }, password: 'my-own-password' }]);
  });

  it('saves threshold changes without asking for the password', async () => {
    const calls = serverLike();
    view = await render(<SettingsEditor />);
    await typeInto(inputFor('Stop charging at'), '55');
    await click(byText(document.body, 'button', 'Save'));
    await flush();
    expect(dialog()).toBeNull();
    expect(puts(calls).map((c) => c.body)).toEqual([{ device_id: DEVICE, settings: { watchdog: { charge_limit_pct: 55 } } }]);
  });

  it('is read-only with an explanation while no phone is paired', async () => {
    useUi.setState({ deviceId: null });
    view = await render(<SettingsEditor />);
    expect(document.body.textContent).toMatch(/No phone is paired yet/);
    expect(overrideBox()?.disabled).toBe(true);
    expect(byText(document.body, 'button', 'Save')).toBeUndefined();
  });
});

describe('SettingsEditor: the profile override needs your password (F01)', () => {
  it('keeps the dialog and the edits on a wrong password', async () => {
    const calls = serverLike();
    view = await render(<SettingsEditor />);
    await typeInto(overrideBox(), JSON.stringify({ usb_function: 'rndis' }));
    await saveWithPassword('wrong');
    expect(puts(calls)).toHaveLength(1);
    expect(dialog()?.textContent).toMatch(/Wrong password/);
    expect(useSession.getState().token).toBe('sess_1');
    expect(useData.getState().settings[DEVICE]?.version).toBe(7);

    const label = byText<HTMLLabelElement>(dialog()!, 'label', 'Your password')!;
    await typeInto(document.getElementById(label.htmlFor), 'my-own-password');
    await click(byText(dialog()!, 'button', 'Confirm and save'));
    await flush();
    expect(dialog()).toBeNull();
    expect(useData.getState().settings[DEVICE]?.settings.profile_override).toEqual({ usb_function: 'rndis' });
  });

  it('cancelling sends nothing and keeps the unsaved edit', async () => {
    const calls = serverLike();
    view = await render(<SettingsEditor />);
    await typeInto(overrideBox(), JSON.stringify({ usb_function: 'rndis' }));
    await click(byText(document.body, 'button', 'Save'));
    await click(byText(dialog()!, 'button', 'Cancel'));
    expect(puts(calls)).toEqual([]);
    expect(document.body.textContent).toMatch(/1 unsaved change/);
  });

  it('asks for the password when the Server answers reauth_required to a save', async () => {
    let first = true;
    const calls = mockApi((c) => {
      if (c.method !== 'PUT') return undefined;
      if (first) {
        first = false;
        return { status: 401, body: { error: 'reauth_required', message: 'Password required' } };
      }
      return { status: 200, body: { device_id: DEVICE, settings: { ...stored, watchdog: { ...stored.watchdog, charge_limit_pct: 55 } }, version: 8 } };
    });
    view = await render(<SettingsEditor />);
    await typeInto(inputFor('Stop charging at'), '55');
    await click(byText(document.body, 'button', 'Save'));
    await flush();
    expect(dialog()).not.toBeNull();
    const label = byText<HTMLLabelElement>(dialog()!, 'label', 'Your password')!;
    await typeInto(document.getElementById(label.htmlFor), 'my-own-password');
    await click(byText(dialog()!, 'button', 'Confirm and save'));
    await flush();
    expect(puts(calls).map((c) => (c.body as { password?: string }).password)).toEqual([undefined, 'my-own-password']);
    expect(dialog()).toBeNull();
  });
});
