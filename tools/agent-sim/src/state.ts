import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import type { BufferedMsg, FinderSpot, Settings } from '@hsm/shared';
import type { ArmedRevert } from './revert.js';
import { isPlainObject } from './util.js';

/**
 * What the simulator keeps between runs, in tools/agent-sim/.sim-state.json by default
 * (git-ignored: it holds the device token). The real Agent keeps the same things in
 * Keystore-backed preferences and Room.
 */
export interface SimState {
  version: 1;
  /** Base URL the simulator was paired against (what the user passed as --server). */
  server?: string;
  /** Agent WebSocket URL from the pairing response. */
  ws_url?: string;
  device_id?: string | null;
  token?: string;
  settings?: Settings;
  settings_version?: number;
  /** Highest outbox seq handed out, so seq keeps increasing across runs like a Room autoincrement. */
  last_seq?: number;
  boot_count?: number;
  /** Phone boot time (ms). The phone "keeps running" while the simulator process restarts. */
  boot_at?: number;
  boot_reason?: string;
  imei_tail?: string;
  battery_pct?: number;
  /** Phone switches that survive an Agent restart (mobile data, airplane mode, network mode, ...). */
  flags?: Record<string, unknown>;
  /** Timestamps (ms) of reboots from any source, for the reboot limit. */
  reboots?: number[];
  spots?: FinderSpot[];
  reverts?: ArmedRevert[];
  seen_commands?: string[];
  /** Only with --persist-outbox. */
  outbox?: BufferedMsg[];
}

const SAVE_DEBOUNCE_MS = 500;

export class StateStore {
  readonly file: string | null;
  private state: SimState;
  private timer: NodeJS.Timeout | null = null;
  private readonly onError: (message: string) => void;

  /** `file` null keeps everything in memory (tests). */
  constructor(file: string | null, onError: (message: string) => void = () => undefined) {
    this.file = file;
    this.onError = onError;
    this.state = file ? StateStore.load(file) : { version: 1 };
  }

  static load(file: string): SimState {
    if (!existsSync(file)) return { version: 1 };
    let parsed: unknown;
    try {
      parsed = JSON.parse(readFileSync(file, 'utf8'));
    } catch (err) {
      throw new Error(`State file ${file} is not valid JSON (${(err as Error).message}). Delete it or pass --state <other file>.`);
    }
    if (!isPlainObject(parsed)) throw new Error(`State file ${file} does not contain a JSON object.`);
    return { ...(parsed as Partial<SimState>), version: 1 };
  }

  get(): Readonly<SimState> {
    return this.state;
  }

  update(patch: Partial<SimState>, immediate = false): void {
    this.state = { ...this.state, ...patch, version: 1 };
    if (!this.file) return;
    if (immediate) {
      this.flush();
      return;
    }
    if (!this.timer) {
      this.timer = setTimeout(() => {
        this.timer = null;
        this.flush();
      }, SAVE_DEBOUNCE_MS);
      this.timer.unref?.();
    }
  }

  /** Writes now (atomically: temp file + rename). */
  flush(): void {
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    if (!this.file) return;
    try {
      mkdirSync(dirname(this.file), { recursive: true });
      const tmp = `${this.file}.tmp`;
      writeFileSync(tmp, JSON.stringify(this.state, null, 2) + '\n', { mode: 0o600 });
      renameSync(tmp, this.file);
    } catch (err) {
      this.onError(`could not save ${this.file}: ${(err as Error).message}`);
    }
  }
}
