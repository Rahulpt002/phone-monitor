import type { Settings, WatchdogSettings } from '@hsm/shared';
import type { SimClock } from './clock.js';
import type { EventSpec } from './events.js';
import type { LockSet } from './locks.js';
import type { Logger } from './log.js';
import type { Phone, PhoneFlags } from './phone/phone.js';
import type { StopReason } from './phone/power.js';
import { delay, formatDuration, localDateKey, round1 } from './util.js';

export interface WatchdogHost {
  phone: Phone;
  clock: SimClock;
  log: Logger;
  locks: LockSet;
  settings(): Settings;
  emit(spec: EventSpec): void;
  setFlags(patch: Partial<PhoneFlags>): void;
  rebootsInWindow(): number;
  reboot(source: 'watchdog' | 'schedule', bootReason: string): void;
  /** Adds or removes a charge-stop reason and emits charging_stopped / charging_resumed. */
  chargeStop(reason: StopReason, on: boolean, message: string): void;
  /** Increments whenever the phone goes down; work started before must not report afterwards. */
  generation(): number;
}

const OWNER = 'watchdog';

/**
 * Local recovery rules (plan 6.5). Runs on simulated time, so --speed shortens the ladder
 * (90 s / 180 s / 600 s by default). Charge-limit, heat, weak-power and movement rules are battery
 * and safety monitoring and stay on even when `watchdog.enabled` is false; that switch turns off
 * the recovery actions (internet ladder, tether fix, weekly reboot).
 */
export class Watchdog {
  /** Simulated seconds the internet check has been failing. */
  private downForS = 0;
  /** Last ladder step taken: 0 none, 1 data cycle, 2 airplane cycle, 3 reboot. */
  private step = 0;
  private rebootRetryAtS = 0;
  private acting = false;
  private tetherOffS = 0;
  private heatState: 'normal' | 'warning' | 'critical' = 'normal';
  private avgCurrentMa: number | null = null;
  private weak = { seconds: 0, startPct: 0, reported: false };
  private radioRestTimer: NodeJS.Timeout | null = null;
  private moveReported = false;
  private weeklyDoneKey = '';

  constructor(private readonly host: WatchdogHost) {}

  tick(dt: number): void {
    const { phone } = this.host;
    if (!phone.powered) return;
    const s = this.host.settings().watchdog;
    this.chargeLimitRule(s);
    this.heatRules(s);
    this.weakPowerRule(s, dt);
    this.moveRule(s);
    if (!s.enabled) {
      this.downForS = 0;
      this.step = 0;
      this.tetherOffS = 0;
      return;
    }
    this.internetRule(s, dt);
    this.tetherRule(s, dt);
    this.weeklyRebootRule(s);
  }

  /** After a boot the ladder starts again (the plan's "retry hourly" covers repeated failures). */
  resetAfterBoot(): void {
    this.downForS = 0;
    this.step = 0;
    this.rebootRetryAtS = 0;
    this.acting = false;
    this.tetherOffS = 0;
    this.avgCurrentMa = null;
    this.weak = { seconds: 0, startPct: 0, reported: false };
    if (this.radioRestTimer) clearTimeout(this.radioRestTimer);
    this.radioRestTimer = null;
  }

  dispose(): void {
    if (this.radioRestTimer) clearTimeout(this.radioRestTimer);
    this.radioRestTimer = null;
  }

  /** How long the internet check has been failing (simulated seconds), for logs and tests. */
  get internetDownForS(): number {
    return this.downForS;
  }

  // --- internet recovery ladder ------------------------------------------------------------

  private internetRule(s: WatchdogSettings, dt: number): void {
    const { phone } = this.host;
    const f = phone.flags;
    if (this.acting) {
      this.downForS += dt;
      return;
    }
    if (phone.internetOk) {
      if (this.step > 0) this.host.log.info('wdog', `internet recovered after ${formatDuration(this.downForS)}`);
      this.downForS = 0;
      this.step = 0;
      this.rebootRetryAtS = 0;
      return;
    }
    // Mobile data off or airplane on by command is handled by auto-revert, not by the ladder.
    if (!f.dataEnabled || f.airplane || f.radioResting || f.modeSwitchCut) {
      this.downForS = 0;
      this.step = 0;
      return;
    }
    this.downForS += dt;
    if (this.step < 1 && this.downForS >= s.internet_fail_s) {
      void this.cycle(1, 'data_cycle', s);
    } else if (this.step < 2 && this.downForS >= s.airplane_after_s) {
      void this.cycle(2, 'airplane_cycle', s);
    } else if (this.step < 3 && this.downForS >= s.reboot_after_s && this.downForS >= this.rebootRetryAtS) {
      this.rebootStep(s);
    }
  }

