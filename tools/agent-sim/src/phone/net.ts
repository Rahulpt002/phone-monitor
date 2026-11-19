import { clamp, type NetInfo, type TrafficInfo } from '@hsm/shared';
import { ouStep, type Random } from '../rng.js';
import { dayBump, localDateKey, round1 } from '../util.js';

/** Mobile data interface on the simulated phone. */
export const MOBILE_IF = 'rmnet_data2';

/** Household demand through the day (0..1): evening streaming peak, smaller daytime use. */
export function usageDemand(hour: number): number {
  return clamp(0.06 + 0.55 * dayBump(hour, 20.5, 2.2) + 0.22 * dayBump(hour, 13, 3) + 0.12 * dayBump(hour, 9, 1.5), 0, 1);
}

/** Local midnight of the current billing cycle's start day, as "YYYY-MM-DD". */
export function cycleStartDate(nowMs: number, startDay: number): string {
  const day = clamp(Math.round(startDay) || 1, 1, 28);
  const d = new Date(nowMs);
  const start = d.getDate() >= day ? new Date(d.getFullYear(), d.getMonth(), day) : new Date(d.getFullYear(), d.getMonth() - 1, day);
  return localDateKey(start.getTime());
}

function localMidnightMs(dateKey: string): number {
  const [y, m, d] = dateKey.split('-').map(Number) as [number, number, number];
  return new Date(y, m - 1, d).getTime();
}

export interface NetEnv {
  online: boolean;
  hour: number;
  congestion: number;
  sinr: number | null;
  /** Devices using the tether / hotspot right now. */
  clients: number;
  nowMs: number;
  cycleStartDay: number;
}

interface Counter {
  rx: number;
  tx: number;
}

/** Internet probes, current throughput and the traffic counters. */
export class NetModel {
  rxBps = 0;
  txBps = 0;
  /** Phone time (ms) when the internet check first failed, while it is failing. */
  downSince: number | null = null;
  private burst = 0;
  private mobile: Counter;
  private tether: Counter;
  private today: Counter & { key: string };
  private cycle: Counter & { start: string };
  private ipv4 = '';
  private ipv6 = '';

  constructor(private readonly rng: Random, nowMs: number, cycleStartDay: number, uptimeS: number) {
    const dailyRx = 2.3e9;
    const dayFraction = (nowMs - localMidnightMs(localDateKey(nowMs))) / 86_400_000;
    this.today = { rx: Math.round(dailyRx * dayFraction), tx: Math.round(dailyRx * 0.09 * dayFraction), key: localDateKey(nowMs) };
    const start = cycleStartDate(nowMs, cycleStartDay);
    const cycleDays = (nowMs - localMidnightMs(start)) / 86_400_000;
    this.cycle = { rx: Math.round(dailyRx * cycleDays), tx: Math.round(dailyRx * 0.09 * cycleDays), start };
    const upDays = uptimeS / 86_400;
    this.mobile = { rx: Math.round(dailyRx * upDays), tx: Math.round(dailyRx * 0.09 * upDays) };
    this.tether = { rx: Math.round(this.mobile.tx * 0.97), tx: Math.round(this.mobile.rx * 0.97) };
    this.renewAddress();
  }

  /** New carrier-NAT address, as after a data / airplane cycle or a reboot. Documentation ranges only. */
  renewAddress(): void {
    this.ipv4 = `203.0.113.${this.rng.int(2, 254)}`;
    this.ipv6 = `2001:db8:${this.rng.hex(4)}:${this.rng.hex(4)}::${this.rng.hex(3)}`;
  }

  /** /sys/class/net counters start from zero after a reboot. */
  resetInterfaceCounters(): void {
    this.mobile = { rx: 0, tx: 0 };
    this.tether = { rx: 0, tx: 0 };
  }

  step(dt: number, env: NetEnv): void {
    this.rollover(env.nowMs, env.cycleStartDay);
    if (!env.online) {
      this.rxBps = 0;
      this.txBps = 0;
      return;
    }
    this.burst = ouStep(this.burst, 0, 0.7, 25, dt, this.rng);
    const demand = clamp(usageDemand(env.hour) * Math.exp(this.burst), 0, 1);
    const capacity = Math.max(0.5, ((env.sinr ?? 0) + 6) * 2.3) * 1e6 * (1 - 0.45 * env.congestion);
    const rx = env.clients > 0 ? demand * capacity : 20_000 + this.rng.range(0, 30_000);
    const tx = rx * (0.07 + 0.05 * this.rng.next()) + 8_000;
    this.rxBps = Math.round(rx);
    this.txBps = Math.round(tx);
    this.addBytes((rx * dt) / 8, (tx * dt) / 8, env.clients > 0);
  }

  /** Counts traffic on the mobile interface (and mirrors it on the tether when clients use it). */
  addBytes(rx: number, tx: number, viaTether: boolean): void {
    this.mobile.rx += rx;
    this.mobile.tx += tx;
    this.today.rx += rx;
    this.today.tx += tx;
    this.cycle.rx += rx;
    this.cycle.tx += tx;
    if (viaTether) {
      this.tether.tx += rx * 0.98;
      this.tether.rx += tx * 0.98;
    }
  }

  info(online: boolean, congestion: number, dataActive: boolean, clockIso: (ms: number) => string): NetInfo {
    if (!online) {
      return {
        online: false,
        http_ms: null,
        ping_ms: null,
        ping_loss_pct: 100,
        jitter_ms: null,
        dns_ok: false,
        public_ipv4: null,
        public_ipv6: null,
        rx_bps: 0,
        tx_bps: 0,
        active_if: dataActive ? MOBILE_IF : null,
        internet_down_since: this.downSince != null ? clockIso(this.downSince) : null,
      };
    }
    const rng = this.rng;
    const ping = round1(Math.max(14, 36 + 28 * congestion + rng.gauss(0, 3.5)));
    return {
      online: true,
      http_ms: Math.max(40, Math.round(ping * 2.4 + 70 + rng.gauss(0, 12))),
      ping_ms: ping,
      ping_loss_pct: rng.chance(0.03 + 0.1 * congestion) ? 10 : 0,
      jitter_ms: round1(1.8 + 6 * congestion + Math.abs(rng.gauss(0, 1.2))),
      dns_ok: true,
      public_ipv4: this.ipv4,
      public_ipv6: this.ipv6,
      rx_bps: this.rxBps,
      tx_bps: this.txBps,
      active_if: MOBILE_IF,
      internet_down_since: null,
    };
  }

  traffic(tetherIf: string | null): TrafficInfo {
    const perIf: Record<string, { rx: number; tx: number }> = {
      [MOBILE_IF]: { rx: Math.round(this.mobile.rx), tx: Math.round(this.mobile.tx) },
    };
    if (tetherIf) perIf[tetherIf] = { rx: Math.round(this.tether.rx), tx: Math.round(this.tether.tx) };
    return {
      today_rx: Math.round(this.today.rx),
      today_tx: Math.round(this.today.tx),
      cycle_rx: Math.round(this.cycle.rx),
      cycle_tx: Math.round(this.cycle.tx),
      cycle_start: this.cycle.start,
      per_if: perIf,
    };
  }

  private rollover(nowMs: number, cycleStartDay: number): void {
    const key = localDateKey(nowMs);
    if (key !== this.today.key) this.today = { rx: 0, tx: 0, key };
    const start = cycleStartDate(nowMs, cycleStartDay);
    if (start !== this.cycle.start) this.cycle = { rx: 0, tx: 0, start };
  }
}
