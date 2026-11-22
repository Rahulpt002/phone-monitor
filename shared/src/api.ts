/**
 * REST API (plan section 8.3). All endpoints are JSON over HTTPS and live under the `/api` prefix
 * (POST /api/auth/login, GET /api/device, ...) so the Server can also serve the Dashboard SPA from
 * the same origin. The paths in the comments below omit the prefix. Exceptions: GET /healthz (root,
 * for the hosting health check) and the WebSockets at /ws/agent and /ws/dashboard.
 * Except where noted, endpoints need
 * `Authorization: Bearer <session token>` from /auth/login (+ /auth/totp).
 * Errors: HTTP status + `{ error: ApiErrorCode, message: string }`.
 *
 * Multi-device ready: every device-scoped endpoint takes `device_id`; when omitted the Server
 * uses the first paired device (plan 8.4: one phone today, more later).
 */
import type { CommandName, CommandRecord } from './commands.js';
import type { AgentEvent } from './events.js';
import type { FinderSpot } from './finder.js';
import type { AlertRecord, DeviceSnapshot, DeviceSummary, Role, SmsRecord, UserInfo } from './protocol.js';
import type { Settings } from './settings.js';
import type { DeviceInfo } from './telemetry.js';
import type { ScreenQuality } from './screen.js';

export type ApiErrorCode =
  | 'bad_request'
  | 'unauthorized' // no / invalid / expired session
  | 'forbidden' // role does not allow it
  | 'totp_enrollment_required' // user without TOTP tried anything but enrollment
  | 'reauth_required' // password missing or wrong for a reauth command
  | 'confirmation_required' // `confirmed: true` missing for a confirm command
  | 'locked' // too many failed logins (HTTP 429)
  | 'rate_limited' // HTTP 429
  | 'not_found'
  | 'conflict'
  | 'device_offline' // informational; commands are still queued until expiry
  | 'internal';

export interface ApiError {
  error: ApiErrorCode;
  message: string;
}

// --- auth -------------------------------------------------------------------------------------
// POST /auth/login (public, rate-limited, lockout after 5 failures for 15 min)
//
// Lockout keys (docs/protocol.md 7): failures count per username and per IP. A browser that
// presents a valid `device_token` for that user (issued by its last full sign-in) counts its
// failures on that token instead of the username, so wrong passwords from strangers cannot lock a
// known browser out of signing in.
export interface LoginRequest {
  username: string;
  password: string;
  /** From the last SessionResponse of this browser for this user, if any. */
  device_token?: string;
}
export type LoginResponse =
  | { totp_required: false; token: string; user: UserInfo; expires_at: string; device_token?: string }
  | { totp_required: true; pending_token: string }; // pending_token valid 5 min

// POST /auth/totp (public)
export interface TotpLoginRequest {
  pending_token: string;
  code: string;
  /** Same as LoginRequest.device_token. */
  device_token?: string;
}
export interface SessionResponse {
  token: string;
  user: UserInfo;
  expires_at: string;
  /**
   * Known-device token, issued on every full sign-in (the one presented is replaced). The browser
   * keeps it per username and sends it with its next sign-in. Valid 180 days; dropped when the
   * user's password changes or all of the user's sessions are revoked.
   */
  device_token?: string;
}

// GET /auth/me
export interface MeResponse {
  user: UserInfo;
  /**
   * True for any user (admin or viewer) without TOTP (plan 10: password plus TOTP for every
   * Dashboard login). Until enrolled, the session may only call GET /auth/me, POST /auth/logout,
   * POST /auth/password, POST /auth/totp/setup, POST /auth/totp/enable and GET /config; everything
   * else (REST and the dashboard socket) answers 403 totp_enrollment_required.
   */
  totp_enrollment_required: boolean;
}

