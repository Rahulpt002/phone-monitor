import { EventEmitter } from 'node:events';
import {
  DEFAULT_SETTINGS,
  mergeSettings,
  type AgentToServer,
  type CommandSource,
  type HeartbeatMsg,
  type NetworkMode,
  type Rates,
  type ServerToAgent,
  type Settings,
  type TelemetryTier,
} from '@hsm/shared';
import { SimClock } from './clock.js';
import { CommandExecutor, NETWORK_MODES, type CommandHost } from './commands.js';
import { Connection, type CloseInfo, type FatalCode } from './connection.js';
import { makeEvent, transitions, watchPhone, type EventSpec, type PhoneWatch } from './events.js';
import { Finder, type FinderHost } from './finder.js';
import { LockSet } from './locks.js';
import type { Logger } from './log.js';
import { Outbox, type OutboxInput } from './outbox.js';
import { AGENT_VERSION, Phone, persistableFlags, restoreFlags, type PhoneFlags } from './phone/phone.js';
import type { StopReason } from './phone/power.js';
import { RevertManager, type ArmedRevert, type RevertHost } from './revert.js';
import { Random } from './rng.js';
import { ScenarioRunner, type ScenarioHost, type ScenarioName } from './scenarios.js';
import type { StateStore } from './state.js';
import { isPlainObject, newId, round1, formatDuration } from './util.js';
import { Watchdog, type WatchdogHost } from './watchdog.js';

export interface AgentOptions {
  /** wss://host/ws/agent */
  wsUrl: string;
  token: string;
  deviceId?: string | null;
  /** --speed: fast-forward factor for the simulated world. */
  speed?: number;
  scenarios?: readonly ScenarioName[];
  /** --keep-link: commands that would cut the Agent's link leave it up, so auto-revert confirms. */
  keepLink?: boolean;
  /** --offline-for: drop the link for this many seconds once, `offlineAfterS` after the first hello. */
  offlineForS?: number | null;
  offlineAfterS?: number;
  seed?: number | null;
  clockSkewS?: number;
  /** --persist-outbox: keep unacked messages in the state file across runs. */
  persistOutbox?: boolean;
  /** Real milliseconds between physics ticks. */
  tickMs?: number;
  /** Simulated seconds a reboot keeps the phone off. */
  rebootDowntimeS?: number;
  backoffBaseMs?: number;
  backoffMaxMs?: number;
  stableMs?: number;
  pingIntervalMs?: number;
  /** Wait after close code 4002 before reconnecting (docs/protocol.md 3.1; Kotlin SyncConfig.replacedBackoffMs). */
  replacedBackoffMs?: number;
  /** --exit-on-replaced: stop and exit on 4002 instead of waiting and reconnecting. */
  exitOnReplaced?: boolean;
}

type ResolvedOptions = Required<Omit<AgentOptions, 'deviceId' | 'offlineForS' | 'seed'>> &
  Pick<AgentOptions, 'deviceId' | 'offlineForS' | 'seed'>;

export type AgentExitReason = 'unauthorized' | 'replaced' | 'powered_off';

/** Battery level at which a phone that died of an empty battery powers on again on the charger. */
const AUTOBOOT_MIN_PCT = 3;

interface Repeating {
  ms: number;
  handle: NodeJS.Timeout;
}

/**
 * The simulated Agent: owns the phone model, outbox, command executor, auto-revert, watchdog,
 * Finder and scenarios, and speaks the Agent protocol (docs/protocol.md 3) over `Connection`.
 *
 * Emits 'exit' (AgentExitReason) when it stops by itself (4001, power_off, and 4002 only with
 * `exitOnReplaced`).
 */
export class Agent extends EventEmitter {
  readonly clock: SimClock;
  readonly rng: Random;
  readonly log: Logger;
  readonly store: StateStore;
  readonly outbox: Outbox;
  readonly phone: Phone;
  readonly finder: Finder;
  readonly reverts: RevertManager;
  readonly executor: CommandExecutor;
  readonly watchdog: Watchdog;
  readonly scenarios: ScenarioRunner;
  readonly locks = new LockSet();
  private readonly conn: Connection;
  private readonly opts: ResolvedOptions;
  private settings: Settings;
  private settingsVersion: number;
  private rates: Rates;
  private deviceId: string | null;
  private ready = false;
  private holdDrain = false;
  private started = false;
  private stopped = false;
  private everHello = false;
  private simOffline = false;
  private agentDown = false;
  /** The battery ran out: boot by itself when charged to AUTOBOOT_MIN_PCT. */
  private autoBootPending = false;
  /** Increments whenever the phone or the Agent goes down: in-flight work from before is dropped. */
  private gen = 0;
  private rebootHistory: number[];
  private watchPrev: PhoneWatch | null = null;
  private tickTimer: NodeJS.Timeout | null = null;
  private readonly repeating = new Map<string, Repeating>();
  private readonly timeouts = new Set<NodeJS.Timeout>();
  private lastBlocker: string | null | undefined = undefined;
  private readonly storedReverts: ArmedRevert[];

