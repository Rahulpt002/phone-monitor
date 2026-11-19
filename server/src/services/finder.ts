import { rankSpots, type FinderSpot, type FinderSpotsResponse } from '@hsm/shared';
import { z } from 'zod';
import type { AppContext } from '../context.js';
import { Db } from '../db/client.js';
import { safeJsonParse, type Plain } from '../util.js';
import type { DeviceRuntime } from './devices.js';
import type { IngestBatch } from './ingest.js';

const Stats = z.looseObject({
  avg: z.number(),
  min: z.number(),
  max: z.number(),
  std: z.number(),
  samples: z.number(),
});

/** Enough structure for ranking, baseline comparison and the Dashboard; extra fields pass through. */
export const FinderSpotSchema = z.looseObject({
  id: z.string().min(1).max(100),
  name: z.string().max(200),
  created_at: z.string().max(64),
  duration_s: z.number(),
  serving: z.looseObject({ net_type: z.string().max(16) }),
  cell_switches: z.number().default(0),
  metrics: z.looseObject({
    score: Stats,
    rsrp: Stats,
    sinr: Stats.nullish(),
    rsrq: Stats.nullish(),
  }),
  speed: z
    .array(
      z.looseObject({
        down_mbps: z.number().nullable(),
        up_mbps: z.number().nullable(),
        runs: z.array(z.unknown()).default([]),
      }),
    )
    .default([]),
  baseline: z.boolean().default(false),
});

/** Signal Finder spots (plan 7.3): upserted by id, ranked by speed then score, one baseline. */
export class FinderService {
  constructor(private ctx: AppContext) {}

  async loadBaselines(): Promise<void> {
    const rows = await this.ctx.db.all<{ device_id: string; data: string }>(
      'SELECT device_id, data FROM finder_spots WHERE baseline = 1 ORDER BY updated_at ASC',
    );
    for (const r of rows) {
      const dev = this.ctx.devices.get(String(r.device_id));
      const spot = safeJsonParse<FinderSpot | null>(r.data, null);
      if (dev && spot) dev.baseline = spot;
    }
  }

  planUpsert(batch: IngestBatch, dev: DeviceRuntime, item: Plain): boolean {
    const parsed = FinderSpotSchema.safeParse(item.spot);
    if (!parsed.success) {
      this.ctx.log.warn({ device_id: dev.id, issues: parsed.error.issues.slice(0, 3) }, 'invalid finder_spot ignored');
      return false;
    }
    const spot = parsed.data as unknown as FinderSpot;
    const now = this.ctx.clock.now();
    let clearIdx = -1;
    if (spot.baseline) {
      // Only one baseline per device: clear the flag (column and JSON) on every other spot.
      clearIdx = batch.add({
        sql: `UPDATE finder_spots SET baseline = 0, data = json_set(data, '$.baseline', json('false')), updated_at = ?
              WHERE device_id = ? AND id <> ? AND baseline = 1 RETURNING data`,
        args: [now, dev.id, spot.id],
      });
    }
    batch.add({
      sql: `INSERT INTO finder_spots (device_id, id, created_at, baseline, data, updated_at) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (device_id, id) DO UPDATE SET created_at = excluded.created_at, baseline = excluded.baseline,
              data = excluded.data, updated_at = excluded.updated_at`,
      args: [dev.id, spot.id, spot.created_at, spot.baseline ? 1 : 0, JSON.stringify(spot), now],
    });
    batch.after((results) => {
      if (clearIdx >= 0) {
        for (const r of Db.rows(results[clearIdx])) {
          const cleared = safeJsonParse<FinderSpot | null>(r.data, null);
          if (cleared) this.ctx.hub.broadcastDevice(dev.id, { t: 'finder_spot', device_id: dev.id, spot: cleared });
        }
      }
      if (spot.baseline) dev.baseline = spot;
      else if (dev.baseline?.id === spot.id) dev.baseline = null;
      this.ctx.hub.broadcastDevice(dev.id, { t: 'finder_spot', device_id: dev.id, spot });
    });
    return true;
  }

  planDelete(batch: IngestBatch, dev: DeviceRuntime, item: Plain): boolean {
    const spotId = item.spot_id;
    if (typeof spotId !== 'string' || !spotId || spotId.length > 100) return false;
    batch.add({ sql: 'DELETE FROM finder_spots WHERE device_id = ? AND id = ?', args: [dev.id, spotId] });
    batch.after(() => {
      if (dev.baseline?.id === spotId) dev.baseline = null;
      this.ctx.hub.broadcastDevice(dev.id, { t: 'finder_spot_deleted', device_id: dev.id, spot_id: spotId });
    });
    return true;
  }

  async list(dev: DeviceRuntime): Promise<FinderSpotsResponse> {
    const rows = await this.ctx.db.all<{ data: string }>('SELECT data FROM finder_spots WHERE device_id = ?', [dev.id]);
    const spots: FinderSpot[] = [];
    for (const r of rows) {
      const parsed = FinderSpotSchema.safeParse(safeJsonParse(r.data, null));
      if (parsed.success) spots.push(parsed.data as unknown as FinderSpot);
    }
    const ranked = rankSpots(spots);
    return { spots: ranked, baseline_id: ranked.find((s) => s.baseline)?.id ?? null };
  }
}
