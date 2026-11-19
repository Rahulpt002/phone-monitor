import type { Db } from './client.js';

/**
 * Schema (plan 8.4). Every device-scoped table has a `device_id` column so more phones can be
 * added later. Time columns used for range queries are epoch milliseconds (INTEGER); columns that
 * are only displayed are ISO strings.
 *
 * Alert rules are not a separate table: they are part of each device's Settings (`alerts`), which
 * the Dashboard edits through PUT /api/settings (docs/protocol.md 3.4).
 *
 * Migrations are append-only. Never edit a released migration; add a new one.
 */
const MIGRATIONS: { version: number; statements: string[] }[] = [
  {
    version: 1,
    statements: [
      `CREATE TABLE users (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        username TEXT NOT NULL UNIQUE COLLATE NOCASE,
        password_hash TEXT NOT NULL,
        role TEXT NOT NULL CHECK (role IN ('admin', 'viewer')),
        totp_secret TEXT,
        totp_pending_secret TEXT,
        totp_last_step INTEGER,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL
      )`,
      `CREATE TABLE sessions (
        token_hash TEXT PRIMARY KEY,
        user_id INTEGER NOT NULL,
        kind TEXT NOT NULL CHECK (kind IN ('session', 'pending')),
        created_at INTEGER NOT NULL,
        expires_at INTEGER NOT NULL,
        last_used_at INTEGER,
        ip TEXT,
        user_agent TEXT
      )`,
      `CREATE INDEX sessions_user ON sessions(user_id)`,
      `CREATE INDEX sessions_expires ON sessions(expires_at)`,
      `CREATE TABLE devices (
        id TEXT PRIMARY KEY,
        name TEXT NOT NULL,
        token_hash TEXT NOT NULL UNIQUE,
        paired_at TEXT NOT NULL,
        paired_by TEXT,
        revoked_at TEXT,
        last_seen TEXT,
        connected_since TEXT,
        info TEXT,
        state TEXT NOT NULL DEFAULT '{}',
        state_ts TEXT NOT NULL DEFAULT '{}',
        settings TEXT NOT NULL,
        settings_version INTEGER NOT NULL DEFAULT 1,
        outbox INTEGER,
        offline_alert_open INTEGER NOT NULL DEFAULT 0,
        offline_since TEXT,
        last_back_online TEXT,
        alert_state TEXT NOT NULL DEFAULT '{}'
      )`,
      `CREATE TABLE pair_codes (
        code TEXT PRIMARY KEY,
        name TEXT,
        created_by TEXT,
        created_at INTEGER NOT NULL,
        expires_at INTEGER NOT NULL,
        used_at INTEGER,
        device_id TEXT
      )`,
      `CREATE TABLE telemetry_raw (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        device_id TEXT NOT NULL,
        ts INTEGER NOT NULL,
        tier TEXT NOT NULL,
        data TEXT NOT NULL,
        received_at INTEGER NOT NULL,
        UNIQUE (device_id, tier, ts)
      )`,
      `CREATE INDEX telemetry_raw_device_ts ON telemetry_raw(device_id, ts)`,
      `CREATE TABLE telemetry_1m (
        device_id TEXT NOT NULL,
        minute INTEGER NOT NULL,
        n INTEGER NOT NULL,
        data TEXT NOT NULL,
        PRIMARY KEY (device_id, minute)
      )`,
      `CREATE TABLE events (
        device_id TEXT NOT NULL,
        id TEXT NOT NULL,
        ts INTEGER NOT NULL,
        kind TEXT NOT NULL,
        severity TEXT NOT NULL,
        message TEXT NOT NULL,
        data TEXT,
        received_at INTEGER NOT NULL,
        PRIMARY KEY (device_id, id)
      )`,
      `CREATE INDEX events_device_ts ON events(device_id, ts)`,
      `CREATE TABLE commands (
        id TEXT PRIMARY KEY,
        device_id TEXT NOT NULL,
        name TEXT NOT NULL,
        args TEXT NOT NULL,
        source TEXT NOT NULL,
        user TEXT,
        status TEXT NOT NULL,
        created_at TEXT NOT NULL,
        created_ms INTEGER NOT NULL,
        delivered_at TEXT,
        acked_at TEXT,
        finished_at TEXT,
        ok INTEGER,
        output TEXT,
        error TEXT,
        duration_ms INTEGER,
        revert_after_s INTEGER,
        revert_confirm TEXT,
        revert_deadline TEXT,
        updated_ms INTEGER NOT NULL
      )`,
      `CREATE INDEX commands_device_created ON commands(device_id, created_ms)`,
      `CREATE INDEX commands_status ON commands(status)`,
      `CREATE TABLE alerts_sent (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        device_id TEXT,
        kind TEXT NOT NULL,
        severity TEXT NOT NULL,
        title TEXT NOT NULL,
        message TEXT NOT NULL,
        ts INTEGER NOT NULL,
        channels TEXT NOT NULL,
        data TEXT
      )`,
      `CREATE INDEX alerts_sent_ts ON alerts_sent(ts)`,
      `CREATE INDEX alerts_sent_device_ts ON alerts_sent(device_id, ts)`,
      `CREATE TABLE finder_spots (
        device_id TEXT NOT NULL,
        id TEXT NOT NULL,
        created_at TEXT NOT NULL,
        baseline INTEGER NOT NULL DEFAULT 0,
        data TEXT NOT NULL,
        updated_at INTEGER NOT NULL,
        PRIMARY KEY (device_id, id)
      )`,
      `CREATE TABLE sms (
        device_id TEXT NOT NULL,
        id TEXT NOT NULL,
        direction TEXT NOT NULL,
        peer TEXT NOT NULL,
        body TEXT NOT NULL,
        at INTEGER NOT NULL,
        received_at INTEGER NOT NULL,
        PRIMARY KEY (device_id, id)
      )`,
      `CREATE INDEX sms_device_at ON sms(device_id, at)`,
      `CREATE TABLE push_subscriptions (
        endpoint TEXT PRIMARY KEY,
        user_id INTEGER NOT NULL,
        p256dh TEXT NOT NULL,
        auth TEXT NOT NULL,
        created_at TEXT NOT NULL,
        user_agent TEXT
      )`,
      `CREATE INDEX push_subscriptions_user ON push_subscriptions(user_id)`,
      `CREATE TABLE kv (
        key TEXT PRIMARY KEY,
        value TEXT NOT NULL,
        updated_at TEXT NOT NULL
      )`,
    ],
  },
];

export const SCHEMA_VERSION = MIGRATIONS[MIGRATIONS.length - 1]?.version ?? 0;

export async function migrate(db: Db): Promise<number[]> {
  await db.client.execute(
    'CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)',
  );
  const done = new Set((await db.all<{ version: number }>('SELECT version FROM schema_migrations')).map((r) => Number(r.version)));
  const applied: number[] = [];
  for (const m of MIGRATIONS) {
    if (done.has(m.version)) continue;
    await db.batch([
      ...m.statements.map((sql) => ({ sql })),
      { sql: 'INSERT INTO schema_migrations (version, applied_at) VALUES (?, ?)', args: [m.version, new Date().toISOString()] },
    ]);
    applied.push(m.version);
  }
  return applied;
}
