# Dashboard (PWA)

The web app from plan section 9: Overview, Signal, Network, Power and heat, Controls, System, Signal Finder,
Messages, Alerts and Settings. It runs in any browser and installs on the monitoring phone's home screen.
React 18 + TypeScript + Vite, Tailwind CSS 4, zustand, uPlot, vite-plugin-pwa (injectManifest, `src/sw.ts`).

## Develop

```sh
npm install            # once, at the repo root (npm workspaces)
npm run dev:server     # the Server on http://localhost:8080
npm run dev -w web     # this app on http://localhost:5173
```

The Vite dev server proxies `/api` and `/ws` (WebSocket) to `http://localhost:8080`, so the app talks to the
Server at the same origin. The service worker is disabled in dev; push notifications and the offline shell need
the built app (`npm run build -w web && npm run preview -w web`, or the Server serving `web/dist`).

```sh
npm run typecheck -w web   # tsc --noEmit
npm test -w web            # vitest (jsdom)
npm run build -w web       # typecheck + production build into web/dist (sw.js, manifest.webmanifest, icons)
npm run icons -w web       # regenerate public/icons/*.png and public/favicon.svg (no image dependencies)
```

## Configuration

| Variable | Default | Meaning |
|----------|---------|---------|
| `VITE_API_BASE` | empty | Server base URL. Empty = same origin (the Server serves `web/dist`, or the dev proxy). Set it only when the Dashboard is hosted elsewhere, e.g. `https://hsm.onrender.com`. The WebSocket URL is derived from it (`https` -> `wss`). |

Sessions use a bearer token in `localStorage` (no cookies), so a Dashboard on another origin works as long as the
Server allows that origin in `CORS_ORIGINS`.

## Deploy

**Same origin (default).** `npm run build` at the repo root builds this app; the Server serves `web/dist` (see
`WEB_DIST`) next to `/api` and the sockets. Nothing else to configure.

**Separately on Vercel (optional).** Create a Vercel project with Root Directory `web` (keep "Include files outside
the root directory" on, because `@hsm/shared` lives in the workspace). `web/vercel.json` installs from the repo
root, builds, serves `dist`, and rewrites every route to `index.html`. Set `VITE_API_BASE` to the Server URL in the
Vercel project, and add the Vercel URL to the Server's `CORS_ORIGINS`.

## Install on the monitoring phone

Open the Dashboard URL in Chrome (Android) or Safari (iPhone) and use "Install app" / "Add to Home Screen".
Then turn on push alerts under **Alerts**. iPhones only deliver web push to the home-screen app.
When a new version is deployed, the app shows "A new version is available" with a Reload button.

## Layout

```
src/
  lib/          api client, formatting, grades, settings schema/validation, control visibility, push, CSV
  store/        zustand stores: session, realtime data (pure reducer in reducer.ts), socket, ui, toasts
  components/   layout (shell, nav, banners), charts (uPlot), command runner and control cards, UI primitives
  screens/      one file per screen
  sw.ts         service worker: precache, push, notification click, update
test/           vitest suites (fixtures from shared/fixtures)
```

Viewer accounts see everything except Controls, the root console and Messages (plan 9). Admins without TOTP are
guided to enrol before any control or settings change. Controls the phone reports as unsupported in its capability
map are hidden; controls not yet probed are shown.
