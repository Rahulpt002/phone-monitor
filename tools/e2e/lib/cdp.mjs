import { existsSync, readFileSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import WebSocket from 'ws';
import { sleep } from './util.mjs';

/** Where Chrome usually lives; CHROME_PATH (or --chrome) wins. */
export function findChrome(explicit) {
  const candidates = [
    explicit,
    process.env.CHROME_PATH,
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/Applications/Chromium.app/Contents/MacOS/Chromium',
    '/usr/bin/google-chrome',
    '/usr/bin/google-chrome-stable',
    '/usr/bin/chromium',
    '/usr/bin/chromium-browser',
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  ].filter(Boolean);
  return candidates.find((p) => existsSync(p)) ?? null;
}

/**
 * Starts headless Chrome with a throwaway profile and returns the DevTools browser WebSocket URL.
 * `--remote-debugging-port=0` picks a free port; Chrome writes it to DevToolsActivePort.
 */
export async function launchChrome(procs, chromePath, profileDir) {
  rmSync(profileDir, { recursive: true, force: true });
  const child = procs.spawn(
    'chrome',
    chromePath,
    [
      '--headless=new',
      '--remote-debugging-port=0',
      `--user-data-dir=${profileDir}`,
      '--no-first-run',
      '--no-default-browser-check',
      '--disable-extensions',
      '--disable-background-networking',
      '--disable-sync',
      '--disable-component-update',
      '--disable-features=Translate,MediaRouter,OptimizationHints',
      '--metrics-recording-only',
      '--password-store=basic',
      '--use-mock-keychain',
      '--window-size=1280,900',
      'about:blank',
    ],
    { quiet: true },
  );
  const portFile = join(profileDir, 'DevToolsActivePort');
  const deadline = Date.now() + 20_000;
  for (;;) {
    if (existsSync(portFile)) {
      const [port, path] = readFileSync(portFile, 'utf8').trim().split('\n');
      if (port && path) return { child, wsUrl: `ws://127.0.0.1:${port}${path}` };
    }
    if (child.exit) throw new Error(`Chrome exited during start (${JSON.stringify(child.exit)}); see ${child.logFile}`);
    if (Date.now() > deadline) throw new Error(`Chrome did not open its DevTools port; see ${child.logFile}`);
    await sleep(100);
  }
}

/** A tiny CDP client over the browser WebSocket with flat sessions (Target.attachToTarget flatten). */
export class Cdp {
  constructor() {
    this.ws = null;
    this.nextId = 1;
    this.pending = new Map();
    this.listeners = new Set();
  }

  async connect(wsUrl) {
    this.ws = new WebSocket(wsUrl, { perMessageDeflate: false, maxPayload: 256 * 1024 * 1024 });
    await new Promise((resolve, reject) => {
      this.ws.once('open', resolve);
      this.ws.once('error', reject);
    });
    this.ws.on('message', (data) => {
      const msg = JSON.parse(data.toString());
      if (msg.id !== undefined) {
        const p = this.pending.get(msg.id);
        if (!p) return;
        this.pending.delete(msg.id);
        if (msg.error) p.reject(new Error(`${p.method}: ${msg.error.message}`));
        else p.resolve(msg.result);
        return;
      }
      for (const fn of this.listeners) fn(msg);
    });
    this.ws.on('close', () => {
      for (const p of this.pending.values()) p.reject(new Error('DevTools connection closed'));
      this.pending.clear();
    });
  }

  send(method, params = {}, sessionId) {
    const id = this.nextId++;
    const payload = { id, method, params, ...(sessionId ? { sessionId } : {}) };
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject, method });
      this.ws.send(JSON.stringify(payload));
      setTimeout(() => {
        if (this.pending.has(id)) {
          this.pending.delete(id);
          reject(new Error(`${method}: no reply within 30 s`));
        }
      }, 30_000).unref();
    });
  }

  on(fn) {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  close() {
    try {
      this.ws?.close();
    } catch {
      // ignore
    }
  }
}