// POST /auth/logout -> 204
// POST /auth/totp/setup -> { secret, otpauth_url } (secret is base32)
export interface TotpSetupResponse {
  secret: string;
  otpauth_url: string;
}
// POST /auth/totp/enable { code } -> { ok: true }
// POST /auth/totp/disable { password, code } -> { ok: true }
// POST /auth/password { current, next } -> { ok: true }  (min 10 chars)

// --- users (admin) ----------------------------------------------------------------------------
// GET /users -> UserInfo[]
// POST /users CreateUserRequest -> 201 UserInfo
// PATCH /users/:id UpdateUserRequest -> UserInfo
//   reset_totp: true clears that user's authenticator, revokes their sessions and deletes their
//   push subscriptions (lost phone).
// DELETE /users/:id { reauth_password } -> 204 (cannot delete the last admin or yourself)
// Every write here needs `reauth_password` = the acting admin's own password (401 reauth_required).
// An admin cannot change their own password or reset their own TOTP here (400 bad_request):
// that goes through POST /auth/password and POST /auth/totp/disable, which need the current password.
export interface CreateUserRequest {
  username: string;
  password: string;
  role: Role;
  reauth_password: string;
}
export interface UpdateUserRequest {
  role?: Role;
  password?: string; // new password for that user (min 10 chars)
  reset_totp?: true;
  reauth_password: string;
}
export interface DeleteUserRequest {
  reauth_password: string;
}

// --- pairing ----------------------------------------------------------------------------------
// POST /pair/codes (admin) -> one-time pairing code valid 15 minutes
export interface PairCodeRequest {
  name?: string; // device display name
}
export interface PairCodeResponse {
  code: string; // 8 chars, A-Z2-9 without lookalikes
  expires_at: string;
  /** Opened by the phone's camera (QR) or by ADB; the Agent's PairActivity handles it. */
  deep_link: string; // hsm://pair?server=<urlencoded base url>&code=<code>
  /**
   * For a phone with a dead screen. It targets the activity-alias `.ui.AdbPair`, which is guarded
   * by android.permission.DUMP so only adb (shell uid) or root can start it; only that entry point
   * honours `--ez auto true` (pair without a tap) and `--ez replace true`. The browsable hsm://pair
   * link (QR code) always needs a tap on the phone. The inner command is quoted so the phone's
   * shell does not split the link at '&':
   * adb shell "am start -n com.hotspotmonitor.agent/.ui.AdbPair -a android.intent.action.VIEW -d '<deep_link>' --ez auto true"
   */
  adb_command: string;
}

// POST /pair (public, rate-limited) - called by the Agent
export interface PairRequest {
  code: string;
  device: DeviceInfo;
}
export interface PairResponse {
  device_id: string;
  device_token: string; // shown once; Server stores only its SHA-256
  ws_url: string; // wss://<host>/ws/agent
  /** Absolute base URL of the Server (no trailing slash), e.g. https://hsm.onrender.com */
  server_url: string;
}

// --- devices ----------------------------------------------------------------------------------
// GET /devices -> DeviceSummary[]
// GET /device?device_id= -> DeviceSnapshot
// PATCH /devices/:id { name } (admin) -> DeviceSummary
// DELETE /devices/:id (admin, reauth: { password }) -> 204  revokes the token
export type DevicesResponse = DeviceSummary[];
export type DeviceResponse = DeviceSnapshot;

// --- telemetry history ------------------------------------------------------------------------
// GET /telemetry?device_id=&metric=<MetricKey>&from=<ISO>&to=<ISO>&res=auto|raw|1m
// res=auto: raw if the range <= 6 h, otherwise 1m averages. Max 5000 points (downsampled).
export interface TelemetryHistoryResponse {
  metric: string;
  res: 'raw' | '1m';
  points: [number, number | null][]; // [unix ms, value]
}

