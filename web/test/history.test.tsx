import { act } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BACKLOG_REFETCH_MS, useHistory } from '../src/components/charts/useHistory';
import { configureApi } from '../src/lib/api';
import { emptySnapshot } from '../src/store/reducer';
import { useData } from '../src/store/data';
import { useUi } from '../src/store/ui';
import { flush, mockApi, render, type Rendered } from './dom';

const DEV = 'dev_1';
let view: Rendered | null = null;

function Probe() {
  const h = useHistory(['power.current_ma'], '1h');
  return <output>{(h.series['power.current_ma'] ?? []).map((p) => p[1]).join(',')}</output>;
}

beforeEach(() => {
  useData.getState().reset();
  useUi.setState({ deviceId: DEV });
  useData.getState().apply({ t: 'snapshot', device: { ...emptySnapshot(DEV), online: false, last_seen: '2026-11-19T09:00:00.000Z' } });
});

afterEach(() => {
  view?.unmount();
  view = null;
  document.body.innerHTML = '';
  vi.useRealTimers();
  useUi.setState({ deviceId: null });
  useData.getState().reset();
  configureApi({ fetchImpl: (...a) => globalThis.fetch(...a) });
});

const status = (online: boolean, at: string) =>
  act(async () => {
    useData.getState().apply({ t: 'status', device_id: DEV, online, last_seen: at, connected_since: online ? at : null });
  });

describe('chart history after an outage (F45)', () => {
  it('refetches once the phone is back online and its backlog had time to arrive', async () => {
    let value = -100;
    const calls = mockApi((c) => (c.path.startsWith('/telemetry') ? { status: 200, body: { metric: 'power.current_ma', res: 'raw', points: [[Date.now() - 60_000, value]] } } : undefined));
    const historyCalls = () => calls.filter((c) => c.path.startsWith('/telemetry')).length;
    view = await render(<Probe />);
    await flush();
    expect(historyCalls()).toBe(1);
    expect(document.querySelector('output')?.textContent).toBe('-100');

    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    value = -250; // the backlog the Server stored without relaying it live
    await status(true, '2026-11-19T10:00:00.000Z');
    expect(historyCalls()).toBe(1);
    await act(async () => {
      vi.advanceTimersByTime(BACKLOG_REFETCH_MS[0]!);
    });
    vi.useRealTimers();
    await flush();
    expect(historyCalls()).toBe(2);
    expect(document.querySelector('output')?.textContent).toBe('-250');
  });

  it('does not refetch for status messages that are not a return from offline', async () => {
    const calls = mockApi((c) => (c.path.startsWith('/telemetry') ? { status: 200, body: { metric: 'power.current_ma', res: 'raw', points: [] } } : undefined));
    useData.getState().apply({ t: 'status', device_id: DEV, online: true, last_seen: '2026-11-19T09:30:00.000Z', connected_since: '2026-11-19T09:30:00.000Z' });
    view = await render(<Probe />);
    await flush();
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    await status(true, '2026-11-19T10:00:00.000Z');
    await status(false, '2026-11-19T10:05:00.000Z');
    await act(async () => {
      vi.advanceTimersByTime(Math.max(...BACKLOG_REFETCH_MS) + 1000);
    });
    vi.useRealTimers();
    await flush();
    expect(calls.filter((c) => c.path.startsWith('/telemetry'))).toHaveLength(1);
  });
});