  constructor(options: AgentOptions, store: StateStore, log: Logger) {
    super();
    this.opts = {
      deviceId: null,
      speed: 1,
      scenarios: [],
      keepLink: false,
      offlineForS: null,
      offlineAfterS: 20,
      seed: null,
      clockSkewS: 0,
      persistOutbox: false,
      tickMs: 1000,
      rebootDowntimeS: 20,
      backoffBaseMs: 2000,
      backoffMaxMs: 60_000,
      stableMs: 60_000,
      pingIntervalMs: 20_000,
      replacedBackoffMs: 30_000,
      exitOnReplaced: false,
      ...options,
    };
    this.store = store;
    this.log = log;
    this.clock = new SimClock(this.opts.speed, this.opts.clockSkewS);
    this.rng = new Random(this.opts.seed);

    const st = store.get();
    this.settings = isPlainObject(st.settings) ? mergeSettings(DEFAULT_SETTINGS, st.settings) : DEFAULT_SETTINGS;
    this.settingsVersion = typeof st.settings_version === 'number' ? st.settings_version : 0;
    this.rates = { fast_s: this.settings.telemetry.fast_idle_s, mirror: false };
    this.deviceId = this.opts.deviceId ?? st.device_id ?? null;
    this.rebootHistory = Array.isArray(st.reboots) ? st.reboots.filter((t) => Number.isFinite(t)) : [];
    this.storedReverts = Array.isArray(st.reverts) ? st.reverts : [];

    const now = this.clock.now();
    const imeiTail = typeof st.imei_tail === 'string' && /^\d{4}$/.test(st.imei_tail) ? st.imei_tail : String(this.rng.int(0, 9999)).padStart(4, '0');
    const bootAt = typeof st.boot_at === 'number' && st.boot_at <= now ? st.boot_at : now - this.rng.int(26, 70) * 3_600_000;
    const bootCount = typeof st.boot_count === 'number' ? st.boot_count : 42;
    store.update({ imei_tail: imeiTail, boot_at: bootAt, boot_count: bootCount });

    this.phone = new Phone({
      rng: this.rng,
      clock: this.clock,
      settings: this.settings,
      bootCount,
      bootAt,
      bootReason: typeof st.boot_reason === 'string' ? st.boot_reason : 'reboot,userrequested',
      imeiTail,
      batteryPct: typeof st.battery_pct === 'number' ? st.battery_pct : undefined,
    });
    restoreFlags(this.phone.flags, isPlainObject(st.flags) ? st.flags : undefined);
    this.phone.refreshReading();
    this.outbox = new Outbox({ lastSeq: st.last_seq ?? 0, items: this.opts.persistOutbox ? (st.outbox ?? []) : [] });

    const finderHost: FinderHost = {
      clock: this.clock,
      rng: this.rng,
      phone: this.phone,
      settings: () => this.settings,
      spotSaved: (spot) => {
        this.store.update({ spots: this.finder.list() });
        this.enqueue({ t: 'finder_spot', spot });
      },
      spotDeleted: (spotId) => {
        this.store.update({ spots: this.finder.list() });
        this.enqueue({ t: 'finder_spot_deleted', spot_id: spotId });
      },
      emit: (spec) => this.emitEvent(spec),
    };
    this.finder = new Finder(finderHost, Array.isArray(st.spots) ? st.spots : []);

    const revertHost: RevertHost = {
      now: () => this.clock.now(),
      isConnected: () => this.ready && this.conn.connected,
      newProbeId: () => newId('p_'),
      sendProbe: (id) => {
        if (this.conn.send({ t: 'probe', id })) this.log.debug('tx', `probe ${id}`);
      },
      undo: (r) => this.applyUndo(r),
      report: (r, state, detail, quiet) => {
        this.log.info('revert', `${r.command_id} ${r.name}: ${state} (${detail})`);
        this.enqueue({ t: 'cmd_revert', id: r.command_id, state, detail, ts: this.clock.serverIso() });
        if (state === 'reverted' && !quiet) {
          this.emitEvent({
            kind: 'revert',
            severity: 'warning',
            message: `Auto-revert: ${r.name} undone (${detail})`,
            data: { command_id: r.command_id, name: r.name },
          });
        }
      },
      persist: (list) => this.store.update({ reverts: list }),
    };
    this.reverts = new RevertManager(revertHost);

    const commandHost: CommandHost = {
      clock: this.clock,
      rng: this.rng,
      log: this.log,
      phone: this.phone,
      finder: this.finder,
      reverts: this.reverts,
      locks: this.locks,
      keepLink: this.opts.keepLink,
      settings: () => this.settings,
      enqueue: (m) => this.enqueue(m),
      reportQueued: (id) => this.outbox.peek().some((m) => (m.t === 'cmd_ack' || m.t === 'cmd_result') && m.id === id),
      emit: (s) => this.emitEvent(s),
      generation: () => this.gen,
      rebootsInWindow: () => this.rebootsInWindow(),
      setFlags: (p, o) => this.setFlags(p, o),
      updateLink: () => this.updateLink(),
      chargeStop: (r, on, m) => this.chargeStop(r, on, m),
      sendTiers: (tiers) => tiers.forEach((t) => this.sendTelemetry(t)),
      capabilityProbe: () => {
        const caps = this.phone.probeCapabilities();
        this.sendTelemetry('static');
        return caps;
      },
      simulateInternetCheckFailure: (d) => this.scenarios.simulateInternetCheckFailure(d),
      requestReboot: (source, bootReason) => this.later(300, () => this.reboot(source, bootReason)),
      requestPowerOff: () => this.requestPowerOff(),
      requestAgentRestart: () => this.requestAgentRestart(),
      recordSms: (sms) => this.enqueue({ t: 'sms', sms }),
      later: (ms, fn) => this.later(ms, fn),
      rememberCommands: (ids) => this.store.update({ seen_commands: [...ids] }),
    };
    this.executor = new CommandExecutor(commandHost, Array.isArray(st.seen_commands) ? st.seen_commands : []);

    const watchdogHost: WatchdogHost = {
      phone: this.phone,
      clock: this.clock,
      log: this.log,
      locks: this.locks,
      settings: () => this.settings,
      emit: (s) => this.emitEvent(s),
      setFlags: (p) => this.setFlags(p),
      rebootsInWindow: () => this.rebootsInWindow(),
      reboot: (source, bootReason) => this.reboot(source, bootReason),
      chargeStop: (r, on, m) => this.chargeStop(r, on, m),
      generation: () => this.gen,
    };
    this.watchdog = new Watchdog(watchdogHost);

    const scenarioHost: ScenarioHost = {
      phone: this.phone,
      log: this.log,
      clock: this.clock,
      rng: this.rng,
      linkChanged: () => {
        this.checkTransitions();
        this.updateLink();
      },
      powerCut: () => {
        this.phone.power.plugged = false;
        this.setFlags({ usbConnected: false, usbTether: false });
      },
      batteryDied: () => this.batteryEmpty(),
      powerRestored: () => {
        this.phone.power.plugged = true;
        this.phone.flags.usbConnected = true;
        this.later(this.clock.realMs(15), () => this.bootUp('cold,charger', true));
      },
    };
    this.scenarios = new ScenarioRunner(scenarioHost, this.opts.scenarios);

    this.conn = new Connection({
      url: this.opts.wsUrl,
      token: this.opts.token,
      log: this.log,
      rng: this.rng,
      backoffBaseMs: this.opts.backoffBaseMs,
      backoffMaxMs: this.opts.backoffMaxMs,
      stableMs: this.opts.stableMs,
      pingIntervalMs: this.opts.pingIntervalMs,
      replacedBackoffMs: this.opts.replacedBackoffMs,
      fatalOnReplaced: this.opts.exitOnReplaced,
      userAgent: `hsm-agent-sim/${AGENT_VERSION}`,
    });
    this.watchPrev = watchPhone(this.phone);
    this.conn.on('open', () => this.log.info('conn', `connected to ${this.opts.wsUrl}, waiting for hello`));
    this.conn.on('message', (msg: unknown) => this.handleServerMessage(msg));
    this.conn.on('close', (info: CloseInfo) => this.onClose(info));
    this.conn.on('fatal', (code: FatalCode) => this.onFatal(code));
  }

