/**
 * CLI entry: `npm run sim -- --code <CODE>` (from the repo root) or `npx tsx src/index.ts` here.
 */
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Agent, type AgentExitReason } from './agent.js';
import { CliError, DEFAULT_SERVER, parseCli, usage, type CliOptions } from './cli.js';
import { Logger } from './log.js';
import { pair, PairError } from './pairing.js';
import { simDeviceInfo } from './phone/phone.js';
import { StateStore } from './state.js';
import { agentWsUrl, errorMessage, normalizeBaseUrl } from './util.js';

const PACKAGE_DIR = join(dirname(fileURLToPath(import.meta.url)), '..');

const EXIT_CODES: Record<AgentExitReason, number> = { unauthorized: 3, replaced: 4, powered_off: 0 };

interface Credentials {
  wsUrl: string;
  token: string;
  deviceId: string | null;
}

async function resolveCredentials(opts: CliOptions, store: StateStore, log: Logger): Promise<Credentials | null> {
  const state = store.get();
  const server = normalizeBaseUrl(opts.server ?? state.server ?? DEFAULT_SERVER);

  if (opts.code) {
    log.info('pair', `pairing with ${server} using code ${opts.code.toUpperCase()} (a sleeping free-tier server can take a minute to wake)`);
    const imeiTail = state.imei_tail && /^\d{4}$/.test(state.imei_tail) ? state.imei_tail : String(Math.floor(Math.random() * 10_000)).padStart(4, '0');
    const res = await pair(server, opts.code, simDeviceInfo(null, imeiTail));
    // A new device id means a fresh device on the Server: forget what belonged to the old one.
    const fresh =
      state.device_id !== res.device_id
        ? { settings: undefined, settings_version: 0, outbox: [], reverts: [], seen_commands: [], spots: [] }
        : {};
    store.update({ ...fresh, server, ws_url: res.ws_url, device_id: res.device_id, token: res.device_token, imei_tail: imeiTail }, true);
    log.info('pair', `paired as ${res.device_id}; credentials saved to ${store.file ?? '(memory)'}`);
    return { wsUrl: res.ws_url, token: res.device_token, deviceId: res.device_id };
  }

  if (opts.token) {
    const wsUrl = agentWsUrl(server);
    store.update({ server, ws_url: wsUrl, token: opts.token, device_id: opts.deviceId }, true);
    return { wsUrl, token: opts.token, deviceId: opts.deviceId };
  }

  if (state.token) {
    if (opts.server && state.server && normalizeBaseUrl(opts.server) !== state.server) {
      log.error(`${store.file} holds a pairing for ${state.server}, not ${server}.`);
      log.error('Pair with the new server using --code <CODE>, or pass --state <other file>.');
      return null;
    }
    const savedServer = state.server ?? server;
    return { wsUrl: state.ws_url ?? agentWsUrl(savedServer), token: state.token, deviceId: state.device_id ?? null };
  }

  log.error(`not paired yet (no token in ${store.file}).`);
  log.error('In the Dashboard open Settings > Pair phone, then run: npm run sim -- --code <CODE>' + (opts.server ? ` --server ${server}` : ''));
  return null;
}

async function main(): Promise<number> {
  let opts: CliOptions;
  try {
    opts = parseCli(process.argv.slice(2), {
      cwd: process.env.INIT_CWD ?? process.cwd(),
      defaultStateFile: join(PACKAGE_DIR, '.sim-state.json'),
      colorDefault: Boolean(process.stdout.isTTY) && !process.env.NO_COLOR,
    });
  } catch (err) {
    if (err instanceof CliError) {
      process.stderr.write(`${err.message}\n\n${usage()}`);
      return 2;
    }
    throw err;
  }
  if (opts.help) {
    process.stdout.write(usage());
    return 0;
  }

  const log = new Logger({ color: opts.color, verbose: opts.verbose });
  let store: StateStore;
  try {
    store = new StateStore(opts.stateFile, (m) => log.warn(m));
  } catch (err) {
    log.error(errorMessage(err));
    return 1;
  }

  let creds: Credentials | null;
  try {
    creds = await resolveCredentials(opts, store, log);
  } catch (err) {
    log.error(err instanceof PairError ? err.message : `pairing failed: ${errorMessage(err)}`);
    if (err instanceof PairError && (err.status === 400 || err.status === 404 || err.status === 410)) {
      log.error('Pairing codes are single-use and expire after 15 minutes: create a new one in the Dashboard.');
    }
    return 1;
  }
  if (!creds) return 2;

  const agent = new Agent(
    {
      wsUrl: creds.wsUrl,
      token: creds.token,
      deviceId: creds.deviceId,
      speed: opts.speed,
      scenarios: opts.scenarios,
      keepLink: opts.keepLink,
      offlineForS: opts.offlineForS,
      offlineAfterS: opts.offlineAfterS,
      seed: opts.seed,
      clockSkewS: opts.clockSkewS,
      persistOutbox: opts.persistOutbox,
      exitOnReplaced: opts.exitOnReplaced,
    },
    store,
    log,
  );

  return new Promise<number>((resolveExit) => {
    agent.once('exit', (reason: AgentExitReason) => {
      if (reason === 'unauthorized') {
        store.update({ token: undefined, device_id: undefined }, true);
        log.info('sim', `removed the rejected token from ${store.file}`);
      }
      resolveExit(EXIT_CODES[reason]);
    });
    let stopping = false;
    const shutdown = (signal: string) => {
      if (stopping) process.exit(130);
      stopping = true;
      log.info('sim', `${signal}: stopping (state saved to ${store.file})`);
      void agent.stop().then(() => resolveExit(0));
    };
    process.on('SIGINT', () => shutdown('SIGINT'));
    process.on('SIGTERM', () => shutdown('SIGTERM'));
    agent.start();
  });
}

main().then(
  (code) => process.exit(code),
  (err) => {
    process.stderr.write(`agent-sim crashed: ${err instanceof Error ? (err.stack ?? err.message) : String(err)}\n`);
    process.exit(1);
  },
);
