import type { AppContext } from './context.js';
import { PAIR_CODE_TTL_MS } from './services/devices.js';

const TICK_MS = 5_000;

interface Job {
  name: string;
  everyMs: number;
  run: () => Promise<unknown>;
  lastRun: number;
}

/**
 * Background work on one timer:
 * - every 5 s: command expiry, pending "back online" alerts, remote-screen time limits
 * - every 15 s: offline detection and time-based alert conditions
 * - every 30 s: persist device state (last_seen, merged state, alert timers), ping sockets
 * - every 60 s: 1-minute rollups, close Dashboard sockets whose session expired
 * - hourly: retention and housekeeping
 */
export class Jobs {
  private timer: NodeJS.Timeout | null = null;
  private running = false;
  private jobs: Job[];

  constructor(private ctx: AppContext) {
    this.jobs = [
      { name: 'commands.expire', everyMs: 5_000, run: () => ctx.commands.expireDue(), lastRun: 0 },
      { name: 'alerts.backOnline', everyMs: 5_000, run: () => ctx.alerts.processPendingBackOnline(), lastRun: 0 },
      { name: 'screen.tick', everyMs: 5_000, run: () => ctx.screen.tick(), lastRun: 0 },
      { name: 'alerts.tick', everyMs: 15_000, run: () => ctx.alerts.tick(), lastRun: 0 },
      { name: 'devices.flush', everyMs: 30_000, run: () => ctx.devices.flush(), lastRun: 0 },
      { name: 'sockets.ping', everyMs: 30_000, run: async () => ctx.hub.pingAll(), lastRun: 0 },
      { name: 'telemetry.rollup', everyMs: 60_000, run: () => ctx.telemetry.rollupDirty(), lastRun: 0 },
      { name: 'dashboards.sessions', everyMs: 60_000, run: async () => this.dropExpiredDashboards(), lastRun: 0 },
      { name: 'retention', everyMs: 3_600_000, run: () => this.retention(), lastRun: 0 },
    ];
  }

  start(): void {
    if (this.timer) return;
    const now = this.ctx.clock.now();
    // Retention first runs an hour after start, not during a cold start.
    for (const j of this.jobs) j.lastRun = j.name === 'retention' ? now : 0;
    this.timer = setInterval(() => void this.tick(), TICK_MS);
    this.timer.unref();
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }

  async tick(): Promise<void> {
    if (this.running) return;
    this.running = true;
    try {
      for (const j of this.jobs) {
        const now = this.ctx.clock.now();
        if (now - j.lastRun < j.everyMs) continue;
        j.lastRun = now;
        try {
          await j.run();
        } catch (err) {
          this.ctx.log.error({ err, job: j.name }, 'background job failed');
        }
      }
    } finally {
      this.running = false;
    }
  }

  private dropExpiredDashboards(): void {
    this.ctx.hub.closeDashboards((c) => !!c.sessionHash && !this.ctx.auth.sessionAlive(c.sessionHash), 'session expired');
  }

  async retention(): Promise<void> {
    const now = this.ctx.clock.now();
    await this.ctx.telemetry.retention(now);
    await this.ctx.records.retention(now);
    await this.ctx.commands.retention(now);
    await this.ctx.alerts.retention(now);
    await this.ctx.auth.cleanup();
    await this.ctx.db.run('DELETE FROM pair_codes WHERE expires_at < ?', [now - PAIR_CODE_TTL_MS - 86_400_000]);
  }
}