  // --- lifecycle -----------------------------------------------------------------------------

  start(): void {
    if (this.started || this.stopped) return;
    this.started = true;
    const p = this.phone;
    const scen = this.opts.scenarios.filter((s) => s !== 'normal');
    this.log.info(
      'sim',
      `simulated ${p.staticGroups(null).device.model} (${p.profile.id}), battery ${Math.round(p.power.percent)}%, boot #${p.system.bootCount}` +
        `${this.opts.speed !== 1 ? `, speed x${this.opts.speed}` : ''}${scen.length ? `, scenarios: ${scen.join(', ')}` : ''}${this.opts.keepLink ? ', keep-link' : ''}`,
    );
    this.watchPrev = watchPhone(this.phone);
    this.emitEvent({ kind: 'agent_start', severity: 'info', message: 'Agent service started (simulator)', data: { reason: 'manual' } });
    this.reverts.restore(this.storedReverts);
    const blocker = this.linkBlocker();
    if (blocker && this.reverts.size === 0) {
      // A change kept earlier (with --keep-link) still cuts the link and no auto-revert will undo it.
      this.log.warn(`${blocker} (saved from an earlier run): the phone cannot reach the Server.`);
      this.log.warn(`Run with --keep-link and switch it back from the Dashboard, or delete ${this.store.file ?? 'the state file'} to reset the phone.`);
    }
    this.tickTimer = setInterval(() => this.tick(), this.opts.tickMs);
    this.restartTimers();
    this.updateLink();
  }

