import { clamp, type NeighbourCell, type NetType, type NetworkMode, type RadioInfo } from '@hsm/shared';
import { ouStep, type Random } from '../rng.js';
import { dayBump, round1 } from '../util.js';

export interface Cell {
  pci: number;
  cellId: number;
  earfcn: number;
  band: number;
  tac: number;
  ta: number;
  /** How much weaker than the primary cell this one is received at the mounting spot. */
  rsrpOffset: number;
  sinrOffset: number;
}

/** Primary serving cell (same values as the shared fixtures). */
export const CELL_A: Cell = { pci: 211, cellId: 123456789, earfcn: 39150, band: 40, tac: 4021, ta: 3, rsrpOffset: 0, sinrOffset: 0 };
/** Second sector of the same band; the cell-flap scenario hands over between A and B. */
export const CELL_B: Cell = { pci: 97, cellId: 123456530, earfcn: 39150, band: 40, tac: 4021, ta: 5, rsrpOffset: -2.5, sinrOffset: -3 };
const CELL_BAND3 = { pci: 305, earfcn: 1350 };
const NR_LEG = { band: 78, arfcn: 636666, pci: 402, nci: 8364832197 };
const WCDMA = { band: 1, uarfcn: 10713, cellId: 21345678 };

export const OPERATOR = 'SimTel 4G';
export const MCC_MNC = '00101';

/** Cell load through the day (0..1): evening peak around 21:00, smaller daytime bumps. */
export function congestion(hour: number): number {
  return clamp(0.08 + 0.85 * dayBump(hour, 21, 1.6) + 0.35 * dayBump(hour, 10, 1.5) + 0.25 * dayBump(hour, 14, 2), 0, 1);
}

/** Android's default LTE RSRP thresholds for the 0..4 bars. */
export function lteSignalLevel(rsrp: number): number {
  if (rsrp >= -85) return 4;
  if (rsrp >= -95) return 3;
  if (rsrp >= -105) return 2;
  if (rsrp >= -115) return 1;
  return 0;
}

export interface RadioReading {
  rsrp: number;
  rsrq: number;
  sinr: number;
}

export interface RadioInfoParams {
  reading: RadioReading | null;
  score: number | null;
  radioOn: boolean;
  dataEnabled: boolean;
  airplane: boolean;
  networkMode: NetworkMode;
  hour: number;
}

/**
 * LTE anchor + NR (NSA) leg. Values follow mean-reverting random walks around the cell's level;
 * SINR and RSRQ sag with the diurnal congestion curve (the plan's "busy cell" effect).
 */
export class RadioModel {
  private rsrp: number;
  private sinr: number;
  private rsrq: number;
  private nrRsrp: number;
  private nrSinr: number;
  private nrRsrq: number;
  private servingCell: Cell = CELL_A;
  private switchTimes: number[] = [];
  private taStep = 0;
  /** Serving-cell changes since start. */
  switchCount = 0;
  /** >1 while someone walks around with the phone (Finder mirror on). */
  volatility = 1;
  /** Signal gained or lost by the phone's orientation during a spot test (dB). */
  orientationOffsetDb = 0;
  /** How far below the serving cell the strongest neighbour is (cell-flap makes it small). */
  neighbourGapDb = 9;

  constructor(private readonly rng: Random) {
    this.rsrp = rng.gauss(-91, 1.5);
    this.sinr = rng.gauss(13, 1.5);
    this.rsrq = rng.gauss(-10.5, 0.5);
    this.nrRsrp = this.rsrp - 8;
    this.nrSinr = this.sinr - 3.5;
    this.nrRsrq = -11.5;
  }

  get serving(): Cell {
    return this.servingCell;
  }

  /** Advances the radio by `dt` simulated seconds. */
  step(dt: number, hour: number): void {
    if (dt <= 0) return;
    const c = congestion(hour);
    const v = this.volatility;
    const cell = this.servingCell;
    const rng = this.rng;
    this.rsrp = clamp(ouStep(this.rsrp, -91 + cell.rsrpOffset, 3 * v, 240 / v, dt, rng), -125, -62);
    this.sinr = clamp(ouStep(this.sinr, 15.5 - 7.5 * c + cell.sinrOffset, 2.4 * v, 60 / v, dt, rng), -10, 30);
    this.rsrq = clamp(ouStep(this.rsrq, -9.5 - 3.5 * c, 1.1, 60, dt, rng), -19.5, -4);
    this.nrRsrp = clamp(ouStep(this.nrRsrp, this.rsrp - 8, 2.2, 45, dt, rng), -130, -65);
    this.nrSinr = clamp(ouStep(this.nrSinr, this.sinr - 3.5, 2.2, 45, dt, rng), -10, 28);
    this.nrRsrq = clamp(ouStep(this.nrRsrq, -11.5 - 2 * c, 1, 45, dt, rng), -20, -5);
    if (rng.chance(Math.min(1, dt / 120))) this.taStep = rng.chance(0.8) ? 0 : 1;
  }

  /** One measurement: the current state plus measurement noise (and any orientation offset). */
  reading(): RadioReading {
    const o = this.orientationOffsetDb;
    return {
      rsrp: Math.round(clamp(this.rsrp + o + this.rng.gauss(0, 0.8), -140, -44)),
      rsrq: Math.round(clamp(this.rsrq + o * 0.2 + this.rng.gauss(0, 0.4), -20, -3)),
      sinr: round1(clamp(this.sinr + o * 0.7 + this.rng.gauss(0, 0.6), -20, 30)),
    };
  }

