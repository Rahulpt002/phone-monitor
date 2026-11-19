package com.hotspotmonitor.agent.finder

import com.hotspotmonitor.agent.protocol.NetType
import com.hotspotmonitor.agent.protocol.NrLeg
import com.hotspotmonitor.agent.protocol.RadioInfo
import com.hotspotmonitor.agent.protocol.ScoreWeights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinderSamplingTest {
    private fun lte(cellId: Long?, pci: Int?, earfcn: Int? = 39150, rsrp: Int? = -92, sinr: Double? = 13.0, rsrq: Int? = -11) =
        RadioInfo(netType = NetType.LTE, cellId = cellId, pci = pci, earfcn = earfcn, band = 40, ta = 3, rsrp = rsrp, sinr = sinr, rsrq = rsrq)

    @Test
    fun countsServingCellChangesInTheLastFiveMinutes() {
        val c = CellSwitchCounter(windowMs = 300_000)
        assertFalse(c.observe(CellIdentity.of(lte(100, 1)), 0))
        assertFalse(c.observe(CellIdentity.of(lte(100, 1)), 1_000))
        assertTrue(c.observe(CellIdentity.of(lte(200, 2)), 2_000))
        assertTrue(c.observe(CellIdentity.of(lte(100, 1)), 3_000))
        assertEquals(2, c.countRecent(3_000))
        assertEquals(2, c.total())
        // The first switch (t = 2 s) leaves the window at 302 s, the second at 303 s.
        assertEquals(1, c.countRecent(302_000))
        assertEquals(0, c.countRecent(303_000))
        assertEquals(2, c.total())
    }

    @Test
    fun unknownReadingsAndMissingIdsAreNotSwitches() {
        val c = CellSwitchCounter()
        c.observe(CellIdentity.of(lte(100, 1)), 0)
        // Lost the cell for a moment: not a switch, and coming back to the same cell is not one either.
        assertFalse(c.observe(CellIdentity.of(lte(null, null)), 1_000))
        assertFalse(c.observe(CellIdentity.of(lte(100, 1)), 2_000))
        // Cell id missing but same PCI/EARFCN: same cell.
        assertFalse(c.observe(CellIdentity.of(lte(null, 1)), 3_000))
        // Same PCI on another channel: another cell.
        assertTrue(c.observe(CellIdentity.of(lte(null, 1, earfcn = 1300)), 4_000))
        // Net type change on the same anchor cell (LTE -> NR_NSA) is not a cell switch.
        val nsa = lte(100, 1, earfcn = 1300).copy(netType = NetType.NR_NSA, nr = NrLeg(ssRsrp = -95, pci = 400))
        assertFalse(c.observe(CellIdentity.of(nsa), 5_000))
        assertEquals(1, c.countRecent(5_000))
        c.reset()
        assertEquals(0, c.total())
    }

    @Test
    fun nrStandaloneUsesTheNrLegIdentity() {
        val sa = RadioInfo(netType = NetType.NR_SA, nr = NrLeg(ssRsrp = -90, ssSinr = 10.0, ssRsrq = -11, band = 78, arfcn = 636666, pci = 55, nci = 987654321L))
        val id = CellIdentity.of(sa)
        assertEquals(987654321L, id.cellId)
        assertEquals(55, id.pci)
        val cell = SpotAccumulator.servingCellOf(sa)
        assertEquals(78, cell.band)
        assertEquals(636666, cell.earfcn)
        // NSA keeps reporting the LTE anchor.
        val nsa = lte(100, 1).copy(netType = NetType.NR_NSA, band = null, nr = NrLeg(band = 78, pci = 55))
        assertNull(SpotAccumulator.servingCellOf(nsa).band)
        assertEquals(1, SpotAccumulator.servingCellOf(nsa).pci)
    }

    @Test
    fun spotAccumulatorBuildsMetricsAndPicksTheMostCommonCell() {
        val acc = SpotAccumulator(ScoreWeights.DEFAULT)
        assertNull(acc.metrics())
        repeat(3) { acc.add(lte(100, 1, rsrp = -90)) }
        acc.add(lte(200, 2, rsrp = -100))
        acc.add(lte(100, 1, rsrp = null)) // no RSRP: not a sample
        assertEquals(4, acc.samples)
        val m = acc.metrics()
        assertNotNull(m)
        assertEquals(-92.5, m!!.rsrp.avg, 0.0)
        assertEquals(-100.0, m.rsrp.min, 0.0)
        assertEquals(4, m.score.samples)
        assertEquals(13.0, m.sinr!!.avg, 0.0)
        assertEquals(-11.0, m.rsrq!!.avg, 0.0)
        val serving = acc.servingCell()!!
        assertEquals(100L, serving.cellId)
        assertEquals(NetType.LTE, serving.netType)
        assertEquals(3, serving.ta)
    }

    @Test
    fun spotAccumulatorWithoutSinrAndRsrqStillHasScoreAndRsrp() {
        val acc = SpotAccumulator(ScoreWeights.DEFAULT)
        acc.add(lte(100, 1, sinr = null, rsrq = null))
        val m = acc.metrics()!!
        assertNull(m.sinr)
        assertNull(m.rsrq)
        assertEquals(1, m.rsrp.samples)
    }
}