  async stop(): Promise<void> {
    if (this.stopped) return;
    this.stopped = true;
    this.gen++;
    if (this.tickTimer) clearInterval(this.tickTimer);
    this.tickTimer = null;
    this.cancelRepeating();
    for (const t of this.timeouts) clearTimeout(t);
    this.timeouts.clear();
    this.reverts.dispose();
    this.watchdog.dispose();
    this.finder.abort();
    this.conn.stop(1000, 'agent stopped');
    this.ready = false;
    this.persistNow();
  }

  get isReady(): boolean {
    return this.ready;
  }

  get isStopped(): boolean {
    return this.stopped;
  }

  currentSettings(): { settings: Settings; version: number; rates: Rates; offsetMs: number } {
    return { settings: this.settings, version: this.settingsVersion, rates: this.rates, offsetMs: this.clock.serverOffsetMs };
  }

  rebootsInWindow(): number {
    const windowMs = this.settings.watchdog.reboot_window_h * 3_600_000;
    const now = this.clock.now();
    return this.rebootHistory.filter((t) => now - t < windowMs).length;
  }

  // --- server messages -----------------------------------------------------------------------

  /** Public so tests can drive the Agent without a socket. */
  handleServerMessage(raw: unknown): void {
    if (this.stopped) return;
    if (!isPlainObject(raw) || typeof raw.t !== 'string') {
      this.log.debug('rx', 'ignored a message without a type');
      return;
    }
    const msg = raw as unknown as ServerToAgent;
    switch (msg.t) {
      case 'hello':
        this.onHello(msg);
        break;
      case 'settings':
        this.applySettings(msg.settings, msg.version, 'settings');
        break;
      case 'rates':
        this.applyRates(msg);
        break;
      case 'cmd':
        if (!this.phone.powered || this.agentDown) return;
        this.executor.handle(msg);
        break;
      case 'cmd_keep':
        if (!this.reverts.onKeep(msg.id)) this.log.debug('rx', `cmd_keep ${msg.id}: nothing armed`);
        break;
      case 'ack': {
        const removed = this.outbox.ack(Number(msg.seq));
        this.log.debug('rx', `ack ${msg.seq}: ${removed} removed, ${this.outbox.size} left`);
        this.persistOutbox();
        this.drain();
        break;
      }
      case 'probe_ack':
        if (this.reverts.onProbeAck(msg.id)) this.log.debug('rx', `probe_ack ${msg.id} confirmed a change`);
        break;
      case 'error':
        this.log.warn(`server error ${String(msg.code)}: ${String(msg.message)}`);
        break;
      default:
        this.log.debug('rx', `ignored unknown message type "${String(raw.t)}"`);
    }
  }

  private onHello(msg: Extract<ServerToAgent, { t: 'hello' }>): void {
    const serverMs = Date.parse(msg.server_time);
    // Like Kotlin Clock.syncWithServer: an unparseable server_time keeps the previous offset.
    if (Number.isFinite(serverMs)) this.clock.setServerOffset(serverMs - this.clock.now());
    const offsetMs = this.clock.serverOffsetMs;
    if (typeof msg.device_id === 'string' && msg.device_id && msg.device_id !== this.deviceId) {
      this.deviceId = msg.device_id;
      this.store.update({ device_id: msg.device_id });
    }
    this.holdDrain = true;
    this.applySettings(msg.settings, msg.settings_version, 'hello');
    this.ready = true;
    this.outbox.resetSent();
    if (isPlainObject(msg.rates)) this.applyRates(msg.rates as unknown as Rates);
    else this.restartTimers();
    const backlog = this.outbox.size;
    this.log.info(
      'hello',
      `device ${this.deviceId ?? '?'}, settings v${this.settingsVersion}, fast every ${this.rates.fast_s} s${this.rates.mirror ? ', finder mirror on' : ''}, ` +
        `clock offset ${offsetMs >= 0 ? '+' : ''}${offsetMs} ms${backlog ? `, ${backlog} buffered to send` : ''}`,
    );
    this.sendTelemetry('static');
    this.sendTelemetry('slow');
    this.sendTelemetry('fast');
    this.holdDrain = false;
    this.drain();
    this.reverts.onConnected();
    if (!this.everHello) {
      this.everHello = true;
      this.scenarios.start();
      this.scheduleOfflineSimulation();
    }
  }

