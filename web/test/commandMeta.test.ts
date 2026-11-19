import { COMMAND_NAMES, COMMANDS } from '@hsm/shared';
import { describe, expect, it } from 'vitest';
import { SHELL_TIMEOUT_MAX_S, describeCommand, hotspotPasswordError, parseShellTimeout, withoutSecretArgs } from '../src/lib/commandMeta';

describe('secret command arguments', () => {
  it('never shows the hotspot password in a command description (lists, toasts, dialogs)', () => {
    const args = { enabled: true, ssid: 'Roof', password: 'hunter2hunter2' };
    expect(describeCommand('hotspot', args)).toBe('Wi-Fi hotspot on as "Roof"');
    for (const name of COMMAND_NAMES) {
      for (const key of COMMANDS[name].secret_args ?? []) {
        expect(describeCommand(name, { [key]: 'hunter2hunter2', cmd: 'x', code: '*1#' })).not.toContain('hunter2');
      }
    }
  });
  it('strips the keys a command declares secret', () => {
    expect(withoutSecretArgs('hotspot', { enabled: true, ssid: 'Roof', password: 'p' })).toEqual({ enabled: true, ssid: 'Roof' });
    const plain = { enabled: false };
    expect(withoutSecretArgs('mobile_data', plain)).toBe(plain);
  });
});

describe('argument rules shared with the Agent (F16)', () => {
  it('accepts hotspot passwords of 8 to 63 printable ASCII characters only', () => {
    expect(hotspotPasswordError('12345678')).toBeNull();
    expect(hotspotPasswordError('a'.repeat(63))).toBeNull();
    expect(hotspotPasswordError('with space ~!')).toBeNull();
    expect(hotspotPasswordError('1234567')).toMatch(/at least 8/i);
    expect(hotspotPasswordError('a'.repeat(64))).toMatch(/at most 63/i);
    expect(hotspotPasswordError('Straße-1234')).toMatch(/printable ASCII/);
    expect(hotspotPasswordError('pass\tword1')).toMatch(/printable ASCII/);
    expect(hotspotPasswordError('😀😀😀😀😀')).toMatch(/printable ASCII/);
  });
  it('takes a root-shell timeout of 1 to 600 s', () => {
    expect(SHELL_TIMEOUT_MAX_S).toBe(600);
    expect(parseShellTimeout('30')).toBe(30);
    expect(parseShellTimeout('600')).toBe(600);
    expect(parseShellTimeout('601')).toBeNull();
    expect(parseShellTimeout('0')).toBeNull();
    expect(parseShellTimeout('2.5')).toBeNull();
    expect(parseShellTimeout('')).toBeNull();
  });
});
