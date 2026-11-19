import { mkdirSync } from 'node:fs';
import { dirname, isAbsolute, resolve } from 'node:path';
import { createClient, type Client, type InValue, type ResultSet } from '@libsql/client';

export type Row = Record<string, unknown>;
export interface Stmt {
  sql: string;
  args?: InValue[];
}

function isLocal(url: string): boolean {
  return url.startsWith('file:') || url === ':memory:';
}

/** For `file:` URLs, make sure the parent directory exists (SQLite does not create it). */
function ensureDir(url: string): void {
  if (!url.startsWith('file:')) return;
  const rest = url.slice('file:'.length).split('?')[0] ?? '';
  if (rest === '' || rest.startsWith(':memory:')) return;
  const path = rest.startsWith('//') ? rest.replace(/^\/\/[^/]*/, '') : rest;
  const abs = isAbsolute(path) ? path : resolve(process.cwd(), path);
  mkdirSync(dirname(abs), { recursive: true });
}

function toObjects(rs: ResultSet): Row[] {
  const cols = rs.columns;
  return rs.rows.map((r) => {
    const o: Row = {};
    for (let i = 0; i < cols.length; i++) o[cols[i] as string] = r[i];
    return o;
  });
}

/**
 * Thin wrapper over @libsql/client. The same code runs against a local SQLite file, an
 * in-memory database (tests) and a remote Turso database. Remote round trips cost ~50-100 ms,
 * so callers group writes with `batch` (one round trip, one transaction).
 */
export class Db {
  private constructor(
    readonly client: Client,
    readonly remote: boolean,
  ) {}

  static async open(url: string, authToken: string | null): Promise<Db> {
    const local = isLocal(url);
    ensureDir(url);
    const client = createClient({
      url,
      authToken: authToken ?? undefined,
      // One connection for local files: every write is serialized, so SQLITE_BUSY cannot occur.
      ...(local ? { concurrency: 1, timeout: 5000 } : {}),
    });
    const db = new Db(client, !local);
    if (local && url !== ':memory:' && !url.includes(':memory:')) {
      await client.execute('PRAGMA journal_mode = WAL');
      await client.execute('PRAGMA synchronous = NORMAL');
    }
    return db;
  }

  async all<T = Row>(sql: string, args: InValue[] = []): Promise<T[]> {
    const rs = await this.client.execute({ sql, args });
    return toObjects(rs) as T[];
  }

  async get<T = Row>(sql: string, args: InValue[] = []): Promise<T | undefined> {
    const rows = await this.all<T>(sql, args);
    return rows[0];
  }

  async run(sql: string, args: InValue[] = []): Promise<{ changes: number; lastId: number | null }> {
    const rs = await this.client.execute({ sql, args });
    return { changes: rs.rowsAffected, lastId: rs.lastInsertRowid == null ? null : Number(rs.lastInsertRowid) };
  }

  /** Runs statements in one write transaction (and one network round trip on Turso). */
  async batch(stmts: Stmt[]): Promise<ResultSet[]> {
    if (stmts.length === 0) return [];
    return this.client.batch(
      stmts.map((s) => ({ sql: s.sql, args: s.args ?? [] })),
      'write',
    );
  }

  static rows(rs: ResultSet | undefined): Row[] {
    return rs ? toObjects(rs) : [];
  }

  async ping(): Promise<boolean> {
    try {
      await this.client.execute('SELECT 1');
      return true;
    } catch {
      return false;
    }
  }

  close(): void {
    this.client.close();
  }
}