  private applySettings(settings: unknown, version: unknown, source: 'hello' | 'settings'): void {
    if (!isPlainObject(settings) || typeof version !== 'number' || !Number.isFinite(version)) {
      this.log.warn(`ignored ${source} without valid settings`);
      return;
    }
    if (version === this.settingsVersion) return;
    const merged = mergeSettings(DEFAULT_SETTINGS, settings);
    this.settings = merged;
    this.settingsVersion = version;
    const profileChanged = this.phone.applySettings(merged);
    this.store.update({ settings: merged, settings_version: version });
    this.emitEvent({ kind: 'settings_applied', severity: 'info', message: `Settings version ${version} applied`, data: { version } });
    this.restartTimers();
    if (profileChanged) {
      this.log.info('sim', `device profile override applied (${this.phone.profile.id}): capability map updated`);
      if (source === 'settings') this.sendTelemetry('static');
    }
  }

  private applyRates(r: Partial<Rates>): void {
    const fast = typeof r.fast_s === 'number' && r.fast_s > 0 ? r.fast_s : this.settings.telemetry.fast_idle_s;
    const mirror = r.mirror === true;
    if (fast !== this.rates.fast_s || mirror !== this.rates.mirror) {
      this.log.info('rx', `rates: fast telemetry every ${fast} s, finder mirror ${mirror ? 'on' : 'off'}`);
    }
    this.rates = { fast_s: fast, mirror };
    this.restartTimers();
  }

  private onClose(info: CloseInfo): void {
    const wasReady = this.ready;
    this.ready = false;
    if (info.code === 4002 && !info.local) {
      this.log.warn(
        `replaced (4002): another connection with this device token took over; waiting ${formatDuration(this.opts.replacedBackoffMs / 1000)} before reconnecting ` +
          `(${this.outbox.size} messages buffered). Run one simulator per paired device.`,
      );
    } else if (info.wasOpen && !info.local) {
      this.log.info('conn', `disconnected (code ${info.code}${info.reason ? `, ${info.reason}` : ''}); ${this.outbox.size} messages buffered`);
    } else if (wasReady && info.local) {
      this.log.debug('conn', `socket closed (${info.reason})`);
    }
    this.restartTimers();
  }

  private onFatal(code: FatalCode): void {
    this.ready = false;
    if (code === 4001) {
      this.log.error('the Server rejected the device token (4001): this simulator is not paired any more.');
      this.log.error('Create a new pairing code in the Dashboard (Settings > Pair phone) and run again with --code <CODE>.');
      this.exit('unauthorized');
    } else {
      this.log.error('replaced (4002): another Agent connected with this device token.');
      this.log.error('Stopping (--exit-on-replaced). Run one simulator per paired device.');
      this.exit('replaced');
    }
  }

  private exit(reason: AgentExitReason): void {
    void this.stop().then(() => this.emit('exit', reason));
  }

  // --- outbox and sending --------------------------------------------------------------------

  private enqueue(input: OutboxInput): void {
    if (this.stopped) return;
    const dropped = this.outbox.dropped;
    this.outbox.push(input);
    if (this.outbox.dropped > dropped) this.log.debug('sim', `outbox full: dropped ${this.outbox.dropped - dropped} old fast telemetry rows`);
    this.store.update({ last_seq: this.outbox.lastSeq });
    this.persistOutbox();
    this.drain();
  }

  private drain(): void {
    if (!this.ready || this.holdDrain || !this.conn.connected) return;
    for (const frame of this.outbox.takeFrames()) {
      if (!this.conn.send(frame)) break;
      this.logFrame(frame);
    }
  }

