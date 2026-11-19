import type { StoredEvent } from '@hsm/shared';
import { afterEach, describe, expect, it } from 'vitest';
import { EventList } from '../src/components/live';
import { settingsChangeSummary } from '../src/lib/events';
import { byText, click, render, type Rendered } from './dom';

let view: Rendered | null = null;
afterEach(() => {
  view?.unmount();
  view = null;
  document.body.innerHTML = '';
});

const changed: StoredEvent = {
  id: 'e_s1',
  device_id: 'dev_1',
  ts: '2026-11-19T10:00:00.000Z',
  kind: 'settings_changed',
  severity: 'warning',
  message: 'Settings changed by admin (version 8)',
  data: {
    user: 'admin',
    version: 8,
    changed: ['watchdog.charge_limit_pct', 'alerts.enabled.offline', 'profile_override', 'something.new'],
    profile_override_changed: true,
  },
};

describe("'settings_changed' audit events", () => {
  it('summarise who changed what, with the editor’s field names', () => {
    expect(settingsChangeSummary(changed.data)).toEqual({
      user: 'admin',
      version: 8,
      fields: ['Stop charging at', 'Phone offline alert', 'Device profile override', 'something.new'],
      profileOverride: true,
    });
    expect(settingsChangeSummary({})).toEqual({ user: null, version: null, fields: [], profileOverride: false });
    expect(settingsChangeSummary({ changed: 'nope', profile_override_changed: 'yes' })).toEqual({ user: null, version: null, fields: [], profileOverride: false });
  });

  it('render readably in the event lists', async () => {
    view = await render(<EventList events={[changed, { ...changed, id: 'e_s2', data: { user: 'ravi', version: 9, changed: ['plan.quota_gb'], profile_override_changed: false } }]} />);
    const rows = [...document.querySelectorAll('li')];
    expect(rows[0]!.textContent).toMatch(/Settings changed/);
    expect(rows[0]!.textContent).toMatch(/by admin · version 8/);
    expect(rows[0]!.textContent).toMatch(/Stop charging at, Phone offline alert, Device profile override, something\.new/);
    expect(rows[0]!.textContent).toMatch(/Root command templates changed/);
    expect(rows[1]!.textContent).toMatch(/by ravi · version 9 · Quota per billing cycle/);
    expect(rows[1]!.textContent).not.toMatch(/Root command templates/);
    // The raw data stays available.
    await click(byText(rows[0]!, 'button', 'Details'));
    expect(document.querySelector('pre')?.textContent).toMatch(/profile_override_changed/);
  });
});
