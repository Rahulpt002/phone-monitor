import type { DeviceSummary, UserInfo } from '@hsm/shared';
import { act, lazy } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MainContent } from '../src/components/layout/AppShell';
import { usableWithoutPhone } from '../src/components/layout/NoPhone';
import { NO_PHONE_POLL_MS, useDataLoader } from '../src/components/layout/useDataLoader';
import { configureApi } from '../src/lib/api';
import { useData } from '../src/store/data';
import { useSession } from '../src/store/session';
import { useUi } from '../src/store/ui';
import { flush, mockApi, render, type Rendered } from './dom';

const admin: UserInfo = { id: 1, username: 'admin', role: 'admin', totp_enabled: true, created_at: '2026-11-01T10:00:00Z' };
const viewer: UserInfo = { id: 2, username: 'guest', role: 'viewer', totp_enabled: false, created_at: '2026-11-01T10:00:00Z' };
const phone: DeviceSummary = { device_id: 'dev_1', name: 'Roof', online: true, last_seen: '2026-11-19T10:00:00Z', paired_at: '2026-11-19T09:00:00Z', info: null };

let view: Rendered | null = null;

beforeEach(() => {
  useSession.setState({ token: 'sess_1', user: admin, totpEnrollmentRequired: false, phase: 'ready' });
  useData.getState().reset();
  useUi.setState({ deviceId: null });
});

afterEach(() => {
  view?.unmount();
  view = null;
  document.body.innerHTML = '';
  vi.useRealTimers();
  useData.getState().reset();
  useUi.setState({ deviceId: null });
  configureApi({ fetchImpl: (...a) => globalThis.fetch(...a) });
});

const LazyScreen = lazy(async () => ({ default: () => <p>lazy screen</p> }));
const BrokenScreen = lazy(() => Promise.reject(new TypeError('Failed to fetch dynamically imported module: /assets/Signal-x.js')));

function shell(path: string, loaded = true) {
  return (
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<MainContent loaded={loaded} />}>
          <Route index element={<p>overview screen</p>} />
          <Route path="settings" element={<p>settings screen</p>} />
          <Route path="alerts" element={<p>alerts screen</p>} />
          <Route path="signal" element={<LazyScreen />} />
          <Route path="broken" element={<BrokenScreen />} />
        </Route>
      </Routes>
    </MemoryRouter>
  );
}

describe('no phone paired', () => {
  it('shows admins a Pair a phone action instead of the phone screens', async () => {
    view = await render(shell('/'));
    const text = document.body.textContent ?? '';
    expect(text).toMatch(/No phone is paired yet/);
    expect(text).not.toMatch(/overview screen/);
    const link = [...document.querySelectorAll('a')].find((a) => a.textContent === 'Pair a phone');
    expect(link?.getAttribute('href')).toBe('/settings?tab=pairing');
  });

  it('tells admins without 2FA to set it up first', async () => {
    useSession.setState({ user: { ...admin, totp_enabled: false }, totpEnrollmentRequired: true });
    view = await render(shell('/'));
    expect(document.body.textContent).toMatch(/Set up two-factor authentication first/);
  });

  it('explains the state to viewers, without a pairing action', async () => {
    useSession.setState({ user: viewer });
    view = await render(shell('/'));
    const text = document.body.textContent ?? '';
    expect(text).toMatch(/No phone is paired yet/);
    expect(text).toMatch(/An admin has to pair the hotspot phone/);
    expect([...document.querySelectorAll('a')].some((a) => a.textContent === 'Pair a phone')).toBe(false);
  });

  it('keeps Settings and Alerts usable', async () => {
    view = await render(shell('/settings'));
    expect(document.body.textContent).toMatch(/settings screen/);
    view.unmount();
    view = await render(shell('/alerts'));
    expect(document.body.textContent).toMatch(/alerts screen/);
    expect(usableWithoutPhone('/settings')).toBe(true);
    expect(usableWithoutPhone('/signal')).toBe(false);
    expect(usableWithoutPhone('/settingsx')).toBe(false);
  });

  it('shows the screens once a phone is paired, and a loader before the list arrives', async () => {
    view = await render(shell('/', false));
    expect(document.body.textContent).toMatch(/Connecting to the server/);
    view.unmount();
    useData.getState().setDevices([phone]);
    view = await render(shell('/'));
    expect(document.body.textContent).toMatch(/overview screen/);
  });
});

describe('lazy screens', () => {
  it('load inside the shell', async () => {
    useData.getState().setDevices([phone]);
    view = await render(shell('/signal'));
    await flush();
    expect(document.body.textContent).toMatch(/lazy screen/);
  });

  it('show a reload notice instead of a blank app when a chunk fails to load', async () => {
    // React (dev build) rethrows the caught error through a window error event; expected here.
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const swallow = (e: ErrorEvent) => e.preventDefault();
    window.addEventListener('error', swallow);
    try {
      useData.getState().setDevices([phone]);
      view = await render(shell('/broken'));
      await flush();
      expect(document.body.textContent).toMatch(/This screen could not be loaded/);
      expect([...document.querySelectorAll('button')].some((b) => b.textContent === 'Reload')).toBe(true);
    } finally {
      window.removeEventListener('error', swallow);
    }
  });
});

function Probe() {
  const l = useDataLoader();
  return <output>{`${l.devicesLoaded ? 'loaded' : 'loading'}|${l.error ?? 'ok'}`}</output>;
}
const probeText = () => document.querySelector('output')?.textContent;

describe('useDataLoader without a phone', () => {
  it('looks for a newly paired phone every 15 s and stops once one appears', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    let devices: DeviceSummary[] = [];
    const calls = mockApi((c) => (c.method === 'GET' && c.path === '/devices' ? { status: 200, body: devices } : undefined));
    const deviceListCalls = () => calls.filter((c) => c.path === '/devices').length;

    view = await render(<Probe />);
    await flush();
    expect(probeText()).toBe('loaded|ok');
    expect(deviceListCalls()).toBe(1);

    await act(async () => {
      vi.advanceTimersByTime(NO_PHONE_POLL_MS);
    });
    await flush();
    expect(deviceListCalls()).toBe(2);

    devices = [phone];
    await act(async () => {
      vi.advanceTimersByTime(NO_PHONE_POLL_MS);
    });
    await flush();
    expect(deviceListCalls()).toBe(3);
    expect(useUi.getState().deviceId).toBe('dev_1');

    await act(async () => {
      vi.advanceTimersByTime(NO_PHONE_POLL_MS * 3);
    });
    await flush();
    expect(deviceListCalls()).toBe(3);
  });

  it('does not keep an error from a stale stored phone once the list shows none is paired', async () => {
    useUi.setState({ deviceId: 'dev_gone' });
    // The device list answers last, after the per-phone requests for the stale id failed with 404.
    const calls = mockApi(async (c) => {
      if (c.method === 'GET' && c.path === '/devices') {
        await new Promise((r) => setTimeout(r, 30));
        return { status: 200, body: [] };
      }
      return { status: 404, body: { error: 'not_found', message: 'Device dev_gone not found' } };
    });
    view = await render(<Probe />);
    await act(async () => {
      await new Promise((r) => setTimeout(r, 80));
    });
    await flush();
    expect(calls.some((c) => c.path.startsWith('/device?device_id=dev_gone'))).toBe(true);
    expect(useUi.getState().deviceId).toBeNull();
    expect(probeText()).toBe('loaded|ok');
  });
});