  private logFrame(frame: AgentToServer): void {
    if (frame.t === 'batch') {
      const first = frame.items[0]?.seq;
      const last = frame.items[frame.items.length - 1]?.seq;
      this.log.info('tx', `batch of ${frame.items.length} buffered messages (seq ${first}..${last})`);
      return;
    }
    if (!this.log.verbose) return;
    const seq = 'seq' in frame ? ` #${frame.seq}` : '';
    const detail = frame.t === 'telemetry' ? ` ${frame.tier}` : frame.t === 'event' ? ` ${frame.event.kind}` : '';
    this.log.debug('tx', `${frame.t}${detail}${seq}`);
  }

  private emitEvent(spec: EventSpec): void {
    if (this.stopped) return;
    const ev = makeEvent(spec, this.clock.serverIso());
    this.log.info('event', `${spec.kind}: ${spec.message}`);
    this.enqueue({ t: 'event', event: ev });
  }

  private sendTelemetry(tier: TelemetryTier): void {
    if (!this.phone.powered || this.agentDown || this.stopped) return;
    const ts = this.clock.serverIso();
    if (tier === 'fast') {
      this.enqueue({ t: 'telemetry', ts, tier, ...this.phone.fastGroups() });
    } else if (tier === 'slow') {
      this.enqueue({ t: 'telemetry', ts, tier, ...this.phone.slowGroups(this.rebootsInWindow()) });
      this.store.update({ battery_pct: round1(this.phone.power.percent) });
    } else {
      this.enqueue({ t: 'telemetry', ts, tier, ...this.phone.staticGroups(this.deviceId) });
    }
  }

  private sendHeartbeat(): void {
    if (!this.ready) return;
    const now = this.clock.now();
    const sys = this.phone.system;
    const hb: HeartbeatMsg = {
      t: 'heartbeat',
      ts: this.clock.serverIso(now),
      uptime_s: Math.max(0, Math.floor((now - sys.bootAt) / 1000)),
      agent_uptime_s: Math.max(0, Math.floor((now - sys.agentStartAt) / 1000)),
      outbox: this.outbox.size,
      settings_version: this.settingsVersion,
    };
    if (this.conn.send(hb)) this.log.debug('tx', `heartbeat (outbox ${hb.outbox})`);
  }

  private sendFinderLive(): void {
    if (!this.ready) return;
    this.conn.send({ t: 'finder_live', live: this.finder.live() });
  }

  // --- timers --------------------------------------------------------------------------------

  private restartTimers(): void {
    if (!this.started || this.stopped) return;
    if (!this.phone.powered || this.agentDown) {
      this.cancelRepeating();
      return;
    }
    const t = this.settings.telemetry;
    const d = DEFAULT_SETTINGS.telemetry;
    this.every('heartbeat', t.heartbeat_s, d.heartbeat_s, () => this.sendHeartbeat());
    // Offline, the fast tier falls back to the idle rate (nobody is watching live).
    this.every('fast', this.ready ? this.rates.fast_s : t.fast_idle_s, d.fast_idle_s, () => this.sendTelemetry('fast'));
    this.every('slow', t.slow_s, d.slow_s, () => this.sendTelemetry('slow'));
    const mirror = this.ready && this.rates.mirror;
    this.finder.mirroring = mirror;
    this.phone.radio.volatility = mirror ? 2 : 1;
    if (mirror) this.every('mirror', t.mirror_s, d.mirror_s, () => this.sendFinderLive());
    else this.cancelRepeating('mirror');
  }

  private every(name: string, seconds: number, fallbackS: number, fn: () => void): void {
    const s = typeof seconds === 'number' && seconds > 0 ? seconds : fallbackS;
    const ms = Math.max(50, Math.round(s * 1000));
    const cur = this.repeating.get(name);
    if (cur?.ms === ms) return;
    if (cur) clearInterval(cur.handle);
    this.repeating.set(name, { ms, handle: setInterval(fn, ms) });
  }

  private cancelRepeating(name?: string): void {
    for (const [key, r] of this.repeating) {
      if (name && key !== name) continue;
      clearInterval(r.handle);
      this.repeating.delete(key);
    }
  }

  /** setTimeout that dies with the Agent. */
  private later(ms: number, fn: () => void): void {
    if (this.stopped) return;
    const handle = setTimeout(() => {
      this.timeouts.delete(handle);
      if (!this.stopped) fn();
    }, Math.max(0, ms));
    this.timeouts.add(handle);
  }

  private scheduleOfflineSimulation(): void {
    const forS = this.opts.offlineForS;
    if (!forS || forS <= 0) return;
    this.later(this.opts.offlineAfterS * 1000, () => {
      this.simOffline = true;
      this.log.info('sim', `--offline-for: dropping the link for ${formatDuration(forS)}; telemetry keeps buffering`);
      this.updateLink();
      this.later(forS * 1000, () => {
        this.simOffline = false;
        this.log.info('sim', `--offline-for: link back after ${formatDuration(forS)}; back-filling ${this.outbox.size} buffered messages`);
        this.updateLink();
      });
    });
  }

