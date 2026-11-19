import { COMMAND_NAMES, type CapabilityMap, type CommandName } from '@hsm/shared';
import { isPlainObject } from './util.js';

/** Device profile (plan 6.1); same JSON schema as agent/app/src/main/assets/profiles/<id>.json. */
export interface DeviceProfile {
  id: string;
  name: string;
  match: { manufacturer?: string[]; model_regex?: string };
  charge_control: { path: string; on: string; off: string; note?: string }[];
  current_now: { unit: 'uA' | 'mA'; sign: 1 | -1 };
  usb_function: 'rndis' | 'ncm';
  autoboot: {
    method: 'fastboot_oem_off_mode_charge' | 'magisk_autoboot' | 'lpm_replace' | 'none' | 'unknown';
    note?: string;
  };
  thermal: { cpu: string[]; modem: string[]; skin: string[]; battery: string[] };
  commands?: Record<string, string[]>;
  network_mode?: Record<string, string>;
  notes?: string[];
}

/** The simulated phone's built-in profile: a Galaxy S21 (SM-G991B) as in the shared fixtures. */
export const SIM_PROFILE: DeviceProfile = {
  id: 'samsung_galaxy_s',
  name: 'Samsung Galaxy S10/S20/S21',
  match: { manufacturer: ['samsung'], model_regex: '^SM-G9[789]' },
  charge_control: [
    {
      path: '/sys/class/power_supply/battery/batt_slate_mode',
      on: '0',
      off: '1',
      note: 'Slate mode: the phone runs from USB while the battery rests.',
    },
  ],
  current_now: { unit: 'uA', sign: 1 },
  usb_function: 'rndis',
  autoboot: { method: 'magisk_autoboot', note: 'Simulated: the phone boots by itself when power returns.' },
  thermal: {
    cpu: ['cpu', 'tsens', 'soc'],
    modem: ['modem', 'mdm', 'pa_therm'],
    skin: ['skin', 'xo_therm', 'quiet_therm'],
    battery: ['battery'],
  },
  commands: {},
  notes: ['Built into the agent simulator (tools/agent-sim).'],
};

/** Deep merge: objects merge key by key, arrays and scalars replace. */
function deepMerge(base: unknown, patch: unknown): unknown {
  if (!isPlainObject(base) || !isPlainObject(patch)) return patch === undefined ? base : patch;
  const out: Record<string, unknown> = { ...base };
  for (const [k, v] of Object.entries(patch)) out[k] = k in base ? deepMerge(base[k], v) : v;
  return out;
}

/**
 * settings.profile_override deep-merged over the built-in profile. Fields with the wrong shape
 * fall back to the built-in value, so a bad override cannot break the simulator.
 */
export function mergeProfile(base: DeviceProfile, override: Record<string, unknown> | null | undefined): DeviceProfile {
  if (!override || !isPlainObject(override)) return base;
  const merged = deepMerge(base, override) as DeviceProfile;
  return {
    ...merged,
    id: typeof merged.id === 'string' && merged.id ? merged.id : base.id,
    name: typeof merged.name === 'string' ? merged.name : base.name,
    usb_function: merged.usb_function === 'ncm' || merged.usb_function === 'rndis' ? merged.usb_function : base.usb_function,
    charge_control: Array.isArray(merged.charge_control)
      ? merged.charge_control.filter((c) => isPlainObject(c) && typeof c.path === 'string')
      : base.charge_control,
    commands: isPlainObject(merged.commands) ? merged.commands : base.commands,
    network_mode: isPlainObject(merged.network_mode) ? merged.network_mode : undefined,
  };
}

const DEFAULT_METHOD: Record<CommandName, string> = {
  mobile_data: 'svc data',
  data_cycle: 'svc data',
  airplane_mode: 'cmd connectivity airplane-mode',
  airplane_cycle: 'cmd connectivity airplane-mode',
  usb_tethering: 'svc usb setFunctions',
  hotspot: 'cmd wifi start-softap',
  wifi: 'svc wifi',
  bluetooth: 'svc bluetooth',
  network_mode: 'cmd phone set-allowed-network-types-for-users',
  reboot: 'svc power reboot',
  power_off: 'svc power shutdown',
  screen: 'input keyevent 223/224',
  brightness: 'settings put system screen_brightness',
  charging: '',
  charge_limit: '',
  app_list: 'pm list packages',
  app_force_stop: 'am force-stop',
  app_disable: 'pm disable-user --user 0',
  app_enable: 'pm enable',
  process_list: 'top -n 1',
  send_sms: 'SmsManager',
  ussd: 'TelephonyManager.sendUssdRequest',
  shell: 'su (magisk 27.0)',
  refresh: 'agent',
  capability_probe: 'agent',
  watchdog_simulate: 'agent',
  speed_test: 'HTTP download/upload (finder.speed_down_url / speed_up_url)',
  finder_spot_test: 'agent',
  finder_set_baseline: 'agent',
  finder_delete_spot: 'agent',
};

/**
 * Capability map (plan 6.1). Like the shared fixture, the simulated phone cannot change its
 * network mode unless the profile (or settings.profile_override) supplies a mechanism for it.
 */
export function buildCapabilities(profile: DeviceProfile): CapabilityMap {
  const caps: CapabilityMap = {};
  const chargePath = profile.charge_control[0]?.path ?? null;
  for (const name of COMMAND_NAMES) {
    const override = profile.commands?.[name];
    const method = (Array.isArray(override) && typeof override[0] === 'string' ? override[0] : null) ?? DEFAULT_METHOD[name];
    if (name === 'network_mode') {
      const hasMechanism =
        (profile.network_mode && Object.keys(profile.network_mode).length > 0) || (Array.isArray(override) && override.length > 0);
      caps[name] = hasMechanism
        ? { supported: true, method }
        : { supported: false, note: 'cmd phone set-allowed-network-types-for-users not available' };
      continue;
    }
    if (name === 'charging' || name === 'charge_limit') {
      caps[name] = chargePath
        ? { supported: true, method: chargePath }
        : { supported: false, note: 'no writable charge-control file in the device profile' };
      continue;
    }
    caps[name] = { supported: true, method };
  }
  return caps;
}
