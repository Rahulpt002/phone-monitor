import type { AgentEvent, EventKind, NetType, Severity } from '@hsm/shared';
import type { Phone } from './phone/phone.js';
import { formatDuration, newId } from './util.js';

export interface EventSpec {
  kind: EventKind;
  severity: Severity;
  message: string;
  data?: Record<string, unknown>;
}

export function makeEvent(spec: EventSpec, ts: string): AgentEvent {
  const ev: AgentEvent = { id: newId('e_'), ts, kind: spec.kind, severity: spec.severity, message: spec.message };
  if (spec.data) ev.data = spec.data;
  return ev;
}

/** The phone state that broadcast receivers and callbacks on the real Agent react to. */
export interface PhoneWatch {
  online: boolean;
  netType: NetType;
  pci: number | null;
  cellId: number | null;
  band: number | null;
  usbConnected: boolean;
  tetherOn: boolean;
  tetherIf: string | null;
  plugged: boolean;
}

export function watchPhone(phone: Phone): PhoneWatch {
  const reading = phone.radioOn && phone.flags.networkMode !== 'wcdma_only';
  const cell = phone.radio.serving;
  return {
    online: phone.internetOk,
    netType: phone.netType(),
    pci: reading ? cell.pci : null,
    cellId: reading ? cell.cellId : null,
    band: reading ? cell.band : null,
    usbConnected: phone.flags.usbConnected,
    tetherOn: phone.tetherOn,
    tetherIf: phone.tetherIf,
    plugged: phone.power.plugged,
  };
}

/**
 * Events for state changes between two observations (plan 6.2 "Events" row).
 * `downForS` is how long the internet had been down when it comes back.
 */
export function transitions(prev: PhoneWatch, cur: PhoneWatch, downForS: number | null): EventSpec[] {
  const out: EventSpec[] = [];
  if (prev.online && !cur.online) {
    out.push({ kind: 'connectivity', severity: 'warning', message: 'Internet check failing', data: { online: false } });
  } else if (!prev.online && cur.online) {
    const down = downForS != null ? Math.round(downForS) : null;
    out.push({
      kind: 'connectivity',
      severity: 'info',
      message: down != null ? `Internet back after ${formatDuration(down)}` : 'Internet back',
      data: down != null ? { online: true, down_for_s: down } : { online: true },
    });
  }
  if (prev.pci != null && cur.pci != null && (prev.pci !== cur.pci || prev.cellId !== cur.cellId)) {
    out.push({
      kind: 'cell_change',
      severity: 'info',
      message: `Serving cell changed: PCI ${prev.pci} -> ${cur.pci}`,
      data: {
        from: { pci: prev.pci, cell_id: prev.cellId, band: prev.band },
        to: { pci: cur.pci, cell_id: cur.cellId, band: cur.band },
      },
    });
  }
  if (prev.netType !== cur.netType && prev.netType !== 'UNKNOWN' && cur.netType !== 'UNKNOWN') {
    out.push({
      kind: 'net_type_change',
      severity: 'info',
      message: `Network type ${prev.netType} -> ${cur.netType}`,
      data: { from: prev.netType, to: cur.netType },
    });
  }
  if (prev.usbConnected !== cur.usbConnected) {
    out.push(
      cur.usbConnected
        ? { kind: 'usb_connected', severity: 'info', message: 'USB host connected' }
        : { kind: 'usb_disconnected', severity: 'warning', message: 'USB host disconnected' },
    );
  }
  if (prev.tetherOn !== cur.tetherOn) {
    out.push(
      cur.tetherOn
        ? { kind: 'tether_up', severity: 'info', message: `USB tethering up (${cur.tetherIf})`, data: { iface: cur.tetherIf } }
        : { kind: 'tether_down', severity: 'warning', message: 'USB tethering down', data: { iface: prev.tetherIf } },
    );
  }
  if (prev.plugged !== cur.plugged) {
    out.push(
      cur.plugged
        ? { kind: 'power_connected', severity: 'info', message: 'USB power connected' }
        : { kind: 'power_lost', severity: 'critical', message: 'USB power lost: running on battery' },
    );
  }
  return out;
}
