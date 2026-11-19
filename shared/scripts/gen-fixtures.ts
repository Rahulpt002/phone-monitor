/**
 * Generates shared/fixtures/*.json from type-checked objects, so the fixtures always match the
 * TypeScript contract. The Kotlin Agent's unit tests parse these files to prove that its
 * serialization matches (docs/protocol.md). Run: npm run fixtures -w shared
 */
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  DEFAULT_SETTINGS,
  signalScore,
  type AgentToServer,
  type ServerToAgent,
  type FinderSpot,
  type TelemetryMsg,
} from '../src/index.js';

const out = join(dirname(fileURLToPath(import.meta.url)), '..', 'fixtures');
mkdirSync(out, { recursive: true });
const write = (name: string, data: unknown) =>
  writeFileSync(join(out, name), JSON.stringify(data, null, 2) + '\n');

const fast: TelemetryMsg = {
  t: 'telemetry',
  seq: 101,
  ts: '2026-11-19T10:15:00.000Z',
  tier: 'fast',
  radio: {
    operator: 'Example 4G',
    mcc_mnc: '405857',
    net_type: 'NR_NSA',
    band: 40,
    earfcn: 39150,
    pci: 211,
    cell_id: 123456789,
    tac: 4021,
    rsrp: -94,
    rsrq: -11,
    sinr: 12.5,
    rssi: -65,
    cqi: 9,
    ta: 3,
    signal_level: 3,
    nr: { ss_rsrp: -101, ss_rsrq: -12, ss_sinr: 8, band: 78, arfcn: 636666, pci: 402, nci: 68719476735 },
    neighbours: [{ type: 'LTE', pci: 97, earfcn: 39150, rsrp: -103, rsrq: -15 }],
    sim_state: 'ready',
    roaming: false,
    data_enabled: true,
    airplane: false,
    score: 61.2,
  },
  net: {
    online: true,
    http_ms: 180,
    ping_ms: 48,
    ping_loss_pct: 0,
    jitter_ms: 4.1,
    dns_ok: true,
    public_ipv4: '203.0.113.7',
    public_ipv6: null,
    rx_bps: 5400000,
    tx_bps: 820000,
    active_if: 'rmnet_data2',
    internet_down_since: null,
  },
};

const slow: TelemetryMsg = {
  t: 'telemetry',
  seq: 102,
  ts: '2026-11-19T10:15:00.000Z',
  tier: 'slow',
  power: {
    percent: 58,
    status: 'not_charging',
    current_ma: -35,
    voltage_mv: 3910,
    health: 'good',
    plug: 'usb',
    charging_enabled: false,
    charge_limit_pct: 60,
    charge_control: '/sys/class/power_supply/battery/batt_slate_mode',
    stop_reason: 'limit',
  },
  heat: {
    battery_c: 36.5,
    cpu_c: 44.2,
    modem_c: 41,
    skin_c: 35.1,
    thermal_status: 0,
    zones: { 'battery': 36.5, 'cpu-0-0-usr': 44.2, 'modem-0-usr': 41 },
    radio_resting: false,
  },
  system: {
    uptime_s: 86400,
    boot_count: 42,
    boot_reason: 'reboot,userrequested',
    last_boot_at: '2026-11-18T10:15:00.000Z',
    cpu_load: [0.42, 0.51, 0.6],
    ram_total_mb: 7680,
    ram_avail_mb: 3120,
    storage_total_mb: 117000,
    storage_free_mb: 96000,
    top: [{ pid: 1234, name: 'com.hotspotmonitor.agent', cpu_pct: 2.1, mem_mb: 88 }],
    screen_on: false,
    agent_version: '0.1.0',
    android_version: '13',
    agent_uptime_s: 86000,
    reboots_in_window: 0,
  },
  tether: {
    usb_connected: true,
    usb_function: 'rndis',
    usb_tether_on: true,
    tether_if: 'rndis0',
    tether_ip: '192.168.42.129',
    hotspot_on: false,
    hotspot_ssid: null,
    clients: [{ ip: '192.168.42.100', mac: 'aa:bb:cc:dd:ee:ff', iface: 'rndis0', hostname: null }],
  },
  traffic: {
    today_rx: 2100000000,
    today_tx: 180000000,
    cycle_rx: 41000000000,
    cycle_tx: 3900000000,
    cycle_start: '2026-11-01',
    per_if: { rmnet_data2: { rx: 912345678901, tx: 81234567890 } },
  },
  position: {
    lat: 12.9716,
    lon: 77.5946,
    acc_m: 12,
    fix_at: '2026-11-19T10:10:00.000Z',
    pitch_deg: 88.5,
    roll_deg: 1.2,
    azimuth_deg: 271,
    tilt_change_deg: 0.4,
    moved_m: 3,
  },
};

