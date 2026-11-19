/**
 * Time for the simulated phone, with the same split as the Kotlin Agent's core/Clock.kt:
 *
 * - `now()` is the phone's own wall clock (real time plus an optional --clock-skew). Use it only
 *   for local decisions and for instants the phone keeps for itself (boot time, reboot history,
 *   auto-revert deadlines, the hour of day).
 * - `serverNow()` / `serverIso()` are the best-known UTC time: phone clock plus the offset learned
 *   from `hello.server_time` (docs/protocol.md 3.1; 0 until the first hello). Every `ts` / `at` /
 *   `revert_deadline` the simulator sends uses them, and so does the command expiry check, so a
 *   phone with a wrong clock still produces timestamps the Server treats as live. The phone's clock
 *   itself never changes. `serverIso(phoneMs)` converts an instant kept on the phone clock.
 * - `speed` (--speed) fast-forwards the simulated world: battery and heat physics, scenario
 *   timelines, the watchdog ladder, Finder tests and reboot downtime. Protocol timings (heartbeat,
 *   telemetry intervals, command expiry, auto-revert deadlines) always run in real time because the
 *   Server keeps its own clocks for them.
 */
export class SimClock {
  readonly speed: number;
  private readonly skewMs: number;
  private offsetMs = 0;

  constructor(speed = 1, skewS = 0) {
    if (!(speed > 0) || !Number.isFinite(speed)) throw new Error(`speed must be a positive number, got ${speed}`);
    this.speed = speed;
    this.skewMs = Math.round(skewS * 1000);
  }

  /** The phone's wall clock (ms). Not for anything sent to the Server. */
  now(): number {
    return Date.now() + this.skewMs;
  }

  /** Server clock minus phone clock, from the last `hello` (0 before the first one). */
  get serverOffsetMs(): number {
    return this.offsetMs;
  }

  setServerOffset(ms: number): void {
    if (Number.isFinite(ms)) this.offsetMs = Math.round(ms);
  }

  /** Best-known UTC time (ms): phone clock plus the Server offset. */
  serverNow(): number {
    return this.now() + this.offsetMs;
  }

  /** ISO-8601 for the wire: now, or a phone-clock instant, on the offset-corrected clock. */
  serverIso(phoneMs?: number): string {
    return this.iso(phoneMs === undefined ? this.serverNow() : phoneMs + this.offsetMs);
  }

  /** Formats an epoch-ms value as it is; no clock conversion. */
  iso(ms: number): string {
    return new Date(ms).toISOString();
  }

  /** Real milliseconds that correspond to a duration of simulated seconds. */
  realMs(simSeconds: number): number {
    return Math.max(1, Math.round((simSeconds * 1000) / this.speed));
  }
}
