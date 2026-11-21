# Hotspot Monitor

Remote monitoring and control for an outdoor hotspot phone: a rooted Android phone on a roof or pole
that shares its mobile data over USB tethering. The project follows [docs/plan.md](docs/plan.md).

It has three parts:

- **Agent** ([agent/](agent/)): Kotlin app on the rooted phone. It collects signal, network, power,
  heat and tethering data. It runs commands from the Dashboard, and its watchdog repairs a dead
  connection without help. It also includes the Signal Finder used to pick the best mounting spot.
- **Server** ([server/](server/)): Node.js (Fastify, WebSockets, libSQL). It relays live data,
  stores history, sends alerts (Telegram and Web Push), handles logins and pairs the phone.
- **Dashboard** ([web/](web/)): a React PWA for admins and viewers. It installs on a phone's home
  screen and receives push alerts.

The parts talk through the contract in [shared/src/](shared/src/), specified in
[docs/protocol.md](docs/protocol.md). The Kotlin Agent is tested against fixtures generated from
that contract.

## Repository layout

| Path | What it is |
|------|------------|
| `shared/` | TypeScript contract: telemetry, commands, settings, events, Signal Finder, WebSocket and REST types, generated fixtures |
| `server/` | Server: REST under `/api`, `/ws/agent`, `/ws/dashboard`, `/healthz`; also serves the built Dashboard |
| `web/` | Dashboard PWA |
| `agent/` | Android Agent (Gradle), Magisk keep-alive module (`agent/magisk/`), device profiles (`agent/app/src/main/assets/profiles/`) |
| `mobile/` | Hotspot Monitor phone app: the Dashboard as an Android app (Trusted Web Activity), see [mobile/README.md](mobile/README.md) |
| `tools/agent-sim/` | Simulated phone that speaks the full Agent protocol, for development and demos without hardware |
| `tools/phase0/` | adb scripts for the Phase 0 hardware tests on a candidate phone |
| `deploy/` | Dockerfile, Docker Compose + Caddy (VPS option), `.env.example` |
| `render.yaml` | Render Blueprint (free web service) |
| `docs/` | Plan, protocol, deployment, install, Phase 0 checklist and device profiles |

## Quick start (no phone needed)

Requires Node 22 (or 20.19+).

```sh
npm install
ADMIN_USERNAME=admin ADMIN_PASSWORD='choose-a-long-password' npm run dev:server   # http://localhost:8080
npm run dev:web                                                                   # http://localhost:5173
```

1. Sign in to the Dashboard as `admin`. Right after the first sign-in it asks you to set up an
   authenticator app (TOTP): every account, admin or viewer, must enroll before it can see or
   change anything (plan 10).
2. Open **Settings > Pairing and phones** to get a pairing code.
3. Start the simulated phone with that code:

   ```sh
   npm run sim -- --code ABCD2345
   npm run sim -- --scenario internet-loss --speed 20   # later runs reuse the pairing
   ```

See [tools/agent-sim/README.md](tools/agent-sim/README.md) for scenarios and options.

## Checks

```sh
npm run typecheck      # all TypeScript workspaces
npm test               # shared, server, web, simulator unit and integration tests
npm run build          # Dashboard, then Server (server/dist)
npm run e2e            # built Server + simulator + headless Chrome Dashboard (tools/e2e/README.md; --no-ui skips Chrome)
cd agent && ./gradlew assembleDebug testDebugUnitTest lintDebug   # Android (JDK 17 + Android SDK 35)
```

## Deploying

The default is free hosting:

- One **Render** free web service runs the Server and also serves the Dashboard.
- **Turso** (libSQL) free tier holds the database, because Render's free tier has no persistent disk.
- The phone's 30-second heartbeat keeps the free instance awake.

[docs/deploy.md](docs/deploy.md) covers:

- the Render + Turso setup and free-tier limits;
- hosting the Dashboard on Vercel, which is optional. The Server can't run there, because Vercel
  functions cap WebSocket duration;
- a VPS with Docker Compose and Caddy.

## Setting up the phone

1. Run the Phase 0 tests on the candidate phone before relying on it:
   [docs/phase0.md](docs/phase0.md), using `tools/phase0/phase0.sh`. They cover root after a cold
   boot, the charge-control path, the current sign, signal parsing, thermal zones and automatic
   power-on.
2. Install, root and pair: [docs/install.md](docs/install.md).
3. Device profiles: [docs/device-profiles.md](docs/device-profiles.md). The shipped profiles are a
   generic one and Samsung Galaxy S.
