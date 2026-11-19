/**
 * Login session: bearer token in localStorage (no cookies, so a separately hosted Dashboard works
 * cross-origin), the signed-in user, and whether an admin still has to enrol TOTP.
 */
import type { UserInfo } from '@hsm/shared';
import { create } from 'zustand';
import { api, configureApi, isApiError } from '../lib/api';
import { KEYS, storage } from '../lib/storage';

export type SessionPhase = 'anonymous' | 'ready';

interface SessionState {
  token: string | null;
  user: UserInfo | null;
  totpEnrollmentRequired: boolean;
  phase: SessionPhase;
  /** Shown on the login screen after an involuntary logout. */
  notice: string | null;
  setSession: (token: string, user: UserInfo) => Promise<void>;
  refreshMe: () => Promise<void>;
  logout: (notice?: string | null, callServer?: boolean) => void;
  setTotpEnrollmentRequired: (v: boolean) => void;
}

const savedToken = storage.get(KEYS.token);
const savedUser = storage.getJson<UserInfo>(KEYS.user);

type LogoutListener = () => void;
const logoutListeners = new Set<LogoutListener>();
/** Lets the socket and data stores reset themselves without a circular import. */
export function onLogout(fn: LogoutListener): () => void {
  logoutListeners.add(fn);
  return () => logoutListeners.delete(fn);
}

export const useSession = create<SessionState>()((set, get) => ({
  token: savedToken,
  user: savedToken ? savedUser : null,
  totpEnrollmentRequired: Boolean(savedUser && savedUser.role === 'admin' && !savedUser.totp_enabled),
  phase: savedToken && savedUser ? 'ready' : 'anonymous',
  notice: null,

  setSession: async (token, user) => {
    storage.set(KEYS.token, token);
    storage.setJson(KEYS.user, user);
    set({
      token,
      user,
      phase: 'ready',
      notice: null,
      totpEnrollmentRequired: user.role === 'admin' && !user.totp_enabled,
    });
    await get().refreshMe();
  },

  refreshMe: async () => {
    if (!get().token) return;
    try {
      const me = await api.me();
      storage.setJson(KEYS.user, me.user);
      set({ user: me.user, totpEnrollmentRequired: me.totp_enrollment_required, phase: 'ready' });
    } catch (e) {
      // 401 already triggered logout through the API client; network errors keep the cached user
      // so the installed app still opens while the server is unreachable or waking up.
      if (isApiError(e, 'unauthorized')) return;
    }
  },

  logout: (notice = null, callServer = true) => {
    const token = get().token;
    if (callServer && token) void api.logout().catch(() => undefined);
    storage.set(KEYS.token, null);
    storage.set(KEYS.user, null);
    set({ token: null, user: null, phase: 'anonymous', notice, totpEnrollmentRequired: false });
    for (const fn of logoutListeners) fn();
  },

  setTotpEnrollmentRequired: (v) => set({ totpEnrollmentRequired: v }),
}));

configureApi({
  getToken: () => useSession.getState().token,
  onUnauthorized: () => {
    if (useSession.getState().token) useSession.getState().logout('Your session has expired. Please sign in again.', false);
  },
});

export const selectIsAdmin = (s: SessionState) => s.user?.role === 'admin';
/** Admin who may actually send commands and change settings right now. */
export const selectCanControl = (s: SessionState) => s.user?.role === 'admin' && !s.totpEnrollmentRequired;