  private async cycle(step: 1 | 2, action: 'data_cycle' | 'airplane_cycle', s: WatchdogSettings): Promise<void> {
    const { locks, clock, log } = this.host;
    // A command is using the radio: try again on the next tick.
    if (!locks.tryAcquire('radio', OWNER)) return;
    const gen = this.host.generation();
    const downFor = this.downForS;
    this.acting = true;
    this.step = step;
    const verb = action === 'data_cycle' ? 'cycled mobile data' : 'cycled airplane mode';
    log.info('wdog', `internet down ${formatDuration(downFor)}: ${action === 'data_cycle' ? `mobile data off for ${s.data_off_s} s` : `airplane mode on for ${s.airplane_off_s} s`}`);
    try {
      // Both switches are persistent settings, so they are restored even if the phone went
      // down meanwhile; otherwise it would boot with mobile data off.
      if (action === 'data_cycle') {
        this.host.setFlags({ dataEnabled: false });
        await delay(clock.realMs(s.data_off_s));
        this.host.setFlags({ dataEnabled: true });
      } else {
        this.host.setFlags({ airplane: true });
        await delay(clock.realMs(s.airplane_off_s));
        this.host.setFlags({ airplane: false });
      }
      // Give the modem time to re-attach before judging the outcome.
      if (gen === this.host.generation()) await delay(clock.realMs(5));
    } finally {
      locks.release('radio', OWNER);
      this.acting = false;
    }
    if (gen !== this.host.generation()) return;
    const recovered = this.host.phone.internetOk;
    this.host.emit({
      kind: 'watchdog_action',
      severity: 'warning',
      message: `Internet down ${formatDuration(downFor)}: ${verb}${recovered ? '' : ', still down'}`,
      data: { rule: 'internet', action, step, outcome: recovered ? 'recovered' : 'still_down' },
    });
  }

  private rebootStep(s: WatchdogSettings): void {
    const { locks } = this.host;
    const used = this.host.rebootsInWindow();
    if (used >= s.max_reboots) {
      this.rebootRetryAtS = this.downForS + s.reboot_retry_s;
      this.host.emit({
        kind: 'reboot_blocked',
        severity: 'warning',
        message: `Reboot limit reached (${used} in ${s.reboot_window_h} h): watchdog reboot postponed by ${formatDuration(s.reboot_retry_s)}`,
        data: { source: 'watchdog', reboots_in_window: used, max_reboots: s.max_reboots },
      });
      this.host.emit({
        kind: 'watchdog_action',
        severity: 'warning',
        message: `Internet down ${formatDuration(this.downForS)}: reboot blocked by the reboot limit`,
        data: { rule: 'internet', action: 'reboot', step: 3, outcome: 'blocked' },
      });
      return;
    }
    if (locks.isHeld('radio') || locks.isHeld('system')) return;
    this.step = 3;
    this.host.emit({
      kind: 'watchdog_action',
      severity: 'warning',
      message: `Internet down ${formatDuration(this.downForS)}: rebooting`,
      data: { rule: 'internet', action: 'reboot', step: 3, outcome: 'rebooting' },
    });
    this.host.reboot('watchdog', 'reboot,watchdog');
  }

  // --- tethering ---------------------------------------------------------------------------

  private tetherRule(s: WatchdogSettings, dt: number): void {
    const f = this.host.phone.flags;
    if (!s.tether_fix || !f.usbConnected || f.usbTether || f.tetherManualOff) {
      this.tetherOffS = 0;
      return;
    }
    this.tetherOffS += dt;
    if (this.tetherOffS < s.tether_fix_after_s) return;
    if (!this.host.locks.tryAcquire('radio', OWNER)) return;
    try {
      this.host.setFlags({ usbTether: true, usbFunction: s.usb_function });
    } finally {
      this.host.locks.release('radio', OWNER);
    }
    const after = Math.round(this.tetherOffS);
    this.tetherOffS = 0;
    this.host.emit({
      kind: 'watchdog_action',
      severity: 'warning',
      message: `USB connected but tethering off for ${formatDuration(after)}: turned tethering back on (${s.usb_function})`,
      data: { rule: 'tether', action: 'usb_tethering', step: 1, outcome: 'recovered' },
    });
  }

  // --- charging and heat -------------------------------------------------------------------

  private chargeLimitRule(s: WatchdogSettings): void {
    const { phone } = this.host;
    const pw = phone.power;
    const limit = phone.chargeLimitPct;
    const resumeAt = limit - s.charge_resume_drop_pct;
    const pct = Math.round(pw.percent);
    if (!pw.hasStop('limit') && limit < 100 && pw.percent >= limit) {
      this.host.chargeStop('limit', true, `Battery at ${pct}%: charging stopped at the ${limit}% limit`);
    } else if (pw.hasStop('limit') && (pw.percent <= resumeAt || limit >= 100)) {
      this.host.chargeStop('limit', false, `Battery at ${pct}%: charging resumed (limit ${limit}%)`);
    }
  }

