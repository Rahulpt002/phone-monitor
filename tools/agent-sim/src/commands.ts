import {
  COMMANDS,
  PROTOCOL,
  normalizePhoneNumber,
  type CapabilityMap,
  type CmdMsg,
  type CmdRejectReason,
  type CommandName,
  type CommandSource,
  type NetworkMode,
  type RevertConfirm,
  type Settings,
  type SmsRecord,
  type TelemetryTier,
} from '@hsm/shared';
import type { SimClock } from './clock.js';
import type { EventSpec } from './events.js';
import { fakeShell, shortCodeReply, topText, ussdReply } from './fakeos.js';
import { FinderAbortedError, type Finder } from './finder.js';
import type { LockName, LockSet } from './locks.js';
import type { Logger } from './log.js';
import type { OutboxInput } from './outbox.js';
import type { Phone, PhoneFlags } from './phone/phone.js';
import type { StopReason } from './phone/power.js';
import { AGENT_PACKAGE } from './phone/system.js';
import { REVERT_TARGET, type ArmedRevert, type RevertManager } from './revert.js';
import type { Random } from './rng.js';
import { delay, errorMessage, formatDuration, isPlainObject, newId, truncateUtf8 } from './util.js';

export interface CommandHost {
  clock: SimClock;
  rng: Random;
  log: Logger;
  phone: Phone;
  finder: Finder;
  reverts: RevertManager;
  locks: LockSet;
  /** --keep-link: the Agent's link survives commands that would normally cut it. */
  keepLink: boolean;
  settings(): Settings;
  enqueue(msg: OutboxInput): void;
  /** A cmd_ack or cmd_result for this command id is still in the outbox (not yet acknowledged). */
  reportQueued(commandId: string): boolean;
  emit(spec: EventSpec): void;
  generation(): number;
  rebootsInWindow(): number;
  /** Changes phone switches. `deferLink` leaves the link as it is until `updateLink()`. */
  setFlags(patch: Partial<PhoneFlags>, opts?: { deferLink?: boolean }): void;
  updateLink(): void;
  chargeStop(reason: StopReason, on: boolean, message: string): void;
  sendTiers(tiers: TelemetryTier[]): void;
  capabilityProbe(): CapabilityMap;
  simulateInternetCheckFailure(durationS: number): void;
  requestReboot(source: CommandSource, bootReason: string): void;
  requestPowerOff(): void;
  requestAgentRestart(): void;
  recordSms(rec: SmsRecord): void;
  /** setTimeout that is cancelled when the Agent stops. */
  later(ms: number, fn: () => void): void;
  rememberCommands(ids: string[]): void;
}

type Args = Record<string, unknown>;

interface Outcome {
  ok: boolean;
  output: string;
  error?: string | null;
  armed?: ArmedRevert | null;
  /** Runs after cmd_result is queued (and flushed), e.g. to drop the link or reboot. */
  after?: () => void;
}

export const NETWORK_MODES: readonly NetworkMode[] = ['auto', 'nr_lte', 'lte_only', 'lte_wcdma', 'wcdma_only'];

/** Locks a command needs; the first is held while it runs, all are checked for `busy`. */
const COMMAND_LOCKS: Partial<Record<CommandName, LockName[]>> = {
  mobile_data: ['radio'],
  data_cycle: ['radio'],
  airplane_mode: ['radio'],
  airplane_cycle: ['radio'],
  usb_tethering: ['radio'],
  hotspot: ['radio'],
  wifi: ['radio'],
  bluetooth: ['radio'],
  network_mode: ['radio'],
  reboot: ['system', 'radio', 'finder'],
  power_off: ['system', 'radio', 'finder'],
  capability_probe: ['system'],
  speed_test: ['finder'],
  finder_spot_test: ['finder'],
};

