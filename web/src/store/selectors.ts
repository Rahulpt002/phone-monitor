import { DEFAULT_SETTINGS, type CommandRecord, type DeviceSnapshot, type FinderSpot, type Settings, type StoredEvent, type TelemetryGroups } from '@hsm/shared';
import { useMemo } from 'react';
import { useShallow } from 'zustand/react/shallow';
import { useData } from './data';
import { lastCommandByName, pendingReverts, type DeviceSms } from './reducer';
import { useSession } from './session';
import { useUi } from './ui';

export const useDeviceId = () => useUi((s) => s.deviceId);

export function useSnapshot(): DeviceSnapshot | undefined {
  const id = useDeviceId();
  return useData((s) => (id ? s.snapshots[id] : undefined));
}

export function useGroup<K extends keyof TelemetryGroups>(key: K): { value: TelemetryGroups[K] | undefined; ts: string | undefined } {
  const snap = useSnapshot();
  return { value: snap?.state[key], ts: snap?.state_ts[key] };
}

/** Device settings (falls back to the shipped defaults until the first GET /settings arrives). */
export function useDeviceSettings(): { settings: Settings; version: number | null; loaded: boolean } {
  const id = useDeviceId();
  const entry = useData((s) => (id ? s.settings[id] : undefined));
  return useMemo(
    () => (entry ? { settings: entry.settings, version: entry.version, loaded: true } : { settings: DEFAULT_SETTINGS, version: null, loaded: false }),
    [entry],
  );
}

export function useDeviceEvents(): StoredEvent[] {
  const id = useDeviceId();
  const events = useData((s) => s.events);
  return useMemo(() => (id ? events.filter((e) => e.device_id === id) : events), [events, id]);
}

export function useDeviceCommands(): CommandRecord[] {
  const id = useDeviceId();
  const commands = useData((s) => s.commands);
  return useMemo(() => (id ? commands.filter((c) => c.device_id === id) : commands), [commands, id]);
}

export function useLastCommands(): Map<string, CommandRecord> {
  const id = useDeviceId();
  const commands = useData((s) => s.commands);
  return useMemo(() => lastCommandByName(commands, id), [commands, id]);
}

export function useCommand(id: string | null | undefined): CommandRecord | undefined {
  return useData((s) => (id ? s.commands.find((c) => c.id === id) : undefined));
}

export function usePendingReverts(): CommandRecord[] {
  const id = useDeviceId();
  const [snapshots, commands] = useData(useShallow((s) => [s.snapshots, s.commands] as const));
  return useMemo(() => pendingReverts({ snapshots, commands } as Parameters<typeof pendingReverts>[0], id), [snapshots, commands, id]);
}

export function useSpots(): FinderSpot[] {
  const id = useDeviceId();
  const spots = useData((s) => (id ? s.spots[id] : undefined));
  return spots ?? EMPTY_SPOTS;
}
const EMPTY_SPOTS: FinderSpot[] = [];

export function useDeviceSms(): DeviceSms[] {
  const id = useDeviceId();
  const sms = useData((s) => s.sms);
  return useMemo(() => (id ? sms.filter((m) => m.device_id === id) : sms), [sms, id]);
}

export const useIsAdmin = () => useSession((s) => s.user?.role === 'admin');
export const useCanControl = () => useSession((s) => s.user?.role === 'admin' && !s.totpEnrollmentRequired);