  /** Whether the 5G leg is attached in this network mode. */
  nrAttached(mode: NetworkMode): boolean {
    return (mode === 'auto' || mode === 'nr_lte') && this.nrRsrp > -116;
  }

  netType(mode: NetworkMode, radioOn: boolean): NetType {
    if (!radioOn) return 'UNKNOWN';
    if (mode === 'wcdma_only') return 'WCDMA';
    return this.nrAttached(mode) ? 'NR_NSA' : 'LTE';
  }

  /** Hands over to the other sector; returns the cells involved. */
  switchCell(nowMs: number): { from: Cell; to: Cell } {
    const from = this.servingCell;
    const to = from === CELL_A ? CELL_B : CELL_A;
    this.servingCell = to;
    this.rsrp += to.rsrpOffset - from.rsrpOffset;
    this.sinr += to.sinrOffset - from.sinrOffset;
    this.switchCount++;
    this.switchTimes.push(nowMs);
    if (this.switchTimes.length > 200) this.switchTimes.shift();
    return { from, to };
  }

  switchesSince(sinceMs: number): number {
    return this.switchTimes.filter((t) => t >= sinceMs).length;
  }

  info(p: RadioInfoParams): RadioInfo {
    const r = p.reading;
    if (!p.radioOn || !r) {
      return {
        operator: null,
        mcc_mnc: null,
        net_type: 'UNKNOWN',
        band: null,
        earfcn: null,
        pci: null,
        cell_id: null,
        tac: null,
        rsrp: null,
        rsrq: null,
        sinr: null,
        rssi: null,
        cqi: null,
        ta: null,
        signal_level: 0,
        nr: null,
        neighbours: [],
        sim_state: p.airplane ? 'not_ready' : 'ready',
        roaming: null,
        data_enabled: p.dataEnabled,
        airplane: p.airplane,
        score: null,
      };
    }
    const c = congestion(p.hour);
    if (p.networkMode === 'wcdma_only') {
      // 3G has RSCP/Ec/Io instead of RSRP/RSRQ/SINR, so the LTE-based score is unknown.
      const rssi = Math.round(r.rsrp + 18 + this.rng.gauss(0, 1));
      return {
        operator: OPERATOR.replace('4G', '3G'),
        mcc_mnc: MCC_MNC,
        net_type: 'WCDMA',
        band: WCDMA.band,
        earfcn: WCDMA.uarfcn,
        pci: null,
        cell_id: WCDMA.cellId,
        tac: this.servingCell.tac,
        rsrp: null,
        rsrq: null,
        sinr: null,
        rssi,
        cqi: null,
        ta: null,
        signal_level: rssi >= -77 ? 4 : rssi >= -87 ? 3 : rssi >= -97 ? 2 : rssi >= -107 ? 1 : 0,
        nr: null,
        neighbours: [],
        sim_state: 'ready',
        roaming: false,
        data_enabled: p.dataEnabled,
        airplane: false,
        score: null,
      };
    }
    const cell = this.servingCell;
    const nrOn = this.nrAttached(p.networkMode);
    return {
      operator: OPERATOR,
      mcc_mnc: MCC_MNC,
      net_type: nrOn ? 'NR_NSA' : 'LTE',
      band: cell.band,
      earfcn: cell.earfcn,
      pci: cell.pci,
      cell_id: cell.cellId,
      tac: cell.tac,
      rsrp: r.rsrp,
      rsrq: r.rsrq,
      sinr: r.sinr,
      rssi: Math.round(r.rsrp + 25 + 4 * c + this.rng.gauss(0, 1)),
      cqi: clamp(Math.round(r.sinr / 2.2 + 3.5), 1, 15),
      ta: cell.ta + this.taStep,
      signal_level: lteSignalLevel(r.rsrp),
      nr: nrOn
        ? {
            ss_rsrp: Math.round(this.nrRsrp + this.rng.gauss(0, 0.8)),
            ss_rsrq: Math.round(this.nrRsrq),
            ss_sinr: round1(this.nrSinr + this.rng.gauss(0, 0.6)),
            band: NR_LEG.band,
            arfcn: NR_LEG.arfcn,
            pci: NR_LEG.pci,
            nci: NR_LEG.nci,
          }
        : null,
      neighbours: this.neighbours(r),
      sim_state: 'ready',
      roaming: false,
      data_enabled: p.dataEnabled,
      airplane: false,
      score: p.score,
    };
  }

  private neighbours(r: RadioReading): NeighbourCell[] {
    const cell = this.servingCell;
    const other = cell === CELL_A ? CELL_B : CELL_A;
    const g = (sd: number) => this.rng.gauss(0, sd);
    return [
      {
        type: 'LTE',
        pci: other.pci,
        earfcn: other.earfcn,
        rsrp: Math.round(r.rsrp - this.neighbourGapDb + other.rsrpOffset - cell.rsrpOffset + g(1.5)),
        rsrq: Math.round(clamp(r.rsrq - 3 + g(1), -20, -3)),
      },
      {
        type: 'LTE',
        pci: CELL_BAND3.pci,
        earfcn: CELL_BAND3.earfcn,
        rsrp: Math.round(r.rsrp - 15 + g(2)),
        rsrq: Math.round(clamp(-16 + g(1), -20, -3)),
      },
    ];
  }
}
