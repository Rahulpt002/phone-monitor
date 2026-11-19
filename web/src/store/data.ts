import type {
  AlertRecord,
  CommandRecord,
  DeviceSnapshot,
  DeviceSummary,
  FinderSpot,
  Settings,
  SmsRecord,
  StoredEvent,
} from '@hsm/shared';
import { create } from 'zustand';
import * as R from './reducer';

interface DataActions {
  apply: (msg: unknown) => void;
  setDevices: (devices: DeviceSummary[]) => void;
  setSnapshot: (snap: DeviceSnapshot) => void;
  mergeCommands: (list: CommandRecord[]) => void;
  mergeEvents: (list: StoredEvent[]) => void;
  mergeAlerts: (list: AlertRecord[]) => void;
  mergeSms: (deviceId: string, list: SmsRecord[]) => void;
  setSpots: (deviceId: string, spots: FinderSpot[], baselineId: string | null) => void;
  setSettings: (deviceId: string, settings: Settings, version: number) => void;
  reset: () => void;
}

export type DataStore = R.DataState & DataActions;

export const useData = create<DataStore>()((set) => ({
  ...R.initialData,
  apply: (msg) => set((s) => R.applyServerMessage(s, msg)),
  setDevices: (devices) => set((s) => R.setDevices(s, devices)),
  setSnapshot: (snap) => set((s) => R.setSnapshot(s, snap)),
  mergeCommands: (list) => set((s) => R.mergeCommands(s, list)),
  mergeEvents: (list) => set((s) => R.mergeEvents(s, list)),
  mergeAlerts: (list) => set((s) => R.mergeAlerts(s, list)),
  mergeSms: (deviceId, list) => set((s) => R.mergeSms(s, deviceId, list)),
  setSpots: (deviceId, spots, baselineId) => set((s) => R.setSpots(s, deviceId, spots, baselineId)),
  setSettings: (deviceId, settings, version) => set((s) => R.applySettings(s, deviceId, settings, version)),
  reset: () => set({ ...R.initialData }),
}));