const stat: TelemetryMsg = {
  t: 'telemetry',
  seq: 103,
  ts: '2026-11-19T10:15:00.000Z',
  tier: 'static',
  device: {
    device_id: 'd_3f9c2a',
    manufacturer: 'samsung',
    model: 'SM-G991B',
    device: 'o1s',
    android_version: '13',
    sdk: 33,
    build_id: 'TP1A.220624.014',
    agent_version: '0.1.0',
    agent_version_code: 1,
    profile_id: 'samsung_galaxy_s',
    rooted: true,
    root_impl: 'magisk 27.0',
    imei_tail: '4821',
  },
  capabilities: {
    mobile_data: { supported: true, method: 'svc data' },
    airplane_mode: { supported: true, method: 'cmd connectivity airplane-mode' },
    network_mode: { supported: false, note: 'cmd phone set-allowed-network-types-for-users not available' },
    charging: { supported: true, method: '/sys/class/power_supply/battery/batt_slate_mode' },
  },
};

const spot: FinderSpot = {
  id: 's_roof_ne',
  name: 'Roof NE corner',
  created_at: '2026-11-19T09:00:00.000Z',
  duration_s: 30,
  lat: 12.9716,
  lon: 77.5946,
  acc_m: 8,
  compass_deg: 45,
  orientation: 'upright',
  pitch_deg: 89,
  roll_deg: 0.5,
  has_photo: false,
  serving: { net_type: 'LTE', band: 40, pci: 211, cell_id: 123456789, earfcn: 39150, ta: 3 },
  cell_switches: 0,
  metrics: {
    score: { avg: 66.4, min: 61, max: 70.2, std: 2.1, samples: 30 },
    rsrp: { avg: -91, min: -94, max: -88, std: 1.4, samples: 30 },
    sinr: { avg: 15.2, min: 12, max: 17, std: 1.1, samples: 30 },
    rsrq: { avg: -10.5, min: -12, max: -9, std: 0.8, samples: 30 },
  },
  speed: [
    {
      at: '2026-11-19T09:05:00.000Z',
      peak_hour: false,
      runs: [
        { down_mbps: 42.1, up_mbps: 8.2, ping_ms: 41, jitter_ms: 5, bytes: 12500000, at: '2026-11-19T09:05:00.000Z' },
        { down_mbps: 39.8, up_mbps: 7.9, ping_ms: 44, jitter_ms: 6, bytes: 12500000, at: '2026-11-19T09:05:30.000Z' },
        { down_mbps: 44.0, up_mbps: 8.5, ping_ms: 40, jitter_ms: 4, bytes: 12500000, at: '2026-11-19T09:06:00.000Z' },
      ],
      down_mbps: 42.1,
      up_mbps: 8.2,
      ping_ms: 41,
      jitter_ms: 5,
    },
  ],
  baseline: false,
};

