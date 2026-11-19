# End-to-end harness

Runs the real pieces together:

- the **built** Server (`node server/dist/index.js`) on a random port, with a throwaway libSQL file
  database, serving the **built** Dashboard (`web/dist`);
- the Agent simulator (`tools/agent-sim`) as a child process that pairs with a real pairing code;
- a Dashboard WebSocket client (`/ws/dashboard`);
- headless Chrome, driven over the raw DevTools protocol (the `ws` package already in
  `node_modules`; no Puppeteer or Playwright).

It needs no network access and installs nothing.

```sh
npm run build        # the harness does not build anything itself
npm run e2e          # = node tools/e2e/run.mjs
```

Options:

| Option | Meaning |
|--------|---------|
| `--out <dir>` | Where logs, screenshots and `report.json` go. Default `$E2E_OUT`, else `<os tmp>/hsm-e2e-<time>`. Never inside the repo. |
| `--no-ui` | Skip the Chrome part. |
| `--chrome <path>` | Chrome or Chromium binary. Default `$CHROME_PATH`, else the usual install paths (macOS app, `/usr/bin/google-chrome`, `chromium`). |
| `--speed <x>` | Simulator `--speed` (default 10: Finder spot tests and reboot downtime finish quickly). |
| `--only <id,...>` | Run only these scenarios (plus the required setup ones). Later scenarios depend on state from earlier ones, so this is mainly for debugging. |

`E2E_VERBOSE=1` echoes the Server and simulator output. Exit code 0 means every scenario passed.
A run takes about 3 minutes, mostly real-time protocol waits (auto-revert deadlines, the offline
alert, command expiry, and TOTP codes, which cannot repeat a 30 s step). Each run starts from an empty database and a fresh simulator state.

## What it checks

