/** Web Push subscription (plan 8.5 "browser push"). The service worker shows the notifications. */
import { api } from './api';

export type PushStatus = 'unsupported' | 'needs_install' | 'denied' | 'off' | 'on';

export function pushSupported(): boolean {
  return (
    typeof window !== 'undefined' &&
    'serviceWorker' in navigator &&
    'PushManager' in window &&
    'Notification' in window
  );
}

/** iPhone and iPad only deliver web push to an app added to the home screen. */
export function needsInstallForPush(): boolean {
  const ua = navigator.userAgent;
  const ios = /iPad|iPhone|iPod/.test(ua) || (ua.includes('Macintosh') && navigator.maxTouchPoints > 1);
  const standalone =
    window.matchMedia?.('(display-mode: standalone)').matches || (navigator as { standalone?: boolean }).standalone === true;
  return ios && !standalone;
}

async function registration(timeoutMs = 6000): Promise<ServiceWorkerRegistration> {
  const reg = await Promise.race([
    navigator.serviceWorker.ready,
    new Promise<never>((_, reject) =>
      setTimeout(
        () => reject(new Error('The service worker is not active. Push needs the built app (npm run build, then preview or deploy).')),
        timeoutMs,
      ),
    ),
  ]);
  return reg;
}

export function urlBase64ToUint8Array(base64: string): Uint8Array<ArrayBuffer> {
  const padding = '='.repeat((4 - (base64.length % 4)) % 4);
  const b64 = (base64 + padding).replace(/-/g, '+').replace(/_/g, '/');
  const raw = atob(b64);
  const out = new Uint8Array(new ArrayBuffer(raw.length));
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
  return out;
}

function sameKey(a: ArrayBuffer | null | undefined, b: Uint8Array): boolean {
  if (!a) return false;
  const x = new Uint8Array(a);
  return x.length === b.length && x.every((v, i) => v === b[i]);
}

export async function pushStatus(): Promise<PushStatus> {
  if (!pushSupported()) return needsInstallForPushSafe() ? 'needs_install' : 'unsupported';
  if (Notification.permission === 'denied') return 'denied';
  try {
    const reg = await registration(3000);
    const sub = await reg.pushManager.getSubscription();
    return sub && Notification.permission === 'granted' ? 'on' : 'off';
  } catch {
    return 'off';
  }
}

function needsInstallForPushSafe(): boolean {
  try {
    return needsInstallForPush();
  } catch {
    return false;
  }
}

export async function enablePush(): Promise<void> {
  if (!pushSupported()) throw new Error('This browser does not support push notifications.');
  const permission = await Notification.requestPermission();
  if (permission !== 'granted') throw new Error('Notifications are blocked. Allow them in the browser settings for this site.');
  const reg = await registration();
  const { public_key } = await api.vapid();
  const key = urlBase64ToUint8Array(public_key);
  let sub = await reg.pushManager.getSubscription();
  if (sub && !sameKey(sub.options.applicationServerKey, key)) {
    await sub.unsubscribe().catch(() => undefined);
    sub = null;
  }
  sub ??= await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: key });
  await api.pushSubscribe(sub.toJSON());
}

export async function disablePush(): Promise<void> {
  if (!pushSupported()) return;
  const reg = await registration();
  const sub = await reg.pushManager.getSubscription();
  if (!sub) return;
  await api.pushUnsubscribe(sub.endpoint).catch(() => undefined);
  await sub.unsubscribe();
}

/**
 * Re-registers an existing subscription after login (the Server may have lost it, or the VAPID key
 * changed after a redeploy). Silent: never prompts.
 */
export async function syncPushSubscription(): Promise<void> {
  if (!pushSupported() || Notification.permission !== 'granted') return;
  try {
    const reg = await registration(5000);
    const sub = await reg.pushManager.getSubscription();
    if (!sub) return;
    const { public_key } = await api.vapid();
    const key = urlBase64ToUint8Array(public_key);
    if (sameKey(sub.options.applicationServerKey, key)) {
      await api.pushSubscribe(sub.toJSON());
    } else {
      await sub.unsubscribe().catch(() => undefined);
      const fresh = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: key });
      await api.pushSubscribe(fresh.toJSON());
    }
  } catch {
    /* best effort */
  }
}