const agentToServer: AgentToServer[] = [
  { t: 'heartbeat', ts: '2026-11-19T10:15:30.000Z', uptime_s: 86430, agent_uptime_s: 86030, outbox: 0, settings_version: 3 },
  fast,
  slow,
  stat,
  {
    t: 'event',
    seq: 104,
    event: {
      id: 'e_7Hk2pQ',
      ts: '2026-11-19T10:16:10.000Z',
      kind: 'watchdog_action',
      severity: 'warning',
      message: 'Internet down 90 s: cycled mobile data',
      data: { rule: 'internet', action: 'data_cycle', step: 1, outcome: 'recovered' },
    },
  },
  { t: 'batch', items: [{ ...fast, seq: 7 }, { ...slow, seq: 8 }] },
  { t: 'cmd_ack', seq: 108, id: 'c_8f2a', accepted: true, reason: null, ts: '2026-11-19T10:16:00.500Z' },
  { t: 'cmd_ack', seq: 109, id: 'c_9b11', accepted: false, reason: 'expired', ts: '2026-11-19T10:18:05.000Z' },
  {
    t: 'cmd_result', seq: 110, id: 'c_8f2a', ok: true, output: '', error: null, duration_ms: 14200,
    revert_deadline: null, ts: '2026-11-19T10:16:14.700Z',
  },
  {
    t: 'cmd_result', seq: 111, id: 'c_a001', ok: true, output: 'mobile data disabled', error: null, duration_ms: 310,
    revert_deadline: '2026-11-19T10:22:00.000Z', ts: '2026-11-19T10:20:00.300Z',
  },
  { t: 'cmd_revert', seq: 112, id: 'c_a001', state: 'reverted', detail: 'no server round-trip within 120 s', ts: '2026-11-19T10:22:00.100Z' },
  { t: 'probe', id: 'p_1' },
  {
    t: 'finder_live',
    live: {
      ts: '2026-11-19T10:15:01.000Z', mode: 'walk', score: 63.5, score_raw: 64.9, rsrp: -92, sinr: 13.1, rsrq: -11,
      net_type: 'LTE', band: 40, pci: 211, cell_id: 123456789, ta: 3, cell_switches_5min: 0, progress: null,
    },
  },
  { t: 'finder_spot', seq: 105, spot },
  { t: 'finder_spot_deleted', seq: 106, spot_id: 's_old' },
  { t: 'sms', seq: 107, sms: { id: 'm_x1', direction: 'in', peer: 'JX-JIOINF', body: 'Your plan expires in 3 days.', at: '2026-11-19T08:00:00.000Z' } },
];

const serverToAgent: ServerToAgent[] = [
  {
    t: 'hello',
    server_time: '2026-11-19T10:15:00.000Z',
    device_id: 'd_3f9c2a',
    settings: DEFAULT_SETTINGS,
    settings_version: 3,
    rates: { fast_s: 60, mirror: false },
  },
  { t: 'settings', settings: DEFAULT_SETTINGS, version: 4 },
  { t: 'rates', fast_s: 5, mirror: true },
  {
    t: 'cmd', id: 'c_8f2a', name: 'airplane_cycle', args: { off_seconds: 10 },
    sent_at: '2026-11-19T10:16:00.000Z', user: 'admin', revert_after_s: null, revert_confirm: null,
  },
  {
    t: 'cmd', id: 'c_a001', name: 'mobile_data', args: { enabled: false },
    sent_at: '2026-11-19T10:20:00.000Z', user: 'admin', revert_after_s: 120, revert_confirm: 'agent_link',
  },
  { t: 'cmd_keep', id: 'c_b777' },
  { t: 'ack', seq: 112 },
  { t: 'probe_ack', id: 'p_1', server_time: '2026-11-19T10:20:15.000Z' },
  { t: 'error', code: 'unauthorized', message: 'invalid device token' },
];

const scoreInputs: [number | null, number | null, number | null][] = [
  [-80, 25, -8], [-120, -5, -20], [-94, 12.5, -11], [-100, 0, -15], [-70, 30, -3],
  [-130, -10, -25], [-90, null, -12], [-90, 10, null], [null, 10, -10], [-85.5, 17.25, -9.75],
];
const scoreCases = scoreInputs.map(([rsrp, sinr, rsrq]) => ({ rsrp, sinr, rsrq, score: signalScore(rsrp, sinr, rsrq) }));

write('agent-to-server.json', agentToServer);
write('server-to-agent.json', serverToAgent);
write('settings-default.json', DEFAULT_SETTINGS);
write('score-cases.json', scoreCases);
console.log('fixtures written to', out);
