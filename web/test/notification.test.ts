import { describe, expect, it } from 'vitest';
import { notificationFor } from '../src/lib/notification';

describe('push notification options (service worker)', () => {
  it('re-alerts when a new alert replaces one with the same tag (F26)', () => {
    const n = notificationFor({ title: 'Phone offline', body: 'No contact for 5 min', tag: 'd_1:offline', kind: 'offline', severity: 'critical' });
    expect(n.title).toBe('Phone offline');
    expect(n.options.tag).toBe('d_1:offline');
    expect(n.options.renotify).toBe(true);
    expect(n.options.requireInteraction).toBe(true);
    expect(n.options.data).toEqual({ url: '/' });
  });
  it('derives a tag from the kind and still re-alerts', () => {
    const n = notificationFor({ alert: { kind: 'low_battery', title: 'Low battery', message: '14 %', severity: 'warning' } });
    expect(n.options.tag).toBe('hsm-low_battery');
    expect(n.options.renotify).toBe(true);
    expect(n.options.requireInteraction).toBe(false);
    expect(n.options.data).toEqual({ url: '/power' });
  });
  it('sets no renotify without a tag (browsers reject renotify with an empty tag)', () => {
    const n = notificationFor({ body: 'hello' });
    expect(n.title).toBe('Hotspot Monitor');
    expect(n.options.tag).toBeUndefined();
    expect('renotify' in n.options).toBe(false);
    expect(n.options.data).toEqual({ url: '/alerts' });
  });
  it('prefers a specific screen from the Server', () => {
    expect(notificationFor({ kind: 'watchdog', url: '/system?tab=audit' }).options.data).toEqual({ url: '/system?tab=audit' });
    expect(notificationFor({ kind: 'watchdog', url: '/' }).options.data).toEqual({ url: '/system?tab=events' });
  });
});
