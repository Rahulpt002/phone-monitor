package com.hotspotmonitor.agent.finder

import com.hotspotmonitor.agent.protocol.NetType
import com.hotspotmonitor.agent.protocol.RadioInfo
import com.hotspotmonitor.agent.protocol.ScoreWeights
import com.hotspotmonitor.agent.protocol.ServingCell
import com.hotspotmonitor.agent.protocol.SpotMetrics
import com.hotspotmonitor.agent.protocol.rawScore
import com.hotspotmonitor.agent.protocol.scoreInputs

/**
 * Identity of the serving cell in one reading. Two readings are the same cell when their cell ids
 * match, or (when an id is missing on either side) when PCI and channel match. A reading without
 * cell id and PCI is unknown and never counts as a switch.
 */
data class CellIdentity(val cellId: Long?, val pci: Int?, val earfcn: Int?) {
    val known: Boolean get() = cellId != null || pci != null

    fun sameCellAs(other: CellIdentity): Boolean = when {
        cellId != null && other.cellId != null -> cellId == other.cellId
        pci != null && other.pci != null -> pci == other.pci && (earfcn == null || other.earfcn == null || earfcn == other.earfcn)
        else -> true
    }

    /** Grouping key for "most common serving cell during a test"; null when unknown. */
    fun key(): String? = when {
        cellId != null -> "cid:$cellId"
        pci != null -> "pci:$pci/${earfcn ?: "?"}"
        else -> null
    }

    companion object {
        fun of(r: RadioInfo): CellIdentity {
            val saLeg = r.nr.takeIf { r.netType == NetType.NR_SA }
            return CellIdentity(r.cellId ?: saLeg?.nci, r.pci ?: saLeg?.pci, r.earfcn ?: saLeg?.arfcn)
        }
    }
}

/**
 * Counts serving-cell changes (plan 7.5: a spot that keeps switching between cells gives unstable
 * speed). [observe] every reading in time order; [countRecent] gives the switches inside the
 * window, [total] a monotonic count used to measure switches during a test. Thread-safe.
 */
class CellSwitchCounter(private val windowMs: Long = DEFAULT_WINDOW_MS) {
    private var last: CellIdentity? = null
    private val switchTimes = ArrayDeque<Long>()
    private var total = 0

    /** Returns true when this reading is a switch to another cell. */
    @Synchronized
    fun observe(id: CellIdentity, atMs: Long): Boolean {
        if (!id.known) return false
        val previous = last
        last = id
        if (previous == null || previous.sameCellAs(id)) return false
        switchTimes.addLast(atMs)
        total++
        prune(atMs)
        return true
    }

    @Synchronized
    fun countRecent(nowMs: Long): Int {
        prune(nowMs)
        return switchTimes.size
    }

    @Synchronized
    fun total(): Int = total

    @Synchronized
    fun reset() {
        last = null
        switchTimes.clear()
        total = 0
    }

    private fun prune(nowMs: Long) {
        val cutoff = nowMs - windowMs
        while (switchTimes.isNotEmpty() && switchTimes.first() <= cutoff) switchTimes.removeFirst()
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 5 * 60_000L
    }
}

/** Collects the 1 Hz samples of a spot test and turns them into SpotMetrics + the serving cell. */
class SpotAccumulator(private val weights: ScoreWeights) {
    private val score = ArrayList<Double>()
    private val rsrp = ArrayList<Double>()
    private val sinr = ArrayList<Double>()
    private val rsrq = ArrayList<Double>()
    private val cellCounts = LinkedHashMap<String, Int>()
    private val cellLatest = HashMap<String, ServingCell>()

    val samples: Int get() = score.size

    fun add(reading: RadioInfo) {
        val inputs = reading.scoreInputs()
        val s = reading.rawScore(weights) ?: return // no RSRP: not a usable sample
        score.add(s)
        inputs.rsrp?.let(rsrp::add)
        inputs.sinr?.takeIf { it.isFinite() }?.let(sinr::add)
        inputs.rsrq?.let(rsrq::add)
        val key = CellIdentity.of(reading).key() ?: UNKNOWN_CELL
        cellCounts[key] = (cellCounts[key] ?: 0) + 1
        cellLatest[key] = servingCellOf(reading)
    }

    /** Null when no sample had RSRP (score and RSRP stats are mandatory in FinderSpot). */
    fun metrics(): SpotMetrics? {
        val scoreStats = FinderMath.metricStats(score) ?: return null
        val rsrpStats = FinderMath.metricStats(rsrp) ?: return null
        return SpotMetrics(
            score = scoreStats,
            rsrp = rsrpStats,
            sinr = FinderMath.metricStats(sinr),
            rsrq = FinderMath.metricStats(rsrq),
        )
    }

    /** The cell seen in most samples (ties: the first seen), with its latest TA. */
    fun servingCell(): ServingCell? {
        val best = cellCounts.entries.maxByOrNull { it.value }?.key ?: return null
        return cellLatest[best]
    }

    companion object {
        private const val UNKNOWN_CELL = "unknown"

        /** For NR_SA the NR leg fills in what the top level lacks; NR_NSA reports its LTE anchor. */
        fun servingCellOf(r: RadioInfo): ServingCell {
            val saLeg = r.nr.takeIf { r.netType == NetType.NR_SA }
            return ServingCell(
                netType = r.netType,
                band = r.band ?: saLeg?.band,
                pci = r.pci ?: saLeg?.pci,
                cellId = r.cellId ?: saLeg?.nci,
                earfcn = r.earfcn ?: saLeg?.arfcn,
                ta = r.ta,
            )
        }
    }
}
