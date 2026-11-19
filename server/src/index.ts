import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import * as nodeUtil from 'node:util';
import { ConfigError, loadConfig, REPO_DIR, SERVER_DIR } from './config.js';
import { buildServer } from './server.js';

// Entry point only. Import buildServer from ./server.js (tests do) to embed the server.

/**
 * Loads the first .env found (cwd, server/, repo root) for local development. Variables already
 * in the environment win, so hosting platforms are unaffected.
 */
function loadDotEnv(): string | null {
  const parseEnv = (nodeUtil as { parseEnv?: (s: string) => Record<string, string> }).parseEnv;
  if (!parseEnv) return null;
  for (const dir of [process.cwd(), SERVER_DIR, REPO_DIR]) {
    const file = resolve(dir, '.env');
    if (!existsSync(file)) continue;
    const vars = parseEnv(readFileSync(file, 'utf8'));
    for (const [k, v] of Object.entries(vars)) if (process.env[k] === undefined) process.env[k] = v;
    return file;
  }
  return null;
}

async function main(): Promise<void> {
  const envFile = loadDotEnv();
  let config;
  try {
    config = loadConfig();
  } catch (err) {
    console.error(err instanceof ConfigError ? err.message : err);
    process.exit(1);
  }
  const server = await buildServer(config);
  const log = server.ctx.log;
  const address = await server.listen();
  log.info(
    {
      address,
      version: config.version,
      public_url: config.publicUrl,
      database: config.databaseUrl.split('?')[0],
      env_file: envFile,
      telegram: server.ctx.telegram.configured,
    },
    'Hotspot Monitor server listening',
  );

  let shuttingDown = false;
  const shutdown = async (signal: string) => {
    if (shuttingDown) return;
    shuttingDown = true;
    log.info({ signal }, 'shutting down');
    const force = setTimeout(() => {
      log.error('shutdown took too long; exiting');
      process.exit(1);
    }, 10_000);
    force.unref();
    try {
      await server.close();
      process.exit(0);
    } catch (err) {
      log.error({ err }, 'error during shutdown');
      process.exit(1);
    }
  };
  process.on('SIGTERM', () => void shutdown('SIGTERM'));
  process.on('SIGINT', () => void shutdown('SIGINT'));
  process.on('unhandledRejection', (err) => log.error({ err }, 'unhandled promise rejection'));
}

main().catch((err) => {
  console.error('Failed to start the server:', err);
  process.exit(1);
});
