/** Reconnect delay: 1 s, 2 s, 4 s ... capped, with +-20 % jitter so many tabs do not reconnect in lockstep. */
export function backoffDelay(attempt: number, baseMs = 1000, maxMs = 30_000, random: () => number = Math.random): number {
  const exp = Math.min(maxMs, baseMs * 2 ** Math.max(0, attempt - 1));
  const jitter = 1 + (random() * 0.4 - 0.2);
  return Math.round(Math.min(maxMs, exp * jitter));
}
