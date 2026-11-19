import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { CliError, parseCli } from '../src/cli.js';
import { pair, PairError } from '../src/pairing.js';
import { simDeviceInfo } from '../src/phone/phone.js';
import { StateStore } from '../src/state.js';
import { agentWsUrl, truncateUtf8 } from '../src/util.js';

const env = { cwd: '/work', defaultStateFile: '/pkg/.sim-state.json', colorDefault: true };

describe('parseCli', () => {
  it('applies defaults', () => {
    const o = parseCli([], env);
    expect(o).toMatchObject({ server: null, stateFile: '/pkg/.sim-state.json', speed: 1, scenarios: [], color: true, keepLink: false });
  });

  it('parses every option, with repeated and comma-separated scenarios', () => {
    const o = parseCli(
      [
        '--server=https://hsm.example.com',
        '--code', 'abcd2345',
        '--state', 'tmp/s.json',
        '--speed', '30',
        '--scenario', 'overheat',
        '--scenario', 'cell-flap,weak-power',
        '--offline-for', '120',
        '--keep-link',
        '--no-color',
        '--verbose',
      ],
      env,
    );
    expect(o.server).toBe('https://hsm.example.com');
    expect(o.code).toBe('abcd2345');
    expect(o.stateFile).toBe('/work/tmp/s.json');
    expect(o.speed).toBe(30);
    expect(o.scenarios).toEqual(['overheat', 'cell-flap', 'weak-power']);
    expect(o.offlineForS).toBe(120);
    expect(o.keepLink && o.verbose && !o.color).toBe(true);
  });

  it('rejects bad input', () => {
    expect(() => parseCli(['--scenario', 'meteor'], env)).toThrow(CliError);
    expect(() => parseCli(['--speed', '0'], env)).toThrow(/speed/);
    expect(() => parseCli(['--server', 'ftp://x'], env)).toThrow(/http/);
    expect(() => parseCli(['--code', 'A2B3C4D5', '--token', 't'], env)).toThrow(/either/);
    expect(() => parseCli(['--code'], env)).toThrow(/needs a value/);
    expect(() => parseCli(['--bogus'], env)).toThrow(/unknown option/);
  });
});

describe('pairing (POST /api/pair)', () => {
  let http: Server | null = null;
  afterEach(() => {
    http?.close();
    http = null;
  });

  async function serve(handler: (body: Record<string, unknown>) => { status: number; body: unknown }): Promise<string> {
    http = createServer((req, res) => {
      let raw = '';
      req.on('data', (c) => (raw += c));
      req.on('end', () => {
        if (req.method !== 'POST' || req.url !== '/api/pair') {
          res.writeHead(404).end();
          return;
        }
        const out = handler(JSON.parse(raw));
        res.writeHead(out.status, { 'content-type': 'application/json' }).end(JSON.stringify(out.body));
      });
    });
    await new Promise<void>((r) => http!.listen(0, '127.0.0.1', () => r()));
    return `http://127.0.0.1:${(http!.address() as AddressInfo).port}`;
  }

  it('sends the code and a DeviceInfo, and returns the credentials', async () => {
    let seen: Record<string, unknown> = {};
    const base = await serve((body) => {
      seen = body;
      return { status: 200, body: { device_id: 'd_1', device_token: 'tok', ws_url: 'ws://x/ws/agent', server_url: 'http://x/' } };
    });
    const res = await pair(`${base}/`, 'abcd2345', simDeviceInfo(null, '1234'));
    expect(res).toEqual({ device_id: 'd_1', device_token: 'tok', ws_url: 'ws://x/ws/agent', server_url: 'http://x' });
    expect(seen.code).toBe('ABCD2345');
    expect(seen.device).toMatchObject({ manufacturer: 'samsung', model: 'SM-G991B', profile_id: 'samsung_galaxy_s', rooted: true, imei_tail: '1234' });
  });

  it('turns an API error into a PairError', async () => {
    const base = await serve(() => ({ status: 400, body: { error: 'bad_request', message: 'invalid or expired pairing code' } }));
    const err = await pair(base, 'ZZZZ2222', simDeviceInfo(null, '1234')).catch((e) => e);
    expect(err).toBeInstanceOf(PairError);
    expect(err.status).toBe(400);
    expect(err.message).toMatch(/invalid or expired pairing code/);
  });

  it('reports an unreachable server', async () => {
    const err = await pair('http://127.0.0.1:9', 'ABCD2345', simDeviceInfo(null, '1234'), { timeoutMs: 3000 }).catch((e) => e);
    expect(err).toBeInstanceOf(PairError);
    expect(err.message).toMatch(/cannot reach/);
  });
});

describe('helpers', () => {
  it('derives the agent socket URL from the server URL', () => {
    expect(agentWsUrl('http://localhost:8080')).toBe('ws://localhost:8080/ws/agent');
    expect(agentWsUrl('https://hsm.onrender.com/')).toBe('wss://hsm.onrender.com/ws/agent');
  });

  it('truncates long output on a UTF-8 boundary', () => {
    const out = truncateUtf8('é'.repeat(100), 51);
    expect(Buffer.byteLength(out)).toBeLessThanOrEqual(51);
    expect(out.endsWith('[output truncated]')).toBe(true);
  });

  it('persists state atomically and reads it back', () => {
    const dir = mkdtempSync(join(tmpdir(), 'hsm-sim-'));
    try {
      const file = join(dir, 'state.json');
      const s = new StateStore(file);
      s.update({ token: 'secret', device_id: 'd_9' }, true);
      expect(JSON.parse(readFileSync(file, 'utf8'))).toMatchObject({ version: 1, token: 'secret', device_id: 'd_9' });
      expect(new StateStore(file).get().device_id).toBe('d_9');
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});
