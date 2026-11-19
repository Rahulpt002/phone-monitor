/**
 * Time for the simulated phone.
 *
 * - `now()` is the phone's wall clock (real time plus an optional --clock-skew). Every timestamp
 *   the simulator sends uses it, so the Server sees ordinary real-time telemetry.
 * - `speed` (--speed) fast-forwards the simulated world: battery and heat physics, scenario
 *   timelines, the watchdog ladder, Finder tests and reboot downtime. Protocol timings (heartbeat,
 *   telemetry intervals, command expiry, auto-revert deadlines) always run in real time because the
 *   Server keeps its own clocks for them.
 */
export class SimClock {
  readonly speed: number;
  private readonly skewMs: number;

  constructor(speed = 1, skewS = 0) {
    if (!(speed > 0) || !Number.isFinite(speed)) throw new Error(`speed must be a positive number, got ${speed}`);
    this.speed = speed;
    this.skewMs = Math.round(skewS * 1000);
  }

  now(): number {
    return Date.now() + this.skewMs;
  }

  iso(ms: number = this.now()): string {
    return new Date(ms).toISOString();
  }

  /** Real milliseconds that correspond to a duration of simulated seconds. */
  realMs(simSeconds: number): number {
    return Math.max(1, Math.round((simSeconds * 1000) / this.speed));
  }
}
