/**
 * Login session: bearer token in localStorage (no cookies, so a separately hosted Dashboard works
 * cross-origin), the signed-in user, and whether the user still has to enrol TOTP. Plan 10 asks
 * for password plus TOTP for every Dashboard login, so until then the app shows only enrollment.
 */
import type { UserInfo } from '@hsm/shared';
import { create } from 'zustand';
import { api, configureApi, isApiError } from '../lib/api';
import { unsubscribePushForSignOut } from '../lib/push';
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

/** Every role needs TOTP (plan 10); the Server's MeResponse.totp_enrollment_required has the final say. */
const needsEnrollment = (u: UserInfo | null | undefined) => Boolean(u && !u.totp_enabled);

/**
 * Ends the session on the Server in the background, with the token the app has already dropped:
 * first this browser's push subscription (so a signed-out or lost device stops showing alerts,
 * F04), then the session itself. Both best effort.
 */
async function endSessionOnServer(token: string, enrolled: boolean): Promise<void> {
  try {
    // A session without TOTP may not call /push/unsubscribe (it answers 403); the browser side still goes.
    await unsubscribePushForSignOut(enrolled ? token : null);
  } catch {
    /* best effort */
  }
  await api.logout(token).catch(() => undefined);
}

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
  totpEnrollmentRequired: Boolean(savedToken) && needsEnrollment(savedUser),
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
      totpEnrollmentRequired: needsEnrollment(user),
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
    if (callServer && token) void endSessionOnServer(token, !get().totpEnrollmentRequired);
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
  onTotpEnrollmentRequired: () => {
    if (useSession.getState().token) useSession.getState().setTotpEnrollmentRequired(true);
  },
});

export const selectIsAdmin = (s: SessionState) => s.user?.role === 'admin';
/** Admin who may actually send commands and change settings right now (enrolled in TOTP). */
export const selectCanControl = (s: SessionState) => s.user?.role === 'admin' && !s.totpEnrollmentRequired;