/** One browser tab with console, exception, CSP and network bookkeeping. */
export class Page {
  constructor(cdp, sessionId) {
    this.cdp = cdp;
    this.sessionId = sessionId;
    this.console = [];
    this.exceptions = [];
    this.logEntries = [];
    this.responses = [];
    this.failedRequests = [];
    this.wsCreated = [];
    cdp.on((msg) => {
      if (msg.sessionId !== sessionId) return;
      const p = msg.params ?? {};
      switch (msg.method) {
        case 'Runtime.consoleAPICalled':
          this.console.push({
            type: p.type,
            text: (p.args ?? []).map((a) => a.value ?? a.description ?? a.type).join(' '),
            url: p.stackTrace?.callFrames?.[0]?.url ?? null,
          });
          break;
        case 'Runtime.exceptionThrown':
          this.exceptions.push(p.exceptionDetails?.exception?.description ?? p.exceptionDetails?.text ?? 'exception');
          break;
        case 'Log.entryAdded':
          this.logEntries.push({ level: p.entry.level, source: p.entry.source, text: p.entry.text, url: p.entry.url ?? null });
          break;
        case 'Network.responseReceived':
          this.responses.push({ url: p.response.url, status: p.response.status, type: p.type, mime: p.response.mimeType, csp: p.response.headers?.['content-security-policy'] ?? null });
          break;
        case 'Network.loadingFailed':
          this.failedRequests.push({ errorText: p.errorText, blockedReason: p.blockedReason ?? null, canceled: p.canceled ?? false, type: p.type });
          break;
        case 'Network.webSocketCreated':
          this.wsCreated.push(p.url);
          break;
        default:
      }
    });
  }

  static async open(cdp) {
    const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank' });
    const { sessionId } = await cdp.send('Target.attachToTarget', { targetId, flatten: true });
    const page = new Page(cdp, sessionId);
    page.targetId = targetId;
    await page.send('Page.enable');
    await page.send('Runtime.enable');
    await page.send('Log.enable');
    await page.send('Network.enable');
    // CSP violations as the page sees them (the DevTools-injected script is not subject to the CSP).
    await page.send('Page.addScriptToEvaluateOnNewDocument', {
      source: `window.__e2eCsp = [];
        document.addEventListener('securitypolicyviolation', (e) => {
          window.__e2eCsp.push({ directive: e.violatedDirective, blocked: e.blockedURI, source: e.sourceFile, line: e.lineNumber, sample: e.sample });
        });`,
    });
    await page.send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });
    return page;
  }

  send(method, params = {}) {
    return this.cdp.send(method, params, this.sessionId);
  }

  async navigate(url) {
    await this.send('Page.navigate', { url });
  }

  /** Evaluates an expression (string) in the page; returns its JSON value. */
  async eval(expression) {
    const r = await this.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) throw new Error(`page eval failed: ${r.exceptionDetails.exception?.description ?? r.exceptionDetails.text}`);
    return r.result.value;
  }

  async waitFor(expression, { timeout = 15_000, label = expression } = {}) {
    const deadline = Date.now() + timeout;
    let lastErr = null;
    for (;;) {
      try {
        const v = await this.eval(expression);
        if (v) return v;
      } catch (err) {
        lastErr = err;
      }
      if (Date.now() > deadline) {
        throw new Error(`browser: timed out after ${timeout / 1000} s waiting for ${label}${lastErr ? ` (${lastErr.message})` : ''}`);
      }
      await sleep(150);
    }
  }

  /** Sets a React-controlled input through the native setter so React sees an input event. */
  async fill(selector, value) {
    const ok = await this.eval(`(() => {
      const el = document.querySelector(${JSON.stringify(selector)});
      if (!el) return false;
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
      el.focus();
      setter.call(el, ${JSON.stringify(value)});
      el.dispatchEvent(new Event('input', { bubbles: true }));
      return true;
    })()`);
    if (!ok) throw new Error(`browser: no element ${selector}`);
  }

  async click(selector) {
    const ok = await this.eval(`(() => {
      const els = [...document.querySelectorAll(${JSON.stringify(selector)})];
      const el = els.find((e) => e.offsetParent !== null) ?? els[0];
      if (!el) return false;
      el.click();
      return true;
    })()`);
    if (!ok) throw new Error(`browser: no element ${selector}`);
  }

  async clickText(selector, text) {
    const ok = await this.eval(`(() => {
      const els = [...document.querySelectorAll(${JSON.stringify(selector)})].filter((e) => e.textContent.trim() === ${JSON.stringify(text)});
      const el = els.find((e) => e.offsetParent !== null) ?? els[0];
      if (!el) return false;
      el.click();
      return true;
    })()`);
    if (!ok) throw new Error(`browser: no ${selector} with text "${text}"`);
  }

  text() {
    return this.eval('document.body ? document.body.innerText : ""');
  }

  async screenshot() {
    const m = await this.send('Page.getLayoutMetrics');
    const size = m.cssContentSize ?? m.contentSize;
    const width = Math.max(1, Math.ceil(size.width));
    const height = Math.max(1, Math.min(Math.ceil(size.height), 6000));
    const r = await this.send('Page.captureScreenshot', {
      format: 'png',
      captureBeyondViewport: true,
      clip: { x: 0, y: 0, width, height, scale: 1 },
    });
    return Buffer.from(r.data, 'base64');
  }

  cspViolations() {
    return this.eval('window.__e2eCsp || []');
  }
}
