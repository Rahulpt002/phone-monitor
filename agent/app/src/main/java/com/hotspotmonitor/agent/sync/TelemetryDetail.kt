package com.hotspotmonitor.agent.sync

import com.hotspotmonitor.agent.protocol.Rates
import com.hotspotmonitor.agent.protocol.TelemetryMsg
import com.hotspotmonitor.agent.protocol.TelemetrySettings

/**
 * Keeps background telemetry near the plan 8.2 budget ("about 1 MB per day"). The bulky lists only
 * the Dashboard's detail views show are left out of most messages:
 *
 * - `heat.zones`, `system.top` and `traffic.per_if` go with a slow message every [detailEveryMs]
 *   (15 min), and with every slow message while a Dashboard is open;
 * - `radio.neighbours` go with fast messages only while a Dashboard is open.
 *
 * "A Dashboard is open" is the Server's rates: fast_s == telemetry.fast_active_s (docs/protocol.md 5,
 * point 4). The Server replaces a group as a whole, so an open Dashboard sees the lists; the
 * SyncClient also sends a slow message at once when a Dashboard opens.
 */
internal class TelemetryDetail(private val detailEveryMs: Long = DETAIL_EVERY_MS) {
    private var lastDetailAtMs: Long? = null

    /** The slow message to send at [nowMs] (elapsed ms): full, or without the detail lists. */
    @Synchronized
    fun slow(msg: TelemetryMsg, nowMs: Long, dashboardOpen: Boolean): TelemetryMsg {
        val last = lastDetailAtMs
        if (dashboardOpen || last == null || nowMs - last >= detailEveryMs) {
            if (msg.heat?.zones != null || msg.system?.top != null || msg.traffic?.perIf != null) lastDetailAtMs = nowMs
            return msg
        }
        return msg.copy(
            heat = msg.heat?.copy(zones = null),
            system = msg.system?.copy(top = null),
            traffic = msg.traffic?.copy(perIf = null),
        )
    }

    /** The fast message: neighbour cells only while a Dashboard is open. */
    fun fast(msg: TelemetryMsg, dashboardOpen: Boolean): TelemetryMsg =
        if (dashboardOpen || msg.radio?.neighbours == null) msg else msg.copy(radio = msg.radio.copy(neighbours = null))

    companion object {
        const val DETAIL_EVERY_MS = 15 * 60_000L

        fun dashboardOpen(rates: Rates, telemetry: TelemetrySettings): Boolean = rates.fastS == telemetry.fastActiveS
    }
}
