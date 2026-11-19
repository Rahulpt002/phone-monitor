/**
 * Small seeded PRNG (mulberry32) so a run can be reproduced with --seed and tests are
 * deterministic. Ids that must be globally unique use util.newId instead.
 */
export class Random {
  private state: number;

  constructor(seed?: number | null) {
    this.state = (seed ?? Math.floor(Math.random() * 2 ** 32)) >>> 0;
  }

  /** Uniform in [0, 1). */
  next(): number {
    this.state = (this.state + 0x6d2b79f5) >>> 0;
    let t = this.state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  }

  range(lo: number, hi: number): number {
    return lo + (hi - lo) * this.next();
  }

  /** Integer in [lo, hi], both inclusive. */
  int(lo: number, hi: number): number {
    return Math.floor(this.range(lo, hi + 1));
  }

  chance(p: number): boolean {
    return this.next() < p;
  }

  /** Normal distribution (Box-Muller). */
  gauss(mean = 0, sd = 1): number {
    let u = 0;
    while (u === 0) u = this.next();
    const v = this.next();
    return mean + sd * Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
  }

  pick<T>(items: readonly T[]): T {
    if (items.length === 0) throw new Error('pick() from an empty list');
    return items[Math.floor(this.next() * items.length)] as T;
  }

  hex(length: number): string {
    let out = '';
    for (let i = 0; i < length; i++) out += Math.floor(this.next() * 16).toString(16);
    return out;
  }
}

/**
 * Exact Ornstein-Uhlenbeck step: a mean-reverting random walk with stationary standard deviation
 * `sd` and time constant `tauS`. Exact for any step size, so a large --speed stays stable.
 */
export function ouStep(x: number, mean: number, sd: number, tauS: number, dtS: number, rng: Random): number {
  if (dtS <= 0) return x;
  const a = Math.exp(-dtS / Math.max(1e-6, tauS));
  return mean + (x - mean) * a + sd * Math.sqrt(1 - a * a) * rng.gauss();
}
