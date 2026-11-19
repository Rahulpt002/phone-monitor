/**
 * Loads everything the screens share over REST: device list, snapshot, recent commands, events,
 * alerts, settings, spots and SMS. Runs again after a socket reconnect (epoch) and on device change.
 */
import { useEffect, useState } from 'react';
import { api, errorMessage, isAbort } from '../../lib/api';
import { useDocumentVisible } from '../../lib/hooks';
import { syncPushSubscription } from '../../lib/push';
import { useConn } from '../../store/conn';
import { useData } from '../../store/data';
import { useSession } from '../../store/session';
import { useUi } from '../../store/ui';

/** How often to look for a newly paired phone while none is paired. */
export const NO_PHONE_POLL_MS = 15_000;

export interface LoaderState {
  devicesLoaded: boolean;
  error: string | null;
  retry: () => void;
}

export function useDataLoader(): LoaderState {
  // Nothing loads for a session that still has to enrol TOTP (the Server would answer 403).
  const token = useSession((s) => (s.totpEnrollmentRequired ? null : s.token));
  const isAdmin = useSession((s) => s.user?.role === 'admin');
  const epoch = useConn((s) => s.epoch);
  const deviceId = useUi((s) => s.deviceId);
  const noPhone = useData((s) => s.devices.length === 0);
  const visible = useDocumentVisible();
  const [devicesLoaded, setDevicesLoaded] = useState(false);
  const [devicesError, setDevicesError] = useState<string | null>(null);
  const [dataError, setDataError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  const [devicesTick, setDevicesTick] = useState(0);

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
        setDevicesError(null);
      })
      .catch((e: unknown) => {
        if (!isAbort(e)) setDevicesError(errorMessage(e));
      });
    return () => ctrl.abort();
  }, [token, epoch, tick, devicesTick]);

  // The socket only reports phones the Dashboard already knows, so look for a new one now and then.
  useEffect(() => {
    if (!token || !devicesLoaded || !noPhone || !visible) return;
    const id = setInterval(() => setDevicesTick((t) => t + 1), NO_PHONE_POLL_MS);
    return () => clearInterval(id);
  }, [token, devicesLoaded, noPhone, visible]);

  useEffect(() => {
    // A previous run may have failed for a device that is gone (unpaired, or a stale stored id).
    setDataError(null);
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
      setDataError(failed.length ? `Some data could not be loaded: ${errorMessage(failed[0]!.reason)}` : null);
    });
    return () => ctrl.abort();
  }, [token, deviceId, epoch, isAdmin, tick]);

  // Keep an existing push subscription registered (silent; never prompts).
  useEffect(() => {
    if (token) void syncPushSubscription();
  }, [token]);

  return { devicesLoaded, error: devicesError ?? dataError, retry: () => setTick((t) => t + 1) };
}
