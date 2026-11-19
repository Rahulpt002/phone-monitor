import {
  DEFAULT_SETTINGS,
  EVENT_ALERT,
  signalScore,
  type AgentEvent,
  type AlertKind,
  type AlertRecord,
  type AlertSeverity,
  type FinderSpot,
  type RadioInfo,
} from '@hsm/shared';
import type { AppContext } from '../context.js';
import { escapeHtml } from '../notify/telegram.js';
import type { PushPayload } from '../notify/push.js';
import { formatDuration, iso, isNum, parseTs, safeJsonParse } from '../util.js';
import type { DeviceRuntime, GroupKey } from './devices.js';

export interface ServingCell {
  cell_id: number | null;
  pci: number | null;
  earfcn: number | null;
}

/** Timers and flags of the server-evaluated alerts, persisted with the device. */
export interface AlertState {
  lowBattery?: boolean;
  signalLowSince?: number | null;
  signalDropAlerted?: boolean;
  cell?: ServingCell | null;
  cellSince?: number | null;
  /** Learned "usual" serving cell when no baseline spot is set. */
  usualCell?: ServingCell | null;
  cellOffSince?: number | null;
  cellChangedAlerted?: boolean;
  tetherDownSince?: number | null;
  tetherDownAlerted?: boolean;
  quota?: { cycle: string; steps: number[] } | null;
  /** Whether the open offline alert was actually delivered (back online is sent only then). */
  offlineAlertSent?: boolean;
}

export interface RaiseInput {
  deviceId: string | null;
  kind: AlertKind;
  severity: AlertSeverity;
  title?: string;
  message: string;
  data?: Record<string, unknown> | null;
  /** Ignore `alerts.enabled` and channel switches (test alert). */
  force?: boolean;
  /** Content viewers must not read (SMS text). */
  sensitive?: boolean;
}

/** Wait for backlog after a reconnect so "back online" can name the reason (protocol 6). */
export const BACK_ONLINE_DELAY_MS = 10_000;
/**
 * After a restart (deploy, free-tier spin-down) the Agent needs a moment to reconnect, so a device
 * is not declared offline sooner than this after the process started.
 */
export const STARTUP_GRACE_MS = 90_000;
/** Without a baseline, a cell that has served this long becomes the "usual" cell. */
export const USUAL_CELL_LEARN_MS = 6 * 3_600_000;
/** Readings older than this are not used for condition timers. */
export const STALE_STATE_MS = 10 * 60_000;
/** Event alerts for older (backlog) events are skipped; the back-online alert summarizes them. */
export const EVENT_ALERT_MAX_AGE_MS = 30 * 60_000;
export const ALERT_RETENTION_MS = 365 * 86_400_000;
const NO_COOLDOWN: AlertKind[] = ['back_online', 'data_quota', 'server_test'];
const REASON_KINDS = ['boot', 'agent_start', 'watchdog_action', 'power_lost', 'power_connected', 'revert', 'reboot_blocked'];

const TITLES: Record<AlertKind, string> = {
  offline: 'Phone offline',
  back_online: 'Phone back online',
  weak_power: 'Power supply too weak',
  low_battery: 'Low battery',
  hot: 'Phone is hot',
  signal_drop: 'Signal drop',
  cell_changed: 'Serving cell changed',
  tether_down: 'USB tethering down',
  usb_power_lost: 'USB power lost',
  data_quota: 'Data quota',
  moved: 'Phone moved',
  watchdog: 'Watchdog acted',
  carrier_sms: 'Carrier SMS',
  server_test: 'Test alert',
};

export function sameCell(a: ServingCell, b: ServingCell): boolean {
  if (a.cell_id != null && b.cell_id != null) return a.cell_id === b.cell_id;
  if (a.pci != null && b.pci != null) {
    return a.pci === b.pci && (a.earfcn == null || b.earfcn == null || a.earfcn === b.earfcn);
  }
  // Not enough information to claim a change.
  return true;
}

const num = (v: unknown): number | null => (isNum(v) ? v : null);

