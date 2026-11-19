#!/usr/bin/env node
/**
 * End-to-end run of the real pieces together: the BUILT Server (node server/dist/index.js) serving
 * the built Dashboard (web/dist), the Agent simulator (tools/agent-sim) as a child process, a
 * Dashboard WebSocket client, and headless Chrome driving the Dashboard UI over raw CDP.
 *
 *   npm run build && npm run e2e
 *   node tools/e2e/run.mjs [--out <dir>] [--no-ui] [--chrome <path>] [--speed <x>] [--only <id,...>]
 *
 * See tools/e2e/README.md for what each scenario covers.
 */
import { randomBytes } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync, renameSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Api, expectStatus } from './lib/api.mjs';
import { Cdp, findChrome, launchChrome, Page } from './lib/cdp.mjs';
import { DashClient } from './lib/dash.mjs';
import { installExitHandlers, Procs } from './lib/procs.mjs';
import { AssertionError, Authenticator, check, eq, freePort, short, sleep, waitFor } from './lib/util.mjs';

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const SERVER_ENTRY = join(REPO, 'server', 'dist', 'index.js');
const WEB_INDEX = join(REPO, 'web', 'dist', 'index.html');
const SIM_ENTRY = join(REPO, 'tools', 'agent-sim', 'src', 'index.ts');

// --- arguments ---------------------------------------------------------------------------------

function parseArgs(argv) {
  const o = { out: null, ui: true, chrome: null, speed: 10, only: null, help: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const val = () => {
      const v = argv[++i];
      if (v === undefined) throw new Error(`${a} needs a value`);
      return v;
    };
    if (a === '--out') o.out = val();
    else if (a === '--no-ui') o.ui = false;
    else if (a === '--chrome') o.chrome = val();
    else if (a === '--speed') o.speed = Number(val());
    else if (a === '--only') o.only = val().split(',').map((s) => s.trim()).filter(Boolean);
    else if (a === '--help' || a === '-h') o.help = true;
    else throw new Error(`unknown option ${a}`);
  }
  if (!(o.speed > 0)) throw new Error('--speed must be a positive number');
  return o;
}

let args;
try {
  args = parseArgs(process.argv.slice(2));
} catch (err) {
  process.stderr.write(`${err.message}\n`);
  process.exit(2);
}
if (args.help) {
  process.stdout.write(
    'Usage: node tools/e2e/run.mjs [--out <dir>] [--no-ui] [--chrome <path>] [--speed <x>] [--only <id,...>]\n' +
      '  Expects `npm run build` first. Artifacts (logs, screenshots, report.json) go to --out\n' +
      '  (default: $E2E_OUT or <tmp>/hsm-e2e-<time>). E2E_VERBOSE=1 echoes child output.\n',
  );
  process.exit(0);
}

for (const [file, what] of [
  [SERVER_ENTRY, 'the Server build (server/dist/index.js)'],
  [WEB_INDEX, 'the Dashboard build (web/dist/index.html)'],
]) {
  if (!existsSync(file)) {
    process.stderr.write(`Missing ${what}. Run "npm run build" first.\n`);
    process.exit(2);
  }
}

const stamp = new Date().toISOString().replace(/[:.]/g, '-');
const OUT = resolve(args.out ?? process.env.E2E_OUT ?? join(tmpdir(), `hsm-e2e-${stamp}`));
const LOGS = join(OUT, 'logs');
const SHOTS = join(OUT, 'screens');
const WORK = join(OUT, 'work');
rmSync(LOGS, { recursive: true, force: true });
rmSync(SHOTS, { recursive: true, force: true });
rmSync(WORK, { recursive: true, force: true });
for (const d of [LOGS, SHOTS, WORK]) mkdirSync(d, { recursive: true });

const procs = new Procs(LOGS);
installExitHandlers(procs, () => procs.stopAll());

// --- shared state ------------------------------------------------------------------------------

const ADMIN = { username: 'admin', password: `e2e-admin-${randomBytes(9).toString('base64url')}` };
const VIEWER = { username: 'e2e-viewer', password: `e2e-viewer-${randomBytes(9).toString('base64url')}` };
const SIM_STATE = join(WORK, 'sim-state.json');

const S = {
  base: null,
  wsBase: null,
  api: null,
  server: null,
  token: null, // admin session (with TOTP)
  auth: null, // Authenticator for the admin
  viewerToken: null,
  deviceId: null,
  pairCode: null,
  dash: null, // admin Dashboard socket
  sim: null,
  spots: {},
  offline: null, // facts from the offline scenario for the dedup scenario
};

const log = (...parts) => process.stdout.write(`${new Date().toISOString().slice(11, 19)} ${parts.join(' ')}\n`);

// --- runner ------------------------------------------------------------------------------------

const results = [];
let blockedBy = null;

/**
 * Runs one scenario. `required` scenarios block everything after them when they fail (the Server,
 * login, pairing). Notes returned by `fn` (string or array) are recorded with the result.
 */
async function scenario(id, title, fn, { required = false } = {}) {
  if (args.only && !args.only.includes(id) && !required) {
    results.push({ id, title, passed: null, skipped: true, notes: 'not selected (--only)' });
    return;
  }
  if (blockedBy) {
    results.push({ id, title, passed: false, skipped: true, notes: `not run: blocked by failed scenario "${blockedBy}"` });
    log('SKIP', id, `(blocked by ${blockedBy})`);
    return;
  }
  log('....', id, '-', title);
  const t0 = Date.now();
  const notes = [];
  try {
    const r = await fn(notes);
    if (typeof r === 'string') notes.push(r);
    else if (Array.isArray(r)) notes.push(...r);
    const ms = Date.now() - t0;
    results.push({ id, title, passed: true, ms, notes: notes.join('; ') });
    log('PASS', id, `(${(ms / 1000).toFixed(1)} s)`, notes.length ? `- ${short(notes.join('; '), 400)}` : '');
  } catch (err) {
    const ms = Date.now() - t0;
    const msg = err instanceof AssertionError ? err.message : (err?.stack ?? String(err));
    results.push({ id, title, passed: false, ms, notes: [...notes, `FAILED: ${msg}`].join('; ') });
    log('FAIL', id, `(${(ms / 1000).toFixed(1)} s) - ${msg}`);
    if (required) blockedBy = id;
  }
}

// --- helpers -----------------------------------------------------------------------------------

const api = () => S.api;
const adminOpts = () => ({ token: S.token });
const iso = (ms) => new Date(ms).toISOString();

function serverEnv(port) {
  // Every variable the Server reads is set explicitly, so a developer's .env (loaded by
  // server/src/index.ts for unset variables only) cannot point the run at real Telegram, Web Push
  // or a Turso database.
  return {
    ...process.env,
    NODE_ENV: 'production',
    PORT: String(port),
    HOST: '127.0.0.1',
    DATABASE_URL: `file:${join(WORK, 'e2e.db')}`,
    DATABASE_AUTH_TOKEN: '',
    ADMIN_USERNAME: ADMIN.username,
    ADMIN_PASSWORD: ADMIN.password,
    LOG_LEVEL: 'warn',
    PUBLIC_URL: '',
    RENDER_EXTERNAL_URL: '',
    TELEGRAM_BOT_TOKEN: '',
    TELEGRAM_CHAT_ID: '',
    VAPID_PUBLIC_KEY: '',
    VAPID_PRIVATE_KEY: '',
    VAPID_SUBJECT: '',
    CORS_ORIGINS: '',
    WEB_DIST: '',
    TRUST_PROXY: '',
    SESSION_TTL_DAYS: '',
  };
}

function startSim(extra = []) {
  const simArgs = [
    '--import',
    'tsx',
    SIM_ENTRY,
    '--server',
    S.base,
    '--state',
    SIM_STATE,
    '--speed',
    String(args.speed),
    '--no-color',
    '--verbose',
    '--persist-outbox',
    ...extra,
  ];
  const env = { ...process.env, NO_COLOR: '1' };
  delete env.FORCE_COLOR; // Node warns when both are set
  S.sim = procs.spawn('sim', process.execPath, simArgs, { cwd: REPO, env });
  return S.sim;
}

async function stopSim() {
  const sim = S.sim;
  if (!sim) return null;
  const res = await procs.stop(sim, { timeout: 8000 });
  S.sim = null;
  return res;
}

