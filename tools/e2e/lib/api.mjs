/** Minimal REST client for the Server (`/api`, docs/protocol.md 1). */
export class Api {
  constructor(base) {
    this.base = base.replace(/\/+$/, '');
  }

  async req(method, path, { token, body, headers = {} } = {}) {
    const res = await fetch(`${this.base}${path}`, {
      method,
      headers: {
        accept: 'application/json',
        ...(token ? { authorization: `Bearer ${token}` } : {}),
        ...(body !== undefined ? { 'content-type': 'application/json' } : {}),
        ...headers,
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout(20_000),
    });
    const text = await res.text();
    let json = null;
    try {
      json = text ? JSON.parse(text) : null;
    } catch {
      json = text;
    }
    return { status: res.status, body: json, headers: res.headers, text };
  }

  get(path, opts) {
    return this.req('GET', path, opts);
  }
  post(path, body, opts = {}) {
    return this.req('POST', path, { ...opts, body });
  }
  put(path, body, opts = {}) {
    return this.req('PUT', path, { ...opts, body });
  }
  patch(path, body, opts = {}) {
    return this.req('PATCH', path, { ...opts, body });
  }
  /** DELETE, optionally with a JSON body (DELETE /api/users/:id carries reauth_password). */
  del(path, body, opts = {}) {
    return this.req('DELETE', path, { ...opts, body });
  }
}

/** Throws with the response body when the status is not the expected one. */
export function expectStatus(res, status, what) {
  const ok = Array.isArray(status) ? status.includes(res.status) : res.status === status;
  if (!ok) {
    throw new Error(`${what}: expected HTTP ${status}, got ${res.status} ${typeof res.body === 'string' ? res.body.slice(0, 300) : JSON.stringify(res.body)?.slice(0, 300)}`);
  }
  return res.body;
}
