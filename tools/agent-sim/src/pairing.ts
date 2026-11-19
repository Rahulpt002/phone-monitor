import type { ApiError, DeviceInfo, PairRequest, PairResponse } from '@hsm/shared';
import { agentWsUrl, isPlainObject, normalizeBaseUrl } from './util.js';

export class PairError extends Error {
  constructor(
    message: string,
    readonly status: number | null,
    readonly code: string | null,
  ) {
    super(message);
  }
}

export interface PairOptions {
  /** A sleeping free-tier server can take close to a minute to wake up. */
  timeoutMs?: number;
  fetchImpl?: typeof fetch;
}

/** POST <server>/api/pair {code, device} -> {device_id, device_token, ws_url, server_url} (docs/protocol.md 2). */
export async function pair(server: string, code: string, device: DeviceInfo, opts: PairOptions = {}): Promise<PairResponse> {
  const base = normalizeBaseUrl(server);
  const url = `${base}/api/pair`;
  const body: PairRequest = { code: code.trim().toUpperCase(), device };
  const doFetch = opts.fetchImpl ?? fetch;
  let res: Response;
  try {
    res = await doFetch(url, {
      method: 'POST',
      headers: { 'content-type': 'application/json', accept: 'application/json', 'user-agent': 'hsm-agent-sim' },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(opts.timeoutMs ?? 90_000),
    });
  } catch (err) {
    const cause = err instanceof Error ? ((err.cause as Error | undefined)?.message ?? err.message) : String(err);
    throw new PairError(`cannot reach ${url}: ${cause}`, null, null);
  }
  const text = await res.text();
  let json: unknown = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    // Not JSON: reported below with the raw text.
  }
  if (!res.ok) {
    const apiErr = isPlainObject(json) ? (json as Partial<ApiError>) : {};
    const message = apiErr.message ?? (text.slice(0, 200) || res.statusText);
    throw new PairError(`pairing failed (HTTP ${res.status}${apiErr.error ? ` ${apiErr.error}` : ''}): ${message}`, res.status, apiErr.error ?? null);
  }
  if (!isPlainObject(json) || typeof json.device_id !== 'string' || typeof json.device_token !== 'string') {
    throw new PairError(`unexpected pairing response from ${url}: ${text.slice(0, 200)}`, res.status, null);
  }
  return {
    device_id: json.device_id,
    device_token: json.device_token,
    ws_url: typeof json.ws_url === 'string' && json.ws_url ? json.ws_url : agentWsUrl(base),
    server_url: typeof json.server_url === 'string' && json.server_url ? normalizeBaseUrl(json.server_url) : base,
  };
}
