import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ACKED_NO_RESULT_MS, REVERT_REPORT_GRACE_MS } from '../src/services/commands.js';
import type { DeviceRuntime } from '../src/services/devices.js';
import type { HsmServer } from '../src/server.js';
import { iso } from '../src/util.js';
import {
  ADMIN,
  adminWithTotp,
  api,
  connectFakeAgent,
  connectFakeDashboard,
  type FakeClock,
  ingest,
  loginWithTotp,
  makeServer,
  msgsOfType,
  pairDevice,
  userWithTotp,
} from './helpers.js';

describe('commands: secrets, re-delivery, framing and final timeouts', () => {
  let server: HsmServer;
  let clock: FakeClock;
  let token: string;
  let secret: string;
  let dev: DeviceRuntime;

  beforeEach(async () => {
    ({ server, clock } = await makeServer());
    ({ token, secret } = await adminWithTotp(server, clock));
    ({ dev } = await pairDevice(server));
  });
  afterEach(async () => server.close());

  const send = (body: Record<string, unknown>) => api(server, 'POST', '/api/commands', { token, body });
  const row = async (id: string) =>
    server.ctx.db.get<{ status: string; args: string; error: string | null }>('SELECT status, args, error FROM commands WHERE id = ?', [id]);
  const now = () => iso(clock.now());
  const hotspot = { enabled: true, ssid: 'Pole', password: 'SuperSecret123' };

  it('never returns the hotspot password to any role, and blanks it once the Agent has it (F21/F48)', async () => {
    const agent = connectFakeAgent(server, dev);
    const adminDash = connectFakeDashboard(server, 'admin', { deviceId: dev.id, finder: false });
    const viewerDash = connectFakeDashboard(server, 'viewer', { deviceId: dev.id, finder: false });
    const r = await send({ name: 'hotspot', args: hotspot });
    expect(r.status).toBe(201);
    expect(r.body.args).toEqual({ enabled: true, ssid: 'Pole' });
    // The Agent needs it.
    expect(msgsOfType(agent, 'cmd')[0]).toMatchObject({ id: r.body.id, args: hotspot });
    // Kept in storage only until the Agent acknowledges it (a re-delivery may need it).
    expect(JSON.parse((await row(r.body.id))!.args)).toEqual(hotspot);

    await ingest(server, dev, { t: 'cmd_ack', seq: 1, id: r.body.id, accepted: true, ts: now() });
    expect(JSON.parse((await row(r.body.id))!.args)).toEqual({ enabled: true, ssid: 'Pole' });
    await ingest(server, dev, { t: 'cmd_result', seq: 2, id: r.body.id, ok: true, output: 'hotspot on', duration_ms: 900, ts: now() });

    for (const sock of [adminDash.sock, viewerDash.sock]) {
      const seen = msgsOfType(sock, 'command');
      expect(seen.length).toBeGreaterThanOrEqual(3);
      for (const m of seen) expect(JSON.stringify(m)).not.toContain('SuperSecret123');
    }
    const { token: vt } = await userWithTotp(server, clock, token, { username: 'viewer', password: 'viewer-password', role: 'viewer' });
    for (const t of [token, vt]) {
      const list = await api(server, 'GET', '/api/commands', { token: t });
      expect(list.status).toBe(200);
      expect(list.body[0]).toMatchObject({ name: 'hotspot', args: { enabled: true, ssid: 'Pole' } });
      expect(JSON.stringify(list.body)).not.toContain('SuperSecret123');
    }
  });

  it('blanks the password of a hotspot command that expired undelivered', async () => {
    const r = await send({ name: 'hotspot', args: hotspot });
    expect(r.body.status).toBe('queued');
    expect(JSON.stringify(server.ctx.commands.get(r.body.id))).not.toContain('SuperSecret123');
    clock.advance(61_000);
    await server.ctx.commands.expireDue();
    expect(await row(r.body.id)).toMatchObject({ status: 'expired' });
    expect(JSON.parse((await row(r.body.id))!.args)).toEqual({ enabled: true, ssid: 'Pole' });
  });

  it('accepts only printable ASCII hotspot passwords and shell timeouts up to 600 s (F16)', async () => {
    connectFakeAgent(server, dev);
    expect((await send({ name: 'hotspot', args: { enabled: true, password: 'Straße-1234' } })).status).toBe(400);
    expect((await send({ name: 'hotspot', args: { enabled: true, password: 'short' } })).status).toBe(400);
    expect((await send({ name: 'hotspot', args: { enabled: true, password: 'x'.repeat(64) } })).status).toBe(400);
    expect((await send({ name: 'hotspot', args: { enabled: true, password: 'with space ~!' } })).status).toBe(201);
    const shell = (timeout_s: number) => send({ name: 'shell', args: { cmd: 'id', timeout_s }, confirmed: true, password: ADMIN.password });
    expect((await shell(600)).status).toBe(201);
    expect((await shell(601)).status).toBe(400);
  });

  it('applies the Kotlin Agent\'s rules to SMS numbers, USSD codes and spot names (F16)', async () => {
    connectFakeAgent(server, dev);
    const sms = (to: string) => send({ name: 'send_sms', args: { to, body: 'hi' }, confirmed: true });
    expect((await sms('+49 (170) 123-45.67')).status).toBe(201);
    expect((await sms('INFO')).status).toBe(400);
    expect((await sms('1'.repeat(21))).status).toBe(400);
    const ussd = (code: string) => send({ name: 'ussd', args: { code } });
    expect((await ussd('*123#')).status).toBe(201);
    expect((await ussd('*100*2+1#')).status).toBe(201);
    expect((await ussd('123#')).status).toBe(400);
    const spot = (args: Record<string, unknown>) => send({ name: 'finder_spot_test', args });
    expect((await spot({ name: 'Roof NE', orientation: 'upright' })).status).toBe(201);
    expect((await spot({ name: 'x'.repeat(65) })).status).toBe(400);
    expect((await spot({ name: '   ' })).status).toBe(400);
    expect((await spot({ name: 'bad\u0007name' })).status).toBe(400);
    expect((await spot({ name: 'ok', orientation: 'o'.repeat(33) })).status).toBe(400);
    expect((await send({ name: 'finder_set_baseline', args: { spot_id: 'has space' } })).status).toBe(400);
  });

  it('counts wrong command passwords per session, not per user (F39)', async () => {
    connectFakeAgent(server, dev);
    for (let i = 0; i < 5; i++) {
      const r = await send({ name: 'reboot', confirmed: true, password: 'wrong-password' });
      expect(r.status).toBe(401);
    }
    expect((await send({ name: 'reboot', confirmed: true, password: ADMIN.password })).status).toBe(429);
    // Another session of the same admin is not locked out, and neither is unpairing from it.
    const other = await loginWithTotp(server, clock, ADMIN, secret);
    const fromOther = await api(server, 'POST', '/api/commands', { token: other, body: { name: 'reboot', confirmed: true, password: ADMIN.password } });
    expect(fromOther.status).toBe(201);
    for (let i = 0; i < 5; i++) {
      expect((await api(server, 'DELETE', `/api/devices/${dev.id}`, { token: other, body: { password: 'wrong-password' } })).status).toBe(401);
    }
    expect((await api(server, 'DELETE', `/api/devices/${dev.id}`, { token: other, body: { password: ADMIN.password } })).status).toBe(429);
    const third = await loginWithTotp(server, clock, ADMIN, secret);
    const put = await api(server, 'PUT', '/api/settings', {
      token: third,
      body: { device_id: dev.id, settings: { profile_override: { usb_function: 'ncm' } }, password: ADMIN.password },
    });
    expect(put.status).toBe(200);
  });

  it('re-sends an unacknowledged "sent" command after the Agent reconnects (F14)', async () => {
    const dead = connectFakeAgent(server, dev); // looks open, but the frame never arrives
    const r = await send({ name: 'hotspot', args: hotspot });
    expect(r.body.status).toBe('sent');
    const acked = await send({ name: 'wifi', args: { enabled: false } });
    await ingest(server, dev, { t: 'cmd_ack', seq: 1, id: acked.body.id, accepted: true, ts: now() });
    expect(msgsOfType(dead, 'cmd')).toHaveLength(2);

    clock.advance(30_000);
    const fresh = connectFakeAgent(server, dev);
    await server.ctx.commands.deliverQueued(dev.id);
    const cmds = msgsOfType(fresh, 'cmd');
    expect(cmds).toHaveLength(1); // the acked one is not re-sent
    expect(cmds[0]).toMatchObject({ id: r.body.id, name: 'hotspot', args: hotspot, sent_at: r.body.created_at });
    expect(server.ctx.commands.get(r.body.id)).toMatchObject({ status: 'sent', delivered_at: iso(clock.now()) });

    // Past commands.expiry_s it is not offered again (the Agent would reject it as expired).
    clock.advance(31_000);
    const later = connectFakeAgent(server, dev);
    await server.ctx.commands.deliverQueued(dev.id);
    expect(msgsOfType(later, 'cmd')).toHaveLength(0);
  });

  it('ends with the same status whether the reports arrive in one batch or one by one (F15)', async () => {
    connectFakeAgent(server, dev);
    const reports = (id: string, seq: number) => [
      { t: 'cmd_ack', seq, id, accepted: true, ts: now() },
      { t: 'cmd_result', seq: seq + 1, id, ok: false, error: 'The Agent stopped while the command was running', duration_ms: 5, ts: now() },
      { t: 'cmd_revert', seq: seq + 2, id, state: 'reverted', detail: 'restored after restart', ts: now() },
    ];
    const a = await send({ name: 'mobile_data', args: { enabled: false } });
    await ingest(server, dev, ...reports(a.body.id, 1));
    const b = await send({ name: 'mobile_data', args: { enabled: false } });
    for (const m of reports(b.body.id, 4)) await ingest(server, dev, m);
    expect((await row(a.body.id))?.status).toBe('reverted');
    expect((await row(b.body.id))?.status).toBe('reverted');
    expect(await row(b.body.id)).toMatchObject({ error: 'restored after restart' });

    // Also after a restart, when the failed command is no longer in memory.
    const c = await send({ name: 'airplane_mode', args: { enabled: true } });
    const [ack, result, revert] = reports(c.body.id, 7);
    await ingest(server, dev, ack, result);
    await server.ctx.commands.load();
    await ingest(server, dev, { ...revert, state: 'kept' });
    expect((await row(c.body.id))?.status).toBe('kept');
  });

  it('gives up on a command that was acked but never finished, but still takes a late result (F17)', async () => {
    connectFakeAgent(server, dev);
    const dash = connectFakeDashboard(server, 'admin', { deviceId: dev.id, finder: false });
    const r = await send({ name: 'refresh' });
    await ingest(server, dev, { t: 'cmd_ack', seq: 1, id: r.body.id, accepted: true, ts: now() });
    clock.advance(ACKED_NO_RESULT_MS - 1000);
    await server.ctx.commands.expireDue();
    expect((await row(r.body.id))?.status).toBe('acked');
    clock.advance(1000);
    await server.ctx.commands.expireDue();
    expect(await row(r.body.id)).toMatchObject({ status: 'expired', error: expect.stringContaining('No result from the Agent within 30 min') });
    expect(msgsOfType(dash.sock, 'command').at(-1)).toMatchObject({ command: { status: 'expired' } });
    // A repeated ack does not move it back; the late result does count.
    await ingest(server, dev, { t: 'cmd_ack', seq: 1, id: r.body.id, accepted: true, ts: now() });
    expect((await row(r.body.id))?.status).toBe('expired');
    await ingest(server, dev, { t: 'cmd_result', seq: 2, id: r.body.id, ok: true, output: 'late', duration_ms: 1, ts: now() });
    expect((await row(r.body.id))?.status).toBe('done');

    // A long shell command gets its own timeout on top.
    const sh = await send({ name: 'shell', args: { cmd: 'sleep 590', timeout_s: 600 }, confirmed: true, password: ADMIN.password });
    await ingest(server, dev, { t: 'cmd_ack', seq: 3, id: sh.body.id, accepted: true, ts: now() });
    clock.advance(ACKED_NO_RESULT_MS + 5 * 60_000);
    await server.ctx.commands.expireDue();
    expect((await row(sh.body.id))?.status).toBe('acked');
    clock.advance(5 * 60_000);
    await server.ctx.commands.expireDue();
    expect((await row(sh.body.id))?.status).toBe('expired');
  });

  it('gives up on an auto-revert the Agent never reported, but still takes a late report (F17)', async () => {
    connectFakeAgent(server, dev);
    const r = await send({ name: 'mobile_data', args: { enabled: false } });
    const deadline = clock.now() + 120_000;
    await ingest(server, dev,
      { t: 'cmd_ack', seq: 1, id: r.body.id, accepted: true, ts: now() },
      { t: 'cmd_result', seq: 2, id: r.body.id, ok: true, output: '', duration_ms: 5, revert_deadline: iso(deadline), ts: now() },
    );
    expect(server.ctx.devices.snapshot(dev).pending_reverts).toHaveLength(1);
    clock.t = deadline + REVERT_REPORT_GRACE_MS - 1000;
    await server.ctx.commands.expireDue();
    expect((await row(r.body.id))?.status).toBe('revert_armed');
    clock.advance(1000);
    await server.ctx.commands.expireDue();
    expect(await row(r.body.id)).toMatchObject({ status: 'expired', error: expect.stringContaining('keeping or reverting') });
    expect(server.ctx.devices.snapshot(dev).pending_reverts).toHaveLength(0);
    // A repeated result does not re-arm it; the late cmd_revert counts.
    await ingest(server, dev, { t: 'cmd_result', seq: 2, id: r.body.id, ok: true, output: '', duration_ms: 5, revert_deadline: iso(deadline), ts: now() });
    expect((await row(r.body.id))?.status).toBe('expired');
    await ingest(server, dev, { t: 'cmd_revert', seq: 3, id: r.body.id, state: 'kept', ts: now() });
    expect((await row(r.body.id))?.status).toBe('kept');
  });

  const raceCases = [
    { order: 'before', from: 'revert_armed', report: 'kept' },
    { order: 'after', from: 'revert_armed', report: 'kept' },
    { order: 'before', from: 'acked', report: 'revert_armed' },
    { order: 'after', from: 'acked', report: 'revert_armed' },
  ] as const;
  for (const { order, from, report } of raceCases) {
    it(`never shows 'expired' over a ${report} committed while expireDue writes ${from} -> expired (report ${order} its UPDATE)`, async () => {
      connectFakeAgent(server, dev);
      const dash = connectFakeDashboard(server, 'admin', { deviceId: dev.id, finder: false });
      const r = await send({ name: 'mobile_data', args: { enabled: false } });
      const id = r.body.id as string;
      await ingest(server, dev, { t: 'cmd_ack', seq: 1, id, accepted: true, ts: now() });
      let lateReport: Record<string, unknown>;
      if (from === 'revert_armed') {
        const deadline = clock.now() + 120_000;
        await ingest(server, dev, { t: 'cmd_result', seq: 2, id, ok: true, output: '', duration_ms: 5, revert_deadline: iso(deadline), ts: now() });
        clock.t = deadline + REVERT_REPORT_GRACE_MS;
        lateReport = { t: 'cmd_revert', seq: 3, id, state: 'kept' };
      } else {
        clock.advance(ACKED_NO_RESULT_MS);
        lateReport = { t: 'cmd_result', seq: 2, id, ok: true, output: '', duration_ms: 5, revert_deadline: iso(clock.now() + 120_000) };
      }
      expect(server.ctx.commands.get(id)?.status).toBe(from);
      const db = server.ctx.db;
      const realBatch = db.batch.bind(db);
      let intercepted = false;
      db.batch = async (stmts) => {
        if (intercepted) return realBatch(stmts);
        intercepted = true;
        const late = () => ingest(server, dev, { ...lateReport, ts: now() });
        if (order === 'before') await late();
        const out = await realBatch(stmts);
        if (order === 'after') await late();
        return out;
      };
      try {
        await server.ctx.commands.expireDue();
      } finally {
        db.batch = realBatch;
      }
      expect((await row(id))?.status).toBe(report);
      // Memory (preferred by GET /commands and snapshots) and the last broadcast agree with storage.
      if (report === 'revert_armed') expect(server.ctx.commands.get(id)?.status).toBe('revert_armed');
      else expect(server.ctx.commands.get(id)).toBeUndefined();
      const list = await api(server, 'GET', '/api/commands', { token });
      expect(list.body.find((c: { id: string }) => c.id === id)?.status).toBe(report);
      expect(msgsOfType(dash.sock, 'command').at(-1)).toMatchObject({ command: { status: report } });
    });
  }
});