// --- commands ---------------------------------------------------------------------------------
// POST /commands -> 201 CommandRecord
export interface SendCommandRequest {
  device_id?: string;
  name: CommandName;
  args?: Record<string, unknown>;
  confirmed?: boolean; // required when COMMANDS[name].confirm
  password?: string; // required when COMMANDS[name].reauth
}
// POST /commands/:id/keep -> CommandRecord (only for revert_armed + revert_confirm 'dashboard')
// GET /commands?device_id=&limit=50&before=<ISO> -> CommandRecord[] (newest first)
export type CommandsResponse = CommandRecord[];

// --- events -----------------------------------------------------------------------------------
// GET /events?device_id=&from=&to=&kind=&limit=100&before=<ISO> -> StoredEvent[] (newest first)
export interface StoredEvent extends AgentEvent {
  device_id: string;
}

// --- settings ---------------------------------------------------------------------------------
// GET /settings?device_id= -> SettingsResponse
// PUT /settings (admin) PutSettingsRequest -> SettingsResponse
//   deep-merged over the current settings (profile_override replaces whole), validated,
//   version += 1, pushed to the Agent, and recorded as a 'settings_changed' event (audit).
//   A change to profile_override (it holds root shell templates) needs `password`
//   (the admin's own; 401 reauth_required without it).
export interface PutSettingsRequest {
  device_id?: string;
  settings: Partial<Settings> | Record<string, unknown>;
  password?: string;
}
export interface SettingsResponse {
  device_id: string;
  settings: Settings;
  version: number;
}

// --- finder -----------------------------------------------------------------------------------
// GET /finder/spots?device_id= -> FinderSpotsResponse (ranked: speed first, score second)
export interface FinderSpotsResponse {
  spots: FinderSpot[];
  baseline_id: string | null;
}

// --- alerts and push --------------------------------------------------------------------------
// GET /alerts?device_id=&limit=100&before= -> AlertRecord[] (newest first)
// POST /alerts/test (admin) -> AlertRecord   sends a 'server_test' alert on every channel
// GET /push/vapid -> { public_key } (public key for PushManager.subscribe)
// POST /push/subscribe { subscription: PushSubscriptionJSON } -> 204
// POST /push/unsubscribe { endpoint } -> 204
// POST /push/fcm/register { token, platform? } -> 204   (native app; per signed-in session)
// POST /push/fcm/unregister { token } -> 204
export type AlertsResponse = AlertRecord[];

// --- messages ---------------------------------------------------------------------------------
// GET /sms?device_id=&limit=100 -> SmsRecord[] (newest first, admin only)
export type SmsResponse = SmsRecord[];

// --- misc -------------------------------------------------------------------------------------
// GET /healthz (public) -> { ok: true, version, uptime_s, db: 'ok' | 'error' }
// GET /config  (public) -> PublicConfig
export interface PublicConfig {
  version: string;
  telegram_configured: boolean;
  push_configured: boolean;
  /** Firebase Cloud Messaging for the native Android app (FIREBASE_SERVICE_ACCOUNT). */
  fcm_configured: boolean;
}

// --- remote screen (screen.ts, docs/protocol.md section 8) -------------------------------------
// POST /screen/sessions (admin, reauth: password + a fresh TOTP code) -> 201 ScreenSessionResponse
//   401 reauth_required (password missing or wrong), 400 bad_request (code wrong or already used),
//   409 device_offline (the Agent is not connected), 409 conflict (the phone cannot stream:
//   capability screen_mirror not supported). A new session for the same phone ends the old one.
// DELETE /screen/sessions/:id (admin, own session or any admin) -> 204
export interface CreateScreenSessionRequest {
  device_id: string;
  quality?: ScreenQuality; // default 'standard'
  password: string;
  code: string; // 6-digit TOTP code
}

export interface ScreenSessionResponse {
  session_id: string;
  device_id: string;
  quality: ScreenQuality;
  created_at: string;
  expires_at: string; // created_at + SCREEN.max_duration_s
  /** Path of the viewer socket; derive the ws(s) URL from the API base. */
  ws_path: '/ws/screen/view';
}
