/**
 * REST client for the Server (shared/src/api.ts). Every path below is relative to `/api`.
 * Errors always surface as ApiRequestError with a stable `code` the UI can switch on.
 */
import type {
  AlertRecord,
  AlertsResponse,
  ApiErrorCode,
  CommandRecord,
  CommandsResponse,
  CreateUserRequest,
  DeleteUserRequest,
  DeviceSnapshot,
  DeviceSummary,
  EventKind,
  FinderSpotsResponse,
  LoginRequest,
  LoginResponse,
  MeResponse,
  PairCodeResponse,
  PublicConfig,
  PutSettingsRequest,
  SendCommandRequest,
  SessionResponse,
  SettingsResponse,
  SmsResponse,
  StoredEvent,
  TelemetryHistoryResponse,
  TotpSetupResponse,
  UpdateUserRequest,
  UserInfo,
} from '@hsm/shared';
import { API_BASE } from './config';

export type ClientErrorCode = ApiErrorCode | 'network' | 'timeout' | 'invalid_response';

export class ApiRequestError extends Error {
  readonly status: number;
  readonly code: ClientErrorCode;
  constructor(status: number, code: ClientErrorCode, message: string) {
    super(message);
    this.name = 'ApiRequestError';
    this.status = status;
    this.code = code;
  }
}

export function isApiError(e: unknown, code?: ClientErrorCode): e is ApiRequestError {
  return e instanceof ApiRequestError && (code === undefined || e.code === code);
}

/** Human-readable message for any thrown value. */
export function errorMessage(e: unknown): string {
  if (e instanceof ApiRequestError) return e.message;
  if (e instanceof Error) return e.message;
  return String(e);
}

export function isAbort(e: unknown): boolean {
  return e instanceof DOMException ? e.name === 'AbortError' : (e as { name?: string })?.name === 'AbortError';
}

interface ApiConfig {
  base: string;
  getToken: () => string | null;
  onUnauthorized: () => void;
  /** The session has no TOTP yet: the Server refuses everything but enrollment (plan 10). */
  onTotpEnrollmentRequired: () => void;
  fetchImpl: typeof fetch;
  /** Render's free tier can take close to a minute to wake up, so the default is generous. */
  timeoutMs: number;
}

const config: ApiConfig = {
  base: API_BASE,
  getToken: () => null,
  onUnauthorized: () => {},
  onTotpEnrollmentRequired: () => {},
  fetchImpl: (...args) => globalThis.fetch(...args),
  timeoutMs: 70_000,
};

export function configureApi(partial: Partial<ApiConfig>): void {
  Object.assign(config, partial);
}

type QueryValue = string | number | boolean | null | undefined;

export interface RequestOptions {
  body?: unknown;
  query?: Record<string, QueryValue>;
  signal?: AbortSignal;
  /** Send the session token (default true). Public endpoints pass false. */
  auth?: boolean;
  /** Use this session token instead of the current one (sign-out finishes after the app dropped it). */
  token?: string;
  timeoutMs?: number;
}

const STATUS_CODES: Record<number, ApiErrorCode> = {
  400: 'bad_request',
  401: 'unauthorized',
  403: 'forbidden',
  404: 'not_found',
  409: 'conflict',
  429: 'rate_limited',
};

const DEFAULT_MESSAGES: Partial<Record<ClientErrorCode, string>> = {
  network: 'Cannot reach the server. Check your connection.',
  timeout: 'The server took too long to answer. It may be waking up; try again.',
  unauthorized: 'Your session has expired. Please sign in again.',
  forbidden: 'Your account is not allowed to do this.',
  totp_enrollment_required: 'Set up two-factor authentication before making changes.',
  reauth_required: 'Enter your password to confirm.',
  confirmation_required: 'This action needs confirmation.',
  locked: 'Too many failed attempts. Try again later.',
  rate_limited: 'Too many requests. Wait a moment and try again.',
  not_found: 'Not found.',
  internal: 'The server had a problem. Try again.',
};