  // --- the simulated world -------------------------------------------------------------------

  private tick(): void {
    if (this.stopped) return;
    const dt = (this.opts.tickMs / 1000) * this.clock.speed;
    this.scenarios.tick(dt);
    const power = this.phone.power;
    if (!this.phone.powered) {
      this.phone.tickOff(dt);
      // Automatic power-on (plan 4.3): back up once the charger has put some charge in.
      if (this.autoBootPending && power.plugged && power.percent >= AUTOBOOT_MIN_PCT) this.bootUp('cold,charger', true);
      return;
    }
    this.phone.tick(dt);
    if (power.percent <= 0 && power.currentMa < 0) {
      this.batteryEmpty();
      return;
    }
    if (this.agentDown) return;
    this.checkTransitions();
    this.watchdog.tick(dt);
    this.updateLink();
  }

  private checkTransitions(): void {
    if (!this.phone.powered || this.agentDown || this.stopped) return;
    const cur = watchPhone(this.phone);
    const prev = this.watchPrev;
    this.watchPrev = cur;
    if (!prev) return;
    const now = this.clock.now();
    let downFor: number | null = null;
    if (prev.online && !cur.online) {
      this.phone.net.downSince = now;
    } else if (!prev.online && cur.online) {
      downFor = this.phone.net.downSince != null ? (now - this.phone.net.downSince) / 1000 : null;
      this.phone.net.downSince = null;
    }
    for (const spec of transitions(prev, cur, downFor)) this.emitEvent(spec);
  }

  /** Why the Agent cannot reach the Server right now, or null when it can. */
  private linkBlocker(): string | null {
    const p = this.phone;
    const f = p.flags;
    if (!p.powered) return 'phone is off';
    if (this.agentDown) return 'Agent restarting';
    if (this.simOffline) return 'simulated outage (--offline-for)';
    if (f.internetBroken) return 'internet down';
    if (this.opts.keepLink) return null;
    if (f.airplane) return 'airplane mode on';
    if (f.radioResting) return 'radio resting (heat)';
    if (!f.dataEnabled) return 'mobile data off';
    if (f.modeSwitchCut) return 'modem re-registering after a network mode change';
    return null;
  }

  private updateLink(): void {
    if (!this.started || this.stopped) return;
    const blocker = this.linkBlocker();
    if (blocker !== this.lastBlocker) {
      if (blocker) this.log.info('sim', `link down: ${blocker}; buffering (${this.outbox.size} in outbox)`);
      else if (this.lastBlocker !== undefined) this.log.info('sim', 'link up: reconnecting now');
      this.lastBlocker = blocker;
    }
    this.conn.setEnabled(blocker === null);
  }

  private setFlags(patch: Partial<PhoneFlags>, opts: { deferLink?: boolean } = {}): void {
    const wasOnline = this.phone.internetOk;
    Object.assign(this.phone.flags, patch);
    this.store.update({ flags: persistableFlags(this.phone.flags) });
    if (this.phone.powered) {
      this.phone.refreshReading();
      // Re-attaching to the network gets a new carrier-NAT address.
      if (!wasOnline && this.phone.internetOk) this.phone.net.renewAddress();
    }
    this.checkTransitions();
    if (!opts.deferLink) this.updateLink();
  }

  private chargeStop(reason: StopReason, on: boolean, message: string): void {
    const { before, after } = this.phone.power.setStop(reason, on);
    if (before === after) return;
    if (after !== null && (before === null || after === 'heat')) {
      this.emitEvent({ kind: 'charging_stopped', severity: after === 'heat' ? 'warning' : 'info', message, data: { reason: after } });
    } else if (after === null) {
      this.emitEvent({ kind: 'charging_resumed', severity: 'info', message });
    } else {
      this.log.debug('sim', `charging still stopped (${after})`);
    }
  }

  private applyUndo(r: ArmedRevert): void {
    const a = r.undo.args;
    const f = this.phone.flags;
    switch (r.undo.name) {
      case 'mobile_data':
        this.setFlags({ dataEnabled: a.enabled !== false });
        break;
      case 'airplane_mode':
        this.setFlags({ airplane: a.enabled === true });
        break;
      case 'usb_tethering': {
        const on = a.enabled === true;
        const fn = a.function === 'ncm' || a.function === 'rndis' ? a.function : f.usbFunction;
        this.setFlags({ usbTether: on && f.usbConnected, usbFunction: fn, tetherManualOff: !on });
        break;
      }
      case 'network_mode': {
        const mode = NETWORK_MODES.includes(a.mode as NetworkMode) ? (a.mode as NetworkMode) : 'auto';
        this.setFlags({ networkMode: mode, modeSwitchCut: false });
        break;
      }
      default:
        this.log.warn(`no undo action for ${r.undo.name}`);
    }
  }

