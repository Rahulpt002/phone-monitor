import { isAbsolute, resolve } from 'node:path';
import { SCENARIO_HELP, SCENARIO_NAMES, type ScenarioName } from './scenarios.js';

export const DEFAULT_SERVER = 'http://localhost:8080';

export interface CliOptions {
  /** null: not given (use the state file's server, else the default). */
  server: string | null;
  code: string | null;
  token: string | null;
  deviceId: string | null;
  stateFile: string;
  speed: number;
  scenarios: ScenarioName[];
  offlineForS: number | null;
  offlineAfterS: number;
  keepLink: boolean;
  color: boolean;
  verbose: boolean;
  seed: number | null;
  persistOutbox: boolean;
  clockSkewS: number;
  help: boolean;
}

export class CliError extends Error {}

export interface CliEnv {
  /** Where relative --state paths resolve (npm sets INIT_CWD to the caller's directory). */
  cwd: string;
  defaultStateFile: string;
  /** Colour by default only on a terminal and without NO_COLOR. */
  colorDefault: boolean;
}

function number(flag: string, raw: string, check: (n: number) => boolean, what: string): number {
  const n = Number(raw);
  if (!Number.isFinite(n) || !check(n)) throw new CliError(`${flag} must be ${what}, got "${raw}"`);
  return n;
}

export function parseCli(argv: readonly string[], env: CliEnv): CliOptions {
  const o: CliOptions = {
    server: null,
    code: null,
    token: null,
    deviceId: null,
    stateFile: env.defaultStateFile,
    speed: 1,
    scenarios: [],
    offlineForS: null,
    offlineAfterS: 20,
    keepLink: false,
    color: env.colorDefault,
    verbose: false,
    seed: null,
    persistOutbox: false,
    clockSkewS: 0,
    help: false,
  };
  for (let i = 0; i < argv.length; i++) {
    const raw = argv[i] as string;
    let flag = raw;
    let inline: string | undefined;
    const eq = raw.indexOf('=');
    if (raw.startsWith('--') && eq > 2) {
      flag = raw.slice(0, eq);
      inline = raw.slice(eq + 1);
    }
    const value = (): string => {
      if (inline !== undefined) return inline;
      const next = argv[i + 1];
      if (next === undefined || next.startsWith('--')) throw new CliError(`${flag} needs a value`);
      i++;
      return next;
    };
    switch (flag) {
      case '--server':
        o.server = value();
        break;
      case '--code':
        o.code = value().trim();
        break;
      case '--token':
        o.token = value().trim();
        break;
      case '--device-id':
        o.deviceId = value().trim();
        break;
      case '--state': {
        const v = value();
        o.stateFile = isAbsolute(v) ? v : resolve(env.cwd, v);
        break;
      }
      case '--speed':
        o.speed = number(flag, value(), (n) => n > 0 && n <= 10_000, 'a number above 0 (at most 10000)');
        break;
      case '--scenario':
        for (const name of value().split(',').map((s) => s.trim()).filter(Boolean)) {
          if (!(SCENARIO_NAMES as readonly string[]).includes(name)) {
            throw new CliError(`unknown scenario "${name}" (choose from ${SCENARIO_NAMES.join(', ')})`);
          }
          o.scenarios.push(name as ScenarioName);
        }
        break;
      case '--offline-for':
        o.offlineForS = number(flag, value(), (n) => n > 0, 'a number of seconds above 0');
        break;
      case '--offline-after':
        o.offlineAfterS = number(flag, value(), (n) => n >= 0, 'a number of seconds');
        break;
      case '--keep-link':
        o.keepLink = true;
        break;
      case '--no-color':
        o.color = false;
        break;
      case '--color':
        o.color = true;
        break;
      case '--verbose':
      case '-v':
        o.verbose = true;
        break;
      case '--seed':
        o.seed = number(flag, value(), (n) => Number.isInteger(n) && n >= 0, 'a non-negative integer');
        break;
      case '--persist-outbox':
        o.persistOutbox = true;
        break;
      case '--clock-skew':
        o.clockSkewS = number(flag, value(), () => true, 'a number of seconds');
        break;
      case '--help':
      case '-h':
        o.help = true;
        break;
      default:
        throw new CliError(`unknown option ${raw}`);
    }
  }
  if (o.server !== null) {
    let url: URL;
    try {
      url = new URL(o.server);
    } catch {
      throw new CliError(`--server must be an http(s) URL, got "${o.server}"`);
    }
    if (url.protocol !== 'http:' && url.protocol !== 'https:') throw new CliError(`--server must start with http:// or https://`);
  }
  if (o.code && o.token) throw new CliError('use either --code (pair now) or --token (already paired), not both');
  if (o.deviceId && !o.token) throw new CliError('--device-id only makes sense with --token');
  if (o.code !== null && !/^[A-Za-z0-9-]{4,32}$/.test(o.code)) throw new CliError(`--code looks wrong: "${o.code}"`);
  return o;
}

export function usage(): string {
  const scen = SCENARIO_NAMES.map((n) => `      ${n.padEnd(14)} ${SCENARIO_HELP[n]}`).join('\n');
  return `hsm agent simulator: a fake hotspot phone that speaks the Agent protocol.

Usage:
  npm run sim -- --code <PAIRING CODE> [--server <url>]     pair, then run
  npm run sim -- [options]                                  run with the saved pairing

Pairing:
  --server <url>        Server base URL (default ${DEFAULT_SERVER}, or the one saved at pairing)
  --code <code>         pairing code from the Dashboard (Settings > Pair phone)
  --token <t>           use an existing device token instead of pairing
  --device-id <id>      device id that belongs to --token (optional; hello tells it anyway)
  --state <file>        state file (default tools/agent-sim/.sim-state.json, git-ignored)

Simulation:
  --scenario <name>     repeatable or comma-separated:
${scen}
  --speed <x>           fast-forward the simulated world (physics, scenarios, watchdog ladder,
                        Finder tests, reboot time) x times. Protocol timing stays real.
  --offline-for <s>     drop the link for s seconds (once, --offline-after s after the first
                        hello, default 20), keep buffering, then reconnect and back-fill
  --keep-link           commands that would cut the phone's link (mobile data off, airplane on,
                        network mode) leave it up, so the auto-revert probe confirms the change
  --persist-outbox      keep unacked messages in the state file across restarts
  --clock-skew <s>      phone clock offset in seconds (tests the Server's clock-offset handling)
  --seed <n>            reproducible random values

Output:
  --verbose, -v         also log every frame, ack and heartbeat
  --no-color            plain output
  --help, -h            this text
`;
}