const isBool = (v: unknown): v is boolean => typeof v === 'boolean';
const isNum = (v: unknown, lo: number, hi: number): v is number => typeof v === 'number' && Number.isFinite(v) && v >= lo && v <= hi;
const isInt = (v: unknown, lo: number, hi: number): v is number => isNum(v, lo, hi) && Number.isInteger(v);
const isStr = (v: unknown, min: number, max: number): v is string => typeof v === 'string' && v.trim().length >= min && v.length <= max;
const absent = (v: unknown) => v === undefined || v === null;
const PACKAGE_RE = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/;
const USSD_RE = /^[*#][0-9*#+]{1,63}$/;

/** Argument check for each command (`invalid_args`); returns a reason or null. */
export function validateArgs(name: CommandName, a: Args): string | null {
  switch (name) {
    case 'mobile_data':
    case 'airplane_mode':
    case 'wifi':
    case 'bluetooth':
    case 'charging':
      return isBool(a.enabled) ? null : 'enabled must be true or false';
    case 'data_cycle':
    case 'airplane_cycle':
      return absent(a.off_seconds) || isNum(a.off_seconds, 1, 600) ? null : 'off_seconds must be 1..600';
    case 'usb_tethering':
      if (!isBool(a.enabled)) return 'enabled must be true or false';
      return absent(a.function) || a.function === 'rndis' || a.function === 'ncm' ? null : "function must be 'rndis' or 'ncm'";
    case 'hotspot':
      if (!isBool(a.enabled)) return 'enabled must be true or false';
      if (!absent(a.ssid) && !isStr(a.ssid, 1, 32)) return 'ssid must be 1..32 characters';
      if (!absent(a.password) && !(typeof a.password === 'string' && /^[\x20-\x7E]{8,63}$/.test(a.password))) {
        return 'password must be 8..63 printable ASCII characters';
      }
      return null;
    case 'network_mode':
      return NETWORK_MODES.includes(a.mode as NetworkMode) ? null : `mode must be one of ${NETWORK_MODES.join(', ')}`;
    case 'screen':
      return isBool(a.on) ? null : 'on must be true or false';
    case 'brightness':
      return isInt(a.level, 0, 255) ? null : 'level must be an integer 0..255';
    case 'charge_limit':
      return isInt(a.percent, 20, 100) ? null : 'percent must be an integer 20..100';
    case 'app_force_stop':
    case 'app_disable':
    case 'app_enable':
      return typeof a.package === 'string' && PACKAGE_RE.test(a.package) ? null : 'package must be a package name';
    case 'send_sms':
      if (typeof a.to !== 'string' || normalizePhoneNumber(a.to) === null) return 'to must be a phone number';
      return isStr(a.body, 1, 1600) ? null : 'body must be 1..1600 characters';
    case 'ussd':
      return typeof a.code === 'string' && USSD_RE.test(a.code.trim()) ? null : 'code must look like *123#';
    case 'shell':
      if (!isStr(a.cmd, 1, 4096)) return 'cmd must be a non-empty command';
      return absent(a.timeout_s) || isNum(a.timeout_s, 1, 600) ? null : 'timeout_s must be 1..600';
    case 'watchdog_simulate':
      if (a.scenario !== 'internet_down') return "scenario must be 'internet_down'";
      return isNum(a.duration_s, 10, 86_400) ? null : 'duration_s must be 10..86400';
    case 'speed_test':
      return absent(a.spot_id) || isStr(a.spot_id, 1, 64) ? null : 'spot_id must be a spot id';
    case 'finder_spot_test':
      if (typeof a.name !== 'string' || !isStr(a.name.trim(), 1, 64)) return 'name must be 1..64 characters';
      if (!absent(a.orientation) && !(typeof a.orientation === 'string' && a.orientation.trim().length <= 32)) {
        return 'orientation must be at most 32 characters';
      }
      return absent(a.duration_s) || isNum(a.duration_s, 5, 600) ? null : 'duration_s must be 5..600';
    case 'finder_set_baseline':
    case 'finder_delete_spot':
      return isStr(a.spot_id, 1, 64) ? null : 'spot_id is required';
    case 'reboot':
    case 'power_off':
    case 'app_list':
    case 'process_list':
    case 'refresh':
    case 'capability_probe':
      return null;
  }
}

const SEEN_LIMIT = 500;
/** Latest reports kept in memory for re-sending (outputs can be large, so fewer than SEEN_LIMIT). */
const REPORT_LIMIT = 100;

type Report = Extract<OutboxInput, { t: 'cmd_ack' } | { t: 'cmd_result' }>;

/** Receives `cmd`, applies the checks of docs/protocol.md 3.5 and reports through the outbox. */
export class CommandExecutor {
  private readonly seen: string[];
  private readonly seenSet: Set<string>;
  /** Latest cmd_ack / cmd_result per command id, oldest first (Map insertion order). */
  private readonly reports = new Map<string, Report>();

  constructor(private readonly host: CommandHost, seen: readonly string[] = []) {
    this.seen = seen.slice(-SEEN_LIMIT);
    this.seenSet = new Set(this.seen);
  }

  handle(cmd: CmdMsg): void {
    const { log } = this.host;
    if (typeof cmd.id !== 'string' || cmd.id.length === 0) {
      log.warn('ignored a cmd without an id');
      return;
    }
    // The Server re-sends a command after a reconnect when it has no cmd_ack for it (it may have
    // been written to a half-open socket). Never execute one twice; make sure the Server gets its
    // latest report instead.
    if (this.seenSet.has(cmd.id)) {
      this.resendReport(cmd.id);
      return;
    }
    this.remember(cmd.id);
    const args: Args = isPlainObject(cmd.args) ? cmd.args : {};
    const who = cmd.user ? ` by ${cmd.user}` : '';
    const reason = this.check(cmd, args);
    if (reason) {
      this.report({ t: 'cmd_ack', id: cmd.id, accepted: false, reason, ts: this.host.clock.serverIso() });
      log.info('cmd', `${cmd.id} ${String(cmd.name)} ${argsForLog(cmd.name, args)}${who}: rejected (${reason})`);
      if (reason === 'reboot_limit') {
        const s = this.host.settings().watchdog;
        this.host.emit({
          kind: 'reboot_blocked',
          severity: 'warning',
          message: `Reboot from the Dashboard refused: ${this.host.rebootsInWindow()} reboots in the last ${s.reboot_window_h} h`,
          data: { source: 'dashboard', command_id: cmd.id, reboots_in_window: this.host.rebootsInWindow(), max_reboots: s.max_reboots },
        });
      }
      return;
    }
    this.report({ t: 'cmd_ack', id: cmd.id, accepted: true, reason: null, ts: this.host.clock.serverIso() });
    log.info('cmd', `${cmd.id} ${cmd.name} ${argsForLog(cmd.name, args)}${who}: accepted`);
    void this.execute(cmd, args);
  }

  /** Checks in protocol order: expiry, unsupported, blocked, reboot_limit, invalid_args, busy. */
  check(cmd: CmdMsg, args: Args): CmdRejectReason | null {
    const h = this.host;
    const settings = h.settings();
    const sentAt = Date.parse(cmd.sent_at);
    const serverNow = h.clock.serverNow();
    // A command whose age cannot be proven is not fresh (plan 6.4 rule 6).
    if (!Number.isFinite(sentAt) || serverNow - sentAt > settings.commands.expiry_s * 1000) return 'expired';
    if (typeof cmd.name !== 'string' || !Object.hasOwn(COMMANDS, cmd.name)) return 'unsupported';
    const name = cmd.name;
    if (!h.phone.capabilities[name]?.supported) return 'unsupported';
    if (name === 'power_off' && !settings.commands.allow_power_off) return 'blocked';
    if (COMMANDS[name].is_reboot && h.rebootsInWindow() >= settings.watchdog.max_reboots) return 'reboot_limit';
    if (validateArgs(name, args) !== null) return 'invalid_args';
    if ((COMMAND_LOCKS[name] ?? []).some((l) => h.locks.isHeld(l))) return 'busy';
    return null;
  }

  private remember(id: string): void {
    this.seen.push(id);
    this.seenSet.add(id);
    while (this.seen.length > SEEN_LIMIT) {
      const old = this.seen.shift();
      if (old !== undefined) this.seenSet.delete(old);
    }
    this.host.rememberCommands(this.seen);
  }

  /** Queues a cmd_ack / cmd_result and keeps it as this command's latest report. */
  private report(msg: Report): void {
    this.reports.delete(msg.id);
    this.reports.set(msg.id, msg);
    while (this.reports.size > REPORT_LIMIT) {
      const oldest = this.reports.keys().next().value;
      if (oldest === undefined) break;
      this.reports.delete(oldest);
    }
    this.host.enqueue(msg);
  }

  /**
   * A duplicate `cmd`: queue its latest report again (same content and ts, new seq) unless one is
   * still in the outbox, which delivers it anyway. Reports are re-sent idempotently by the Server.
   */
  private resendReport(id: string): void {
    const { log } = this.host;
    const last = this.reports.get(id);
    if (!last) {
      log.debug('cmd', `${id} already handled, ignoring the duplicate (no report kept to send again)`);
      return;
    }
    if (this.host.reportQueued(id)) {
      log.debug('cmd', `${id} already handled, ignoring the duplicate (its report is still queued)`);
      return;
    }
    log.info('cmd', `${id} received again: already handled, sending its ${last.t} again instead of running it twice`);
    this.host.enqueue({ ...last });
  }

  private async execute(cmd: CmdMsg, args: Args): Promise<void> {
    const h = this.host;
    const started = Date.now();
    const gen = h.generation();
    const lock = COMMAND_LOCKS[cmd.name]?.[0];
    if (lock) h.locks.tryAcquire(lock, cmd.id);
    let outcome: Outcome;
    try {
      outcome = await this.run(cmd, args);
    } catch (err) {
      if (err instanceof FinderAbortedError) outcome = { ok: false, output: '', error: err.message };
      else outcome = { ok: false, output: '', error: errorMessage(err) };
    } finally {
      if (lock) h.locks.release(lock, cmd.id);
    }
    // The phone rebooted or the Agent restarted while this ran: like on a real phone, the result is lost.
    if (gen !== h.generation()) {
      h.log.debug('cmd', `${cmd.id} result dropped: the Agent restarted while it ran`);
      return;
    }
    const duration = Date.now() - started;
    const output = truncateUtf8(outcome.output, PROTOCOL.max_output_bytes);
    this.report({
      t: 'cmd_result',
      id: cmd.id,
      ok: outcome.ok,
      output,
      error: outcome.ok ? null : (outcome.error ?? 'failed'),
      duration_ms: duration,
      // The deadline is kept on the phone clock (local timers); the wire carries server time.
      revert_deadline: outcome.armed ? h.clock.serverIso(outcome.armed.deadline) : null,
      ts: h.clock.serverIso(),
    });
    const summary = outcome.ok ? oneLine(output) : `failed: ${outcome.error ?? 'failed'}`;
    const revert = outcome.armed ? `; auto-revert armed (${outcome.armed.confirm}) until ${new Date(outcome.armed.deadline).toLocaleTimeString()}` : '';
    h.log.info('cmd', `${cmd.id} ${cmd.name} done in ${formatDuration(duration / 1000)}${summary ? `: ${summary}` : ''}${revert}`);
    outcome.after?.();
  }

  /** Records the undo action and arms auto-revert when this command can cut a link (protocol 3.5 step 1). */
  private arm(cmd: CmdMsg, args: Args, undo: ArmedRevert['undo']): ArmedRevert | null {
    const h = this.host;
    const confirm: RevertConfirm | null = cmd.revert_confirm ?? COMMANDS[cmd.name].revertFor(args);
    const superseded = h.reverts.supersede(REVERT_TARGET[cmd.name], cmd.id, COMMANDS[cmd.name].label);
    if (!confirm) return null;
    // An earlier unconfirmed change on this state: the undo goes back to the state before it.
    if (superseded[0]) undo = superseded[0].undo;
    const afterS =
      typeof cmd.revert_after_s === 'number' && cmd.revert_after_s > 0 ? cmd.revert_after_s : h.settings().commands.revert_after_s;
    const now = h.clock.now();
    return h.reverts.arm({ command_id: cmd.id, name: cmd.name, undo, confirm, applied_at: now, deadline: now + afterS * 1000 });
  }

  /** Simulated execution time of a quick root command. */
  private work(minMs: number, maxMs: number): Promise<void> {
    return delay(this.host.rng.range(minMs, maxMs));
  }

  private async run(cmd: CmdMsg, a: Args): Promise<Outcome> {
    const h = this.host;
    const ph = h.phone;
    const f = ph.flags;
    const settings = h.settings();
    switch (cmd.name) {
      case 'mobile_data': {
        const on = a.enabled as boolean;
        const armed = this.arm(cmd, a, { name: 'mobile_data', args: { enabled: f.dataEnabled } });
        await this.work(250, 450);
        h.setFlags({ dataEnabled: on }, { deferLink: true });
        return { ok: true, output: `mobile data ${on ? 'enabled' : 'disabled'}`, armed, after: () => h.updateLink() };
      }
      case 'data_cycle': {
        const off = typeof a.off_seconds === 'number' ? a.off_seconds : settings.watchdog.data_off_s;
        this.arm(cmd, a, { name: 'mobile_data', args: { enabled: true } });
        h.setFlags({ dataEnabled: false });
        await delay(off * 1000);
        h.setFlags({ dataEnabled: true });
        await this.work(800, 1600);
        return { ok: true, output: `mobile data off for ${off} s, then on${ph.internetOk ? '' : '; internet still down'}` };
      }
      case 'airplane_mode': {
        const on = a.enabled as boolean;
        const armed = this.arm(cmd, a, { name: 'airplane_mode', args: { enabled: f.airplane } });
        await this.work(300, 600);
        h.setFlags({ airplane: on }, { deferLink: true });
        return { ok: true, output: `airplane mode ${on ? 'on' : 'off'}`, armed, after: () => h.updateLink() };
      }
      case 'airplane_cycle': {
        const off = typeof a.off_seconds === 'number' ? a.off_seconds : settings.watchdog.airplane_off_s;
        this.arm(cmd, a, { name: 'airplane_mode', args: { enabled: false } });
        h.setFlags({ airplane: true });
        await delay(off * 1000);
        h.setFlags({ airplane: false });
        // Network registration after airplane mode takes a few seconds.
        await this.work(3000, 4500);
        return { ok: true, output: '' };
      }
      case 'usb_tethering': {
        const on = a.enabled as boolean;
        const fn = a.function === 'ncm' || a.function === 'rndis' ? a.function : f.usbFunction;
        if (on && !f.usbConnected) return { ok: false, output: '', error: 'USB is not connected to a host' };
        const armed = this.arm(cmd, a, { name: 'usb_tethering', args: { enabled: f.usbTether, function: f.usbFunction } });
        await this.work(400, 900);
        h.setFlags({ usbTether: on, usbFunction: fn, tetherManualOff: !on });
        return { ok: true, output: on ? `usb tethering on (${fn})` : 'usb tethering off', armed };
      }
      case 'hotspot': {
        if (a.enabled === true) {
          const ssid = typeof a.ssid === 'string' ? a.ssid : (f.hotspotSsid ?? 'HSM-Outdoor');
          await this.work(1500, 2500);
          h.setFlags({ hotspot: true, hotspotSsid: ssid });
          // The password itself never goes into output or logs (CommandSpec.secret_args).
          return { ok: true, output: `hotspot on: SSID "${ssid}", WPA2${typeof a.password === 'string' ? ', new password set' : ''}` };
        }
        await this.work(400, 800);
        h.setFlags({ hotspot: false });
        return { ok: true, output: 'hotspot off' };
      }
      case 'wifi':
        await this.work(300, 700);
        h.setFlags({ wifi: a.enabled as boolean });
        return { ok: true, output: `wifi ${a.enabled ? 'enabled' : 'disabled'}` };
      case 'bluetooth':
        await this.work(300, 700);
        h.setFlags({ bluetooth: a.enabled as boolean });
        return { ok: true, output: `bluetooth ${a.enabled ? 'enabled' : 'disabled'}` };
      case 'network_mode': {
        const mode = a.mode as NetworkMode;
        const armed = this.arm(cmd, a, { name: 'network_mode', args: { mode: f.networkMode } });
        await this.work(800, 1500);
        // The modem re-registers; without --keep-link the link stays cut until the change is reverted.
        h.setFlags({ networkMode: mode, modeSwitchCut: armed !== null && !h.keepLink }, { deferLink: true });
        const mechanism = ph.profile.network_mode?.[mode] ?? ph.profile.commands?.network_mode?.[0] ?? 'profile mechanism';
        return { ok: true, output: `network mode set to ${mode} (${mechanism})`, armed, after: () => h.updateLink() };
      }
      case 'reboot':
        return { ok: true, output: 'rebooting (svc power reboot)', after: () => h.requestReboot('dashboard', 'reboot,userrequested') };
      case 'power_off':
        return { ok: true, output: 'powering off (svc power shutdown)', after: () => h.requestPowerOff() };
      case 'screen': {
        const on = a.on as boolean;
        await this.work(150, 300);
        ph.system.screenOn = on;
        return { ok: true, output: `screen ${on ? 'on' : 'off'} (input keyevent ${on ? 224 : 223})` };
      }
      case 'brightness':
        await this.work(100, 250);
        ph.system.brightness = a.level as number;
        return { ok: true, output: `brightness ${a.level as number}` };
      case 'charging': {
        const on = a.enabled as boolean;
        await this.work(100, 250);
        h.chargeStop('manual', !on, on ? 'Charging enabled from the Dashboard' : 'Charging disabled from the Dashboard');
        const still = ph.power.stopReason;
        if (on && still) {
          const why = still === 'heat' ? `battery ${ph.heat.battery.toFixed(1)} °C` : `battery at the ${ph.chargeLimitPct}% limit`;
          return { ok: true, output: `manual stop cleared; charging stays off (${why})` };
        }
        return { ok: true, output: on ? 'charging enabled' : 'charging disabled (manual)' };
      }
      case 'charge_limit': {
        const pct = a.percent as number;
        await this.work(100, 250);
        ph.chargeLimitPct = pct;
        return { ok: true, output: `charge limit ${pct}% (resume at ${pct - settings.watchdog.charge_resume_drop_pct}%)` };
      }
      case 'app_list':
        await this.work(400, 900);
        return { ok: true, output: JSON.stringify(ph.apps.map((x) => ({ pkg: x.pkg, enabled: x.enabled, system: x.system }))) };
      case 'app_force_stop': {
        const pkg = a.package as string;
        await this.work(200, 500);
        if (!ph.apps.some((x) => x.pkg === pkg)) return { ok: false, output: '', error: `Unknown package: ${pkg}` };
        if (pkg === AGENT_PACKAGE) {
          return { ok: true, output: 'agent force-stopped; the keep-alive script restarts it', after: () => h.requestAgentRestart() };
        }
        return { ok: true, output: '' };
      }
      case 'app_disable':
      case 'app_enable': {
        const pkg = a.package as string;
        const enable = cmd.name === 'app_enable';
        await this.work(400, 900);
        const app = ph.apps.find((x) => x.pkg === pkg);
        if (!app) {
          return { ok: false, output: '', error: `Exception occurred while executing '${enable ? 'enable' : 'disable-user'}': java.lang.IllegalArgumentException: Unknown package: ${pkg}` };
        }
        if (!enable && pkg === AGENT_PACKAGE) return { ok: false, output: '', error: 'refusing to disable the Agent itself' };
        app.enabled = enable;
        return { ok: true, output: `Package ${pkg} new state: ${enable ? 'enabled' : 'disabled-user'}` };
      }
      case 'process_list':
        await this.work(300, 700);
        return { ok: true, output: topText(ph) };
      case 'send_sms':
        return this.sendSms(a.to as string, a.body as string);
      case 'ussd': {
        const code = a.code as string;
        await this.work(1800, 3500);
        if (!ph.radioOn) return { ok: false, output: '', error: 'USSD failed: no mobile network' };
        return { ok: true, output: ussdReply(code, ph) };
      }
      case 'shell': {
        const timeoutS = typeof a.timeout_s === 'number' ? a.timeout_s : 30;
        const cmdline = (a.cmd as string).trim();
        const sleep = /^sleep\s+(\d+(?:\.\d+)?)$/.exec(cmdline);
        if (sleep) {
          const wantS = Number(sleep[1]);
          await delay(Math.min(wantS, timeoutS) * 1000);
          if (wantS > timeoutS) return { ok: false, output: '', error: `timed out after ${timeoutS} s` };
          return { ok: true, output: '' };
        }
        await this.work(80, 300);
        const res = fakeShell(cmdline, ph, h.clock.now());
        return { ok: res.code === 0, output: res.output, error: res.code === 0 ? null : `exit code ${res.code}` };
      }
      case 'refresh':
        h.sendTiers(['static', 'slow', 'fast']);
        return { ok: true, output: 'sent static, slow and fast telemetry' };
      case 'capability_probe': {
        await this.work(1500, 3000);
        const caps = h.capabilityProbe();
        const names = Object.keys(caps);
        const unsupported = names.filter((n) => !caps[n]?.supported);
        return {
          ok: true,
          output: `${names.length - unsupported.length} of ${names.length} commands supported${unsupported.length ? `; not supported: ${unsupported.join(', ')}` : ''}`,
        };
      }
      case 'watchdog_simulate': {
        const d = a.duration_s as number;
        h.simulateInternetCheckFailure(d);
        const w = settings.watchdog;
        return {
          ok: true,
          output: `internet check fails for ${formatDuration(d)} (simulated time); watchdog steps at ${w.internet_fail_s} s, ${w.airplane_after_s} s and ${w.reboot_after_s} s`,
        };
      }
      case 'speed_test': {
        const { session, spot } = await h.finder.speedTest(typeof a.spot_id === 'string' ? a.spot_id : null);
        return {
          ok: true,
          output: `${session.runs.length} runs${spot ? ` at "${spot.name}"` : ''}: down ${session.down_mbps} Mbps, up ${session.up_mbps} Mbps, ping ${session.ping_ms} ms, jitter ${session.jitter_ms} ms`,
        };
      }
      case 'finder_spot_test': {
        const spot = await h.finder.spotTest({
          name: (a.name as string).trim(),
          orientation: typeof a.orientation === 'string' ? a.orientation : null,
          durationS: typeof a.duration_s === 'number' ? a.duration_s : settings.finder.spot_test_s,
        });
        const m = spot.metrics;
        return {
          ok: true,
          output: `spot "${spot.name}" saved as ${spot.id}: score ${m.score.avg} (min ${m.score.min}, max ${m.score.max}), RSRP ${m.rsrp.avg} dBm, SINR ${m.sinr?.avg ?? '-'} dB, ${spot.cell_switches} cell switches`,
        };
      }
      case 'finder_set_baseline': {
        await this.work(50, 150);
        const spot = h.finder.setBaseline(a.spot_id as string);
        if (!spot) return { ok: false, output: '', error: `spot ${a.spot_id as string} not found` };
        return { ok: true, output: `baseline set to "${spot.name}" (score ${spot.metrics.score.avg})` };
      }
      case 'finder_delete_spot': {
        await this.work(50, 150);
        if (!h.finder.deleteSpot(a.spot_id as string)) return { ok: false, output: '', error: `spot ${a.spot_id as string} not found` };
        return { ok: true, output: 'spot deleted' };
      }
    }
  }

  private async sendSms(toRaw: string, body: string): Promise<Outcome> {
    const h = this.host;
    if (!h.phone.radioOn) {
      await this.work(200, 400);
      return { ok: false, output: '', error: 'SMS failed: no mobile network (RESULT_ERROR_NO_SERVICE)' };
    }
    await this.work(800, 1600);
    const to = toRaw.replace(/[\s-]/g, '');
    const parts = Math.max(1, Math.ceil(body.length / 153));
    h.recordSms({ id: newId('m_'), direction: 'out', peer: to, body, at: h.clock.serverIso() });
    // Carriers answer SMS sent to their short codes.
    if (/^\d{3,6}$/.test(to)) {
      const gen = h.generation();
      h.later(h.clock.realMs(6), () => {
        if (gen !== h.generation()) return;
        const reply = shortCodeReply(to, body);
        const at = h.clock.serverIso();
        const from = `SIMTEL-${to}`;
        h.recordSms({ id: newId('m_'), direction: 'in', peer: from, body: reply, at });
        h.emit({ kind: 'sms_received', severity: 'info', message: `SMS from ${from}: ${reply}`, data: { from, body: reply, at } });
      });
    }
    return { ok: true, output: `SMS sent to ${to} (${parts} part${parts === 1 ? '' : 's'})` };
  }
}

/** Arguments as JSON for the console, with CommandSpec.secret_args (hotspot password) redacted. */
function argsForLog(name: unknown, args: Args): string {
  const secret = typeof name === 'string' && Object.hasOwn(COMMANDS, name) ? (COMMANDS[name as CommandName].secret_args ?? []) : [];
  if (!secret.some((k) => k in args)) return JSON.stringify(args);
  return JSON.stringify(Object.fromEntries(Object.entries(args).map(([k, v]) => [k, secret.includes(k) ? '<redacted>' : v])));
}

function oneLine(text: string): string {
  const first = text.split('\n')[0] ?? '';
  const short = first.length > 100 ? `${first.slice(0, 100)}...` : first;
  return text.includes('\n') ? `${short} (+${text.split('\n').length - 1} lines)` : short;
}
