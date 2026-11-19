package com.hotspotmonitor.agent.finder

import com.hotspotmonitor.agent.core.Clock
import com.hotspotmonitor.agent.protocol.FinderSpot
import com.hotspotmonitor.agent.protocol.MetricStats
import com.hotspotmonitor.agent.protocol.rankSpots
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * CSV export of the saved spots (plan 7.3 "Ranking ... Exports CSV"): RFC 4180 (comma separated,
 * CRLF line ends, fields with a comma, quote or line break are quoted and quotes doubled), one row
 * per spot in ranking order.
 */
object SpotCsv {
    val HEADER: List<String> = listOf(
        "rank", "name", "time", "lat", "lon", "acc_m", "orientation", "compass_deg", "pitch_deg", "roll_deg",
        "net_type", "band", "pci", "cell_id", "earfcn", "ta",
        "score_avg", "score_min", "score_max", "score_std", "samples",
        "rsrp_avg", "rsrp_min", "rsrp_max", "rsrp_std",
        "sinr_avg", "sinr_min", "sinr_max", "sinr_std",
        "rsrq_avg", "rsrq_min", "rsrq_max", "rsrq_std",
        "cell_switches", "unstable",
        "best_down_mbps", "best_up_mbps", "best_ping_ms", "best_jitter_ms", "peak_hour_down_mbps", "speed_sessions",
        "baseline", "has_photo", "duration_s", "id",
    )

    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)

    /** The whole file: header plus one row per spot, ranked with the shared rule. */
    fun build(spots: List<FinderSpot>, zone: ZoneId): String {
        val sb = StringBuilder()
        sb.append(HEADER.joinToString(",")).append(CRLF)
        rankSpots(spots).forEachIndexed { index, spot ->
            sb.append(row(index + 1, spot, zone).joinToString(",")).append(CRLF)
        }
        return sb.toString()
    }

    fun row(rank: Int, spot: FinderSpot, zone: ZoneId): List<String> {
        val m = spot.metrics
        val downs = spot.speed.mapNotNull { it.downMbps }
        return listOf(
            rank.toString(),
            text(spot.name),
            localTime(spot.createdAt, zone),
            num(spot.lat, 6),
            num(spot.lon, 6),
            num(spot.accM, 1),
            text(spot.orientation),
            num(spot.compassDeg, 1),
            num(spot.pitchDeg, 1),
            num(spot.rollDeg, 1),
            spot.serving.netType.name,
            spot.serving.band?.toString().orEmpty(),
            spot.serving.pci?.toString().orEmpty(),
            spot.serving.cellId?.toString().orEmpty(),
            spot.serving.earfcn?.toString().orEmpty(),
            spot.serving.ta?.toString().orEmpty(),
        ) + stats(m.score, withSamples = true) + stats(m.rsrp) + stats(m.sinr) + stats(m.rsrq) + listOf(
            spot.cellSwitches.toString(),
            yesNo(isUnstable(spot)),
            num(downs.maxOrNull(), 1),
            num(spot.speed.mapNotNull { it.upMbps }.maxOrNull(), 1),
            num(spot.speed.mapNotNull { it.pingMs }.minOrNull(), 1),
            num(spot.speed.mapNotNull { it.jitterMs }.minOrNull(), 1),
            num(spot.speed.filter { it.peakHour }.mapNotNull { it.downMbps }.maxOrNull(), 1),
            spot.speed.size.toString(),
            yesNo(spot.baseline),
            yesNo(spot.hasPhoto == true),
            spot.durationS.toString(),
            text(spot.id),
        )
    }

    /** Plan 7.5: a spot whose serving cell changed during the test is flagged unstable. */
    fun isUnstable(spot: FinderSpot): Boolean = spot.cellSwitches > 0

    private fun stats(s: MetricStats?, withSamples: Boolean = false): List<String> {
        val base = listOf(num(s?.avg, 1), num(s?.min, 1), num(s?.max, 1), num(s?.std, 1))
        return if (withSamples) base + (s?.samples?.toString().orEmpty()) else base
    }

    private fun yesNo(v: Boolean) = if (v) "yes" else "no"

    private fun num(v: Double?, decimals: Int): String {
        if (v == null || !v.isFinite()) return ""
        val text = String.format(Locale.ROOT, "%.${decimals}f", v)
        val trimmed = if (decimals > 0) text.trimEnd('0').trimEnd('.') else text
        return if (trimmed == "-0") "0" else trimmed
    }

    private fun localTime(iso: String, zone: ZoneId): String {
        val ms = Clock.parseIso(iso) ?: return escape(iso)
        return TIME_FORMAT.format(Instant.ofEpochMilli(ms).atZone(zone))
    }

    /**
     * Free text: escaped, and a leading = + - @ (or tab / CR) is prefixed with an apostrophe so a
     * spreadsheet does not run a spot name as a formula.
     */
    fun text(value: String?): String {
        if (value == null) return ""
        val safe = if (value.isNotEmpty() && value[0] in FORMULA_START) "'$value" else value
        return escape(safe)
    }

    /** RFC 4180 field escaping. */
    fun escape(field: String): String {
        val needsQuotes = field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"" + field.replace("\"", "\"\"") + "\"" else field
    }

    private const val CRLF = "\r\n"
    private val FORMULA_START = setOf('=', '+', '-', '@', '\t', '\r')
}
