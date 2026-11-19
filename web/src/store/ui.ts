import { create } from 'zustand';
import { KEYS, storage } from '../lib/storage';

export type Theme = 'dark' | 'light';

interface UiState {
  theme: Theme;
  /** Device shown in the Dashboard (multi-device ready; one phone today). */
  deviceId: string | null;
  setTheme: (t: Theme) => void;
  toggleTheme: () => void;
  setDeviceId: (id: string | null) => void;
}

const initialTheme: Theme = storage.get(KEYS.theme) === 'light' ? 'light' : 'dark';

export function applyTheme(t: Theme): void {
  if (typeof document === 'undefined') return;
  document.documentElement.setAttribute('data-theme', t);
  const meta = document.querySelector('meta[name="theme-color"]');
  meta?.setAttribute('content', t === 'light' ? '#f6f7f9' : '#0e1014');
}

export const useUi = create<UiState>()((set, get) => ({
  theme: initialTheme,
  deviceId: storage.get(KEYS.device),
  setTheme: (theme) => {
    storage.set(KEYS.theme, theme);
    applyTheme(theme);
    set({ theme });
  },
  toggleTheme: () => get().setTheme(get().theme === 'dark' ? 'light' : 'dark'),
  setDeviceId: (deviceId) => {
    storage.set(KEYS.device, deviceId);
    set({ deviceId });
  },
}));