export function buildUrl(base: string, path: string, query?: Record<string, QueryValue>): string {
  let url = `${base}/api${path}`;
  if (query) {
    const qs = new URLSearchParams();
    for (const [k, v] of Object.entries(query)) if (v !== undefined && v !== null && v !== '') qs.set(k, String(v));
    const s = qs.toString();
    if (s) url += (url.includes('?') ? '&' : '?') + s;
  }
  return url;
}

export async function request<T>(method: string, path: string, opts: RequestOptions = {}): Promise<T> {
  const useAuth = opts.auth !== false;
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
  const token = useAuth ? (opts.token ?? config.getToken()) : null;
  if (token) headers.Authorization = `Bearer ${token}`;

  const controller = new AbortController();
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, opts.timeoutMs ?? config.timeoutMs);
  const onAbort = () => controller.abort();
  if (opts.signal) {
    if (opts.signal.aborted) controller.abort();
    else opts.signal.addEventListener('abort', onAbort, { once: true });
  }

  let res: Response;
  try {
    res = await config.fetchImpl(buildUrl(config.base, path, opts.query), {
      method,
      headers,
      body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
      signal: controller.signal,
      credentials: 'omit',
    });
  } catch (e) {
    if (timedOut) throw new ApiRequestError(0, 'timeout', DEFAULT_MESSAGES.timeout!);
    if (isAbort(e)) throw e;
    throw new ApiRequestError(0, 'network', DEFAULT_MESSAGES.network!);
  } finally {
    clearTimeout(timer);
    opts.signal?.removeEventListener('abort', onAbort);
  }

  const text = res.status === 204 ? '' : await res.text().catch(() => '');
  let data: unknown = undefined;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = undefined;
    }
  }

  if (!res.ok) {
    const body = (data && typeof data === 'object' ? data : {}) as { error?: unknown; message?: unknown };
    const code: ClientErrorCode =
      typeof body.error === 'string'
        ? (body.error as ApiErrorCode)
        : (STATUS_CODES[res.status] ?? (res.status >= 500 ? 'internal' : 'bad_request'));
    const message =
      typeof body.message === 'string' && body.message
        ? body.message
        : (DEFAULT_MESSAGES[code] ?? `Request failed (HTTP ${res.status}).`);
    // Only answers about the current session change it (not a sign-out finishing in the background).
    const current = useAuth && token && opts.token === undefined;
    // A wrong password on a re-auth action is also a 401, but it must not end the session.
    if (current && res.status === 401 && code === 'unauthorized') config.onUnauthorized();
    if (current && res.status === 403 && code === 'totp_enrollment_required') config.onTotpEnrollmentRequired();
    throw new ApiRequestError(res.status, code, message);
  }

  if (!text) return undefined as T;
  if (data === undefined) throw new ApiRequestError(res.status, 'invalid_response', 'The server sent an unreadable response.');
  return data as T;
}

const get = <T>(path: string, query?: Record<string, QueryValue>, signal?: AbortSignal) =>
  request<T>('GET', path, { query, signal });
const send = <T>(method: 'POST' | 'PUT' | 'PATCH' | 'DELETE', path: string, body?: unknown) =>
  request<T>(method, path, { body });

export interface HistoryQuery {
  device_id?: string | null;
  metric: string;
  from: string;
  to: string;
  res?: 'auto' | 'raw' | '1m';
}