/** Waits for the simulator to print its `hello` line (connected and past the handshake). */
async function simHello(sim, since = 0, timeout = 30_000) {
  const deadline = Date.now() + timeout;
  for (;;) {
    const idx = sim.text.indexOf(' hello ', since);
    if (idx >= 0) return idx;
    if (sim.exit) throw new Error(`simulator exited (${JSON.stringify(sim.exit)}): ${procs.tail(sim, 15)}`);
    if (Date.now() > deadline) throw new AssertionError(`simulator did not connect within ${timeout / 1000} s; see ${sim.logFile}`);
    await sleep(100);
  }
}

function readSimState() {
  return JSON.parse(readFileSync(SIM_STATE, 'utf8'));
}

function writeSimState(state) {
  const tmp = `${SIM_STATE}.e2e-tmp`;
  writeFileSync(tmp, JSON.stringify(state, null, 2) + '\n', { mode: 0o600 });
  renameSync(tmp, SIM_STATE);
}

async function sendCommand(name, cmdArgs = {}, extra = {}) {
  return api().post('/api/commands', { device_id: S.deviceId, name, args: cmdArgs, ...extra }, adminOpts());
}

const TERMINAL = ['done', 'failed', 'rejected', 'expired', 'kept', 'reverted'];

/** Statuses the admin Dashboard socket has seen for a command, in order. */
function seenStatuses(id) {
  return S.dash.filter((m) => m.t === 'command' && m.command?.id === id).map((e) => e.msg.command.status);
}

/**
 * Waits until the command reaches one of `want`. Uses the Dashboard socket's `command` broadcasts
 * and GET /api/commands. Fails early when it reaches a different final status.
 */
async function waitCommand(id, want, timeout = 20_000) {
  const wants = Array.isArray(want) ? want : [want];
  const deadline = Date.now() + timeout;
  let last = null;
  for (;;) {
    const ws = S.dash.filter((m) => m.t === 'command' && m.command?.id === id).map((e) => e.msg.command);
    const hit = ws.find((c) => wants.includes(c.status));
    if (hit) return hit;
    const r = await api().get('/api/commands?limit=200', adminOpts());
    const rec = Array.isArray(r.body) ? r.body.find((c) => c.id === id) : null;
    if (rec) last = rec;
    if (rec && wants.includes(rec.status)) return rec;
    // A wanted intermediate status (revert_armed) would already be in the socket's history.
    if (rec && TERMINAL.includes(rec.status) && !wants.includes(rec.status)) {
      throw new AssertionError(`command ${id} ended as ${rec.status} (wanted ${wants.join('|')}): ${short(rec.error ?? rec.output)}`);
    }
    if (Date.now() > deadline) {
      throw new AssertionError(`command ${id} did not reach ${wants.join('|')} within ${timeout / 1000} s (now ${last?.status ?? 'unknown'}, seen ${seenStatuses(id).join(' > ') || 'none'})`);
    }
    await sleep(250);
  }
}

async function runCommand(name, cmdArgs = {}, { want = 'done', extra = {}, timeout = 20_000 } = {}) {
  const r = await sendCommand(name, cmdArgs, extra);
  const rec = expectStatus(r, 201, `POST /api/commands ${name}`);
  return waitCommand(rec.id, want, timeout);
}

async function alerts(token = S.token, query = '') {
  const r = await api().get(`/api/alerts?limit=1000${query}`, { token });
  return expectStatus(r, 200, 'GET /api/alerts');
}

async function events(token = S.token, query = '') {
  const r = await api().get(`/api/events?device_id=${S.deviceId}&limit=1000${query}`, { token });
  return expectStatus(r, 200, 'GET /api/events');
}

async function history(metric, from, to, res = 'raw') {
  const q = new URLSearchParams({ device_id: S.deviceId, metric, res });
  if (from != null) q.set('from', iso(from));
  if (to != null) q.set('to', iso(to));
  const r = await api().get(`/api/telemetry?${q}`, adminOpts());
  return expectStatus(r, 200, `GET /api/telemetry ${metric}`);
}

async function putSettings(patch) {
  const r = await api().put('/api/settings', { device_id: S.deviceId, settings: patch }, adminOpts());
  return expectStatus(r, 200, 'PUT /api/settings');
}

/** The most recent radio.rsrp values the admin socket received (live telemetry and snapshots). */
function seenRsrp() {
  const vals = [];
  for (const e of S.dash.msgs) {
    const m = e.msg;
    if (m.t === 'telemetry' && m.radio && typeof m.radio.rsrp === 'number') vals.push(m.radio.rsrp);
    if (m.t === 'snapshot' && typeof m.device?.state?.radio?.rsrp === 'number') vals.push(m.device.state.radio.rsrp);
  }
  return vals;
}

// =================================================================================================
// Scenarios
// =================================================================================================