  private heatRules(s: WatchdogSettings): void {
    const { phone, clock } = this.host;
    const t = round1(phone.heat.battery);
    if (t >= s.temp_stop_charge_c) {
      if (this.heatState !== 'critical') {
        this.heatState = 'critical';
        this.host.emit({
          kind: 'temp_critical',
          severity: 'critical',
          message: `Battery ${t} °C: charging stopped until it is below ${s.temp_resume_c} °C`,
          data: { battery_c: t, threshold_c: s.temp_stop_charge_c },
        });
        this.host.chargeStop('heat', true, `Battery ${t} °C: charging stopped (heat)`);
      }
    } else if (t >= s.temp_warn_c) {
      if (this.heatState === 'normal') {
        this.heatState = 'warning';
        this.host.emit({
          kind: 'temp_warning',
          severity: 'warning',
          message: `Battery ${t} °C (warning at ${s.temp_warn_c} °C)`,
          data: { battery_c: t, threshold_c: s.temp_warn_c },
        });
      }
    } else if (t < s.temp_resume_c && this.heatState !== 'normal') {
      this.heatState = 'normal';
      this.host.emit({ kind: 'temp_normal', severity: 'info', message: `Battery back to ${t} °C`, data: { battery_c: t } });
      if (phone.power.hasStop('heat')) this.host.chargeStop('heat', false, `Battery ${t} °C: charging resumed`);
    }

    if (t >= s.temp_radio_rest_c && !phone.flags.radioResting && !this.radioRestTimer) {
      this.host.setFlags({ radioResting: true });
      this.host.emit({
        kind: 'radio_rest',
        severity: 'critical',
        message: `Battery ${t} °C: radio resting for ${formatDuration(s.radio_rest_s)}`,
        data: { seconds: s.radio_rest_s, battery_c: t },
      });
      const gen = this.host.generation();
      this.radioRestTimer = setTimeout(() => {
        this.radioRestTimer = null;
        if (gen !== this.host.generation()) return;
        this.host.setFlags({ radioResting: false });
        this.host.log.info('wdog', 'radio rest over: radio back on');
      }, clock.realMs(s.radio_rest_s));
    }
  }

  /** "Plugged in but battery falling for weak_power_min" on a 60 s average of the battery current. */
  private weakPowerRule(s: WatchdogSettings, dt: number): void {
    const pw = this.host.phone.power;
    const a = 1 - Math.exp(-dt / 60);
    this.avgCurrentMa = this.avgCurrentMa == null ? pw.currentMa : this.avgCurrentMa + (pw.currentMa - this.avgCurrentMa) * a;
    const falling = pw.plugged && this.avgCurrentMa < -60;
    if (!falling) {
      this.weak = { seconds: 0, startPct: pw.percent, reported: false };
      return;
    }
    if (this.weak.seconds === 0) this.weak.startPct = pw.percent;
    this.weak.seconds += dt;
    if (!this.weak.reported && this.weak.seconds >= s.weak_power_min * 60 && pw.percent < this.weak.startPct) {
      this.weak.reported = true;
      const from = Math.round(this.weak.startPct * 10) / 10;
      const to = Math.round(pw.percent * 10) / 10;
      this.host.emit({
        kind: 'weak_power',
        severity: 'warning',
        message: `Plugged in but battery falling for ${formatDuration(this.weak.seconds)} (${from}% -> ${to}%, ${Math.round(this.avgCurrentMa)} mA)`,
        data: { minutes: Math.round(this.weak.seconds / 60), from_pct: from, to_pct: to, current_ma: Math.round(this.avgCurrentMa) },
      });
    }
  }

  private moveRule(s: WatchdogSettings): void {
    const { phone } = this.host;
    const { tilt, moved } = phone.position.drift();
    if (!this.moveReported && (tilt >= s.move_tilt_deg || moved >= s.move_distance_m)) {
      this.moveReported = true;
      const pos = phone.position.info();
      this.host.emit({
        kind: 'moved',
        severity: 'warning',
        message: `Phone moved: tilt ${round1(tilt)}°, ${Math.round(moved)} m from the installed spot`,
        data: { tilt_change_deg: round1(tilt), moved_m: Math.round(moved), lat: pos.lat, lon: pos.lon },
      });
    } else if (this.moveReported && tilt < s.move_tilt_deg / 2 && moved < s.move_distance_m / 2) {
      this.moveReported = false;
    }
  }

  private weeklyRebootRule(s: WatchdogSettings): void {
    const wr = s.weekly_reboot;
    if (!wr.enabled) return;
    const now = this.host.clock.now();
    const d = new Date(now);
    const [hh, mm] = wr.time.split(':').map((x) => Number.parseInt(x, 10));
    const key = localDateKey(now);
    if (d.getDay() !== wr.day || d.getHours() !== hh || d.getMinutes() !== mm || this.weeklyDoneKey === key) return;
    this.weeklyDoneKey = key;
    const used = this.host.rebootsInWindow();
    if (used >= s.max_reboots) {
      this.host.emit({
        kind: 'reboot_blocked',
        severity: 'warning',
        message: `Weekly reboot skipped: reboot limit reached (${used} in ${s.reboot_window_h} h)`,
        data: { source: 'schedule', reboots_in_window: used, max_reboots: s.max_reboots },
      });
      return;
    }
    this.host.emit({
      kind: 'watchdog_action',
      severity: 'info',
      message: `Weekly reboot (${wr.time})`,
      data: { rule: 'schedule', action: 'reboot', step: 1, outcome: 'rebooting' },
    });
    this.host.reboot('schedule', 'reboot,schedule');
  }
}