export const api = {
  // auth
  login: (body: LoginRequest) => request<LoginResponse>('POST', '/auth/login', { body, auth: false }),
  totpLogin: (pending_token: string, code: string, device_token?: string) =>
    request<SessionResponse>('POST', '/auth/totp', { body: { pending_token, code, device_token }, auth: false }),
  me: (signal?: AbortSignal) => get<MeResponse>('/auth/me', undefined, signal),
  /** `token`: the session to end (sign-out drops it from the app before this call finishes). */
  logout: (token?: string) => request<void>('POST', '/auth/logout', { timeoutMs: 5000, token }),
  totpSetup: () => send<TotpSetupResponse>('POST', '/auth/totp/setup'),
  totpEnable: (code: string) => send<{ ok: true }>('POST', '/auth/totp/enable', { code }),
  totpDisable: (password: string, code: string) => send<{ ok: true }>('POST', '/auth/totp/disable', { password, code }),
  changePassword: (current: string, next: string) => send<{ ok: true }>('POST', '/auth/password', { current, next }),

  // users
  users: () => get<UserInfo[]>('/users'),
  // Every user change carries `reauth_password`, the acting admin's own password (401 reauth_required).
  createUser: (body: CreateUserRequest) => send<UserInfo>('POST', '/users', body),
  /** `reset_totp: true` clears that user's authenticator, revokes their sessions and push subscriptions (lost phone). */
  updateUser: (id: number, patch: UpdateUserRequest) => send<UserInfo>('PATCH', `/users/${id}`, patch),
  deleteUser: (id: number, body: DeleteUserRequest) => send<void>('DELETE', `/users/${id}`, body),

  // pairing and devices
  createPairCode: (name?: string) => send<PairCodeResponse>('POST', '/pair/codes', name ? { name } : {}),
  devices: (signal?: AbortSignal) => get<DeviceSummary[]>('/devices', undefined, signal),
  device: (device_id: string, signal?: AbortSignal) => get<DeviceSnapshot>('/device', { device_id }, signal),
  renameDevice: (id: string, name: string) => send<DeviceSummary>('PATCH', `/devices/${encodeURIComponent(id)}`, { name }),
  unpairDevice: (id: string, password: string) => send<void>('DELETE', `/devices/${encodeURIComponent(id)}`, { password }),

  // telemetry history
  telemetry: (q: HistoryQuery, signal?: AbortSignal) =>
    get<TelemetryHistoryResponse>('/telemetry', { ...q, res: q.res ?? 'auto' }, signal),

  // commands
  sendCommand: (body: SendCommandRequest) => send<CommandRecord>('POST', '/commands', body),
  keepCommand: (id: string) => send<CommandRecord>('POST', `/commands/${encodeURIComponent(id)}/keep`),
  commands: (q: { device_id?: string | null; limit?: number; before?: string }, signal?: AbortSignal) =>
    get<CommandsResponse>('/commands', q, signal),

  // events
  events: (
    q: { device_id?: string | null; kind?: EventKind | ''; limit?: number; before?: string; from?: string; to?: string },
    signal?: AbortSignal,
  ) => get<StoredEvent[]>('/events', q, signal),

  // settings
  settings: (device_id: string, signal?: AbortSignal) => get<SettingsResponse>('/settings', { device_id }, signal),
  /** A change to profile_override needs `password` (the admin's own; 401 reauth_required). */
  putSettings: (body: PutSettingsRequest) => send<SettingsResponse>('PUT', '/settings', body),

  // finder
  spots: (device_id: string, signal?: AbortSignal) => get<FinderSpotsResponse>('/finder/spots', { device_id }, signal),

  // alerts and push
  alerts: (q: { device_id?: string | null; limit?: number; before?: string }, signal?: AbortSignal) =>
    get<AlertsResponse>('/alerts', q, signal),
  testAlert: () => send<AlertRecord>('POST', '/alerts/test'),
  vapid: () => get<{ public_key: string }>('/push/vapid'),
  pushSubscribe: (subscription: PushSubscriptionJSON) => send<void>('POST', '/push/subscribe', { subscription }),
  pushUnsubscribe: (endpoint: string, token?: string) =>
    request<void>('POST', '/push/unsubscribe', { body: { endpoint }, token, timeoutMs: token ? 5000 : undefined }),

  // messages
  sms: (q: { device_id?: string | null; limit?: number }, signal?: AbortSignal) => get<SmsResponse>('/sms', q, signal),

  // misc
  config: () => request<PublicConfig>('GET', '/config', { auth: false }),
};
