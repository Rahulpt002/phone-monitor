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
const inputFor = (label: string) => {
  const l = byText<HTMLLabelElement>(document.body, 'label', label);
  return l ? document.getElementById(l.htmlFor) : null;
};

/** Answers PUT /settings the way the Server does (shared mergeSettings). */
function serverLike(): FetchCall[] {
  let current = stored;
  let version = 7;
  return mockApi((c) => {
    if (c.method !== 'PUT' || c.path !== '/settings') return undefined;
    const body = c.body as { device_id: string; settings: unknown };
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

    await click(byText(document.body, 'button', 'Save'));
    await flush();
    const puts = calls.filter((c) => c.method === 'PUT');
    expect(puts).toEqual([{ method: 'PUT', path: '/settings', body: { device_id: DEVICE, settings: { profile_override: { thermal: { cpu: ['cpu-0'] } } } } }]);
    expect(useData.getState().settings[DEVICE]?.settings.profile_override).toEqual({ thermal: { cpu: ['cpu-0'] } });
    expect(useData.getState().settings[DEVICE]?.version).toBe(8);
    expect(document.body.textContent).toMatch(/No changes · version 8/);
  });

  it('sends a threshold change and an override edit together in one PUT, keeping the other override keys', async () => {
    const calls = serverLike();
    view = await render(<SettingsEditor />);
    await typeInto(overrideBox(), JSON.stringify({ usb_function: 'rndis', thermal: { cpu: ['cpu-0'], battery: ['battery'] } }));
    await typeInto(inputFor('Stop charging at'), '55');
    await click(byText(document.body, 'button', 'Save'));
    await flush();
    const puts = calls.filter((c) => c.method === 'PUT');
    expect(puts).toHaveLength(1);
    const sent = (puts[0]!.body as { settings: Partial<Settings> }).settings;
    expect(sent).toEqual({
      watchdog: { charge_limit_pct: 55 },
      profile_override: { usb_function: 'rndis', thermal: { cpu: ['cpu-0'], battery: ['battery'] } },
    });
  });

  it('clears the override with null when the box is emptied', async () => {
    const calls = serverLike();
    view = await render(<SettingsEditor />);
    await typeInto(overrideBox(), '');
    await click(byText(document.body, 'button', 'Save'));
    await flush();
    expect(calls.filter((c) => c.method === 'PUT').map((c) => c.body)).toEqual([{ device_id: DEVICE, settings: { profile_override: null } }]);
  });

  it('is read-only with an explanation while no phone is paired', async () => {
    useUi.setState({ deviceId: null });
    view = await render(<SettingsEditor />);
    expect(document.body.textContent).toMatch(/No phone is paired yet/);
    expect(overrideBox()?.disabled).toBe(true);
    expect(byText(document.body, 'button', 'Save')).toBeUndefined();
  });
});
