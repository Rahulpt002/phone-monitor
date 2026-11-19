import { describe, expect, it } from 'vitest';
import { configWarnings, ConfigError, loadConfig, trustProxyOption } from '../src/config.js';

describe('loadConfig', () => {
  it('has local-development defaults', () => {
    const c = loadConfig({});
    expect(c).toMatchObject({
      port: 8080,
      host: '0.0.0.0',
      publicUrl: null,
      sessionTtlDays: 30,
      corsOrigins: [],
      logLevel: 'info',
      trustProxy: false,
      adminUsername: null,
      adminPassword: null,
    });
    expect(c.databaseUrl).toMatch(/^file:.*server[/\\]data[/\\]hsm\.db$/);
    expect(c.webDist).toMatch(/web[/\\]dist$/);
  });

  it('parses values and treats blanks as unset', () => {
    const c = loadConfig({
      PORT: '10000',
      PUBLIC_URL: 'https://hsm.onrender.com/',
      DATABASE_URL: 'libsql://hsm-me.turso.io',
      DATABASE_AUTH_TOKEN: 'tok',
      CORS_ORIGINS: 'https://hsm.vercel.app, https://other.example/',
      TRUST_PROXY: 'false',
      SESSION_TTL_DAYS: '7',
      TELEGRAM_BOT_TOKEN: '',
      LOG_LEVEL: 'warn',
    });
    expect(c).toMatchObject({
      port: 10000,
      publicUrl: 'https://hsm.onrender.com',
      databaseUrl: 'libsql://hsm-me.turso.io',
      databaseAuthToken: 'tok',
      corsOrigins: ['https://hsm.vercel.app', 'https://other.example'],
      trustProxy: false,
      sessionTtlDays: 7,
      telegramBotToken: null,
      logLevel: 'warn',
    });
    expect(loadConfig({ TRUST_PROXY: 'loopback,uniquelocal' }).trustProxy).toBe('loopback,uniquelocal');
  });

  it('reads TRUST_PROXY as a hop count, a boolean or a list', () => {
    expect(loadConfig({ TRUST_PROXY: '1' }).trustProxy).toBe(1);
    expect(loadConfig({ TRUST_PROXY: ' 2 ' }).trustProxy).toBe(2);
    expect(loadConfig({ TRUST_PROXY: '0' }).trustProxy).toBe(false);
    expect(loadConfig({ TRUST_PROXY: 'TRUE' }).trustProxy).toBe(true);
    expect(loadConfig({ TRUST_PROXY: 'off' }).trustProxy).toBe(false);
    expect(loadConfig({ TRUST_PROXY: '10.0.0.0/8, 127.0.0.1' }).trustProxy).toBe('10.0.0.0/8, 127.0.0.1');
    expect(() => loadConfig({ TRUST_PROXY: '500' })).toThrow(ConfigError);
  });

  it('defaults TRUST_PROXY to false, or to one hop on Render, never to true (F20)', () => {
    expect(loadConfig({}).trustProxy).toBe(false);
    expect(loadConfig({ TRUST_PROXY: '' }).trustProxy).toBe(false);
    expect(loadConfig({ RENDER: 'true' }).trustProxy).toBe(1);
    expect(loadConfig({ RENDER_EXTERNAL_URL: 'https://hsm.onrender.com' }).trustProxy).toBe(1);
    expect(loadConfig({ RENDER: 'true', TRUST_PROXY: '2' }).trustProxy).toBe(2);
    expect(loadConfig({ RENDER: 'true', TRUST_PROXY: 'false' }).trustProxy).toBe(false);
  });

  it('warns about TRUST_PROXY=true only', () => {
    expect(configWarnings(loadConfig({ TRUST_PROXY: 'true' }))).toEqual([expect.stringContaining('TRUST_PROXY=true')]);
    expect(configWarnings(loadConfig({}))).toEqual([]);
    expect(configWarnings(loadConfig({ TRUST_PROXY: '1' }))).toEqual([]);
  });

  it('turns a hop count into a trust function (Fastify 5 ignores numbers)', () => {
    const fn = trustProxyOption(1);
    expect(typeof fn).toBe('function');
    const trust = fn as (addr: string, hop: number) => boolean;
    expect([trust('10.0.0.1', 0), trust('2.2.2.2', 1)]).toEqual([true, false]);
    expect(trustProxyOption(true)).toBe(true);
    expect(trustProxyOption('loopback')).toBe('loopback');
  });

  it('falls back to RENDER_EXTERNAL_URL for the public URL', () => {
    expect(loadConfig({ RENDER_EXTERNAL_URL: 'https://hsm.onrender.com' }).publicUrl).toBe('https://hsm.onrender.com');
    expect(loadConfig({ RENDER_EXTERNAL_URL: 'https://a.onrender.com', PUBLIC_URL: 'https://hsm.example.com' }).publicUrl).toBe(
      'https://hsm.example.com',
    );
  });

  it('reports every invalid variable', () => {
    expect(() => loadConfig({ PORT: 'eighty', PUBLIC_URL: 'hsm.example.com', VAPID_SUBJECT: 'me@example.com' })).toThrow(ConfigError);
    try {
      loadConfig({ PORT: 'eighty', PUBLIC_URL: 'hsm.example.com' });
    } catch (e) {
      expect((e as Error).message).toContain('PORT');
      expect((e as Error).message).toContain('PUBLIC_URL');
    }
    expect(() => loadConfig({ CORS_ORIGINS: 'not a url' })).toThrow(ConfigError);
  });
});