async function main() {
  log(`artifacts: ${OUT}`);

  await scenario(
    'server',
    'Built Server starts with web/dist; /healthz, SPA with CSP, JSON 404',
    async (notes) => {
      const port = await freePort();
      S.base = `http://127.0.0.1:${port}`;
      S.wsBase = `ws://127.0.0.1:${port}`;
      S.api = new Api(S.base);
      S.server = procs.spawn('server', process.execPath, [SERVER_ENTRY], { cwd: WORK, env: serverEnv(port) });
      const health = await waitFor(
        async () => {
          if (S.server.exit) throw new Error(`server exited: ${procs.tail(S.server)}`);
          const r = await fetch(`${S.base}/healthz`).catch(() => null);
          return r && r.ok ? r.json() : null;
        },
        { timeout: 30_000, label: '/healthz', interval: 200 },
      );
      eq(health.ok, true, 'healthz.ok');
      eq(health.db, 'ok', 'healthz.db');
      const index = await fetch(`${S.base}/`);
      eq(index.status, 200, 'GET / status');
      const html = await index.text();
      check(html.includes('<div id="root">'), 'GET / does not serve the Dashboard index.html');
      const csp = index.headers.get('content-security-policy') ?? '';
      check(csp.includes("script-src 'self'") && csp.includes('connect-src'), `SPA CSP missing or weak: ${csp}`);
      const deep = await fetch(`${S.base}/settings?tab=users`);
      eq(deep.status, 200, 'SPA fallback for /settings');
      check((await deep.text()).includes('<div id="root">'), 'SPA fallback did not return index.html');
      const nf = await api().get('/api/nope');
      eq(nf.status, 404, 'unknown API route status');
      eq(nf.body?.error, 'not_found', 'unknown API route error code');
      const cfg = expectStatus(await api().get('/api/config'), 200, 'GET /api/config');
      eq(cfg.telegram_configured, false, 'telegram must be off in the e2e run');
      // Web Push is always available (the Server generates and stores a VAPID pair when none is
      // configured); nothing is sent because no browser subscribes in this run.
      eq(typeof cfg.push_configured, 'boolean', 'push_configured');
      notes.push(`listening on ${S.base}, version ${health.version}`);
    },
    { required: true },
  );

  await scenario(
    'auth-totp',
    'Admin without TOTP is read-only; TOTP enrolled via the API; login with the TOTP step; replayed code refused',
    async (notes) => {
      const login1 = expectStatus(await api().post('/api/auth/login', ADMIN), 200, 'first login');
      eq(login1.totp_required, false, 'first login totp_required');
      const t1 = login1.token;
      const me1 = expectStatus(await api().get('/api/auth/me', { token: t1 }), 200, 'GET /api/auth/me');
      eq(me1.totp_enrollment_required, true, 'me.totp_enrollment_required before enrolment');
      const blocked = await api().post('/api/pair/codes', { name: 'x' }, { token: t1 });
      eq(blocked.status, 403, 'pair code without TOTP: status');
      eq(blocked.body?.error, 'totp_enrollment_required', 'pair code without TOTP: error');
      const reads = await api().get('/api/devices', { token: t1 });
      eq(reads.status, 200, 'admin without TOTP can still read');

      const setup = expectStatus(await api().post('/api/auth/totp/setup', undefined, { token: t1 }), 200, 'TOTP setup');
      check(/^[A-Z2-7]{16,}$/.test(setup.secret), `TOTP secret is not base32: ${setup.secret}`);
      check(setup.otpauth_url.startsWith('otpauth://totp/'), 'otpauth_url');
      S.auth = new Authenticator(setup.secret);
      expectStatus(await api().post('/api/auth/totp/enable', { code: await S.auth.nextCode() }, { token: t1 }), 200, 'TOTP enable');
      const me2 = expectStatus(await api().get('/api/auth/me', { token: t1 }), 200, 'me after enable');
      eq(me2.totp_enrollment_required, false, 'me.totp_enrollment_required after enable');
      eq(me2.user.totp_enabled, true, 'me.user.totp_enabled after enable');

      const login2 = expectStatus(await api().post('/api/auth/login', ADMIN), 200, 'second login');
      eq(login2.totp_required, true, 'second login asks for TOTP');
      check(typeof login2.pending_token === 'string' && !('token' in login2), 'pending login must not carry a session token');
      const wrong = await api().post('/api/auth/totp', { pending_token: login2.pending_token, code: '000000' });
      eq(wrong.status, 401, 'wrong TOTP code status');
      const code = await S.auth.nextCode();
      const sess = expectStatus(await api().post('/api/auth/totp', { pending_token: login2.pending_token, code }), 200, 'TOTP login');
      check(typeof sess.token === 'string' && sess.token.length > 20, 'session token');
      eq(sess.user.totp_enabled, true, 'session user.totp_enabled');
      S.token = sess.token;

      // Replay protection: the same code cannot be used for another sign-in.
      const login3 = expectStatus(await api().post('/api/auth/login', ADMIN), 200, 'third login');
      const replay = await api().post('/api/auth/totp', { pending_token: login3.pending_token, code });
      eq(replay.status, 401, 'replayed TOTP code status');
      notes.push('TOTP enrolled; login needs password + fresh code; a used code is refused');
    },
    { required: true },
  );

  await scenario(
    'pair-code',
    'POST /api/pair/codes returns a code, deep link and the quoted adb command with --ez auto true',
    async (notes) => {
      const r = expectStatus(await api().post('/api/pair/codes', { name: 'E2E phone' }, adminOpts()), 201, 'POST /api/pair/codes');
      check(/^[A-Z2-9]{8}$/.test(r.code), `pairing code format: ${r.code}`);
      const ttl = Date.parse(r.expires_at) - Date.now();
      check(ttl > 14 * 60_000 && ttl <= 15 * 60_000 + 5000, `pairing code TTL ${Math.round(ttl / 1000)} s, expected 15 min`);
      eq(r.deep_link, `hsm://pair?server=${encodeURIComponent(S.base)}&code=${r.code}`, 'deep_link');
      eq(r.adb_command, `adb shell "am start -a android.intent.action.VIEW -d '${r.deep_link}' --ez auto true"`, 'adb_command');
      S.pairCode = r.code;
      notes.push(`adb_command: ${r.adb_command}`);
    },
    { required: true },
  );

  await scenario(
    'sim-pair-live',
    'Simulator pairs with the code; Dashboard socket gets status, snapshot and live telemetry',
    async (notes) => {
      S.dash = new DashClient(`${S.wsBase}/ws/dashboard`, 'admin socket');
      const ok = await S.dash.connect(S.token);
      eq(ok.user.role, 'admin', 'auth_ok user role');
      S.dash.subscribe(null, false);

      const bogus = new DashClient(`${S.wsBase}/ws/dashboard`, 'bogus socket');
      const bogusErr = await bogus.connect('not-a-session').then(
        () => null,
        (e) => e,
      );
      check(bogusErr && /auth failed/.test(bogusErr.message), 'a bad session token must get auth_failed');
      await waitFor(() => bogus.closed, { timeout: 3000, label: 'bogus socket close' });
      eq(bogus.closed.code, 4001, 'close code for a bad dashboard token');

      const sim = startSim(['--code', S.pairCode, '--keep-link']);
      const m = await procs.waitForOutput(sim, /paired as (d_[A-Za-z0-9_-]+)/, 30_000);
      S.deviceId = m[1];
      await simHello(sim);
      await S.dash.waitFor((x) => x.t === 'status' && x.device_id === S.deviceId && x.online === true, { timeout: 15_000, label: 'status online' });

      const devs = expectStatus(await api().get('/api/devices', adminOpts()), 200, 'GET /api/devices');
      eq(devs.length, 1, 'paired devices');
      eq(devs[0].name, 'E2E phone', 'device name from the pairing code');
      eq(devs[0].online, true, 'device online');
      eq(devs[0].info?.model, 'SM-G991B', 'device info model');

      const reuse = await api().post('/api/pair', { code: S.pairCode, device: devs[0].info });
      check(reuse.status >= 400 && reuse.status < 500, `a used pairing code must be refused, got ${reuse.status}`);

      const mark = S.dash.mark();
      S.dash.subscribe(S.deviceId, false);
      const snap = await S.dash.waitFor((x) => x.t === 'snapshot' && x.device?.device_id === S.deviceId, { since: mark, label: 'snapshot' });
      eq(snap.device.online, true, 'snapshot online');
      check(snap.device.connected_since, 'snapshot connected_since');
      const tel = await S.dash.waitFor((x) => x.t === 'telemetry' && x.device_id === S.deviceId && x.tier === 'fast' && x.radio, {
        since: mark,
        timeout: 15_000,
        label: 'live fast telemetry',
      });
      check(typeof tel.radio.rsrp === 'number' && tel.radio.rsrp < -40 && tel.radio.rsrp > -140, `implausible RSRP ${tel.radio.rsrp}`);
      const dev = expectStatus(await api().get(`/api/device?device_id=${S.deviceId}`, adminOpts()), 200, 'GET /api/device');
      check(dev.state.radio && dev.state.power && dev.state.device, 'snapshot state has radio, power (slow) and device (static) groups');
      notes.push(`device ${S.deviceId}; live RSRP ${tel.radio.rsrp} dBm, score ${tel.radio.score}`);
    },
    { required: true },
  );

  await scenario('settings-push', 'PUT /api/settings bumps the version; the simulator applies it (event, state file, faster telemetry)', async (notes) => {
    const before = expectStatus(await api().get(`/api/settings?device_id=${S.deviceId}`, adminOpts()), 200, 'GET /api/settings');
    const mark = S.dash.mark();
    const res = await putSettings({
      telemetry: { fast_active_s: 2, heartbeat_s: 10 },
      commands: { revert_after_s: 30, expiry_s: 10 },
      alerts: { cooldown_min: 0 },
    });
    eq(res.version, before.version + 1, 'settings version after PUT');
    eq(res.settings.telemetry.fast_active_s, 2, 'merged fast_active_s');
    eq(res.settings.telemetry.slow_s, before.settings.telemetry.slow_s, 'deep merge keeps untouched keys');
    await S.dash.waitFor((x) => x.t === 'settings' && x.version === res.version, { since: mark, label: 'settings broadcast' });
    const applied = await S.dash.waitFor((x) => x.t === 'event' && x.event?.kind === 'settings_applied' && x.event.data?.version === res.version, {
      since: mark,
      timeout: 15_000,
      label: `settings_applied v${res.version} event from the simulator`,
    });
    const st = await waitFor(
      () => {
        const s = readSimState();
        return s.settings_version === res.version && s.settings?.commands?.revert_after_s === 30 ? s : null;
      },
      { timeout: 5000, label: 'simulator state file with the new settings' },
    );
    // The simulator's fast tier follows fast_active_s = 2 s while this Dashboard watches.
    const appliedIdx = S.dash.msgs.findIndex((e) => e.msg === applied);
    await S.dash.waitFor(() => S.dash.filter((x) => x.t === 'telemetry' && x.tier === 'fast', appliedIdx).length >= 4, {
      since: appliedIdx,
      timeout: 15_000,
      label: '4 fast telemetry messages',
    });
    const ats = S.dash.filter((x) => x.t === 'telemetry' && x.tier === 'fast', appliedIdx).map((e) => e.at);
    const gaps = ats.slice(1).map((t, i) => t - ats[i]);
    const median = gaps.sort((a, b) => a - b)[Math.floor(gaps.length / 2)];
    check(median > 1000 && median < 3500, `fast telemetry interval ${median} ms, expected about 2000 ms`);
    const bad = await api().put('/api/settings', { device_id: S.deviceId, settings: { telemetry: { heartbeat_s: 0 } } }, adminOpts());
    eq(bad.status, 400, 'invalid settings status');
    const after = expectStatus(await api().get(`/api/settings?device_id=${S.deviceId}`, adminOpts()), 200, 'GET /api/settings');
    eq(after.version, res.version, 'an invalid PUT must not bump the version');
    notes.push(`v${before.version} -> v${res.version}; simulator state settings_version=${st.settings_version}; fast interval ~${median} ms`);
    notes.push("heartbeat.settings_version itself is not exposed by any API; checked via the simulator's settings_applied event and state file");
  });

  await scenario('finder-mirror', 'subscribe finder:true turns the 1 s finder_live mirror on; finder:false turns it off', async (notes) => {
    const mark = S.dash.mark();
    S.dash.subscribe(S.deviceId, true);
    const first = await S.dash.waitFor((x) => x.t === 'finder_live' && x.device_id === S.deviceId, { since: mark, timeout: 10_000, label: 'finder_live' });
    check('score' in first.live && 'rsrp' in first.live, `finder_live shape: ${short(first.live)}`);
    await sleep(3500);
    const n = S.dash.filter((x) => x.t === 'finder_live', mark).length;
    check(n >= 3, `expected about 1 finder_live per second, got ${n} in ~4 s`);
    S.dash.subscribe(S.deviceId, false);
    await sleep(2500);
    const quiet = S.dash.mark();
    await sleep(3000);
    const late = S.dash.filter((x) => x.t === 'finder_live', quiet).length;
    eq(late, 0, 'finder_live messages after finder:false');
    notes.push(`${n} finder_live messages in ~4 s, none after unsubscribe`);
  });

  await scenario('cmd-refresh', 'refresh -> done', async (notes) => {
    const mark = S.dash.mark();
    const rec = await runCommand('refresh');
    check(/static/.test(rec.output ?? ''), `refresh output: ${rec.output}`);
    await S.dash.waitFor((x) => x.t === 'telemetry' && x.tier === 'static', { since: mark, label: 'static telemetry after refresh' });
    const seen = seenStatuses(rec.id);
    check(seen.includes('queued') || seen.includes('sent'), `status trail ${seen.join('>')}`);
    notes.push(`status trail ${seen.join(' > ')}`);
  });

  await scenario('cmd-mobile-data-kept', 'mobile_data off -> revert_armed (agent_link) -> kept by the probe round trip', async (notes) => {
    const mark = S.dash.mark();
    const r = expectStatus(await sendCommand('mobile_data', { enabled: false }), 201, 'POST mobile_data');
    eq(r.revert_confirm, 'agent_link', 'revert_confirm');
    const armed = await waitCommand(r.id, 'revert_armed', 15_000);
    const deadline = Date.parse(armed.revert_deadline);
    check(deadline - Date.now() > 10_000 && deadline - Date.now() <= 31_000, `revert_deadline ${armed.revert_deadline} should be ~30 s away`);
    const kept = await waitCommand(r.id, 'kept', 40_000);
    check(Date.now() < deadline, 'kept only after the deadline');
    const offline = S.dash.find((x) => x.t === 'status' && x.device_id === S.deviceId && x.online === false, mark);
    check(!offline, 'the device must stay connected with --keep-link');
    await runCommand('mobile_data', { enabled: true });
    notes.push(`kept ${Math.round((Date.now() - (deadline - 30_000)) / 1000)} s after arming; trail ${seenStatuses(r.id).join(' > ')}; ${kept.error ?? ''}`.trim());
  });

  await scenario('cmd-usb-keep', 'usb_tethering off -> revert_armed (dashboard) -> POST /keep -> kept', async (notes) => {
    const r = expectStatus(await sendCommand('usb_tethering', { enabled: false }), 201, 'POST usb_tethering');
    eq(r.revert_confirm, 'dashboard', 'revert_confirm');
    await waitCommand(r.id, 'revert_armed', 15_000);
    const snap = expectStatus(await api().get(`/api/device?device_id=${S.deviceId}`, adminOpts()), 200, 'GET /api/device');
    check(snap.pending_reverts.some((c) => c.id === r.id), 'snapshot.pending_reverts lists the armed command');
    const keep = await api().post(`/api/commands/${r.id}/keep`, undefined, adminOpts());
    eq(keep.status, 200, 'POST /keep status');
    await waitCommand(r.id, 'kept', 15_000);
    const again = await api().post(`/api/commands/${r.id}/keep`, undefined, adminOpts());
    eq(again.status, 409, 'second keep status');
    const missing = await api().post('/api/commands/c_doesnotexist/keep', undefined, adminOpts());
    eq(missing.status, 404, 'keep of an unknown command');
    await runCommand('usb_tethering', { enabled: true });
    notes.push(`trail ${seenStatuses(r.id).join(' > ')}; second keep 409, unknown id 404`);
  });

  // The 30 s revert wait runs while the Finder, SMS and viewer scenarios run.
  let revertWait = null;
  await scenario('cmd-usb-revert-armed', 'usb_tethering off without keep: armed (the revert itself is checked later)', async (notes) => {
    const r = expectStatus(await sendCommand('usb_tethering', { enabled: false }), 201, 'POST usb_tethering');
    const armed = await waitCommand(r.id, 'revert_armed', 15_000);
    revertWait = { id: r.id, deadline: Date.parse(armed.revert_deadline), mark: S.dash.mark() };
    notes.push(`armed until ${armed.revert_deadline}`);
  });

  await scenario('finder-spots', 'finder_spot_test -> spot listed; set baseline (confirm); delete spot (confirm)', async (notes) => {
    const mark = S.dash.mark();
    const a = await runCommand('finder_spot_test', { name: 'E2E roof east', orientation: 'east', duration_s: 5 }, { timeout: 30_000 });
    const b = await runCommand('finder_spot_test', { name: 'E2E roof west', orientation: 'west', duration_s: 5 }, { timeout: 30_000 });
    const list = await waitFor(
      async () => {
        const r = expectStatus(await api().get(`/api/finder/spots?device_id=${S.deviceId}`, adminOpts()), 200, 'GET /api/finder/spots');
        const ea = r.spots.find((s) => s.name === 'E2E roof east');
        const wb = r.spots.find((s) => s.name === 'E2E roof west');
        return ea && wb ? { r, ea, wb } : null;
      },
      { timeout: 10_000, label: 'both spots in GET /api/finder/spots' },
    );
    check(typeof list.ea.metrics?.score?.avg === 'number', `spot metrics: ${short(list.ea.metrics)}`);
    await S.dash.waitFor((x) => x.t === 'finder_spot' && x.spot?.id === list.ea.id, { since: mark, label: 'finder_spot broadcast' });
    S.spots = { east: list.ea, west: list.wb };

    const noConfirm = await sendCommand('finder_set_baseline', { spot_id: list.ea.id });
    eq(noConfirm.status, 400, 'set baseline without confirmed');
    eq(noConfirm.body?.error, 'confirmation_required', 'set baseline without confirmed: error');
    await runCommand('finder_set_baseline', { spot_id: list.ea.id }, { extra: { confirmed: true } });
    await waitFor(
      async () => {
        const r = expectStatus(await api().get(`/api/finder/spots?device_id=${S.deviceId}`, adminOpts()), 200, 'spots');
        return r.baseline_id === list.ea.id;
      },
      { timeout: 10_000, label: 'baseline_id = east spot' },
    );
    const dev = expectStatus(await api().get(`/api/device?device_id=${S.deviceId}`, adminOpts()), 200, 'device');
    eq(dev.baseline?.id, list.ea.id, 'snapshot baseline');

    const delMark = S.dash.mark();
    await runCommand('finder_delete_spot', { spot_id: list.wb.id }, { extra: { confirmed: true } });
    await S.dash.waitFor((x) => x.t === 'finder_spot_deleted' && x.spot_id === list.wb.id, { since: delMark, label: 'finder_spot_deleted' });
    const after = expectStatus(await api().get(`/api/finder/spots?device_id=${S.deviceId}`, adminOpts()), 200, 'spots');
    check(!after.spots.some((s) => s.id === list.wb.id), 'deleted spot still listed');
    check(after.spots.some((s) => s.id === list.ea.id), 'baseline spot missing');
    check(a.output?.includes(list.ea.id) && b.output?.includes(list.wb.id), `spot test outputs should name the saved spot ids: ${a.output} / ${b.output}`);
    notes.push(`spots ${list.ea.id} (baseline, score ${list.ea.metrics.score.avg}) and ${list.wb.id} (deleted)`);
  });

  await scenario('sms-admin', 'send_sms to a short code -> outgoing + carrier reply stored; carrier_sms alert (admin sees the text)', async (notes) => {
    const noConfirm = await sendCommand('send_sms', { to: '121', body: 'BAL' });
    eq(noConfirm.status, 400, 'send_sms without confirmed');
    await runCommand('send_sms', { to: '121', body: 'BAL' }, { extra: { confirmed: true } });
    const sms = await waitFor(
      async () => {
        const r = expectStatus(await api().get(`/api/sms?device_id=${S.deviceId}`, adminOpts()), 200, 'GET /api/sms');
        return r.some((m) => m.direction === 'out' && m.peer === '121') && r.find((m) => m.direction === 'in') ? r : null;
      },
      { timeout: 15_000, label: 'outgoing and incoming SMS' },
    );
    const incoming = sms.find((m) => m.direction === 'in');
    const alert = await waitFor(async () => (await alerts()).find((a) => a.kind === 'carrier_sms'), { timeout: 15_000, label: 'carrier_sms alert' });
    check(alert.message.includes(incoming.body.slice(0, 20)) || JSON.stringify(alert.data ?? {}).includes(incoming.body.slice(0, 20)), `admin carrier_sms alert should carry the text: ${short(alert)}`);
    notes.push(`reply from ${incoming.peer}: "${short(incoming.body, 60)}"`);
  });

  await scenario('viewer-role', 'Viewer: 403 for commands, SMS, settings and users; alerts, events and commands redacted (REST and socket)', async (notes) => {
    const created = expectStatus(await api().post('/api/users', { ...VIEWER, role: 'viewer' }, adminOpts()), 201, 'POST /api/users');
    eq(created.role, 'viewer', 'created role');
    const login = expectStatus(await api().post('/api/auth/login', VIEWER), 200, 'viewer login');
    eq(login.totp_required, false, 'viewer without TOTP gets a session directly');
    S.viewerToken = login.token;
    const v = { token: S.viewerToken };
    const me = expectStatus(await api().get('/api/auth/me', v), 200, 'viewer me');
    eq(me.user.role, 'viewer', 'viewer me role');
    eq(me.totp_enrollment_required, false, 'viewers are not asked to enroll');

    const denied = [
      ['POST /api/commands', await api().post('/api/commands', { device_id: S.deviceId, name: 'refresh', args: {} }, v)],
      ['GET /api/sms', await api().get(`/api/sms?device_id=${S.deviceId}`, v)],
      ['PUT /api/settings', await api().put('/api/settings', { device_id: S.deviceId, settings: { plan: { quota_gb: 5 } } }, v)],
      ['GET /api/users', await api().get('/api/users', v)],
      ['POST /api/pair/codes', await api().post('/api/pair/codes', {}, v)],
      ['POST /api/commands/:id/keep', await api().post(`/api/commands/${revertWait?.id ?? 'c_x'}/keep`, undefined, v)],
    ];
    for (const [what, r] of denied) eq(r.status, 403, `viewer ${what}`);
    for (const [what, path] of [
      ['GET /api/settings', `/api/settings?device_id=${S.deviceId}`],
      ['GET /api/device', `/api/device?device_id=${S.deviceId}`],
      ['GET /api/telemetry', `/api/telemetry?device_id=${S.deviceId}&metric=radio.rsrp&res=raw`],
      ['GET /api/finder/spots', `/api/finder/spots?device_id=${S.deviceId}`],
    ]) {
      eq((await api().get(path, v)).status, 200, `viewer ${what}`);
    }

    const va = await alerts(S.viewerToken);
    const vsms = va.filter((a) => a.kind === 'carrier_sms');
    check(vsms.length > 0, 'viewer sees the carrier_sms alert (redacted)');
    for (const a of vsms) {
      check(!/SIMTEL|balance|Balance/.test(a.message), `viewer carrier_sms alert leaks the text: ${a.message}`);
      check(a.data == null, `viewer carrier_sms alert leaks data: ${short(a.data)}`);
    }
    const ve = await events(S.viewerToken, '&kind=sms_received');
    check(ve.length > 0, 'viewer sees sms_received events (redacted)');
    for (const e of ve) {
      eq(e.message, 'SMS received', 'viewer sms_received message');
      check(!e.data || Object.keys(e.data).length === 0, `viewer sms_received leaks data: ${short(e.data)}`);
    }
    const ae = await events(S.token, '&kind=sms_received');
    check(ae.some((e) => e.data && e.data.body), 'admin sees the sms_received body');
    const vc = expectStatus(await api().get(`/api/commands?device_id=${S.deviceId}`, v), 200, 'viewer GET /api/commands');
    const vSend = vc.filter((c) => c.name === 'send_sms');
    check(vSend.length > 0, 'viewer sees send_sms in the audit log');
    for (const c of vSend) {
      check(Object.keys(c.args).length === 0 && c.output == null, `viewer send_sms record leaks args/output: ${short(c)}`);
    }

    // Live: the viewer socket must not get SMS, and gets redacted events, alerts and commands.
    const vdash = new DashClient(`${S.wsBase}/ws/dashboard`, 'viewer socket');
    try {
      await vdash.connect(S.viewerToken);
      vdash.subscribe(S.deviceId, false);
      await vdash.waitFor((x) => x.t === 'snapshot', { label: 'viewer snapshot' });
      const vm = vdash.mark();
      const am = S.dash.mark();
      await runCommand('send_sms', { to: '121', body: 'PLAN' }, { extra: { confirmed: true } });
      await S.dash.waitFor((x) => x.t === 'sms' && x.sms?.direction === 'in', { since: am, timeout: 15_000, label: 'admin live sms' });
      await S.dash.waitFor((x) => x.t === 'alert' && x.alert?.kind === 'carrier_sms', { since: am, timeout: 15_000, label: 'admin carrier_sms alert' });
      const valert = await vdash.waitFor((x) => x.t === 'alert' && x.alert?.kind === 'carrier_sms', { since: vm, timeout: 15_000, label: 'viewer carrier_sms alert' });
      check(valert.alert.data == null && !/SIMTEL/.test(valert.alert.message), `viewer live alert leaks: ${short(valert.alert)}`);
      const vev = await vdash.waitFor((x) => x.t === 'event' && x.event?.kind === 'sms_received', { since: vm, timeout: 15_000, label: 'viewer sms_received' });
      eq(vev.event.message, 'SMS received', 'viewer live sms_received message');
      await sleep(500);
      eq(vdash.filter((x) => x.t === 'sms', vm).length, 0, 'sms messages on the viewer socket');
      const vcmd = vdash.filter((x) => x.t === 'command' && x.command?.name === 'send_sms', vm);
      check(vcmd.length > 0, 'viewer gets command broadcasts');
      for (const e of vcmd) check(Object.keys(e.msg.command.args).length === 0, `viewer live command leaks args: ${short(e.msg.command)}`);
    } finally {
      vdash.close();
    }
    notes.push('403 x6, reads 200, carrier_sms/sms_received/send_sms redacted for viewers over REST and WS, no sms frames on the viewer socket');
  });

  await scenario('cmd-usb-reverted', 'usb_tethering off without keep -> reverted at the deadline (revert event + watchdog alert)', async (notes) => {
    check(revertWait, 'the armed command from cmd-usb-revert-armed is missing');
    const rec = await waitCommand(revertWait.id, 'reverted', Math.max(10_000, revertWait.deadline - Date.now() + 20_000));
    check(Date.now() >= revertWait.deadline - 1000, 'reverted before its deadline');
    const ev = await S.dash.waitFor((x) => x.t === 'event' && x.event?.kind === 'revert' && x.event.data?.command_id === revertWait.id, {
      since: revertWait.mark,
      timeout: 10_000,
      label: 'revert event',
    });
    const alert = await waitFor(async () => (await alerts()).find((a) => a.kind === 'watchdog' && /revert/i.test(a.message)), {
      timeout: 10_000,
      label: 'watchdog alert for the revert',
    });
    notes.push(`trail ${seenStatuses(revertWait.id).join(' > ')}; "${rec.error}"; event "${ev.event.message}"; alert "${short(alert.message, 80)}"`);
  });

  await scenario('cmd-reboot', 'reboot: 400 confirmation_required, 401 reauth_required, then accepted; phone reboots and reconnects', async (notes) => {
    const a = await sendCommand('reboot', {});
    eq(a.status, 400, 'reboot without confirmed');
    eq(a.body?.error, 'confirmation_required', 'reboot without confirmed: error');
    const b = await sendCommand('reboot', {}, { confirmed: true });
    eq(b.status, 401, 'reboot without password');
    eq(b.body?.error, 'reauth_required', 'reboot without password: error');
    const c = await sendCommand('reboot', {}, { confirmed: true, password: 'not-the-password' });
    eq(c.status, 401, 'reboot with a wrong password');
    eq(c.body?.error, 'reauth_required', 'reboot with a wrong password: error');
    const mark = S.dash.mark();
    const ok = expectStatus(await sendCommand('reboot', {}, { confirmed: true, password: ADMIN.password }), 201, 'reboot with confirmation and password');
    await waitCommand(ok.id, 'done', 20_000);
    await S.dash.waitFor((x) => x.t === 'status' && x.device_id === S.deviceId && x.online === false, { since: mark, timeout: 15_000, label: 'status offline during reboot' });
    await S.dash.waitFor((x) => x.t === 'status' && x.device_id === S.deviceId && x.online === true && S.dash.find((y) => y.t === 'status' && y.online === false, mark), {
      since: mark,
      timeout: 40_000,
      label: 'status online after reboot',
    });
    const boot = await S.dash.waitFor((x) => x.t === 'event' && x.event?.kind === 'boot', { since: mark, timeout: 20_000, label: 'boot event' });
    eq(boot.event.data?.boot_reason, 'reboot,userrequested', 'boot reason');
    await S.dash.waitFor((x) => x.t === 'event' && x.event?.kind === 'agent_start' && x.event.data?.reason === 'boot', { since: mark, timeout: 10_000, label: 'agent_start after boot' });
    notes.push(`trail ${seenStatuses(ok.id).join(' > ')}; boot #${boot.event.data?.boot_count}`);
  });

  await scenario('cmd-expiry', 'A command queued while the phone is offline expires after commands.expiry_s; a fresh one is delivered on reconnect', async (notes) => {
    const mark = S.dash.mark();
    const exit = await stopSim();
    eq(exit?.code, 0, 'simulator exit code after SIGTERM');
    await S.dash.waitFor((x) => x.t === 'status' && x.device_id === S.deviceId && x.online === false, { since: mark, timeout: 10_000, label: 'status offline after stopping the simulator' });
    const stale = expectStatus(await sendCommand('refresh'), 201, 'queue refresh while offline');
    eq(stale.status, 'queued', 'status of a command for an offline phone');
    const t0 = Date.parse(stale.created_at);
    const expired = await waitCommand(stale.id, 'expired', 25_000);
    const age = Date.parse(expired.finished_at) - t0;
    check(age >= 10_000, `expired after ${age} ms, before expiry_s = 10 s`);
    check(/Not delivered/i.test(expired.error ?? ''), `expiry error: ${expired.error}`);

    const fresh = expectStatus(await sendCommand('refresh'), 201, 'queue a fresh refresh');
    eq(fresh.status, 'queued', 'fresh command status while offline');
    const sim = startSim(['--keep-link']);
    await simHello(sim);
    const done = await waitCommand(fresh.id, 'done', 20_000);
    check(Date.parse(done.delivered_at) - Date.parse(fresh.created_at) < 10_000, 'fresh command delivered within expiry');
    await sleep(1500);
    const all = expectStatus(await api().get('/api/commands?limit=200', adminOpts()), 200, 'commands');
    eq(all.find((x) => x.id === stale.id)?.status, 'expired', 'the stale command stays expired after the reconnect');
    check(!sim.text.includes(stale.id), 'the expired command must never reach the phone');
    notes.push(`expired after ${(age / 1000).toFixed(1)} s ("${expired.error}"); fresh one delivered ${Date.parse(done.delivered_at) - Date.parse(fresh.created_at)} ms after creation`);
    notes.push('Agent-side "expired" rejection (sent_at too old on arrival) cannot be provoked with a correct Server; covered by tools/agent-sim unit tests');
  });

  await scenario(
    'offline-backfill',
    'Link-cutting mobile_data off: offline alert; simulator restarted with its persisted outbox; auto-reverted; back online; backlog stored',
    async (notes) => {
      await putSettings({ alerts: { offline_after_s: 30 }, telemetry: { fast_idle_s: 2 }, commands: { revert_after_s: 60 } });
      // Without --keep-link, mobile data off really cuts the simulated phone's link.
      let mark = S.dash.mark();
      await stopSim();
      let sim = startSim([]);
      await simHello(sim);
      await S.dash.waitFor((x) => x.t === 'status' && x.device_id === S.deviceId && x.online === true, { since: mark, timeout: 15_000, label: 'online' });
      await waitFor(() => readSimState().settings?.commands?.revert_after_s === 60, { timeout: 10_000, label: 'simulator has revert_after_s = 60' });

      mark = S.dash.mark();
      const r = expectStatus(await sendCommand('mobile_data', { enabled: false }), 201, 'POST mobile_data off');
      const armed = await waitCommand(r.id, 'revert_armed', 15_000);
      const deadline = Date.parse(armed.revert_deadline);
      await S.dash.waitFor((x) => x.t === 'status' && x.device_id === S.deviceId && x.online === false, { since: mark, timeout: 15_000, label: 'status offline (link cut)' });
      const linkDownAt = Date.now();
      const offlineAlert = await waitFor(async () => (await alerts()).find((a) => a.kind === 'offline' && a.device_id === S.deviceId && Date.parse(a.ts) > linkDownAt), {
        timeout: 70_000,
        interval: 1000,
        label: 'offline alert',
      });
      await S.dash.waitFor((x) => x.t === 'alert' && x.alert?.kind === 'offline', { since: mark, timeout: 5000, label: 'offline alert on the socket' });
      const offlineAfter = Date.parse(offlineAlert.ts) - linkDownAt;
      const dev = expectStatus(await api().get(`/api/device?device_id=${S.deviceId}`, adminOpts()), 200, 'device');
      eq(dev.online, false, 'device online while the link is cut');

      // Stop the phone's Agent mid-outage: the outbox and the armed revert are persisted.
      eq((await stopSim())?.code, 0, 'simulator exit code');
      const st = readSimState();
      check(Array.isArray(st.outbox) && st.outbox.length > 0, 'persisted outbox is empty');
      const backlogFast = st.outbox.filter((m) => m.t === 'telemetry' && m.tier === 'fast' && Date.parse(m.ts) > linkDownAt);
      check(backlogFast.length >= 5, `expected buffered fast telemetry during the outage, got ${backlogFast.length}`);
      check((st.reverts ?? []).some((x) => x.command_id === r.id), 'the armed revert is not persisted');
      eq(st.flags?.dataEnabled, false, 'persisted mobile data flag');
      S.offline = { savedOutbox: structuredClone(st.outbox), linkDownAt, commandId: r.id };

      // Restart with the persisted outbox. Mobile data is still off, so it waits for the revert deadline.
      mark = S.dash.mark();
      sim = startSim([]);
      const reverted = await waitCommand(r.id, 'reverted', Math.max(15_000, deadline - Date.now() + 30_000));
      await S.dash.waitFor((x) => x.t === 'status' && x.device_id === S.deviceId && x.online === true, { since: mark, timeout: 15_000, label: 'status online after the revert' });
      const reconnectAt = Date.now();
      S.offline.reconnectAt = reconnectAt;
      const back = await waitFor(async () => (await alerts()).find((a) => a.kind === 'back_online' && a.device_id === S.deviceId && Date.parse(a.ts) > linkDownAt), {
        timeout: 30_000,
        interval: 1000,
        label: 'back_online alert',
      });
      check(Array.isArray(back.data?.reasons) && back.data.reasons.length > 0, `back_online should name a reason: ${short(back)}`);
      check(back.data.offline_s >= 30, `back_online offline_s ${back.data.offline_s}`);

      // Backlog: every buffered fast reading is in the history exactly once.
      const hist = await waitFor(
        async () => {
          const h = await history('radio.rsrp', linkDownAt - 5000, Date.now());
          const have = new Set(h.points.map((p) => p[0]));
          return backlogFast.every((m) => have.has(Date.parse(m.ts))) ? h : null;
        },
        { timeout: 20_000, interval: 1000, label: 'buffered telemetry in GET /api/telemetry' },
      );
      const tsList = hist.points.map((p) => p[0]);
      eq(new Set(tsList).size, tsList.length, 'distinct timestamps in raw history');
      const inOutage = hist.points.filter((p) => p[0] > linkDownAt && p[0] < reconnectAt - 3000);
      check(inOutage.length >= backlogFast.length, `history points inside the outage: ${inOutage.length}`);
      const revertEvents = (await events(S.token, '&kind=revert')).filter((e) => e.data?.command_id === r.id);
      eq(revertEvents.length, 1, 'revert events for the command');

      // Soft checks (reported as warnings, see the bug list in the README): docs/protocol.md 6 says
      // the back-online reasons come from the backlog, and the duration is the time offline.
      const lastSeen = Date.parse(offlineAlert.data?.last_seen ?? '') || linkDownAt;
      const reasonText = (ev) => {
        const d = ev.data ?? {};
        if (ev.kind === 'boot') return `phone rebooted${typeof d.boot_reason === 'string' ? ` (${d.boot_reason})` : ''}`;
        if (ev.kind === 'agent_start') return `Agent restarted${typeof d.reason === 'string' ? ` (${d.reason})` : ''}`;
        if (ev.kind === 'power_lost') return 'USB power was lost';
        if (ev.kind === 'power_connected') return 'USB power returned';
        return ev.message || ev.kind;
      };
      const allEvents = await events();
      const duringOutage = new Set(allEvents.filter((e) => Date.parse(e.ts) >= lastSeen).map(reasonText));
      const beforeOutage = allEvents.filter((e) => Date.parse(e.ts) < lastSeen).map(reasonText);
      const stale = back.data.reasons.filter((x) => beforeOutage.includes(x) && !duringOutage.has(x));
      if (stale.length) notes.push(`WARNING back_online cites events from before the outage: ${stale.join(' | ')}`);
      const actualS = Math.round((reconnectAt - lastSeen) / 1000);
      if (back.data.offline_s > actualS + 5) {
        notes.push(`WARNING back_online offline_s=${back.data.offline_s} but the phone was offline ~${actualS} s (last seen -> status online)`);
      }
      notes.push(
        `offline alert ${(offlineAfter / 1000).toFixed(0)} s after the link cut; ${st.outbox.length} rows persisted (${backlogFast.length} fast readings); ` +
          `reverted ("${reverted.error}"); back_online: "${short(back.message, 140)}"; ${inOutage.length} outage readings in history`,
      );
    },
  );

  await scenario('dedup', 'Re-sent outbox rows (same seq/ids) are acknowledged and not stored twice', async (notes) => {
    check(S.offline?.savedOutbox, 'needs the offline-backfill scenario');
    const { savedOutbox, linkDownAt, reconnectAt, commandId } = S.offline;
    const from = linkDownAt - 5000;
    const to = reconnectAt;
    const maxSeq = Math.max(...savedOutbox.map((m) => m.seq));
    await waitFor(() => !(readSimState().outbox ?? []).some((m) => m.seq <= maxSeq), { timeout: 20_000, label: 'backlog acknowledged' });
    const before = await history('radio.rsrp', from, to);
    const beforeSlow = await history('power.percent', from, to);
    const evBefore = await events();
    const alertsBefore = await alerts();
    eq((await stopSim())?.code, 0, 'simulator exit code');

    const st = readSimState();
    const merged = [...savedOutbox, ...(st.outbox ?? []).filter((m) => m.seq > maxSeq)].sort((a, b) => a.seq - b.seq);
    writeSimState({ ...st, outbox: merged });
    const sim = startSim([]);
    await simHello(sim);
    await waitFor(() => !(readSimState().outbox ?? []).some((m) => m.seq <= maxSeq), { timeout: 20_000, label: 're-sent rows acknowledged' });
    check(/batch of \d+ buffered messages/.test(sim.text), 'the re-sent backlog should go out as a batch');

    const after = await history('radio.rsrp', from, to);
    const afterSlow = await history('power.percent', from, to);
    eq(after.points.length, before.points.length, 'radio.rsrp points in the outage window after the replay');
    eq(JSON.stringify(after.points), JSON.stringify(before.points), 'radio.rsrp points unchanged by the replay');
    eq(afterSlow.points.length, beforeSlow.points.length, 'power.percent points after the replay');
    const evAfter = await events();
    const ids = evAfter.map((e) => e.id);
    eq(new Set(ids).size, ids.length, 'distinct event ids');
    for (const m of savedOutbox.filter((x) => x.t === 'event')) {
      eq(ids.filter((id) => id === m.event.id).length, 1, `copies of event ${m.event.id} (${m.event.kind})`);
    }
    const newEvents = evAfter.length - evBefore.length;
    const alertsAfter = await alerts();
    const newAlerts = alertsAfter.filter((a) => !alertsBefore.some((b) => b.id === a.id));
    check(!newAlerts.some((a) => a.kind === 'offline' || a.kind === 'back_online'), `replay raised alerts: ${newAlerts.map((a) => a.kind).join(', ')}`);
    const cmd = expectStatus(await api().get('/api/commands?limit=200', adminOpts()), 200, 'commands').find((c) => c.id === commandId);
    eq(cmd?.status, 'reverted', 'replayed cmd_revert keeps the command reverted');
    notes.push(`${savedOutbox.length} rows replayed (max seq ${maxSeq}); ${after.points.length} readings unchanged; ${newEvents} new events (only the new run's own)`);
  });

  await scenario('history', 'GET /api/telemetry returns raw points for radio.rsrp (and slow metrics; bad requests are 400)', async (notes) => {
    const raw = await history('radio.rsrp');
    eq(raw.res, 'raw', 'res');
    check(raw.points.length > 10, `radio.rsrp raw points: ${raw.points.length}`);
    for (const [t, v] of raw.points) {
      check(Number.isFinite(t), `bad timestamp ${t}`);
      check(v === null || (v < -40 && v > -140), `implausible RSRP ${v}`);
    }
    const sorted = raw.points.every((p, i) => i === 0 || p[0] >= raw.points[i - 1][0]);
    check(sorted, 'points are not in time order');
    const batt = await history('power.percent');
    check(batt.points.length > 0, 'power.percent (slow tier) has no points');
    const auto = await history('radio.score', null, null, 'auto');
    eq(auto.res, '1m', 'res=auto over the default 24 h range');
    eq((await api().get(`/api/telemetry?device_id=${S.deviceId}&metric=nope`, adminOpts())).status, 400, 'unknown metric');
    eq((await api().get(`/api/telemetry?device_id=${S.deviceId}`, adminOpts())).status, 400, 'missing metric');
    notes.push(`radio.rsrp ${raw.points.length} raw points; power.percent ${batt.points.length}; radio.score 24 h auto -> 1m with ${auto.points.length} points`);
  });

  if (args.ui) {
    await scenario('dashboard-ui', 'Headless Chrome: login with TOTP; Overview shows live values; Controls, History, Finder, Settings, Users render; no console errors or CSP violations', uiScenario);
  } else {
    results.push({ id: 'dashboard-ui', title: 'Dashboard UI', passed: null, skipped: true, notes: '--no-ui' });
  }
}

