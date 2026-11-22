# Deploying the Server

The Server (plan section 8) is one Node.js process. It holds the phone's WebSocket, stores history, sends alerts and serves the built Dashboard from the same address. This page covers:

1. [Render + Turso, free tier](#1-render--turso-free-tier) (recommended start)
2. [Keeping it reliable on the free tier](#2-keeping-it-reliable-on-the-free-tier)
3. [Optional: Dashboard on Vercel](#3-optional-dashboard-on-vercel)
4. [VPS with Docker Compose and Caddy](#4-vps-with-docker-compose-and-caddy)
5. [Local development](#5-local-development)
6. [Reference: environment variables, endpoints, recovery](#6-reference)

---

## 1. Render + Turso, free tier

Render's free web service has **no persistent disk**: every restart starts from a clean file system. The database therefore lives in **Turso** (hosted libSQL, SQLite compatible). Locally and on a VPS the same code uses a plain SQLite file.

### 1.1 Create the Turso database

With the CLI (macOS / Linux / WSL):

```sh
curl -sSfL https://get.tur.so/install.sh | bash
turso auth signup            # or: turso auth login
turso db locations           # pick the location closest to the Render region you will use
turso db create hsm --location <location-code>
turso db show hsm --url      # -> libsql://hsm-<your-org>.turso.io   = DATABASE_URL
turso db tokens create hsm   # -> long token                         = DATABASE_AUTH_TOKEN
```

Or in the web UI at <https://app.turso.tech>: **Create Database**, choose a location, then on the database page copy the **URL** (`libsql://…`) and create a **token** (read and write).

The Server creates its tables on first start (migrations run automatically).

### 1.2 Create the Render service from the Blueprint

1. Push this repository to GitHub (or GitLab).
2. In <https://dashboard.render.com>: **New > Blueprint**, connect the repository.
3. Render finds [`render.yaml`](../render.yaml) at the repository root (the Blueprint default), so leave **Blueprint file path** empty.
4. Render asks for the variables marked `sync: false`:

   | Variable | Value |
   |----------|-------|
   | `DATABASE_URL` | `libsql://hsm-<your-org>.turso.io` from step 1.1 |
   | `DATABASE_AUTH_TOKEN` | the Turso token |
   | `ADMIN_USERNAME` | e.g. `admin` |
   | `ADMIN_PASSWORD` | at least 10 characters; only used to create the first admin |
   | `PUBLIC_URL` | leave empty (Render's `RENDER_EXTERNAL_URL` is used); set it only for a custom domain |
   | `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID` | optional, see 1.4; can be added later |
   | `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `VAPID_SUBJECT` | optional, see 1.5 |
   | `FIREBASE_SERVICE_ACCOUNT` | optional: push to the Android app (service account key JSON or base64), see mobile/README.md |
   | `CORS_ORIGINS` | leave empty unless the Dashboard is hosted elsewhere (section 3) |

5. **Apply**. The build runs `npm ci && npm run build` from the repository root (Dashboard, then Server) on Node 22 (`NODE_VERSION`), and starts `npm start` (`node server/dist/index.js`). Render checks `GET /healthz`.
6. Open `https://<service-name>.onrender.com/healthz`. Expect `{"ok":true,…,"db":"ok"}`.

The Blueprint pins the region to `singapore` (closest to India). Change `region` in `render.yaml` before creating the service if the phone is elsewhere; a service cannot move region later. Keep Turso in a nearby location: every write is a network round trip.

Do not add `NODE_ENV=production` to the build environment: `npm ci` would then skip the dev dependencies the build needs.

The Blueprint also sets `TRUST_PROXY=1` (one proxy hop), so the client IP used for login rate limits cannot be forged with an `X-Forwarded-For` header (section 6.1). If you create the web service by hand instead of from the Blueprint, set the same build and start commands, the health check path `/healthz` and `NODE_VERSION=22`. `TRUST_PROXY` may stay unset there: the Server recognises Render (`RENDER` / `RENDER_EXTERNAL_URL`) and then uses one hop by default.

### 1.3 First sign-in and TOTP

1. Open `https://<service-name>.onrender.com` and sign in with `ADMIN_USERNAME` / `ADMIN_PASSWORD`.
2. The Dashboard asks you to set up two-factor sign-in. Scan the QR code with an authenticator app (Aegis, Google Authenticator, 1Password, …) and enter the 6-digit code. TOTP is required for **every** account, admin or viewer (plan section 10): until it is set up, the session can only enroll, change its password or sign out. Everything else, including reading the phone's data and the live WebSocket, answers `403 totp_enrollment_required`.
3. From now on, sign-in asks for the password and then the code.
4. Optional: remove `ADMIN_PASSWORD` from Render's environment. It is only read while the users table is empty.
5. **Settings > Users** adds more accounts: `admin` (monitor and control) or `viewer` (monitor only; no controls, no root shell, no SMS). Every change there (add, change role, set a password, reset an authenticator, delete) asks for **your own** password again. Each new user enrolls TOTP at their first sign-in. Your own password and authenticator are changed under your account (they need the current password), not in the users list.
6. **Settings > Pair phone** shows a QR code and an `adb` command for the Agent (docs/protocol.md section 2). Codes are single use and expire after 15 minutes. Scanning the QR code opens the Agent, which asks for a tap on the phone to confirm. The `adb` command is for a phone whose screen does not work; it pairs without a tap:

   ```sh
   adb shell "am start -n com.hotspotmonitor.agent/.ui.AdbPair -a android.intent.action.VIEW -d 'hsm://pair?server=https%3A%2F%2F<service-name>.onrender.com&code=<CODE>' --ez auto true"
   ```

   Copy it as shown: the double quotes keep the `&` in the link away from the phone's shell. `.ui.AdbPair` can only be started from adb (or root), which is why only this entry point pairs without a tap. To replace an existing pairing without a tap, add `--ez replace true` before the closing quote.

### 1.4 Telegram alerts

1. In Telegram, talk to **@BotFather**: `/newbot`, choose a name. It replies with the bot token, e.g. `123456789:AA…` = `TELEGRAM_BOT_TOKEN`.
2. Open a chat with the new bot and send it any message (bots cannot start a conversation). For a group, add the bot to the group and post a message there.
3. Open `https://api.telegram.org/bot<TOKEN>/getUpdates` in a browser and copy `"chat":{"id": …}` = `TELEGRAM_CHAT_ID` (group ids are negative).
4. Set both variables in Render (**Environment** tab; the service restarts).
5. In the Dashboard: **Alerts > Send test alert** (`POST /api/alerts/test`). The test goes to every configured channel.

### 1.5 Web Push (browser and installed PWA notifications)

Nothing is required: if `VAPID_PUBLIC_KEY` / `VAPID_PRIVATE_KEY` are empty, the Server generates a key pair on first start and stores it in the database (`kv` table), so it survives restarts.

To manage the keys yourself:

```sh
npx web-push generate-vapid-keys
```

Set `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY` and `VAPID_SUBJECT=mailto:you@example.com`. Changing keys invalidates existing subscriptions; each browser must enable notifications again. On iPhone, Web Push works only for the Dashboard installed to the home screen (iOS 16.4 or later).

---

## 2. Keeping it reliable on the free tier

### 2.1 What spins down, and why the phone keeps it up

A free Render web service **stops after 15 minutes without inbound traffic** and starts again on the next request (a cold start of roughly 30 to 60 seconds). Inbound traffic includes HTTP requests and WebSocket messages.

The Agent sends a `heartbeat` every 30 seconds over its WebSocket (docs/protocol.md 3.3). While the phone is online the Server therefore never sleeps. The Server can only spin down when the phone has been silent for 15 minutes and no Dashboard is open, which means the **"Phone offline" alert (after 3 minutes) has already been sent**.

### 2.2 Cold-start behaviour

When the phone comes back after a spin-down:

1. Its reconnect attempt (exponential backoff, at most 60 s apart) wakes the Server. The first attempt may time out while the instance boots; the next one succeeds.
2. On start the Server rebuilds its state from Turso: devices, last seen times, the open offline alert, queued commands, alert cooldowns, settings. It also catches up any missing 1-minute averages from the last 6 hours.
3. The phone uploads its backlog (telemetry and events stored while offline, docs/protocol.md 3.2). The **"Back online"** alert waits until that backlog has been stored (the phone's heartbeat reports how many messages it still holds; at most 2 minutes) so it can name the reason (reboot, watchdog action, power loss) and the time spent offline. Alert-worthy events from the backlog that are more than 30 minutes old arrive as one "(reported late)" alert per kind instead of one each (docs/protocol.md section 6).
4. After any restart (deploy, spin-down, Render maintenance) the Server waits 90 seconds before declaring a phone offline, so a phone that is simply reconnecting does not trigger a false alarm.

### 2.3 Add an external uptime monitor (strongly recommended)

The plan's risk table lists "Server down: no remote view or alerts". The Server cannot alert about its own death, so add a free external monitor:

1. Create a free account at <https://uptimerobot.com> (or Better Stack, Freshping, …).
2. **New monitor**: type HTTP(s), URL `https://<service-name>.onrender.com/healthz`, interval 5 minutes. Optionally use a keyword monitor for `"ok":true`.
3. Add an alert contact (email, or its Telegram integration).

`/healthz` returns HTTP 503 when the database is unreachable, so a broken Turso connection is reported too. As a side effect the monitor keeps the instance awake even while the phone is offline, so Dashboards load instantly and the "Back online" alert is immediate.

### 2.4 Free instance hours

Render gives each workspace **750 free instance hours per month**. One service running all month uses at most 24 × 31 = **744 hours**, so it fits, but only if it is your only free web service that stays awake. Check **Billing > Free usage** in the Render dashboard. Free instances may also be restarted by Render at any time; nothing is lost because all state is in Turso.

### 2.5 Turso row budget

The free plan includes about **10 million rows written** and **500 million rows read** per month (check <https://turso.tech/pricing> for current numbers). The Server is designed to stay far below:

| Writer | Rows per day |
|--------|--------------|
| `telemetry_raw`: fast tier every 60 s when no Dashboard is open, slow tier every 60 s | ~2,900 |
| `telemetry_raw` while a Dashboard is open (fast tier every 5 s) | +720 per hour open |
| `telemetry_1m`: one row per minute | 1,440 |
| `devices`: state and last-seen flush every 30 s | 2,880 |
| Retention deletes (7-day raw window, roughly equal to inserts) | ~2,900 |
| Events, commands, alerts, sessions | tens |

That is roughly **10,000 rows a day, about 300,000 a month**, plus about 22,000 a month for each daily hour a Dashboard stays open: a few percent of the free allowance. Reads are dominated by chart queries; a one-year chart reads at most ~525,000 one-minute rows.

---

## 3. Optional: Dashboard on Vercel

The Server already serves the Dashboard, so this is optional (useful for a CDN or preview deployments).

Vercel reads `vercel.json` only from the project's **Root Directory**. The repository's [`vercel.json`](../vercel.json) sits at the repository root, so the project's Root Directory must stay the repository root (`./`). It already sets everything else:

- install `npm ci` (all workspaces: the Dashboard imports `@hsm/shared` from the workspace), build `npm run build -w web`, output `web/dist`, framework preset none;
- every path except `/assets/*` is rewritten to `/index.html` (single-page app; a missing hashed asset answers 404, not HTML);
- `Cache-Control`: hashed `/assets/*` immutable for a year, everything else (`index.html`, `sw.js`, client routes) `no-cache`, so a deploy reaches clients on their next load;
- the same security headers as the Server ([`server/src/security.ts`](../server/src/security.ts)), with a Content Security Policy whose `connect-src` is `'self' https: wss:` because the API is on another origin.

Steps:

1. In Vercel: **Add New > Project**, import the repository. Leave **Root Directory** at the repository root and leave the build, output and install settings on their defaults: `vercel.json` overrides them.
2. **Environment Variables**: `VITE_API_BASE=https://<service-name>.onrender.com` (no trailing slash). It is read at build time; the Dashboard derives the WebSocket URL from it (`https` becomes `wss`). Redeploy after changing it.
3. Deploy, then on Render set `CORS_ORIGINS=https://<project>.vercel.app` (comma-separate several origins, e.g. preview URLs). Sign-in uses bearer tokens, not cookies, so cross-origin works without anything else.

**Why the Server itself cannot run on Vercel.** Vercel runs code as serverless functions. A WebSocket connection there lives at most as long as the function's maximum duration, while the phone needs one connection open for days. Offline detection also needs an always-on process with timers ("no heartbeat for 3 minutes" must be noticed when nothing is happening), and the Server keeps live state in memory between messages. Render (or a VPS) provides a long-running process; Vercel does not.

---

## 4. VPS with Docker Compose and Caddy

The plan's original hosting option (plan 11): any small Linux VPS with Docker, a domain name, and Caddy for automatic HTTPS. SQLite lives on a Docker volume, so Turso is not needed.

1. Point a DNS `A` (and `AAAA`) record for your domain at the VPS. Open ports 80 and 443.
2. On the VPS:

   ```sh
   git clone <your repository> hotspot-monitor && cd hotspot-monitor
   cp deploy/.env.example deploy/.env
   nano deploy/.env        # DOMAIN, ADMIN_USERNAME, ADMIN_PASSWORD, TELEGRAM_*; leave DATABASE_* empty
   docker compose -f deploy/docker-compose.yml up -d --build
   docker compose -f deploy/docker-compose.yml logs -f server
   ```

3. Open `https://<DOMAIN>` and continue with 1.3. Caddy obtains and renews the certificate itself.

What the files do:

- `deploy/Dockerfile`: multi-stage build of the Dashboard and the Server on `node:22-bookworm-slim`, production dependencies only, runs as the unprivileged `node` user, data in `/data`, health check on `/healthz`.
- `deploy/docker-compose.yml`: `server` (with `DATABASE_URL=file:/data/hsm.db` on the `hsm-data` volume, `PUBLIC_URL=https://$DOMAIN`, and only the Docker network trusted as a proxy) and `caddy` (ports 80/443).
- `deploy/Caddyfile`: HTTPS reverse proxy, WebSockets included.

Update: `git pull && docker compose -f deploy/docker-compose.yml up -d --build`. The Server gets `SIGTERM`, closes sockets with code 1001 (the phone reconnects), and flushes state.

Backup (stop the Server briefly so the SQLite files are consistent):

```sh
docker compose -f deploy/docker-compose.yml stop server
docker run --rm -v hotspot-monitor_hsm-data:/data -v "$PWD":/backup busybox sh -c 'cp /data/hsm.db* /backup/'
docker compose -f deploy/docker-compose.yml start server
```

On Turso, `turso db shell hsm .dump > hsm-backup.sql` makes a logical backup without stopping anything.

---

## 5. Local development

Requires Node.js 22 (`.nvmrc`; at least 22.12). Node 20.19 or newer also builds and runs the Server and the Dashboard (Vite 8 needs `^20.19` or `>=22.12`, the root `package.json` `engines`), but the test runner (Vitest 5) needs Node 22.12 or newer.

```sh
npm install
cp deploy/.env.example server/.env    # set ADMIN_PASSWORD (10+ characters); leave DATABASE_URL empty
npm run dev:server                    # http://localhost:8080, SQLite at server/data/hsm.db, restarts on change
npm run dev:web                       # http://localhost:5173, proxies /api and /ws to :8080
```

Open <http://localhost:5173>, sign in, enroll TOTP, then **Settings > Pair phone** and start the simulated phone with the code:

```sh
npm run sim -- --code <PAIRING CODE> --server http://localhost:8080
```

Other useful commands:

```sh
npm run test -w server        # unit + end-to-end tests (in-memory database, fake clock)
npm run typecheck -w server
npm run build && npm start    # production build served from http://localhost:8080
```

To pair a real phone with a development Server, run the Server on your LAN address (`HOST=0.0.0.0`, `PUBLIC_URL=http://192.168.x.y:8080`) so the deep link in the QR code points somewhere the phone can reach. Production must use HTTPS (plan section 10).

---

## 6. Reference

### 6.1 Environment variables

All variables are documented with examples in [`deploy/.env.example`](../deploy/.env.example).

| Variable | Default | Purpose |
|----------|---------|---------|
| `PORT` | `8080` | Listen port (Render sets it) |
| `HOST` | `0.0.0.0` | Listen address |
| `PUBLIC_URL` | `RENDER_EXTERNAL_URL`, else from request headers | Base URL in pairing links and `ws_url` |
| `DATABASE_URL` | `file:<repo>/server/data/hsm.db` | `libsql://…` (Turso), `file:…` (SQLite) |
| `DATABASE_AUTH_TOKEN` | none | Turso token |
| `ADMIN_USERNAME`, `ADMIN_PASSWORD` | none | First admin, created only when no user exists |
| `SESSION_TTL_DAYS` | `30` | Dashboard session lifetime |
| `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID` | none | Telegram alerts |
| `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `VAPID_SUBJECT` | generated and stored | Web Push |
| `FIREBASE_SERVICE_ACCOUNT` | none (off) | Firebase service account key for push to the Android app ([mobile/README.md](../mobile/README.md)) |
| `CORS_ORIGINS` | none | Extra browser origins for the API |
| `WEB_DIST` | `<repo>/web/dist` | Built Dashboard |
| `LOG_LEVEL` | `info` | Log verbosity |
| `TRUST_PROXY` | `false`; `1` on Render (`RENDER` or `RENDER_EXTERNAL_URL` set) | Proxy headers: hop count, `false`, trusted ranges, or `true` (unsafe) |

`TRUST_PROXY` decides which `X-Forwarded-For`, `X-Forwarded-Proto` and `X-Forwarded-Host` headers the Server believes. The client IP feeds the login and pairing rate limits and the logs.

- **Unset**: `false`, except on Render, where the Server sees `RENDER` / `RENDER_EXTERNAL_URL` and uses `1`. It is never `true` by default.
- **A hop count** (`1` on Render): the Server trusts exactly that many proxies, so the client IP is the entry that many places from the right of `X-Forwarded-For`. With `1` it is the right-most entry, the one Render's proxy appended. A client can put anything in front of it (`X-Forwarded-For: 1.2.3.4` arrives as `1.2.3.4, <real IP>`), but cannot change it. Use a hop count only when that many proxies really sit in front: on a Server that faces the internet directly, hop 0 is the client itself and the "right-most entry" is whatever it sent. Fastify 5 itself treats a number as "trust nothing", so the Server turns the count into a trust function.
- **A list** of addresses or ranges (`loopback,linklocal,uniquelocal`, CIDRs): only those peers are proxies. The Docker Compose file uses this, because Caddy talks to the Server over the private Docker network.
- **`false`** or `0`: headers are ignored (the Server faces the internet directly, or local development).
- **`true`**: every proxy is trusted and the client IP is the left-most entry, which the client chose, so per-IP limits can be dodged by sending a new `X-Forwarded-For` each time. The Server logs a warning at startup when it is set. Do not use it on a public server.

To check the value on a deployment, compare the `ip` in the Server's `agent connected` log line with the phone's public IPv4 on the Dashboard (`net.public_ipv4`, when the phone uses IPv4). If every client shows the same address that belongs to the hosting provider, the chain has one more proxy hop: raise the count by one. Per-username lockout (5 failed sign-ins, 15 minutes) applies regardless of IP; concurrent attempts count as they start, so a burst gets at most 5 guesses. A browser that has signed in before sends its known-device token and is not affected by that username lock (only by its own 5 failures), so strangers guessing at `admin` cannot lock you out of a browser you already use; a new browser has to wait out the lock. Password re-entry inside a signed-in session (reboot, root shell, unpair, user management) has its own counter per session, so failed sign-ins by someone else never block it.

### 6.2 Endpoints

- `GET /healthz`: `{ ok, version, uptime_s, db }` (HTTP 503 if the database is unreachable).
- `GET /api/config`: public `{ version, telegram_configured, push_configured }`.
- REST API under `/api` (shared/src/api.ts), WebSockets at `/ws/agent` and `/ws/dashboard` (docs/protocol.md).
- Everything else serves the Dashboard; unknown client routes return `index.html`.

### 6.3 Recovery

- **Lost authenticator (or a lost phone that was signed in), another admin exists**: that admin opens **Settings > Users** and resets your TOTP, confirming with their own password (`PATCH /api/users/:id` with `{"reset_totp": true, "reauth_password": "…"}`). This also signs out all your sessions and deletes your push subscriptions, so the lost phone stops receiving alerts. Sign in again and enroll a new authenticator.
- **Lost authenticator, only admin**: clear it in the database, restart the Server (it caches users in memory), then sign in and enroll again:
  `turso db shell hsm "UPDATE users SET totp_secret = NULL, totp_last_step = NULL WHERE username = 'admin'"`
  (on a VPS: `sqlite3` on the `hsm.db` file in the `hsm-data` volume, with the Server stopped).
- **Forgotten password**: another admin sets a new one in **Settings > Users** (with their own password; this signs you out everywhere and deletes your push subscriptions). If nobody can sign in as admin, set `ADMIN_USERNAME` / `ADMIN_PASSWORD`, empty the users table (`DELETE FROM sessions; DELETE FROM push_subscriptions; DELETE FROM users;` through `turso db shell hsm` or `sqlite3`), and restart: with no users the Server creates the admin again. Recreate any viewer accounts afterwards.
- **Signing out** deletes that browser's push subscription on the Server. Signing out elsewhere, changing your password or having your sessions revoked also stops alerts to the signed-out devices; a browser that is still signed in registers its subscription again on its next load.
- **Phone lost or replaced**: **Settings > Devices > Unpair** revokes its token (the Agent receives close code 4001), then pair the new phone with a fresh code.
