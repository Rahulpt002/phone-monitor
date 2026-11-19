/**
 * Minimal DOM test helpers on react-dom/client and React's act() (no extra test libraries).
 * Not a test file itself: vitest only runs test/**\/*.test.{ts,tsx}.
 */
import { act, type ReactElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { configureApi } from '../src/lib/api';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

export interface Rendered {
  container: HTMLElement;
  unmount: () => void;
}

export async function render(ui: ReactElement): Promise<Rendered> {
  const container = document.createElement('div');
  document.body.appendChild(container);
  let root: Root | null = null;
  await act(async () => {
    root = createRoot(container);
    root.render(ui);
  });
  return {
    container,
    unmount: () => {
      act(() => root?.unmount());
      container.remove();
    },
  };
}

/** Lets pending fetches, effects and state updates settle. */
export async function flush(times = 3): Promise<void> {
  for (let i = 0; i < times; i++) {
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0));
    });
  }
}

export async function click(el: Element | null | undefined): Promise<void> {
  if (!el) throw new Error('click: element not found');
  await act(async () => {
    (el as HTMLElement).click();
  });
  await flush();
}

/** Types into a React-controlled input or textarea (sets the native value, then fires input). */
export async function typeInto(el: Element | null | undefined, value: string): Promise<void> {
  if (!(el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement)) throw new Error('typeInto: not an input');
  const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
  await act(async () => {
    Object.getOwnPropertyDescriptor(proto, 'value')!.set!.call(el, value);
    el.dispatchEvent(new Event('input', { bubbles: true }));
  });
}

/** First element matching `selector` whose text content matches. */
export function byText<T extends Element = HTMLElement>(root: ParentNode, selector: string, text: string | RegExp): T | undefined {
  return [...root.querySelectorAll<T>(selector)].find((el) => {
    const t = el.textContent?.trim() ?? '';
    return typeof text === 'string' ? t === text : text.test(t);
  });
}

export interface FetchCall {
  method: string;
  path: string;
  body: unknown;
}

type MockResponse = { status: number; body?: unknown } | undefined;
type Route = (call: FetchCall) => MockResponse | Promise<MockResponse>;

/** Routes API requests to `route` (by method and path under /api) and records them. */
export function mockApi(route: Route): FetchCall[] {
  const calls: FetchCall[] = [];
  configureApi({
    base: '',
    timeoutMs: 5000,
    fetchImpl: (async (url: string, init: RequestInit) => {
      const u = new URL(url, 'http://dash.test');
      const call: FetchCall = {
        method: init.method ?? 'GET',
        path: u.pathname.replace(/^\/api/, '') + u.search,
        body: typeof init.body === 'string' ? JSON.parse(init.body) : undefined,
      };
      calls.push(call);
      const res = (await route(call)) ?? { status: 404, body: { error: 'not_found', message: `No mock for ${call.method} ${call.path}` } };
      return new Response(res.body === undefined ? null : JSON.stringify(res.body), {
        status: res.status,
        headers: { 'Content-Type': 'application/json' },
      });
    }) as typeof fetch,
  });
  return calls;
}