// --- Dashboard UI --------------------------------------------------------------------------------

async function uiScenario(notes) {
  const chromePath = findChrome(args.chrome);
  check(chromePath, 'Chrome not found (pass --chrome <path> or set CHROME_PATH, or run with --no-ui)');
  const { child, wsUrl } = await launchChrome(procs, chromePath, join(WORK, 'chrome-profile'));
  const cdp = new Cdp();
  await cdp.connect(wsUrl);
  const shots = [];
  const shot = async (page, name) => {
    const file = join(SHOTS, `${String(shots.length + 1).padStart(2, '0')}-${name}.png`);
    writeFileSync(file, await page.screenshot());
    shots.push(file);
  };
  const problems = (page) => {
    const out = [];
    for (const c of page.console) if (c.type === 'error' || c.type === 'assert') out.push(`console.${c.type}: ${c.text}`);
    for (const e of page.exceptions) out.push(`exception: ${e}`);
    for (const l of page.logEntries) if (l.level === 'error') out.push(`log(${l.source}): ${l.text}${l.url ? ` [${l.url}]` : ''}`);
    return out;
  };
  const errorBoundary = `/This screen had a problem|This screen could not be loaded/.test(document.body.innerText)`;
  const gotoNav = async (page, path, title) => {
    await page.click(`a[href="${path}"]`);
    await page.waitFor(`location.pathname === ${JSON.stringify(path)} && [...document.querySelectorAll('h1')].some((h) => h.textContent.trim() === ${JSON.stringify(title)})`, {
      label: `${title} screen`,
    });
    check(!(await page.eval(errorBoundary)), `${title} screen shows the error boundary`);
  };

  try {
    const page = await Page.open(cdp);
    await page.navigate(`${S.base}/`);
    await page.waitFor(`location.pathname === '/login' && !!document.querySelector('input[autocomplete="username"]')`, { label: 'login form', timeout: 20_000 });
    await shot(page, 'login');
    await page.fill('input[autocomplete="username"]', ADMIN.username);
    await page.fill('input[type="password"]', ADMIN.password);
    await page.click('form button[type="submit"]');
    await page.waitFor(`!!document.querySelector('input[autocomplete="one-time-code"]')`, { label: 'TOTP step' });
    await page.fill('input[autocomplete="one-time-code"]', await S.auth.nextCode());
    await page.click('form button[type="submit"]');
    await page.waitFor(`location.pathname === '/' && [...document.querySelectorAll('h1')].some((h) => h.textContent.trim() === 'Overview')`, { label: 'Overview after login', timeout: 20_000 });

    // Overview: online, the simulated model, and the RSRP the simulator is sending right now.
    await page.waitFor(`document.body.innerText.includes('Online') && /RSRP -\\d+ dBm/.test(document.body.innerText) && document.body.innerText.includes('SM-G991B')`, {
      label: 'Overview with live values',
      timeout: 20_000,
    });
    const shownMatchesLive = await waitFor(
      async () => {
        const text = await page.text();
        const shown = Number(/RSRP (-\d+) dBm/.exec(text)?.[1]);
        const live = S.dash.filter((x) => x.t === 'telemetry' && x.tier === 'fast' && typeof x.radio?.rsrp === 'number').map((e) => e.msg.radio.rsrp);
        return live.length && shown === live[live.length - 1] ? shown : null;
      },
      { timeout: 20_000, interval: 300, label: 'Overview RSRP equal to the latest live reading' },
    );
    check(seenRsrp().includes(shownMatchesLive), 'Overview RSRP is not a value the simulator sent');
    await shot(page, 'overview');
    notes.push(`Overview shows RSRP ${shownMatchesLive} dBm (latest live reading)`);
    check(page.wsCreated.some((u) => u.endsWith('/ws/dashboard')), `the Dashboard did not open /ws/dashboard (${page.wsCreated.join(', ')})`);

    await gotoNav(page, '/controls', 'Controls');
    await page.waitFor(`document.body.innerText.includes('Mobile data') && document.body.innerText.includes('Reboot')`, { label: 'Controls cards' });
    await shot(page, 'controls');

    const histMark = page.responses.length;
    await gotoNav(page, '/signal', 'Signal');
    await page.waitFor(`[...document.querySelectorAll('h2')].some((h) => h.textContent.trim() === 'History')`, { label: 'History section' });
    await waitFor(() => page.responses.slice(histMark).some((r) => r.url.includes('/api/telemetry?') && r.status === 200), { timeout: 15_000, label: 'history request from the Signal screen' });
    await page.waitFor(`document.querySelectorAll('canvas').length > 0`, { label: 'history chart canvas', timeout: 15_000 });
    await sleep(800);
    await shot(page, 'signal-history');

    const finderMark = S.dash.mark();
    await gotoNav(page, '/finder', 'Signal Finder');
    await page.waitFor(`document.body.innerText.includes('E2E roof east')`, { label: 'saved spot on the Finder screen' });
    // The browser's subscribe {finder:true} turns the mirror on for every watcher of the device.
    await S.dash.waitFor((x) => x.t === 'finder_live', { since: finderMark, timeout: 10_000, label: 'finder_live while the Finder screen is open' });
    await sleep(1500);
    await shot(page, 'finder');

    await gotoNav(page, '/settings', 'Settings');
    await shot(page, 'settings');
    await page.clickText('[role="tab"]', 'Users');
    await page.waitFor(`location.search.includes('tab=users') && document.body.innerText.includes(${JSON.stringify(VIEWER.username)})`, { label: 'Users tab listing the viewer' });
    await shot(page, 'users');

    await gotoNav(page, '/alerts', 'Alerts');
    await page.waitFor(`document.body.innerText.includes('Phone offline') && document.body.innerText.includes('Phone back online')`, { label: 'offline / back online alerts listed' });
    await shot(page, 'alerts');
    await gotoNav(page, '/messages', 'Messages');
    await shot(page, 'messages');
    await gotoNav(page, '/system', 'System');
    await shot(page, 'system');

    const doc = page.responses.find((r) => r.type === 'Document' && r.url.startsWith(S.base));
    check(doc?.csp && doc.csp.includes("script-src 'self'"), `the document was served without the SPA CSP: ${doc?.csp}`);
    const swRegistered = await page.eval(`navigator.serviceWorker ? navigator.serviceWorker.getRegistration().then((r) => !!r) : false`);
    check(swRegistered, 'the service worker is not registered');
    const csp = await page.cspViolations();
    const errs = problems(page);
    const failed = page.responses.filter((r) => r.url.startsWith(S.base) && r.status >= 400);

    // Self-check: the detectors above must see a deliberate inline script and console.error.
    const consoleBefore = page.console.length;
    await page.eval(`(() => { const s = document.createElement('script'); s.textContent = 'window.__e2eInline = 1'; document.head.appendChild(s); console.error('e2e detector self-check'); })()`);
    await waitFor(async () => (await page.cspViolations()).length > csp.length, { timeout: 5000, label: 'CSP violation from the self-check inline script' });
    check(!(await page.eval('window.__e2eInline === 1')), 'an inline script ran despite the CSP');
    check(page.console.slice(consoleBefore).some((c) => c.type === 'error' && c.text.includes('self-check')), 'console.error capture does not work');

    // A viewer in a separate browser context: no Controls or Messages, and /controls redirects.
    const { browserContextId } = await cdp.send('Target.createBrowserContext');
    const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank', browserContextId });
    const { sessionId } = await cdp.send('Target.attachToTarget', { targetId, flatten: true });
    const vpage = new Page(cdp, sessionId);
    for (const m of ['Page.enable', 'Runtime.enable', 'Log.enable', 'Network.enable']) await vpage.send(m);
    await vpage.send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });
    await vpage.navigate(`${S.base}/login`);
    await vpage.waitFor(`!!document.querySelector('input[autocomplete="username"]')`, { label: 'viewer login form' });
    await vpage.fill('input[autocomplete="username"]', VIEWER.username);
    await vpage.fill('input[type="password"]', VIEWER.password);
    await vpage.click('form button[type="submit"]');
    await vpage.waitFor(`location.pathname === '/' && document.body.innerText.includes('Online')`, { label: 'viewer Overview', timeout: 20_000 });
    check(!(await vpage.eval(`!!document.querySelector('a[href="/controls"]') || !!document.querySelector('a[href="/messages"]')`)), 'viewer nav shows Controls or Messages');
    await vpage.navigate(`${S.base}/controls`);
    await vpage.waitFor(`location.pathname === '/' && [...document.querySelectorAll('h1')].some((h) => h.textContent.trim() === 'Overview')`, { label: 'viewer redirected from /controls' });
    await shot(vpage, 'viewer-overview');
    const vErrs = problems(vpage);

    const all = [...errs, ...csp.map((v) => `CSP violation: ${v.directive} blocked ${v.blocked} (${v.source}:${v.line})`), ...vErrs.map((e) => `viewer ${e}`)];
    if (failed.length) notes.push(`HTTP >= 400 seen by the page: ${failed.map((f) => `${f.status} ${f.url.replace(S.base, '')}`).join(', ')}`);
    notes.push(`screenshots: ${shots.length} in ${SHOTS}`);
    check(all.length === 0, `browser problems:\n  ${all.join('\n  ')}`);
    check(failed.length === 0, `requests failed in the browser: ${failed.map((f) => `${f.status} ${f.url}`).join(', ')}`);
    notes.push('no console errors, exceptions or CSP violations (admin + viewer); service worker registered; detectors self-checked with a blocked inline script');
  } finally {
    await cdp.send('Browser.close').catch(() => undefined);
    cdp.close();
    await procs.stop(child, { timeout: 5000 });
    rmSync(join(WORK, 'chrome-profile'), { recursive: true, force: true });
  }
}

