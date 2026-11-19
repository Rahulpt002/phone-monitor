import { randomBytes, scrypt as scryptCb, timingSafeEqual, type ScryptOptions } from 'node:crypto';

const scrypt = (password: string, salt: Buffer, keylen: number, opts: ScryptOptions) =>
  new Promise<Buffer>((resolve, reject) =>
    scryptCb(password, salt, keylen, opts, (err, key) => (err ? reject(err) : resolve(key))),
  );

// OWASP's equivalent of N=2^17/p=1 that needs a quarter of the memory (Render's free instance has
// 512 MB): N=2^15, r=8, p=3. Hashes stored with lower parameters are upgraded on the next
// successful sign-in (needsRehash).
const N = 32768;
const R = 8;
const P = 3;
const KEYLEN = 64;
// 128 * N * r = 32 MiB per hash (the p lanes run one after another), just over Node's 32 MiB
// default limit; give headroom.
const MAXMEM = 64 * 1024 * 1024;

/** Format: scrypt$N$r$p$<salt base64>$<hash base64> */
export async function hashPassword(password: string): Promise<string> {
  const salt = randomBytes(16);
  const key = await scrypt(password.normalize('NFKC'), salt, KEYLEN, { N, r: R, p: P, maxmem: MAXMEM });
  return `scrypt$${N}$${R}$${P}$${salt.toString('base64')}$${key.toString('base64')}`;
}

export async function verifyPassword(password: string, stored: string): Promise<boolean> {
  const parts = stored.split('$');
  if (parts.length !== 6 || parts[0] !== 'scrypt') return false;
  const n = Number(parts[1]);
  const r = Number(parts[2]);
  const p = Number(parts[3]);
  if (![n, r, p].every((x) => Number.isInteger(x) && x > 0)) return false;
  const salt = Buffer.from(parts[4] ?? '', 'base64');
  const expected = Buffer.from(parts[5] ?? '', 'base64');
  if (salt.length === 0 || expected.length === 0) return false;
  const key = await scrypt(password.normalize('NFKC'), salt, expected.length, { N: n, r, p, maxmem: MAXMEM });
  return key.length === expected.length && timingSafeEqual(key, expected);
}

/** True when `stored` was hashed with weaker parameters than hashPassword uses now. */
export function needsRehash(stored: string): boolean {
  const parts = stored.split('$');
  if (parts.length !== 6 || parts[0] !== 'scrypt') return true;
  const [n, r, p] = [Number(parts[1]), Number(parts[2]), Number(parts[3])];
  return !(n >= N && r >= R && p >= P);
}

let dummyHash: Promise<string> | null = null;
/**
 * Spends the same scrypt time when the username does not exist, so response timing does not
 * reveal which usernames are valid.
 */
export async function burnPasswordCheck(password: string): Promise<false> {
  dummyHash ??= hashPassword('dummy-password-for-timing');
  await verifyPassword(password, await dummyHash);
  return false;
}
