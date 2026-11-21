# Protocol: Agent ↔ Server ↔ Dashboard

The TypeScript types in [`shared/src/`](../shared/src) are the source of truth. This page explains the rules that types cannot express. Every message type has an example in [`shared/fixtures/`](../shared/fixtures). The Kotlin Agent's unit tests parse those files, so the two languages cannot drift apart silently.

| File | What it defines |
|------|-----------------|
| `telemetry.ts` | Telemetry groups (plan 6.2), tiers, capability map |
| `commands.ts` | Command names, arguments, confirm/re-auth/revert rules (plan 6.3, 6.4), command record and status |
| `settings.ts` | Every editable threshold with its default (plan 6.4, 6.5, 7.2, 8.2, 8.5) |
| `events.ts` | Event kinds, and which events raise alerts |
| `finder.ts` | Signal score formula, grades, Finder live/spot types, ranking (plan 7) |
| `protocol.ts` | WebSocket messages for both sockets |
| `api.ts` | REST endpoints, request and response bodies, error codes (plan 8.3) |
| `metrics.ts` | Chart metrics and their JSON paths |
| `screen.ts` | Remote screen: session types, relay messages, binary video frames, quality presets |

## 1. General rules

- REST endpoints live under `/api` (for example `POST /api/auth/login`). The paths in `api.ts` comments omit that prefix. Exceptions are `GET /healthz` and the two WebSockets, `/ws/agent` and `/ws/dashboard`.
- **Sign-in lockout:** 5 failed sign-ins (password or TOTP step) within 15 min lock the username and the IP for 15 min (429 `locked`); attempts count as they start, so a concurrent burst gets at most 5 checks. Every full sign-in returns a `device_token` (`SessionResponse`, valid 180 days, replaced on each sign-in, dropped when the user's password changes or their sessions are revoked). A browser that sends a valid one for that user (`LoginRequest` / `TotpLoginRequest.device_token`) counts its failures on that token and its IP instead of the username, so wrong passwords from strangers cannot lock a known browser out; a stolen token still gets only 5 guesses per 15 min. It is not a credential: password and code are still required.

- One JSON object per WebSocket text frame, with a `t` field naming the type.
- Receivers **ignore unknown `t` values and unknown fields**.
- Keys are `snake_case`. Timestamps are ISO-8601 UTC with milliseconds (`2026-11-19T10:15:00.000Z`).
- Values the phone cannot read are omitted or `null`, never invented. **A missing field and `null` mean the same thing** for every receiver: the Kotlin Agent omits nulls (`explicitNulls = false`), including fields the TypeScript types mark as required `T | null` (for example `CmdAckMsg.reason`, `CmdResultMsg.error`, `FinderLive.score`, speed values).
- Kotlin: `Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true; classDiscriminator = "t" }`, one `@Serializable @SerialName("<t>")` class per message. Telemetry groups are flattened at the top level of the `telemetry` message (`{"t":"telemetry","seq":1,"ts":"...","tier":"fast","radio":{...},"net":{...}}`).
- Max frame size 512 KiB. Command output is truncated to 64 KiB.

## 2. Pairing (plan section 10)

1. An admin presses **Pair phone** in the Dashboard → `POST /api/pair/codes` → an 8-character one-time code (valid 15 min) plus:
   - `deep_link`: `hsm://pair?server=<url-encoded https base URL>&code=<code>`, shown as a QR code. Any camera app that reads QR codes opens it in the Agent.
   - `adb_command`: `adb shell "am start -n com.hotspotmonitor.agent/.ui.AdbPair -a android.intent.action.VIEW -d '<deep_link>' --ez auto true"` for a phone with a dead screen. The inner command is quoted so the phone's shell does not split the link at `&`. `.ui.AdbPair` is an activity-alias guarded by `android.permission.DUMP`, so only adb (shell uid) or root can start it; only this entry point honours `--ez auto true` (pair without a tap) and `--ez replace true` (replace an existing pairing). The browsable `hsm://pair` link (QR code, other apps, web pages) ignores both extras and always needs a tap on the phone, plus a second confirmation when it would replace an existing pairing.
   - The Agent's local UI also has a manual form (server URL + code).
2. The Agent calls `POST <server>/api/pair {code, device: DeviceInfo}` → `{device_id, device_token, ws_url, server_url}`.
3. The Agent stores `device_token` encrypted with an Android Keystore key and never shows or logs it. The Server keeps only its SHA-256.
4. Unpairing (`DELETE /devices/:id`) revokes the token. The Agent then receives close code 4001 and shows "Not paired".

## 3. Agent socket: `WS /ws/agent`

### 3.1 Connect

- The Agent opens `wss://<host>/ws/agent` with `Authorization: Bearer <device_token>`. A bad token gets `{"t":"error","code":"unauthorized"}`, then close code **4001**. On 4001 the Agent stops reconnecting until it is paired again.
- If the same device connects twice, the Server closes the older socket with `{"t":"error","code":"replaced"}`, then code **4002**. The Agent ignores close events from sockets it has already abandoned; on 4002 for its current socket it waits 30 s before reconnecting (two phones sharing one token would otherwise fight in a tight loop).
- Close **1001** (Server shutting down or redeploying) is a normal reconnect with backoff.
- The Server's first message is **`hello`**: `server_time`, `device_id`, `settings`, `settings_version`, `rates`.
  - **Clock offset** = `server_time − local now` at receipt. The Agent uses it for command expiry (3.5). It does not change the phone's clock.
  - If `settings_version` differs from the version the Agent last applied, the Agent applies and persists the new settings and emits `settings_applied` event.
- Right after `hello`, the Agent enqueues a `static` telemetry message (device + capabilities) and fresh `slow` and `fast` readings, and starts draining its outbox. Because acks are cumulative (3.2), these go out **behind any backlog** in `seq` order; with a large backlog they reach the Server after `live_window_s` and are stored without being relayed live. The Dashboard then shows the last known state until the backlog has drained.
- Reconnect uses exponential backoff with jitter: 2 s, 4 s, 8 s … up to 60 s. Backoff resets after a connection has stayed up for 60 s. Reconnect immediately on an Android network-available callback.
- OkHttp `pingInterval(20 s)` detects dead sockets. The app-level `heartbeat` message is separate (3.3).
- The Agent's 30 s `hello` timeout starts when the socket has opened. The upgrade itself (DNS, TCP, TLS, a free-tier Server waking up) has a separate 100 s backstop, so a slow wake-up is not cut short.
- The Agent persists the last clock offset and uses it, marked "not synced", until the next `hello`. Every `ts`, `at` and `revert_deadline` it sends is in offset-corrected (Server) time; the phone's own clock is only used for local timers.
- `/ws/agent` negotiates `permessage-deflate` (messages over 1 KiB are compressed; OkHttp supports it). `/ws/dashboard` does not offer compression.

### 3.2 Outbox, `seq` and `ack` (plan 6.1 "Sync")

These message types are **buffered**: `telemetry`, `event`, `finder_spot`, `finder_spot_deleted`, `sms`, `cmd_ack`, `cmd_result`, `cmd_revert`.

1. The Agent first inserts each one into its local outbox (Room). The table's autoincrement id becomes `seq`.
2. A single sender coroutine drains the outbox **in `seq` order**. A message produced while connected leaves right away. A backlog leaves in `batch` messages of at most 200 items.
3. After storing, the Server replies `{"t":"ack","seq":N}`, meaning every message with seq ≤ N is stored. The Agent then deletes those rows. The Server acks **every** buffered message it has processed, including ones it drops (semantically invalid, or referring to an unknown command id or spot, for example reports left in the outbox from before a re-pairing). Otherwise one bad row would block the outbox head forever.
4. Unacked rows are re-sent after a reconnect, so the Server must handle duplicates:
   - telemetry: unique `(device_id, tier, ts)` → INSERT OR IGNORE
   - events: unique `event.id`
   - spots: upsert by `spot.id`
   - sms: unique `sms.id`
   - command reports: applied idempotently. A report never moves a command backwards, so a late `cmd_ack` after `cmd_result` is ignored. Reports for a command that is no longer in the Server's memory (after a restart) are matched against the stored row, so the outcome is the same whether reports arrive in one `batch` or one by one.
   - The Server acks only what it stored. A write that fails for a reason other than the item itself (database outage, lost commit response) is not acked, so the Agent re-sends and the duplicate rules above apply; an item the database refuses as invalid (constraint, type or size error) is dropped and acked.
5. Outbox cap is 50,000 rows. When full, the oldest `fast` telemetry is dropped first. Events and command reports are never dropped.
6. These are **not** buffered: `heartbeat`, `probe`, `finder_live`.
7. `heartbeat.outbox` is the number of buffered rows not yet acked, including rows already sent but still waiting for their ack. The Server uses it to decide when a backlog has drained (6).

A `telemetry` message older than `PROTOCOL.live_window_s` (120 s) by Server time is backlog: it is stored and rolled up but not relayed live to Dashboards. The Server updates its "current state" only from newer timestamps, so backlog never overwrites fresh values. A reading dated more than 120 s **ahead** of the Server clock is stored with its own `ts` but merged into the current state as if taken now and not relayed live, so a phone clock in the future cannot freeze the state.

### 3.3 Sending tiers (plan 8.2)

| Message | When |
|---------|------|
| `heartbeat` | every `telemetry.heartbeat_s` (30 s). The Server uses it for offline detection. On Render's free tier it also keeps the instance awake. |
| `telemetry` tier `fast` (radio, net) | every `rates.fast_s`: `fast_active_s` (5 s) while a Dashboard watches this device, otherwise `fast_idle_s` (60 s) |
| `finder_live` | every `mirror_s` (1 s) while `rates.mirror = true` |
| `telemetry` tier `slow` (power, heat, system, tether, traffic, position) | every `slow_s` (60 s) |
| `telemetry` tier `static` (device, capabilities) | on connect, after `capability_probe`, after a profile change |
| `event` | immediately |

The Server sends `rates` whenever the answer changes, based on which Dashboards are subscribed (5).

**Data budget.** The long lists are not sent every minute when nobody is watching: `heat.zones`, `system.top` and `traffic.per_if` go in the `slow` message every 15 min, or every time while a Dashboard watches the device (`rates.fast_s == fast_active_s`); `radio.neighbours` only while a Dashboard watches. A full `slow` message is sent when a Dashboard opens. Measured from `shared/fixtures` (JSON payload only, before TLS/WebSocket framing, acks and pings, no Dashboard open): about 3.2 MB/day at fixture size (3.6 MB before this trimming) and 3.5 MB/day with every list at its cap (8.4 MB before). The base fields every 60 s dominate, so this is above the plan's ~1 MB target; `permessage-deflate` on `/ws/agent` (3.1) reduces what goes over the wire further.

### 3.4 Settings

- The Server owns the settings. `PUT /settings` (body `PutSettingsRequest {device_id?, settings, password?}`) deep-merges, validates, increments `version`, stores, and pushes `{"t":"settings"}` to the Agent if it is connected. Otherwise the Agent gets them in the next `hello`. Updates for one device run one at a time, and memory, Dashboards and the Agent see a new version only after it is stored.
- A change to `profile_override` (it holds root shell templates) needs the admin's own `password`, else 401 `reauth_required` and nothing changes. Sending the same override back unchanged (a full-form save) needs no password.
- Every successful `PUT` is stored, in the same transaction, as a Server-generated event of kind `settings_changed` with `data: {user, version, changed: [dotted paths], profile_override_changed}`, and broadcast like any other event. Internal changes (for example `charge_limit`, 3.5) do not create one.
- The Agent persists the last applied settings and version, and ships `DEFAULT_SETTINGS` (identical to `shared/fixtures/settings-default.json`, which a unit test checks). The watchdog works offline with the last applied copy.

### 3.5 Commands (plan 6.3 and 6.4)

```
Dashboard           Server                                   Agent
   | POST /commands   |                                        |
   |----------------->| validate role, confirm, re-auth        |
   |                  | store status=queued, created_at=now    |
   |                  |--- cmd {id,name,args,sent_at,...} ---->| (status=sent)
   |                  |                                        | expiry check: (now+offset) - sent_at > expiry_s -> reject 'expired'
   |                  |                                        | capability, policy, reboot-limit checks
   |                  |<-- cmd_ack {accepted} -----------------| (status=acked | rejected)
   |                  |                                        | execute
   |                  |<-- cmd_result {ok,output,revert_deadline}| (status=done | failed | revert_armed)
   |                  |<-- cmd_revert {kept|reverted} ---------| (status=kept | reverted)
```

- **Queueing:** if the Agent is offline, the Server holds the command and sends it on reconnect. After `commands.expiry_s` (60 s) from `created_at` the Server marks it `expired`. The Agent also rejects a command whose `sent_at` is too old by its offset-corrected clock (rule 6).
- **Re-delivery:** on Agent connect the Server also re-sends `sent` commands that have no `cmd_ack` yet and are still within `commands.expiry_s` (same `id` and `sent_at`). The Agent never executes a command id twice: for a duplicate it re-queues its latest report (a rejecting `cmd_ack`, or `cmd_ack`, or `cmd_result` plus `cmd_revert` with the armed deadline); for one still running, its `cmd_ack` once it exists. (The simulator skips the re-send while that report is still in its outbox.)
- **Final timeouts:** `acked` with no result for 30 min plus the command's own `timeout_s` / `off_seconds` / `duration_s`, and `revert_armed` with no report 10 min past `revert_deadline`, become `expired` with a message saying so. This is soft: a late report still applies, but never one that would move the command backwards. The allowed moves are `commandStatusAllowedFrom` / `commandMayMove` in `commands.ts`; the Server guards its writes with them and the Dashboard uses the same table to drop stale copies (so `expired` replaces `acked` or `revert_armed` there too and ends the revert countdown). A report that lands while the Server is writing a timeout wins: the timeout is applied only if the row still has the status it was decided on.
- **Secret arguments at rest:** rows stored before `secret_args` were blanked are scrubbed by schema migration 3 (every status except `queued` and `sent`).
- **`charge_limit`** writes `settings.watchdog.charge_limit_pct` only after the Agent reports success (`done` or `revert_armed`), not when the command is created.
- **Secret arguments:** `CommandSpec.secret_args` (the hotspot `password`) are only in the `cmd` frame to the Agent. The Server never returns them over REST or WebSocket, to any role (POST response, `GET /commands`, live `command`, `pending_reverts`), and blanks them in storage once the command has been acked or reached a final status.
- **Arguments** are checked by the Server and again by the Agent with the same rules, for example: hotspot `password` 8 to 63 printable ASCII characters; `send_sms.to` an optional `+` and 2 to 20 digits after removing spaces, dots, dashes and parentheses; `ussd.code` matching `^[*#][0-9*#+]{1,63}$`; `finder_spot_test.name` 1 to 64 characters and `orientation` at most 32, without control characters; `shell.timeout_s` and `data_cycle`/`airplane_cycle.off_seconds` 1 to 600.
- **Checks on the Agent, in order:** expiry → name known and supported in the capability map (`unsupported`) → `power_off` blocked unless `allow_power_off` (`blocked`) → reboot limit: at most `max_reboots` per `reboot_window_h` from any source (`reboot_limit`) → argument validation (`invalid_args`) → conflicting command or watchdog step running (`busy`).
- **Role checks on the Server:** viewers cannot send commands (403). `admin_only` commands need an admin. `confirm` commands need `confirmed: true`, else 400 `confirmation_required`. `reauth` commands need the user's `password`, else 401 `reauth_required`. A session whose user has no TOTP (any role) gets 403 `totp_enrollment_required` for everything except the enrollment endpoints (`api.ts`, plan 10). Failed password re-entries are counted per session (5 per 15 min), separately from sign-in failures.
- **Audit:** every state change is stored in `commands` and broadcast to Dashboards as `{"t":"command"}`. Agent-local actions (watchdog, local UI, schedule) are reported as `event` kind `command` or `watchdog_action` and stored in the timeline.

#### Auto-revert (rule 1)

The Server sets `revert_after_s` (from `commands.revert_after_s`) and `revert_confirm` on `cmd` when `COMMANDS[name].revertFor(args)` is not null.

1. The Agent records the **undo action** before it applies the change, for example `mobile_data {enabled:true}`. It persists `{command_id, undo, deadline, confirm_mode, applied_at}` in Room, in phone wall-clock time, so a revert survives an Agent restart or reboot. On restore the remaining time is capped at the revert window (a clock set back cannot stretch it).
2. `cmd_result` carries `revert_deadline = applied_at + revert_after_s`.
3. Confirmation:
   - `agent_link`: from `applied_at + REVERT_SETTLE_S` (10 s), the Agent sends `{"t":"probe","id"}` every 10 s while connected, and once after every reconnect. The first `probe_ack` for a probe sent at or after the settle time confirms the change: the Agent sends `cmd_revert {state:"kept"}`.
   - `dashboard`: the change is kept only when `{"t":"cmd_keep","id"}` arrives. The Server sends it after `POST /commands/:id/keep`.
4. When the deadline passes without confirmation, the Agent runs the undo action, sends `cmd_revert {state:"reverted"}` (buffered), and emits event `revert`.
5. At Agent start, persisted armed reverts are restored before any new command that arms a revert, or `cmd_keep`, is handled; any that is past its deadline is reverted immediately.
6. **Superseding:** a newer command on the same state (`data`: `mobile_data`, `data_cycle`; `airplane`: `airplane_mode`, `airplane_cycle`; `tether`: `usb_tethering`; `network_mode`) ends an earlier armed revert with `cmd_revert {state:"kept", detail:"Superseded by a newer <label> command (<id>)"}`, without running its undo and without a `revert` event. If the newer command arms a revert itself, it inherits the earlier undo, so an unconfirmed chain still returns to the last state that worked.

### 3.6 Probe

`{"t":"probe","id":"p_…"}` → `{"t":"probe_ack","id":"p_…","server_time":"…"}`. The Server answers at once without storing anything.

## 4. Signal score (plan 7.2)

```
n_rsrp = clamp((RSRP + 120) / 40, 0, 1)
n_sinr = clamp((SINR + 5) / 30, 0, 1)     // missing SINR -> 0
n_rsrq = clamp((RSRQ + 20) / 12, 0, 1)    // missing RSRQ -> 0
score  = round1(100 * (w_sinr*n_sinr + w_rsrp*n_rsrp + w_rsrq*n_rsrq))   // missing RSRP -> null
```

`round1(x) = Math.round(x * 10) / 10`, which is JavaScript `Math.round` (half rounds up). In Kotlin use `floor(x * 10 + 0.5) / 10`. Test vectors: `shared/fixtures/score-cases.json`. For NR_SA use the SS-RSRP/SS-SINR/SS-RSRQ values. For NR_NSA, `radio.score` uses the LTE anchor, and the Dashboard also shows the NR leg. `radio.score` in telemetry is smoothed over `finder.smooth_s` seconds (mean of the readings in the window).

## 5. Dashboard socket: `WS /ws/dashboard`

1. The client connects and must send `{"t":"auth","token":"<session token>"}` within 10 s. The Server replies `auth_ok {user}`, or `auth_failed` and closes with 4001 when the session definitely does not exist, has expired or has no TOTP enrolled yet (`message: "totp_enrollment_required"`; the Dashboard only opens the socket after enrollment). If the session lookup itself fails or times out (database trouble), the Server closes with **1013** (try again later) and the client keeps its token and reconnects with backoff.
   - When the user's role changes (or they turn TOTP off), the Server closes their sockets with **4004**; the session stays valid, and the client reconnects, re-sends `auth` and refetches `GET /api/auth/me` without signing out.
2. The client sends `{"t":"subscribe","device_id":"d_…","finder":false}` and sends it again whenever the screen changes (`finder:true` only while the Finder screen is visible).
3. The Server sends `snapshot {device: DeviceSnapshot}` for the subscribed device (or for every device when `device_id` is null), then live `telemetry`, `event`, `status`, `command`, `finder_live`, `finder_spot`, `finder_spot_deleted`, `alert`, `sms` (admins only), and `settings`. When a phone is paired, and on a phone's first connection, the Server sends a `snapshot` for it (instead of `status`) to Dashboards subscribed to all devices or to that id, so a new phone appears without a reload.
4. `rates` for an Agent: `fast_s = fast_active_s` if at least one authenticated Dashboard is subscribed to that device (or to all), else `fast_idle_s`. `mirror = true` if at least one of those has `finder:true`.
5. The client sends `{"t":"ping"}` every 25 s and the Server answers `pong`. The client reconnects with backoff and re-sends `auth` + `subscribe`.

## 6. Offline detection and alerts (plan 8.5)

- The Server tracks `last_seen` (any Agent message) per device and persists it.
- "Phone offline" fires when `now − last_seen > alerts.offline_after_s` (180 s). The check runs every 15 s, and once at Server start (after a free-tier spin-down the Server reloads `last_seen` and the open offline alert from the database).
- "Back online" is prepared on the first message after an offline alert and sent once the backlog has drained, so it can name the reason:
  - **Drained** means a heartbeat since the reconnect reported `outbox: 0`, or reported N and N buffered messages have been stored since. If no heartbeat has reported yet, 10 s with no buffered message arriving or being stored. The alert waits at most 2 min; after that it says "Reason not yet known … (about N messages left)".
  - **Reasons** are events received after the reconnect whose phone `ts` is at or after `last_seen` (`watchdog_action`, `power_lost`, `reboot_blocked` and `revert` may be up to 60 s earlier), newest 20, shown oldest first.
  - The duration is reconnect time minus `last_seen`. `data`: `{offline_s, reasons, backlog_drained}`.
  - A pending "back online" goes out before a new offline alert for the same phone, and on Server shutdown (capped at 5 s). If the phone returns while the offline alert is still being sent, "back online" is still sent once that one is delivered.
- **Late events:** an alert-worthy event (`EVENT_ALERT`) whose phone `ts` is more than 30 min old when it reaches the Server (`sms_received` excepted) does not alert on its own and is not dropped. Once the backlog has drained (or after at most 2 min) the Server sends one alert per alert kind, titled "<title> (reported late)", with the highest severity, the count and time span and the latest event's message; `data: {late: true, count, event_ids, event_kinds, first_ts, last_ts}`. Events already named in a delivered "back online" alert are left out. This also covers a backlog that arrives while the phone stayed connected.
- A `moved` alert carries the location in its text (latitude, longitude, OpenStreetMap link) and `data: {lat, lon, map_url}`.
- An event or telemetry row that was stored but whose commit response was lost is not acked (3.2); when it comes again as a duplicate the Server still raises its alert (once, checked against `alerts_sent`) and rolls it up.
- Alerts from events: see `EVENT_ALERT` in `events.ts`. Server-evaluated alerts: offline, back online, low battery, signal drop vs baseline, cell changed, tethering down, data quota.
- The same alert kind for the same device is not repeated within `alerts.cooldown_min`. "Back online" and quota steps are not subject to the cooldown.

## 7. Close codes

| Code | Meaning |
|------|---------|
| 1000 | normal |
| 1001 | Server going away (shutdown or redeploy): reconnect with backoff |
| 4001 | unauthorized (bad or revoked token / session, or a dashboard session without TOTP: `auth_failed {message: "totp_enrollment_required"}`, which means "enroll", not "sign out") |
| 4002 | replaced by a newer connection of the same device |
| 4004 | dashboard only: the user's role changed, or they turned TOTP off; the session is still valid. Reconnect, re-send `auth`, and refetch `GET /api/auth/me` |
| 1013 | dashboard only: try again later (session lookup failed or timed out, or no `auth` arrived within 10 s); keep the token and reconnect with backoff |
| 4003 | protocol error (bad message) |
| 4005 | screen relay only: no such screen session, or it has ended |

The screen relay sockets (section 8) also use 4001 (bad token, or the viewer is not the admin
sign-in that started the session) and 4002 (another viewer window opened the same session).

## 8. Remote screen (`screen.ts`)

Live view and control of the phone's screen from the Dashboard. The Agent runs the bundled scrcpy
server (Apache-2.0, version `SCREEN.scrcpy_version`) as root; video goes over a **separate relay
socket pair**, so it never competes with telemetry, acks or commands on `/ws/agent`.

```
Dashboard                        Server                                  Agent
  | POST /api/screen/sessions      |                                       |
  |  {device_id, quality,          | admin, password + fresh TOTP code     |
  |   password, code}              | phone online, capability screen_mirror|
  |<-- 201 {session_id} -----------|--- screen_start (on /ws/agent) ------>| starts scrcpy as root
  |                                |<== WS /ws/screen/agent?session=id ====| (device token header)
  |== WS /ws/screen/view =========>|                                       |
  |   screen_auth {token, id}      |                                       |
  |<-- screen_status, screen_meta -|<-- screen_meta ------------------------|
  |<-- binary video frames --------|<-- binary video frames ---------------|
  |--- touch / key / text / ... -->|--- (validated) ---------------------->| -> scrcpy control socket
  |--- screen_stop --------------->|--- screen_stop (on /ws/agent) ------->| stops scrcpy
```

- **Who:** admins only. Starting needs the admin's password **and** a fresh TOTP code (both count
  toward the per-session lockout; the code's step is recorded, so it cannot be reused). The viewer
  socket is bound to the sign-in session that passed the check (`sessionHash`), not just the user.
- **Checks before the password:** the phone is connected (409 `device_offline`) and reports
  `capabilities.screen_mirror.supported` (409 `conflict` otherwise), so an offline phone does not
  burn a code. `screen_mirror` is not a command name: `screen` is the screen on/off command.
- **One session per phone:** a new one ends the old with reason `replaced`.
- **Limits (`SCREEN`):** at most `max_duration_s` (30 min) per session; the Agent must connect its
  relay within `agent_connect_timeout_s` (30 s); the session ends when no viewer has been connected
  for `viewer_grace_s` (30 s), so a page switch or short network drop survives. The Dashboard drops
  its socket while the page is hidden and reconnects when it is shown again.
- **End reasons** (`ScreenEndReason`): `stopped`, `replaced`, `max_duration`, `viewer_gone`,
  `agent_timeout`, `agent_gone`, `agent_failed`, `session_ended` (sign-in ended or role changed),
  `unpaired`, `server_shutdown`. The Server sends `screen_stop` to the Agent for every end, and
  for a `screen_state` about a session it does not know.
- **Audit:** start and end are stored as Server-generated `screen_session` events (data: user,
  session_id, state, quality, and for the end reason, duration_s, bytes) and appear in the timeline.
- **`screen_state`** (Agent → Server on `/ws/agent`, not buffered): `starting`, `streaming`,
  `ended`, `failed` (with `error`). `failed` ends the session with `agent_failed` and the error.
- **Video frames** (binary, Agent → Server → viewer, forwarded unchanged): byte 0 is the kind.
  `1` = START: scrcpy's 12-byte packet header (u64 big-endian pts and flags: bit 62 codec config,
  bit 61 key frame; then u32 payload size) and the first chunk. `2` = CONT: the next chunk. Frames
  are at most `max_chunk_bytes` (256 KiB) so a key frame fits under the 512 KiB WebSocket limit.
  Payloads are H.264 Annex B; config packets (SPS + PPS) come before the first key frame. The
  Dashboard decodes with WebCodecs (`avc1.PPCCLL` from the SPS) and draws to a canvas.
