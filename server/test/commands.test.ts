import { PROTOCOL } from '@hsm/shared';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { DeviceRuntime } from '../src/services/devices.js';
import { SENT_NO_ACK_MS } from '../src/services/commands.js';
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
  makeServer,
  msgsOfType,
  pairDevice,
  userWithTotp,
} from './helpers.js';

describe('command pipeline', () => {
  let server: HsmServer;
  let clock: FakeClock;
  let token: string;
  let dev: DeviceRuntime;

  beforeEach(async () => {
    ({ server, clock } = await makeServer());
    ({ token } = await adminWithTotp(server, clock));
    ({ dev } = await pairDevice(server));
  });
  afterEach(async () => server.close());

  const send = (body: Record<string, unknown>) => api(server, 'POST', '/api/commands', { token, body });
  const status = (id: string) => server.ctx.commands.get(id)?.status;
  const dbStatus = async (id: string) =>
    (await server.ctx.db.get<{ status: string }>('SELECT status FROM commands WHERE id = ?', [id]))?.status;

  it('queues while offline and expires after commands.expiry_s', async () => {
    const r = await send({ name: 'refresh' });
    expect(r.status).toBe(201);
    expect(r.body).toMatchObject({ status: 'queued', source: 'dashboard', user: 'admin', device_id: dev.id });
    expect(r.body.created_at).toBe(iso(clock.now()));
    clock.advance(59_000);
    expect(await server.ctx.commands.expireDue()).toBe(0);
    clock.advance(2_000);
    expect(await server.ctx.commands.expireDue()).toBe(1);
    expect(await dbStatus(r.body.id)).toBe('expired');
    // Delivering after expiry does nothing.
    const agent = connectFakeAgent(server, dev);
    await server.ctx.commands.deliverQueued(dev.id);
    expect(msgsOfType(agent, 'cmd')).toHaveLength(0);
  });

  it('delivers queued commands when the Agent reconnects', async () => {
    const r = await send({ name: 'wifi', args: { enabled: false } });
    expect(r.body.status).toBe('queued');
    clock.advance(10_000);
    const agent = connectFakeAgent(server, dev);
    await server.ctx.commands.deliverQueued(dev.id);
    const cmd = msgsOfType(agent, 'cmd')[0];
    expect(cmd).toMatchObject({ id: r.body.id, name: 'wifi', args: { enabled: false }, sent_at: r.body.created_at, revert_after_s: null });
    expect(status(r.body.id)).toBe('sent');
  });

  it('runs sent -> acked -> revert_armed -> kept and never moves backwards', async () => {
    const agent = connectFakeAgent(server, dev);
    const dash = connectFakeDashboard(server, 'admin', { deviceId: dev.id, finder: false });
    const r = await send({ name: 'mobile_data', args: { enabled: false } });
    expect(r.body.status).toBe('sent');
    expect(r.body.revert_confirm).toBe('agent_link');
    const cmd = msgsOfType(agent, 'cmd')[0];
    expect(cmd).toMatchObject({ revert_after_s: 120, revert_confirm: 'agent_link', user: 'admin' });
    const id = r.body.id as string;

    const ack = await ingest(server, dev, { t: 'cmd_ack', seq: 1, id, accepted: true, reason: null, ts: iso(clock.now()) });
    expect(ack.ackSeq).toBe(1);
    expect(status(id)).toBe('acked');

    const deadline = iso(clock.now() + 120_000);
    await ingest(server, dev, {
      t: 'cmd_result', seq: 2, id, ok: true, output: 'mobile data disabled', error: null, duration_ms: 310, revert_deadline: deadline, ts: iso(clock.now()),
    });
    expect(status(id)).toBe('revert_armed');
    expect(server.ctx.devices.snapshot(dev).pending_reverts.map((c) => c.id)).toEqual([id]);

    // A duplicate (re-sent) ack after the result is ignored.
    await ingest(server, dev, { t: 'cmd_ack', seq: 1, id, accepted: true, ts: iso(clock.now()) });
    expect(status(id)).toBe('revert_armed');

    await ingest(server, dev, { t: 'cmd_revert', seq: 3, id, state: 'kept', ts: iso(clock.now()) });
    expect(await dbStatus(id)).toBe('kept');
    expect(server.ctx.devices.snapshot(dev).pending_reverts).toHaveLength(0);
    // Late result and ack do not move it back.
    await ingest(server, dev, { t: 'cmd_result', seq: 2, id, ok: true, output: '', duration_ms: 1, ts: iso(clock.now()) });
    await ingest(server, dev, { t: 'cmd_ack', seq: 1, id, accepted: true, ts: iso(clock.now()) });
    expect(await dbStatus(id)).toBe('kept');

    const seen = msgsOfType(dash.sock, 'command').map((m) => (m.command as { status: string }).status);
    expect(seen).toEqual(['queued', 'sent', 'acked', 'revert_armed', 'kept']);

    const list = await api(server, 'GET', '/api/commands', { token });
    expect(list.body[0]).toMatchObject({ id, status: 'kept', output: 'mobile data disabled', duration_ms: 310, ok: true });
  });

  it('records a reverted change and a rejection', async () => {
    connectFakeAgent(server, dev);
    const a = await send({ name: 'airplane_mode', args: { enabled: true } });
    await ingest(server, dev,
      { t: 'cmd_ack', seq: 1, id: a.body.id, accepted: true, ts: iso(clock.now()) },
      { t: 'cmd_result', seq: 2, id: a.body.id, ok: true, output: '', duration_ms: 5, revert_deadline: iso(clock.now() + 120_000), ts: iso(clock.now()) },
      { t: 'cmd_revert', seq: 3, id: a.body.id, state: 'reverted', detail: 'no server round-trip within 120 s', ts: iso(clock.now()) },
    );
    const rec = (await api(server, 'GET', '/api/commands', { token })).body[0];
    expect(rec).toMatchObject({ status: 'reverted', error: 'no server round-trip within 120 s' });

    const b = await send({ name: 'reboot', confirmed: true, password: ADMIN.password });
    expect(b.status).toBe(201);
    const r = await ingest(server, dev, { t: 'cmd_ack', seq: 4, id: b.body.id, accepted: false, reason: 'reboot_limit', ts: iso(clock.now()) });
    expect(r.ackSeq).toBe(4);
    expect(await dbStatus(b.body.id)).toBe('rejected');
    expect(server.ctx.commands.get(b.body.id)).toBeUndefined();
  });

  it('requires confirmation and the password for reboot', async () => {
    const noConfirm = await send({ name: 'reboot' });
    expect(noConfirm.status).toBe(400);
    expect(noConfirm.body.error).toBe('confirmation_required');
    const noPw = await send({ name: 'reboot', confirmed: true });
    expect(noPw.status).toBe(401);
    expect(noPw.body.error).toBe('reauth_required');
    const badPw = await send({ name: 'reboot', confirmed: true, password: 'wrong password!' });
    expect(badPw.status).toBe(401);
    const ok = await send({ name: 'reboot', confirmed: true, password: ADMIN.password });
    expect(ok.status).toBe(201);
  });

  it('blocks power_off unless allow_power_off is set', async () => {
    const blocked = await send({ name: 'power_off', confirmed: true, password: ADMIN.password });
    expect(blocked.status).toBe(403);
    expect(blocked.body.error).toBe('forbidden');
    await server.ctx.settings.update(dev, { commands: { allow_power_off: true } });
    const ok = await send({ name: 'power_off', confirmed: true, password: ADMIN.password });
    expect(ok.status).toBe(201);
  });

  it('validates names and arguments', async () => {
    expect((await send({ name: 'format_disk' })).status).toBe(400);
    expect((await send({ name: 'brightness', args: { level: 900 } })).status).toBe(400);
    expect((await send({ name: 'mobile_data', args: {} })).status).toBe(400);
    expect((await send({ name: 'refresh', device_id: 'd_missing' })).status).toBe(404);
  });

  it('keeps a dashboard-confirmed revert via POST /commands/:id/keep', async () => {
    const agent = connectFakeAgent(server, dev);
    const r = await send({ name: 'usb_tethering', args: { enabled: false } });
    expect(r.body.revert_confirm).toBe('dashboard');
    const id = r.body.id as string;
    const early = await api(server, 'POST', `/api/commands/${id}/keep`, { token });
    expect(early.status).toBe(409);
    await ingest(server, dev,
      { t: 'cmd_ack', seq: 1, id, accepted: true, ts: iso(clock.now()) },
      { t: 'cmd_result', seq: 2, id, ok: true, output: '', duration_ms: 50, revert_deadline: iso(clock.now() + 120_000), ts: iso(clock.now()) },
    );
    const keep = await api(server, 'POST', `/api/commands/${id}/keep`, { token });
    expect(keep.status).toBe(200);
    expect(keep.body.status).toBe('revert_armed');
    expect(msgsOfType(agent, 'cmd_keep')).toEqual([{ t: 'cmd_keep', id }]);
    expect((await api(server, 'POST', '/api/commands/c_unknown/keep', { token })).status).toBe(404);
  });

  it('updates settings.watchdog.charge_limit_pct for charge_limit only once the Agent applied it', async () => {
    const agent = connectFakeAgent(server, dev);
    const v = dev.settingsVersion;
    const before = dev.settings.watchdog.charge_limit_pct;
    const r = await send({ name: 'charge_limit', args: { percent: 55 } });
    expect(r.status).toBe(201);
    // Not yet: an expired or rejected command must never take effect later (F34).
    expect(dev.settings.watchdog.charge_limit_pct).toBe(before);
    expect(dev.settingsVersion).toBe(v);
    await ingest(server, dev, { t: 'cmd_ack', seq: 1, id: r.body.id, accepted: true, ts: iso(clock.now()) });
    expect(dev.settingsVersion).toBe(v);
    await ingest(server, dev, { t: 'cmd_result', seq: 2, id: r.body.id, ok: true, output: '', duration_ms: 4, ts: iso(clock.now()) });
    expect(dev.settings.watchdog.charge_limit_pct).toBe(55);
    expect(dev.settingsVersion).toBe(v + 1);
    expect(msgsOfType(agent, 'settings')[0]).toMatchObject({ version: v + 1 });
  });

  it('leaves the charge limit setting alone when charge_limit expires, is rejected or fails', async () => {
    const before = dev.settings.watchdog.charge_limit_pct;
    const v = dev.settingsVersion;
    // Offline: never delivered, expires.
    await send({ name: 'charge_limit', args: { percent: 60 } });
    clock.advance(61_000);
    await server.ctx.commands.expireDue();
    connectFakeAgent(server, dev);
    await server.ctx.commands.deliverQueued(dev.id);
    const rejected = await send({ name: 'charge_limit', args: { percent: 65 } });
    await ingest(server, dev, { t: 'cmd_ack', seq: 1, id: rejected.body.id, accepted: false, reason: 'unsupported', ts: iso(clock.now()) });
    const failed = await send({ name: 'charge_limit', args: { percent: 70 } });
    await ingest(server, dev, { t: 'cmd_result', seq: 2, id: failed.body.id, ok: false, error: 'no charge control', duration_ms: 3, ts: iso(clock.now()) });
    expect(dev.settings.watchdog.charge_limit_pct).toBe(before);
    expect(dev.settingsVersion).toBe(v);
  });

  it('truncates output to 64 KiB', async () => {
    connectFakeAgent(server, dev);
    const r = await send({ name: 'process_list' });
    await ingest(server, dev, {
      t: 'cmd_result', seq: 1, id: r.body.id, ok: true, output: 'é'.repeat(PROTOCOL.max_output_bytes), duration_ms: 1, ts: iso(clock.now()),
    });
    const rec = (await api(server, 'GET', '/api/commands', { token })).body[0];
    expect(rec.status).toBe('done');
    expect(Buffer.byteLength(rec.output, 'utf8')).toBeLessThanOrEqual(PROTOCOL.max_output_bytes);
    expect(rec.output.endsWith('é')).toBe(true);
  });

  it('marks a sent command without any ack as expired, but accepts a late report', async () => {
    connectFakeAgent(server, dev);
    const r = await send({ name: 'refresh' });
    clock.advance(60_000 + SENT_NO_ACK_MS + 1000);
    await server.ctx.commands.expireDue();
    expect(await dbStatus(r.body.id)).toBe('expired');
    await ingest(server, dev, { t: 'cmd_result', seq: 9, id: r.body.id, ok: true, output: 'ok', duration_ms: 3, ts: iso(clock.now()) });
    expect(await dbStatus(r.body.id)).toBe('done');
  });

  it('hides shell output from viewers', async () => {
    connectFakeAgent(server, dev);
    const r = await send({ name: 'shell', args: { cmd: 'id' }, confirmed: true, password: ADMIN.password });
    await ingest(server, dev, { t: 'cmd_result', seq: 1, id: r.body.id, ok: true, output: 'uid=0(root)', duration_ms: 3, ts: iso(clock.now()) });
    // Viewers need TOTP too (plan 10); creating a user needs the admin's password.
    const { token: vt } = await userWithTotp(server, clock, token, { username: 'viewer', password: 'viewer-password', role: 'viewer' });
    const list = await api(server, 'GET', '/api/commands', { token: vt });
    expect(list.body[0]).toMatchObject({ name: 'shell', args: {}, output: null });
  });
});
