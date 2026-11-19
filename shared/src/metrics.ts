/**
 * Chart metrics available from GET /telemetry (plan 9: Signal, Network, Power and heat screens).
 * `path` is a SQLite json_extract path into the stored telemetry message.
 * The Server rolls raw readings up into 1-minute averages using the same list.
 */
import type { TelemetryTier } from './telemetry.js';

export interface MetricDef {
  key: string;
  label: string;
  unit: string;
  tier: TelemetryTier;
  path: string; // json_extract path
}

export const METRICS = [
  { key: 'radio.score', label: 'Signal score', unit: '', tier: 'fast', path: '$.radio.score' },
  { key: 'radio.rsrp', label: 'RSRP', unit: 'dBm', tier: 'fast', path: '$.radio.rsrp' },
  { key: 'radio.rsrq', label: 'RSRQ', unit: 'dB', tier: 'fast', path: '$.radio.rsrq' },
  { key: 'radio.sinr', label: 'SINR', unit: 'dB', tier: 'fast', path: '$.radio.sinr' },
  { key: 'radio.nr.ss_rsrp', label: '5G SS-RSRP', unit: 'dBm', tier: 'fast', path: '$.radio.nr.ss_rsrp' },
  { key: 'radio.nr.ss_sinr', label: '5G SS-SINR', unit: 'dB', tier: 'fast', path: '$.radio.nr.ss_sinr' },
  { key: 'radio.pci', label: 'Serving PCI', unit: '', tier: 'fast', path: '$.radio.pci' },
  { key: 'net.rx_bps', label: 'Download', unit: 'bps', tier: 'fast', path: '$.net.rx_bps' },
  { key: 'net.tx_bps', label: 'Upload', unit: 'bps', tier: 'fast', path: '$.net.tx_bps' },
  { key: 'net.ping_ms', label: 'Ping', unit: 'ms', tier: 'fast', path: '$.net.ping_ms' },
  { key: 'net.ping_loss_pct', label: 'Packet loss', unit: '%', tier: 'fast', path: '$.net.ping_loss_pct' },
  { key: 'net.jitter_ms', label: 'Jitter', unit: 'ms', tier: 'fast', path: '$.net.jitter_ms' },
  { key: 'power.percent', label: 'Battery', unit: '%', tier: 'slow', path: '$.power.percent' },
  { key: 'power.current_ma', label: 'Battery current', unit: 'mA', tier: 'slow', path: '$.power.current_ma' },
  { key: 'power.voltage_mv', label: 'Battery voltage', unit: 'mV', tier: 'slow', path: '$.power.voltage_mv' },
  { key: 'heat.battery_c', label: 'Battery temp', unit: '°C', tier: 'slow', path: '$.heat.battery_c' },
  { key: 'heat.cpu_c', label: 'CPU temp', unit: '°C', tier: 'slow', path: '$.heat.cpu_c' },
  { key: 'heat.modem_c', label: 'Modem temp', unit: '°C', tier: 'slow', path: '$.heat.modem_c' },
  { key: 'heat.skin_c', label: 'Skin temp', unit: '°C', tier: 'slow', path: '$.heat.skin_c' },
  { key: 'system.load1', label: 'CPU load (1 min)', unit: '', tier: 'slow', path: '$.system.cpu_load[0]' },
  { key: 'system.ram_avail_mb', label: 'Free RAM', unit: 'MB', tier: 'slow', path: '$.system.ram_avail_mb' },
  { key: 'traffic.cycle_rx', label: 'Downloaded this cycle', unit: 'B', tier: 'slow', path: '$.traffic.cycle_rx' },
  { key: 'traffic.cycle_tx', label: 'Uploaded this cycle', unit: 'B', tier: 'slow', path: '$.traffic.cycle_tx' },
] as const satisfies readonly MetricDef[];

export type MetricKey = (typeof METRICS)[number]['key'];

export const METRIC_BY_KEY: Record<string, MetricDef> = Object.fromEntries(METRICS.map((m) => [m.key, m]));