- **Backpressure:** the Agent skips delta frames while its relay socket has more than 1 MiB unsent
  and asks scrcpy for a new key frame (`RESET_VIDEO`). The Server drops video for a viewer whose
  socket has more than `max_viewer_buffered_bytes` (2 MiB) unsent, waits for the next config or key
  frame, and asks the Agent for one (`{"t":"keyframe"}`, at most every 2 s). A viewer that joins or
  reconnects gets video from the next key frame, which the Server requests at once.
- **Control** (viewer → Server → Agent, JSON): `touch {action: down|move|up|cancel, pointer 0..9,
  x, y}`, `scroll {x, y, dx, dy}` (−16..16), `key {key}` (`SCREEN_KEYS`), `text {text}` (at most
  300 UTF-8 bytes), `panel {notifications|settings|collapse}`, `keyframe`. Coordinates are
  fractions (0..1) of the video frame. The Server validates every message, drops invalid ones and
  forwards at most 200 per second; the Agent encodes them as scrcpy control messages.
- **Viewer socket:** first message `screen_auth {token, session_id}` within 10 s; then
  `screen_status`, `screen_meta`, video frames and `pong` (the client pings every 20 s).
- **Data:** quality presets cap the bit rate (`low` 0.8, `standard` 2, `high` 4 Mbit/s; a still
  screen sends far less). Video uses the hotspot's mobile data while a viewer watches.
- **Samsung quirk:** on the Galaxy M52 (One UI 5.1) the video encoder aborts ("stack corruption
  detected" in `CCodec::customConfigVideoEncoder`) when the scrcpy process's command line is longer
  than about 250 bytes, so the Agent keeps it under 220 (defaults are left out).
