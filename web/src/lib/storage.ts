/** localStorage that never throws (private mode, blocked storage, SSR/tests). */
export const storage = {
  get(key: string): string | null {
    try {
      return globalThis.localStorage?.getItem(key) ?? null;
    } catch {
      return null;
    }
  },
  set(key: string, value: string | null): void {
    try {
      if (value === null) globalThis.localStorage?.removeItem(key);
      else globalThis.localStorage?.setItem(key, value);
    } catch {
      /* storage unavailable: the value only lives for this page load */
    }
  },
  getJson<T>(key: string): T | null {
    const raw = storage.get(key);
    if (raw == null) return null;
    try {
      return JSON.parse(raw) as T;
    } catch {
      return null;
    }
  },
  setJson(key: string, value: unknown): void {
    storage.set(key, value === null || value === undefined ? null : JSON.stringify(value));
  },
};

export const KEYS = {
  token: 'hsm.token',
  user: 'hsm.user',
  theme: 'hsm.theme',
  device: 'hsm.device',
  shellHistory: 'hsm.shell_history',
  range: 'hsm.range',
  knownDevices: 'hsm.known_devices',
} as const;