// --- run -----------------------------------------------------------------------------------------

let exitCode = 0;
const started = Date.now();
try {
  await main();
} catch (err) {
  log('harness error:', err?.stack ?? String(err));
  exitCode = 1;
} finally {
  S.dash?.close();
  await stopSim().catch(() => undefined);
  if (S.server) {
    const res = await procs.stop(S.server, { timeout: 12_000 });
    if (res && res.code !== 0) log(`server exited with ${JSON.stringify(res)}`);
    const warn = S.server.text.split('\n').filter((l) => /"level":(50|60)/.test(l));
    if (warn.length) log(`server logged ${warn.length} error line(s); see ${S.server.logFile}`);
  }
  await procs.stopAll();
}

const failed = results.filter((r) => r.passed === false);
const passed = results.filter((r) => r.passed === true);
writeFileSync(join(OUT, 'report.json'), JSON.stringify({ started: iso(started), duration_s: Math.round((Date.now() - started) / 1000), base: S.base, results }, null, 2));
process.stdout.write(`\n${'='.repeat(78)}\n`);
for (const r of results) {
  const tag = r.passed === true ? 'PASS' : r.skipped && r.passed === null ? 'SKIP' : 'FAIL';
  process.stdout.write(`${tag}  ${r.id.padEnd(22)} ${r.title}\n`);
  if (r.passed !== true && r.notes) process.stdout.write(`      ${r.notes.split('\n').join('\n      ')}\n`);
}
process.stdout.write(`${'='.repeat(78)}\n${passed.length} passed, ${failed.length} failed in ${Math.round((Date.now() - started) / 1000)} s. Logs, screenshots and report.json: ${OUT}\n`);
process.exit(exitCode || (failed.length ? 1 : 0));
