import { spawn } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { join } from 'node:path';
import { sleep } from './util.mjs';

const MEMORY_LIMIT = 2 * 1024 * 1024;

/**
 * Child processes of the run (Server, simulator, Chrome). Output goes to <logDir>/<name>.log and
 * a bounded in-memory copy. Everything still running is killed when the harness exits, even on a
 * crash or Ctrl-C (see installExitHandlers).
 */
export class Procs {
  constructor(logDir) {
    this.logDir = logDir;
    this.children = new Set();
    this.counts = new Map();
  }

  spawn(name, command, args, { cwd, env, quiet = false } = {}) {
    const n = (this.counts.get(name) ?? 0) + 1;
    this.counts.set(name, n);
    const label = n === 1 ? name : `${name}-${n}`;
    const logFile = join(this.logDir, `${label}.log`);
    const log = createWriteStream(logFile, { flags: 'a' });
    log.write(`# ${new Date().toISOString()} ${command} ${args.join(' ')}\n`);
    const proc = spawn(command, args, { cwd, env, stdio: ['ignore', 'pipe', 'pipe'], detached: false });
    const child = {
      name: label,
      proc,
      logFile,
      text: '',
      exit: null,
      exited: null,
    };
    const onData = (chunk) => {
      log.write(chunk);
      child.text += chunk.toString('utf8');
      if (child.text.length > MEMORY_LIMIT) child.text = child.text.slice(-MEMORY_LIMIT / 2);
      if (!quiet && process.env.E2E_VERBOSE) process.stdout.write(`[${label}] ${chunk}`);
    };
    proc.stdout.on('data', onData);
    proc.stderr.on('data', onData);
    child.exited = new Promise((resolve) => {
      proc.on('exit', (code, signal) => {
        child.exit = { code, signal };
        log.end(`# exited code=${code} signal=${signal}\n`);
        this.children.delete(child);
        resolve(child.exit);
      });
      proc.on('error', (err) => {
        child.exit = { code: null, signal: null, error: err.message };
        log.end(`# spawn error: ${err.message}\n`);
        this.children.delete(child);
        resolve(child.exit);
      });
    });
    this.children.add(child);
    return child;
  }

  /** Waits until the child's output matches `re` (returns the match) or it exits. */
  async waitForOutput(child, re, timeout = 15_000) {
    const deadline = Date.now() + timeout;
    for (;;) {
      const m = re.exec(child.text);
      if (m) return m;
      if (child.exit) throw new Error(`${child.name} exited (${JSON.stringify(child.exit)}) before printing ${re}; see ${child.logFile}`);
      if (Date.now() > deadline) throw new Error(`${child.name} did not print ${re} within ${timeout / 1000} s; see ${child.logFile}`);
      await sleep(100);
    }
  }

  /** SIGTERM, then SIGKILL after `timeout`. Resolves with the exit status. */
  async stop(child, { signal = 'SIGTERM', timeout = 8000 } = {}) {
    if (!child || child.exit) return child?.exit ?? null;
    try {
      child.proc.kill(signal);
    } catch {
      // already gone
    }
    const res = await Promise.race([child.exited, sleep(timeout).then(() => null)]);
    if (res) return res;
    try {
      child.proc.kill('SIGKILL');
    } catch {
      // already gone
    }
    return child.exited;
  }

  async stopAll() {
    await Promise.all([...this.children].map((c) => this.stop(c, { timeout: 5000 })));
  }

  killAllSync() {
    for (const c of this.children) {
      try {
        c.proc.kill('SIGKILL');
      } catch {
        // ignore
      }
    }
  }

  tail(child, lines = 30) {
    return child.text.split('\n').slice(-lines).join('\n');
  }
}

/** Kills children on every way out: normal exit, uncaught errors, Ctrl-C, SIGTERM. */
export function installExitHandlers(procs, onSignal) {
  process.on('exit', () => procs.killAllSync());
  for (const sig of ['SIGINT', 'SIGTERM', 'SIGHUP']) {
    process.on(sig, () => {
      process.stderr.write(`\n${sig}: stopping child processes\n`);
      Promise.resolve(onSignal?.())
        .catch(() => undefined)
        .finally(() => {
          procs.killAllSync();
          process.exit(130);
        });
    });
  }
  process.on('uncaughtException', (err) => {
    process.stderr.write(`uncaught exception: ${err.stack ?? err}\n`);
    procs.killAllSync();
    process.exit(1);
  });
  process.on('unhandledRejection', (err) => {
    process.stderr.write(`unhandled rejection: ${err?.stack ?? err}\n`);
  });
}
