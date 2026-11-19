import agentToServer from '@hsm/shared/fixtures/agent-to-server.json';
import { COMMAND_NAMES, DEFAULT_SETTINGS, type CapabilityMap, type Settings } from '@hsm/shared';
import { describe, expect, it } from 'vitest';
import { controlState, isSupported, visibleControls } from '../src/lib/controls';

const caps = (agentToServer as { t: string; tier?: string; capabilities?: CapabilityMap }[]).find((m) => m.tier === 'static')!.capabilities!;
const allowPowerOff: Settings = { ...DEFAULT_SETTINGS, commands: { ...DEFAULT_SETTINGS.commands, allow_power_off: true } };

describe('capability-based visibility', () => {
  it('hides commands the phone reported as unsupported', () => {
    expect(caps.network_mode?.supported).toBe(false);
    expect(controlState('network_mode', { role: 'admin', capabilities: caps, settings: DEFAULT_SETTINGS }).visible).toBe(false);
  });
  it('shows supported commands', () => {
    expect(controlState('mobile_data', { role: 'admin', capabilities: caps, settings: DEFAULT_SETTINGS })).toEqual({ visible: true, disabled: false });
  });
  it('shows commands missing from the map (unknown = shown)', () => {
    expect(caps.wifi).toBeUndefined();
    expect(isSupported('wifi', caps)).toBe(true);
    expect(controlState('wifi', { role: 'admin', capabilities: caps, settings: DEFAULT_SETTINGS }).visible).toBe(true);
  });
  it('shows everything before the first capability probe', () => {
    const groups = visibleControls({ role: 'admin', capabilities: undefined, settings: allowPowerOff });
    const shown = Object.values(groups).flat();
    expect(shown.sort()).toEqual([...COMMAND_NAMES].sort());
  });
  it('groups visible controls without the unsupported ones', () => {
    const groups = visibleControls({ role: 'admin', capabilities: caps, settings: DEFAULT_SETTINGS });
    expect(groups.radio).toContain('mobile_data');
    expect(groups.radio).not.toContain('network_mode');
    expect(groups.finder).toContain('speed_test');
  });
});

describe('roles and policy', () => {
  it('viewers see no controls at all', () => {
    const groups = visibleControls({ role: 'viewer', capabilities: caps, settings: DEFAULT_SETTINGS });
    expect(Object.values(groups).flat()).toEqual([]);
    expect(controlState('refresh', { role: 'viewer', capabilities: caps, settings: DEFAULT_SETTINGS }).visible).toBe(false);
    expect(controlState('refresh', { role: null, capabilities: caps, settings: DEFAULT_SETTINGS }).visible).toBe(false);
  });
  it('power off is shown disabled with a reason unless allowed in settings', () => {
    const blocked = controlState('power_off', { role: 'admin', capabilities: caps, settings: DEFAULT_SETTINGS });
    expect(blocked.visible).toBe(true);
    expect(blocked.disabled).toBe(true);
    expect(blocked.reason).toMatch(/Allow remote power off/);
    expect(controlState('power_off', { role: 'admin', capabilities: caps, settings: allowPowerOff })).toEqual({ visible: true, disabled: false });
  });
  it('an unsupported power off stays hidden even when allowed', () => {
    const noPower: CapabilityMap = { power_off: { supported: false } };
    expect(controlState('power_off', { role: 'admin', capabilities: noPower, settings: allowPowerOff }).visible).toBe(false);
  });
  it('admins without TOTP see controls but cannot use them', () => {
    const s = controlState('reboot', { role: 'admin', capabilities: caps, settings: DEFAULT_SETTINGS, totpEnrollmentRequired: true });
    expect(s.visible).toBe(true);
    expect(s.disabled).toBe(true);
    expect(s.reason).toMatch(/two-factor/);
  });
});
