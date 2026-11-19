/**
 * Turns a Web Push payload from the Server into showNotification() arguments. Pure, so the
 * service worker (sw.ts) and the tests share it.
 */

export interface PushPayload {
  title?: string;
  body?: string;
  message?: string;
  url?: string;
  tag?: string;
  kind?: string;
  severity?: string;
  alert?: { id?: number | string; kind?: string; title?: string; message?: string; severity?: string; device_id?: string | null };
}

/** `renotify` is in the Notifications spec but missing from TypeScript's DOM types. */
export type NotificationOptionsWithRenotify = NotificationOptions & { renotify?: boolean };

/** Which screen answers each alert kind (settings.ts AlertKind). */
export const KIND_SCREEN: Record<string, string> = {
  offline: '/',
  back_online: '/',
  weak_power: '/power',
  low_battery: '/power',
  hot: '/power',
  usb_power_lost: '/power',
  signal_drop: '/signal',
  cell_changed: '/signal',
  tether_down: '/network',
  data_quota: '/network',
  moved: '/system',
  watchdog: '/system?tab=events',
  carrier_sms: '/messages',
  server_test: '/alerts',
};

export function notificationFor(p: PushPayload): { title: string; options: NotificationOptionsWithRenotify } {
  const kind = p.kind ?? p.alert?.kind;
  const severity = p.severity ?? p.alert?.severity;
  const title = p.title ?? p.alert?.title ?? 'Hotspot Monitor';
  const body = p.body ?? p.message ?? p.alert?.message ?? '';
  // A specific path from the Server wins; "/" (or none) means "the screen for this alert kind".
  const specific = p.url && p.url.startsWith('/') && p.url !== '/' ? p.url : null;
  const url = specific ?? ((kind && KIND_SCREEN[kind]) || '/alerts');
  const tag = p.tag || (kind ? `hsm-${kind}` : undefined);
  const options: NotificationOptionsWithRenotify = {
    body,
    tag,
    icon: '/icons/icon-192.png',
    badge: '/icons/icon-192.png',
    requireInteraction: severity === 'critical',
    data: { url },
  };
  // A new alert with the same tag replaces the old notification; without renotify it would do so
  // silently (no sound or vibration), so a repeated "Phone offline" would not wake anyone.
  // Browsers reject renotify without a tag, so it is only set with one.
  if (tag) options.renotify = true;
  return { title, options };
}
