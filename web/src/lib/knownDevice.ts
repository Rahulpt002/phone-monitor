/**
 * Known-device tokens (shared/src/api.ts LoginRequest.device_token, F39). The Server issues one
 * on every full sign-in; sending it with the next sign-in of the same user means failed attempts
 * by strangers, which lock the username, do not lock this browser out. It is not a credential:
 * the password (and code) are still needed. Kept per username and survives sign-out.
 */
import { KEYS, storage } from './storage';

const MAX_USERS = 10;

type Stored = Record<string, string>;

const keyOf = (username: string) => username.trim().toLowerCase();

function load(): Stored {
  const v = storage.getJson<unknown>(KEYS.knownDevices);
  if (!v || typeof v !== 'object' || Array.isArray(v)) return {};
  const out: Stored = {};
  for (const [k, t] of Object.entries(v as Record<string, unknown>)) if (typeof t === 'string' && t) out[k] = t;
  return out;
}

export function deviceTokenFor(username: string): string | undefined {
  return load()[keyOf(username)];
}

/** Stores the token a sign-in returned (the previous one is replaced; none returned keeps it). */
export function rememberDeviceToken(username: string, token: string | undefined): void {
  if (!token) return;
  const key = keyOf(username);
  const rest = Object.entries(load()).filter(([k]) => k !== key);
  // Newest last; drop the oldest users beyond the cap.
  const next = Object.fromEntries([...rest, [key, token]].slice(-MAX_USERS));
  storage.setJson(KEYS.knownDevices, next);
}
