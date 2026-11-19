/**
 * Loads everything the screens share over REST: device list, snapshot, recent commands, events,
 * alerts, settings, spots and SMS. Runs again after a socket reconnect (epoch) and on device change.
 */
import { useEffect, useState } from 'react';
import { api, errorMessage, isAbort } from '../../lib/api';
import { syncPushSubscription } from '../../lib/push';
import { useConn } from '../../store/conn';
import { useData } from '../../store/data';
import { useSession } from '../../store/session';
import { useUi } from '../../store/ui';

export interface LoaderState {
  devicesLoaded: boolean;
  error: string | null;
  retry: () => void;
}

export function useDataLoader(): LoaderState {
  const token = useSession((s) => s.token);
  const isAdmin = useSession((s) => s.user?.role === 'admin');
  const epoch = useConn((s) => s.epoch);
  const deviceId = useUi((s) => s.deviceId);
  const [devicesLoaded, setDevicesLoaded] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    if (!token) return;
    const ctrl = new AbortController();
    api
      .devices(ctrl.signal)
      .then((list) => {
        const devices = Array.isArray(list) ? list : [];
        useData.getState().setDevices(devices);
        const cur = useUi.getState().deviceId;
        if (!cur || !devices.some((d) => d.device_id === cur)) useUi.getState().setDeviceId(devices[0]?.device_id ?? null);
        setDevicesLoaded(true);
        setError(null);
      })
      .catch((e: unknown) => {
        if (!isAbort(e)) setError(errorMessage(e));
      });
    return () => ctrl.abort();
  }, [token, epoch, tick]);

  useEffect(() => {
    if (!token || !deviceId) return;
    const ctrl = new AbortController();
    const { signal } = ctrl;
    const store = useData.getState();
    const jobs: Promise<unknown>[] = [
      api.device(deviceId, signal).then((s) => store.setSnapshot(s)),
      api.commands({ device_id: deviceId, limit: 100 }, signal).then((l) => store.mergeCommands(l)),
      api.events({ device_id: deviceId, limit: 100 }, signal).then((l) => store.mergeEvents(l)),
      api.settings(deviceId, signal).then((r) => store.setSettings(r.device_id ?? deviceId, r.settings, r.version)),
      api.spots(deviceId, signal).then((r) => store.setSpots(deviceId, r.spots ?? [], r.baseline_id ?? null)),
      api.alerts({ limit: 100 }, signal).then((l) => store.mergeAlerts(l)),
    ];
    if (isAdmin) jobs.push(api.sms({ device_id: deviceId, limit: 100 }, signal).then((l) => store.mergeSms(deviceId, l)));
    void Promise.allSettled(jobs).then((results) => {
      if (signal.aborted) return;
      const failed = results.filter((r): r is PromiseRejectedResult => r.status === 'rejected' && !isAbort(r.reason));
      setError(failed.length ? `Some data could not be loaded: ${errorMessage(failed[0]!.reason)}` : null);
    });
    return () => ctrl.abort();
  }, [token, deviceId, epoch, isAdmin, tick]);

  // Keep an existing push subscription registered (silent; never prompts).
  useEffect(() => {
    if (token) void syncPushSubscription();
  }, [token]);

  return { devicesLoaded, error, retry: () => setTick((t) => t + 1) };
}