  // --- reboots, power and Agent restarts -----------------------------------------------------

  private reboot(source: CommandSource | 'watchdog' | 'schedule', bootReason: string): void {
    if (this.stopped || !this.phone.powered) return;
    const now = this.clock.now();
    this.rebootHistory = [...this.rebootHistory.filter((t) => now - t < 7 * 86_400_000), now].slice(-50);
    this.store.update({ reboots: this.rebootHistory });
    const downtime = this.opts.rebootDowntimeS;
    this.log.info('sim', `rebooting (requested by ${source}); back in about ${formatDuration(this.clock.realMs(downtime) / 1000)}`);
    this.powerDown(`reboot by ${source}`);
    this.later(this.clock.realMs(downtime), () => this.bootUp(bootReason, false));
  }

  private powerDown(why: string): void {
    if (!this.phone.powered) return;
    this.gen++;
    this.drain();
    this.phone.powered = false;
    this.ready = false;
    this.agentDown = false;
    this.finder.abort();
    this.reverts.suspend();
    this.locks.clear();
    this.watchdog.resetAfterBoot();
    this.cancelRepeating();
    this.watchPrev = null;
    this.log.info('sim', `phone off (${why})`);
    this.updateLink();
    this.persistNow();
  }

  private bootUp(bootReason: string, cold: boolean): void {
    if (this.stopped || this.phone.powered) return;
    this.autoBootPending = false;
    const now = this.clock.now();
    this.phone.boot(now, bootReason, cold);
    const bootCount = this.phone.system.bootCount;
    this.store.update({ boot_count: bootCount, boot_at: now, boot_reason: bootReason, flags: persistableFlags(this.phone.flags) });
    this.watchdog.resetAfterBoot();
    this.log.info('sim', `phone booted (${bootReason}), boot #${bootCount}`);
    this.emitEvent({ kind: 'boot', severity: 'info', message: `Phone booted (${bootReason})`, data: { boot_count: bootCount, boot_reason: bootReason } });
    this.emitEvent({ kind: 'agent_start', severity: 'info', message: 'Agent service started after boot', data: { reason: 'boot' } });
    // A scenario may end with the reboot (internet-loss); its effect is part of the fresh boot.
    this.watchPrev = null;
    this.scenarios.onBoot();
    this.watchPrev = watchPhone(this.phone);
    this.reverts.resume();
    this.restartTimers();
    this.updateLink();
  }

  /** The battery ran out: Android shuts down; the phone powers on by itself once charged a little. */
  private batteryEmpty(): void {
    this.autoBootPending = true;
    this.powerDown('battery empty');
  }

  private requestAgentRestart(): void {
    this.later(300, () => {
      if (!this.phone.powered || this.agentDown) return;
      this.gen++;
      this.drain();
      this.agentDown = true;
      this.ready = false;
      this.finder.abort();
      this.reverts.suspend();
      this.locks.clear();
      this.cancelRepeating();
      this.watchPrev = null;
      this.log.info('sim', 'Agent force-stopped; the keep-alive script restarts it');
      this.updateLink();
      this.later(this.clock.realMs(5), () => {
        if (!this.phone.powered || !this.agentDown) return;
        this.agentDown = false;
        this.phone.system.restartAgent(this.clock.now());
        this.watchPrev = watchPhone(this.phone);
        this.emitEvent({ kind: 'agent_start', severity: 'info', message: 'Agent service restarted by the keep-alive script', data: { reason: 'restart' } });
        this.reverts.resume();
        this.restartTimers();
        this.updateLink();
      });
    });
  }

  private requestPowerOff(): void {
    this.later(300, () => {
      this.powerDown('power_off command');
      this.log.warn('the phone is switched off; a real phone now needs a hand to switch it on again');
      this.later(500, () => this.exit('powered_off'));
    });
  }

  // --- persistence ---------------------------------------------------------------------------

  private persistOutbox(): void {
    if (this.opts.persistOutbox) this.store.update({ outbox: [...this.outbox.peek()] });
  }

  private persistNow(): void {
    this.store.update(
      {
        last_seq: this.outbox.lastSeq,
        battery_pct: round1(this.phone.power.percent),
        ...(this.opts.persistOutbox ? { outbox: [...this.outbox.peek()] } : {}),
      },
      true,
    );
  }
}