export function servingOfRadio(r: RadioInfo | undefined): ServingCell | null {
  if (!r) return null;
  const c = { cell_id: num(r.cell_id), pci: num(r.pci), earfcn: num(r.earfcn) };
  return c.cell_id == null && c.pci == null ? null : c;
}

export function servingOfSpot(s: FinderSpot | null): ServingCell | null {
  const v = s?.serving;
  if (!v) return null;
  const c = { cell_id: num(v.cell_id), pci: num(v.pci), earfcn: num(v.earfcn) };
  return c.cell_id == null && c.pci == null ? null : c;
}

/** Billing cycle start (YYYY-MM-DD, UTC) for a cycle that starts on `day` of each month. */
export function cycleStart(now: number, day: number): string {
  const d = new Date(now);
  let y = d.getUTCFullYear();
  let m = d.getUTCMonth();
  if (d.getUTCDate() < day) {
    m -= 1;
    if (m < 0) {
      m = 11;
      y -= 1;
    }
  }
  return `${y}-${String(m + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

function fmtBytes(n: number): string {
  if (n >= 1e9) return `${(n / 1e9).toFixed(2)} GB`;
  if (n >= 1e6) return `${(n / 1e6).toFixed(1)} MB`;
  return `${Math.round(n / 1e3)} kB`;
}

const fmtTime = (ms: number) => `${iso(ms).replace('T', ' ').slice(0, 16)} UTC`;

function rowToAlert(r: Record<string, unknown>): AlertRecord {
  return {
    id: Number(r.id),
    device_id: (r.device_id as string | null) ?? null,
    kind: String(r.kind) as AlertKind,
    severity: String(r.severity) as AlertSeverity,
    title: String(r.title),
    message: String(r.message),
    ts: iso(Number(r.ts)),
    channels: safeJsonParse<('telegram' | 'push')[]>(r.channels, []),
    data: safeJsonParse<Record<string, unknown> | null>(r.data, null),
  };
}

export function redactAlertForViewer(a: AlertRecord): AlertRecord {
  if (a.kind !== 'carrier_sms') return a;
  return { ...a, message: 'A new SMS arrived (admins can read it in Messages).', data: null };
}

/**
 * Alerts (plan 8.5, docs/protocol.md 6): offline detection, back online, event alerts and the
 * server-evaluated conditions; delivery to Telegram and Web Push; history.
 */
export class AlertService {
  /** Last delivery per device + kind + severity, for the cooldown. */
  private lastSent = new Map<string, number>();
  private pendingBackOnline = new Map<string, { due: number; offlineFrom: number; alertSent: boolean }>();

  constructor(private ctx: AppContext) {}

  async load(): Promise<void> {
    const since = this.ctx.clock.now() - 7 * 86_400_000;
    const rows = await this.ctx.db.all(
      'SELECT device_id, kind, severity, MAX(ts) AS ts FROM alerts_sent WHERE ts > ? GROUP BY device_id, kind, severity',
      [since],
    );
    for (const r of rows) this.lastSent.set(`${(r.device_id as string | null) ?? '-'}|${String(r.kind)}|${String(r.severity)}`, Number(r.ts));
  }

  async raise(input: RaiseInput): Promise<AlertRecord | null> {
    const dev = input.deviceId ? this.ctx.devices.get(input.deviceId) : undefined;
    const settings = dev?.settings ?? DEFAULT_SETTINGS;
    if (!input.force && !settings.alerts.enabled[input.kind]) return null;
    const now = this.ctx.clock.now();
    const key = `${input.deviceId ?? '-'}|${input.kind}|${input.severity}`;
    if (!input.force && !NO_COOLDOWN.includes(input.kind)) {
      const last = this.lastSent.get(key);
      if (last != null && now - last < settings.alerts.cooldown_min * 60_000) {
        this.ctx.log.debug({ kind: input.kind, device_id: input.deviceId }, 'alert suppressed by cooldown');
        return null;
      }
    }
    this.lastSent.set(key, now);

    const title = input.title ?? TITLES[input.kind];
    const deviceName = dev?.name ?? null;
    const { publicUrl } = this.ctx.config;
    const html =
      `<b>${escapeHtml(title)}</b>${input.severity === 'info' ? '' : ` (${input.severity})`}\n` +
      `${escapeHtml(input.message)}\n` +
      `<i>${escapeHtml([deviceName, fmtTime(now)].filter(Boolean).join(' · '))}</i>` +
      (publicUrl ? `\n<a href="${escapeHtml(publicUrl)}">Open dashboard</a>` : '');
    const payload: PushPayload = {
      title: deviceName ? `${title} · ${deviceName}` : title,
      body: input.message,
      tag: `${input.deviceId ?? 'server'}:${input.kind}`,
      kind: input.kind,
      severity: input.severity,
      device_id: input.deviceId,
      ts: iso(now),
      url: '/',
    };
    const viewerPayload = input.sensitive ? { ...payload, body: 'A new SMS arrived.' } : payload;

    const useTelegram = (input.force || settings.alerts.channels.telegram) && this.ctx.telegram.configured;
    const usePush = (input.force || settings.alerts.channels.push) && this.ctx.push.configured;
    const [tg, pushed] = await Promise.all([
      useTelegram ? this.ctx.telegram.send(html).catch(() => false) : Promise.resolve(false),
      usePush ? this.ctx.push.sendAll(payload, viewerPayload).catch(() => 0) : Promise.resolve(0),
    ]);
    const channels: ('telegram' | 'push')[] = [];
    if (tg) channels.push('telegram');
    if (pushed > 0) channels.push('push');

    const r = await this.ctx.db.run(
      'INSERT INTO alerts_sent (device_id, kind, severity, title, message, ts, channels, data) VALUES (?, ?, ?, ?, ?, ?, ?, ?)',
      [input.deviceId, input.kind, input.severity, title, input.message, now, JSON.stringify(channels), input.data ? JSON.stringify(input.data) : null],
    );
    const rec: AlertRecord = {
      id: Number(r.lastId),
      device_id: input.deviceId,
      kind: input.kind,
      severity: input.severity,
      title,
      message: input.message,
      ts: iso(now),
      channels,
      data: input.data ?? null,
    };
    this.ctx.hub.broadcastAll(
      { t: 'alert', alert: rec },
      input.sensitive ? { viewerMsg: { t: 'alert', alert: redactAlertForViewer(rec) } } : {},
    );
    this.ctx.log.info({ kind: rec.kind, severity: rec.severity, device_id: rec.device_id, channels }, 'alert');
    return rec;
  }

  private fire(input: RaiseInput): void {
    this.raise(input).catch((err) => this.ctx.log.error({ err, kind: input.kind }, 'failed to raise alert'));
  }

  /** Alerts that the Agent reports as events (EVENT_ALERT in shared/src/events.ts). */
  onEvent(dev: DeviceRuntime, event: AgentEvent, tsMs: number): void {
    const kind = EVENT_ALERT[event.kind];
    if (!kind) return;
    if (this.ctx.clock.now() - tsMs > EVENT_ALERT_MAX_AGE_MS && event.kind !== 'sms_received') return;
    const severity: AlertSeverity = event.severity;
    let message = event.message || TITLES[kind];
    let title: string | undefined;
    if (event.kind === 'sms_received') {
      const d = event.data ?? {};
      if (typeof d.body === 'string') message = `From ${typeof d.from === 'string' ? d.from : 'unknown'}: ${d.body}`;
    }
    if (event.kind === 'temp_critical') title = 'Phone is critically hot';
    if (event.kind === 'revert') title = 'Change auto-reverted';
    if (event.kind === 'reboot_blocked') title = 'Reboot blocked by the limit';
    this.fire({
      deviceId: dev.id,
      kind,
      severity,
      title,
      message,
      data: { event_id: event.id, event_kind: event.kind, event_ts: event.ts },
      sensitive: event.kind === 'sms_received',
    });
  }

  /** After live telemetry changed the state. */
  evaluateState(dev: DeviceRuntime, _changed: GroupKey[]): void {
    this.evaluateConditions(dev, this.ctx.clock.now());
  }

  private fresh(dev: DeviceRuntime, g: GroupKey, now: number): boolean {
    const ts = dev.stateTs[g];
    return ts != null && now - ts <= STALE_STATE_MS;
  }

  /** Low battery, signal drop, cell changed, tethering down and data quota (protocol 6). */
  evaluateConditions(dev: DeviceRuntime, now: number): void {
    const a = dev.settings.alerts;
    const st = dev.alertState;
    const before = JSON.stringify(st);

    const power = dev.state.power;
    if (this.fresh(dev, 'power', now) && isNum(power?.percent)) {
      if (power.percent <= a.low_battery_pct) {
        if (!st.lowBattery) {
          st.lowBattery = true;
          const charging = power.status === 'charging' ? ' (charging)' : '';
          this.fire({
            deviceId: dev.id,
            kind: 'low_battery',
            severity: power.percent <= Math.max(5, a.low_battery_pct / 2) ? 'critical' : 'warning',
            message: `Battery at ${power.percent}%${charging}.`,
            data: { percent: power.percent, status: power.status ?? null },
          });
        }
      } else if (power.percent > a.low_battery_pct + 2) {
        st.lowBattery = false;
      }
    }

    const radio = dev.state.radio;
    const radioFresh = this.fresh(dev, 'radio', now) && !!radio;
    const base = dev.baseline?.metrics?.score?.avg;
    if (radioFresh && isNum(base) && base > 0) {
      const w = dev.settings.finder;
      const score = isNum(radio.score) ? radio.score : signalScore(radio.rsrp, radio.sinr, radio.rsrq, w);
      if (score != null) {
        const threshold = (base * a.signal_drop_pct) / 100;
        if (score < threshold) {
          st.signalLowSince ??= now;
          if (!st.signalDropAlerted && now - st.signalLowSince >= a.signal_drop_min * 60_000) {
            st.signalDropAlerted = true;
            this.fire({
              deviceId: dev.id,
              kind: 'signal_drop',
              severity: 'warning',
              message: `Signal score ${score} has been below ${a.signal_drop_pct}% of the baseline (${base.toFixed(1)}) for ${a.signal_drop_min} min.`,
              data: { score, baseline: base, threshold },
            });
          }
        } else {
          st.signalLowSince = null;
          st.signalDropAlerted = false;
        }
      }
    } else if (!radioFresh) {
      st.signalLowSince = null;
    }

    if (radioFresh) {
      const cur = servingOfRadio(radio);
      if (cur) {
        if (!st.cell || !sameCell(st.cell, cur)) {
          st.cell = cur;
          st.cellSince = now;
        }
        const baselineCell = servingOfSpot(dev.baseline);
        if (!baselineCell && st.cellSince != null && now - st.cellSince >= USUAL_CELL_LEARN_MS) {
          if (!st.usualCell || !sameCell(st.usualCell, cur)) {
            st.usualCell = cur;
            st.cellOffSince = null;
            st.cellChangedAlerted = false;
          }
        }
        const usual = baselineCell ?? st.usualCell ?? null;
        if (usual && !sameCell(usual, cur)) {
          st.cellOffSince ??= now;
          if (!st.cellChangedAlerted && now - st.cellOffSince >= a.cell_change_min * 60_000) {
            st.cellChangedAlerted = true;
            this.fire({
              deviceId: dev.id,
              kind: 'cell_changed',
              severity: 'warning',
              message: `Serving cell has differed from the usual one for ${a.cell_change_min} min (now PCI ${cur.pci ?? '?'}, cell ${cur.cell_id ?? '?'}; usual PCI ${usual.pci ?? '?'}, cell ${usual.cell_id ?? '?'}).`,
              data: { current: cur, usual },
            });
          }
        } else {
          st.cellOffSince = null;
          st.cellChangedAlerted = false;
        }
      }
    }

    const tether = dev.state.tether;
    if (this.fresh(dev, 'tether', now) && tether) {
      if (tether.usb_connected === true && tether.usb_tether_on === false) {
        st.tetherDownSince ??= now;
        if (!st.tetherDownAlerted && now - st.tetherDownSince >= a.tether_down_min * 60_000) {
          st.tetherDownAlerted = true;
          this.fire({
            deviceId: dev.id,
            kind: 'tether_down',
            severity: 'critical',
            message: `USB is connected but tethering has been off for ${a.tether_down_min} min.`,
          });
        }
      } else {
        st.tetherDownSince = null;
        st.tetherDownAlerted = false;
      }
    }

    const plan = dev.settings.plan;
    const tr = dev.state.traffic;
    if (plan.quota_gb > 0 && tr && (isNum(tr.cycle_rx) || isNum(tr.cycle_tx))) {
      // Decimal GB, as Android's data usage screen counts it.
      const used = (num(tr.cycle_rx) ?? 0) + (num(tr.cycle_tx) ?? 0);
      const pct = (used / (plan.quota_gb * 1e9)) * 100;
      const cycle = typeof tr.cycle_start === 'string' && tr.cycle_start ? tr.cycle_start : cycleStart(now, plan.cycle_start_day);
      if (!st.quota || st.quota.cycle !== cycle) st.quota = { cycle, steps: [] };
      const sent = st.quota.steps;
      const crossed = [...a.quota_steps_pct].sort((x, y) => x - y).filter((s) => pct >= s && !sent.includes(s));
      if (crossed.length) {
        sent.push(...crossed);
        const top = Math.max(...crossed);
        this.fire({
          deviceId: dev.id,
          kind: 'data_quota',
          severity: top >= 100 ? 'critical' : 'warning',
          title: `Data quota ${top}% used`,
          message: `${fmtBytes(used)} of ${plan.quota_gb} GB used this cycle (${pct.toFixed(0)}%, cycle from ${cycle}).`,
          data: { used_bytes: used, quota_gb: plan.quota_gb, pct, step: top, cycle },
        });
      }
    }

    if (JSON.stringify(st) !== before) dev.dirty = true;
  }

  /** "Phone offline": no Agent message for alerts.offline_after_s (protocol 6). */
  async checkOffline(): Promise<void> {
    const now = this.ctx.clock.now();
    for (const dev of this.ctx.devices.all()) {
      if (dev.offlineAlertOpen) continue;
      const offlineMs = dev.settings.alerts.offline_after_s * 1000;
      const ref = dev.lastSeen ?? parseTs(dev.pairedAt) ?? now;
      const effective = Math.max(ref, this.ctx.startedAt + STARTUP_GRACE_MS - offlineMs);
      if (now - effective <= offlineMs) continue;

      dev.offlineAlertOpen = true;
      dev.offlineSince = ref;
      dev.alertState.offlineAlertSent = false;
      this.pendingBackOnline.delete(dev.id);
      if (this.ctx.hub.agents.has(dev.id)) {
        // The socket looks open but nothing arrives: a half-open TCP connection.
        this.ctx.hub.terminateAgent(dev.id);
      }
      dev.connectedSince = null;
      await this.ctx.devices.persistNow(dev);
      this.ctx.devices.broadcastStatus(dev);
      const rec = await this.raise({
        deviceId: dev.id,
        kind: 'offline',
        severity: 'critical',
        message: dev.lastSeen
          ? `No message from the phone for ${formatDuration(now - ref)} (last seen ${fmtTime(ref)}).`
          : `The phone was paired ${formatDuration(now - ref)} ago but has never connected.`,
        data: { last_seen: dev.lastSeen ? iso(dev.lastSeen) : null },
      }).catch((err) => {
        this.ctx.log.error({ err }, 'failed to raise offline alert');
        return null;
      });
      dev.alertState.offlineAlertSent = rec != null;
      dev.dirty = true;
    }
  }

  /** First Agent message after an offline alert. The alert itself waits for the backlog. */
  onAgentActivity(dev: DeviceRuntime): void {
    if (!dev.offlineAlertOpen) return;
    const now = this.ctx.clock.now();
    const offlineFrom = dev.offlineSince ?? dev.lastSeen ?? now;
    dev.offlineAlertOpen = false;
    dev.offlineSince = null;
    dev.lastBackOnline = now;
    this.pendingBackOnline.set(dev.id, {
      due: now + BACK_ONLINE_DELAY_MS,
      offlineFrom,
      alertSent: dev.alertState.offlineAlertSent !== false,
    });
    dev.alertState.offlineAlertSent = false;
    this.ctx.devices.persistNow(dev).catch((err) => this.ctx.log.error({ err }, 'failed to persist back-online state'));
  }

  async processPendingBackOnline(force = false): Promise<void> {
    const now = this.ctx.clock.now();
    for (const [id, p] of [...this.pendingBackOnline]) {
      if (!force && now < p.due) continue;
      this.pendingBackOnline.delete(id);
      const dev = this.ctx.devices.get(id);
      if (!dev || !p.alertSent) continue;
      const events = await this.ctx.records.eventsBetween(id, p.offlineFrom - 60_000, now, REASON_KINDS, 10);
      const reasons: string[] = [];
      for (const e of events) {
        const d = e.data ?? {};
        let r: string;
        switch (e.kind) {
          case 'boot':
            r = `phone rebooted${typeof d.boot_reason === 'string' ? ` (${d.boot_reason})` : ''}`;
            break;
          case 'agent_start':
            r = `Agent restarted${typeof d.reason === 'string' ? ` (${d.reason})` : ''}`;
            break;
          case 'power_lost':
            r = 'USB power was lost';
            break;
          case 'power_connected':
            r = 'USB power returned';
            break;
          default:
            r = e.message || e.kind;
        }
        if (!reasons.includes(r)) reasons.push(r);
      }
      const dur = formatDuration(now - p.offlineFrom);
      const message = reasons.length
        ? `Back online after ${dur}. Reported by the phone: ${reasons.join('; ')}.`
        : `Back online after ${dur}. The phone reported no reboot or watchdog action (likely a network or server-side gap).`;
      await this.raise({
        deviceId: id,
        kind: 'back_online',
        severity: 'info',
        message,
        data: { offline_s: Math.round((now - p.offlineFrom) / 1000), reasons },
      });
    }
  }

  /** Periodic work (every 15 s): offline check and time-based conditions of online devices. */
  async tick(): Promise<void> {
    await this.checkOffline();
    const now = this.ctx.clock.now();
    for (const dev of this.ctx.devices.all()) {
      if (!dev.offlineAlertOpen) this.evaluateConditions(dev, now);
    }
    await this.processPendingBackOnline();
  }

  async list(deviceId: string | null, limit: number, before: number | null, viewer: boolean): Promise<AlertRecord[]> {
    const where: string[] = [];
    const args: (string | number)[] = [];
    if (deviceId) {
      where.push('(device_id = ? OR device_id IS NULL)');
      args.push(deviceId);
    }
    if (before != null) {
      where.push('ts < ?');
      args.push(before);
    }
    args.push(limit);
    const rows = await this.ctx.db.all(
      `SELECT * FROM alerts_sent ${where.length ? `WHERE ${where.join(' AND ')}` : ''} ORDER BY ts DESC, id DESC LIMIT ?`,
      args,
    );
    return rows.map((r) => (viewer ? redactAlertForViewer(rowToAlert(r)) : rowToAlert(r)));
  }

  async test(username: string): Promise<AlertRecord> {
    const rec = await this.raise({
      deviceId: null,
      kind: 'server_test',
      severity: 'info',
      message: `Test alert sent by ${username}. If you can read this, alerts reach you.`,
      force: true,
    });
    if (!rec) throw new Error('test alert was not recorded');
    return rec;
  }

  async retention(now: number): Promise<void> {
    await this.ctx.db.run('DELETE FROM alerts_sent WHERE ts < ?', [now - ALERT_RETENTION_MS]);
  }
}