| Id | Scenario |
|----|----------|
| `server` | The built Server starts with `DATABASE_URL=file:<tmp>/e2e.db`, `ADMIN_USERNAME/PASSWORD`, `HOST=127.0.0.1`, `LOG_LEVEL=warn`. `/healthz` is ok, `/` and SPA routes serve `index.html` with the CSP, unknown `/api` routes give JSON 404. |
| `auth-totp` | The bootstrap admin's first session can only enroll (plan 10: TOTP for every role): `GET /api/auth/me` and `/api/config` answer, while reads (`/api/devices`, `/device`, `/settings`, `/alerts`, `/events`, `/commands`) and `POST /api/pair/codes` give `403 totp_enrollment_required`, and the dashboard socket answers `auth_failed {message: totp_enrollment_required}` and closes with 4001. TOTP is enrolled through `/api/auth/totp/setup` + `/enable`, with codes computed from the base32 secret (RFC 6238, `node:crypto`). Login then needs the TOTP step. A wrong code and a replayed code are refused. |
| `pair-code` | `POST /api/pair/codes` returns an 8-character code, a 15-minute expiry, the `hsm://pair` deep link, and `adb shell "am start -n com.hotspotmonitor.agent/.ui.AdbPair -a android.intent.action.VIEW -d '<deep_link>' --ez auto true"` (the DUMP-guarded alias that alone honours `auto`). |
| `sim-pair-live` | The simulator pairs with the code. A Dashboard socket (auth, then subscribe) receives a `snapshot` and live `telemetry`, and sees the new phone online (the Server announces a phone's first connection with a `snapshot`, later reconnects with `status`). A bad session gets `auth_failed` and close code 4001, and a used pairing code is refused. |
| `settings-push` | `PUT /api/settings` deep-merges and bumps the version. The Dashboard gets `settings`, and the simulator applies the new version (`settings_applied` event, its state file, and fast telemetry every 2 s). An invalid value gives 400 without a version bump. |
| `settings-audit` | Every successful `PUT /api/settings` produces a `settings_changed` event (`data: {user, version, changed, profile_override_changed}`) over the socket and in `GET /api/events`. A `profile_override` change (set or cleared, alone or mixed with other keys) gives `401 reauth_required` without `password` or with a wrong one, and changes nothing. With the admin's password it is stored, and the simulator applies it (its static telemetry then reports `network_mode` as supported). |
| `finder-mirror` | `subscribe {finder:true}` turns the 1/s `finder_live` mirror on, and `finder:false` turns it off. |
| `cmd-refresh` | `refresh` reaches `done` and new static telemetry arrives. |
| `cmd-mobile-data-kept` | `mobile_data {enabled:false}` with `--keep-link` goes `revert_armed` (agent_link) and is `kept` by the probe round trip before the deadline. |
| `cmd-usb-keep` | `usb_tethering {enabled:false}` goes `revert_armed` (dashboard) and appears in `pending_reverts`. `POST /api/commands/:id/keep` makes it `kept`. A second keep gives 409 and an unknown id gives 404. |
| `cmd-usb-revert-armed`, `cmd-usb-reverted` | The same command without keep is `reverted` at `commands.revert_after_s` (lowered to 30 s), with a `revert` event and a `watchdog` alert. The wait overlaps the next three scenarios. |
| `finder-spots` | Two `finder_spot_test` runs appear in `GET /api/finder/spots`. `finder_set_baseline` needs `confirmed` and then sets `baseline_id`. `finder_delete_spot` removes a spot, and a `finder_spot_deleted` message is broadcast. |
| `sms-admin` | `send_sms` to the short code 121 stores the outgoing SMS and the carrier reply, and raises a `carrier_sms` alert that admins can read. |
| `viewer-role` | `POST /api/users` gives `401 reauth_required` without `reauth_password` or with a wrong one, and 201 with the admin's password. The new viewer's first session can only enroll: `GET /api/device` and the other reads give `403 totp_enrollment_required`, and the socket gives `auth_failed` + 4001. After enrolling TOTP it signs in with password + code. It then gets 403 on commands, SMS, settings writes, users, pairing and keep. Viewers can still read device, settings, history and spots. `carrier_sms` alerts, `sms_received` events and `send_sms` audit records are redacted over REST and over the socket, and the viewer socket never receives `sms` frames. |
| `users-reauth` | An admin's `PATCH /api/users/<self>` with `password` or `reset_totp` gives 400 `bad_request`. For a throwaway user, `PATCH` and `DELETE` give `401 reauth_required` without `reauth_password` or with a wrong one. A role change closes that user's dashboard socket with 4004; the same session re-authenticates with the new role. `reset_totp` revokes the user's sessions (REST 401, socket 4001) and the next login must enroll again. The user is then deleted (204); deleting yourself is refused. |
| `cmd-hotspot-secret` | A `hotspot` command with a `password` (`CommandSpec.secret_args`) reaches the phone (the simulator reports "new password set"), but the password appears in no REST response (`POST` and `GET /api/commands`, admin and viewer), no socket message (admin and viewer), no Server or simulator log, and, once delivered, not in `commands.args` in the database (read with `node:sqlite`; skipped with a note when this Node lacks it). |
| `cmd-reboot` | `reboot` without `confirmed` gives 400 `confirmation_required`. Without a password, or with a wrong one, it gives 401 `reauth_required`. With both it is accepted, the phone goes offline, and it reconnects with `boot` and `agent_start` events. |
| `cmd-expiry` | With the simulator stopped and `commands.expiry_s` lowered to 10 s, a queued command becomes `expired` and never reaches the phone. A fresh queued command is delivered on reconnect. |
| `offline-backfill` | Without `--keep-link`, `mobile_data` off cuts the simulated phone's link. With `alerts.offline_after_s` at 30 s, an `offline` alert fires. The simulator is then stopped mid-outage, which persists its outbox and armed revert, and restarted. At the deadline it reverts, reconnects and back-fills. The checks are the command `reverted`, a `back_online` alert with reasons, and every buffered reading present exactly once in `GET /api/telemetry`. |
| `dedup` | The backlog from the outage is written back into the simulator's outbox and sent again. The Server acknowledges it and stores nothing twice: the same history points, one copy of each event, no new offline or back-online alerts, and the command stays `reverted`. |
| `history` | `GET /api/telemetry?metric=radio.rsrp&res=raw` returns plausible points in time order. Slow-tier metrics work too. `res=auto` over 24 h returns `1m`. Unknown or missing metrics give 400. |
| `dashboard-ui` | Chrome loads the app from the Server's origin and signs the admin in with password and TOTP. The Overview shows Online, the simulated model and the RSRP the simulator just sent. Controls, Signal (history chart), Signal Finder (spots, and the mirror turns on), Settings, Users, Alerts, Messages and System render. A new viewer signs in, in a separate browser context: it lands on TOTP enrollment, the harness reads the secret from the page and confirms a code, and no `/ws/dashboard` socket opens before that. The viewer then sees the Overview but no Controls or Messages. The run fails on any console error, exception, `Log` error entry, CSP violation or failed request (admin or viewer page). Screenshots go to `<out>/screens/`. |

## Notes

- The Server process gets every variable it reads set explicitly (blank where unused). A
  developer's `.env`, which `server/src/index.ts` loads for unset variables, therefore cannot send
  alerts to a real Telegram chat or point the run at Turso.
- Child processes (Server, simulator, Chrome) are stopped on normal exit and on failures. They
  are also killed on Ctrl-C and on uncaught errors.
- The simulator always runs with `--persist-outbox`, with its state file in `<out>/work/`, which
  holds a throwaway device token.
- The settings change is also checked through the simulator's `settings_applied` event and its
  state file, because no API exposes the `heartbeat.settings_version` the Agent reports.
- The Agent-side `expired` rejection (a `cmd` whose `sent_at` is already too old when it arrives)
  cannot be provoked through a correct Server, which expires queued commands itself. The simulator's
  unit tests cover it.
- The UI scenario checks its own detectors. It injects an inline script, which the CSP must block
  and report, and a `console.error`, which must be captured. A broken detector therefore cannot
  produce a false "no errors".
- `offline-backfill` fails when the `back_online` alert cites events from before the outage, or
  when its `offline_s` is more than 5 s away from the real time offline (last seen to reconnect),
  the rules in docs/protocol.md section 6.
