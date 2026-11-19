package com.hotspotmonitor.agent.telemetry

import com.hotspotmonitor.agent.protocol.NetType
import com.hotspotmonitor.agent.protocol.NrLeg
import com.hotspotmonitor.agent.protocol.RadioInfo
import com.hotspotmonitor.agent.protocol.SimState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioModelTest {
    private val lteServing = CellSnapshot(
        rat = CellRat.LTE, registered = true, mccMnc = "405857", band = 3, arfcn = 1350, pci = 101,
        cellId = 12345678, tac = 7, rsrp = -95, rsrq = -11, sinr = 12.0, rssi = -65, cqi = 9, ta = 3, level = 3,
    )

    @Test
    fun unavailableValuesBecomeNull() {
        assertNull(RadioMath.valid(Int.MAX_VALUE, -140, -43))
        assertNull(RadioMath.valid(-30, -140, -43))
        assertEquals(-95, RadioMath.valid(-95, -140, -43))
        assertNull(RadioMath.validLong(Long.MAX_VALUE, 0, 68_719_476_735L))
        assertEquals(42L, RadioMath.validLong(42, 0, 68_719_476_735L))
    }

    @Test
    fun rssnrInTenthsIsScaled() {
        assertEquals(13.0, RadioMath.rssnrDb(13)!!, 0.0)
        assertEquals(13.5, RadioMath.rssnrDb(135)!!, 0.0)
        assertEquals(-15.0, RadioMath.rssnrDb(-150)!!, 0.0)
        assertNull(RadioMath.rssnrDb(Int.MAX_VALUE))
        assertNull(RadioMath.rssnrDb(5000))
    }

    @Test
    fun earfcnMapsToLteBand() {
        assertEquals(1, RadioMath.lteBandForEarfcn(300))
        assertEquals(3, RadioMath.lteBandForEarfcn(1350))
        assertEquals(5, RadioMath.lteBandForEarfcn(2450))
        assertEquals(8, RadioMath.lteBandForEarfcn(3600))
        assertEquals(20, RadioMath.lteBandForEarfcn(6300))
        assertEquals(28, RadioMath.lteBandForEarfcn(9410))
        assertEquals(40, RadioMath.lteBandForEarfcn(39150))
        assertEquals(41, RadioMath.lteBandForEarfcn(40620))
        assertEquals(66, RadioMath.lteBandForEarfcn(66786))
        assertNull(RadioMath.lteBandForEarfcn(5000)) // gap between bands 11 and 12
    }

    @Test
    fun dataNetworkTypeMapsToNetType() {
        assertEquals(NetType.LTE, RadioMath.netTypeOf(RadioMath.NT_LTE))
        assertEquals(NetType.LTE, RadioMath.netTypeOf(RadioMath.NT_LTE_CA))
        assertEquals(NetType.NR_SA, RadioMath.netTypeOf(RadioMath.NT_NR))
        assertEquals(NetType.WCDMA, RadioMath.netTypeOf(RadioMath.NT_HSPAP))
        assertEquals(NetType.GSM, RadioMath.netTypeOf(RadioMath.NT_EDGE))
        assertEquals(NetType.UNKNOWN, RadioMath.netTypeOf(null))
        assertEquals(NetType.UNKNOWN, RadioMath.netTypeOf(RadioMath.NT_IWLAN))
    }

    @Test
    fun servingCellPrefersDataSimAndLte() {
        val otherSim = lteServing.copy(mccMnc = "40410", pci = 7, rsrp = -70)
        val nr = CellSnapshot(rat = CellRat.NR, registered = true, mccMnc = "405857", pci = 500, rsrp = -80)
        val wcdma = CellSnapshot(rat = CellRat.WCDMA, registered = true, mccMnc = "405857", pci = 33)
        val neighbour = lteServing.copy(registered = false, pci = 200)
        val cells = listOf(neighbour, otherSim, wcdma, nr, lteServing)
        assertEquals(lteServing, RadioAssembler.chooseServing(cells, RadioMath.NT_LTE, "405857"))
        assertEquals(nr, RadioAssembler.chooseServing(cells, RadioMath.NT_NR, "405857"))
        assertEquals(otherSim, RadioAssembler.chooseServing(cells, RadioMath.NT_LTE, "40410"))
        assertNull(RadioAssembler.chooseServing(listOf(neighbour), RadioMath.NT_LTE, null))
    }

    @Test
    fun netTypeFollowsDisplayInfoThenHints() {
        val base = RadioInputs(cells = listOf(lteServing), dataNetworkType = RadioMath.NT_LTE)
        assertEquals(NetType.LTE, RadioAssembler.assemble(base).netType)
        assertEquals(NetType.NR_NSA, RadioAssembler.assemble(base.copy(displayOverrideNr = true)).netType)
        // Display info says "no NR" -> trust it over a lingering NR signal.
        val withSignal = base.copy(nrSignal = NrSignal(-90, -11, 15.0))
        assertEquals(NetType.LTE, RadioAssembler.assemble(withSignal.copy(displayOverrideNr = false)).netType)
        // API 29: no display info, NR leg in SignalStrength -> NSA.
        assertEquals(NetType.NR_NSA, RadioAssembler.assemble(withSignal).netType)
        assertEquals(NetType.NR_NSA, RadioAssembler.assemble(base.copy(nrConnectedHint = true)).netType)
        assertEquals(NetType.NR_NSA, RadioAssembler.assemble(base.copy(dataNetworkType = RadioMath.NT_NR)).netType)

        val saCell = CellSnapshot(rat = CellRat.NR, registered = true, band = 78, arfcn = 636666, pci = 900, cellId = 12345678901, rsrp = -85, rsrq = -10, sinr = 18.0)
        val sa = RadioAssembler.assemble(RadioInputs(cells = listOf(saCell), dataNetworkType = RadioMath.NT_NR))
        assertEquals(NetType.NR_SA, sa.netType)
        assertEquals(-85, sa.rsrp)
        assertEquals(78, sa.band)
        assertEquals(NrLeg(ssRsrp = -85, ssRsrq = -10, ssSinr = 18.0, band = 78, arfcn = 636666, pci = 900, nci = 12345678901), sa.nr)

        assertEquals(NetType.WCDMA, RadioAssembler.assemble(RadioInputs(cells = emptyList(), dataNetworkType = RadioMath.NT_UMTS)).netType)
        assertEquals(NetType.UNKNOWN, RadioAssembler.assemble(RadioInputs(cells = emptyList())).netType)
    }

    @Test
    fun nsaLegTakesSignalFromSignalStrengthAndIdentityFromNrCell() {
        val nrCell = CellSnapshot(rat = CellRat.NR, registered = false, band = 78, arfcn = 636666, pci = 321, rsrp = -100)
        val info = RadioAssembler.assemble(
            RadioInputs(cells = listOf(lteServing, nrCell), nrSignal = NrSignal(-88, -12, 9.0), dataNetworkType = RadioMath.NT_LTE, displayOverrideNr = true),
        )
        assertEquals(NetType.NR_NSA, info.netType)
        assertEquals(NrLeg(ssRsrp = -88, ssRsrq = -12, ssSinr = 9.0, band = 78, arfcn = 636666, pci = 321), info.nr)
        // LTE anchor stays the top-level serving cell.
        assertEquals(101, info.pci)
        assertEquals(-95, info.rsrp)
        assertNull(RadioAssembler.buildNrLeg(emptyList(), null))
    }

    @Test
    fun assemblesFieldsAndNeighbours() {
        val neighbours = (1..20).map { i -> lteServing.copy(registered = false, pci = i, rsrp = -100 - i, cellId = null) }
        val info = RadioAssembler.assemble(
            RadioInputs(
                cells = neighbours + lteServing,
                dataNetworkType = RadioMath.NT_LTE,
                operatorName = " Jio 4G ",
                networkOperator = "405857",
                simState = SimState.READY,
                roaming = false,
                dataEnabled = true,
                airplane = false,
                signalLevel = 2,
            ),
        )
        assertEquals("Jio 4G", info.operator)
        assertEquals("405857", info.mccMnc)
        assertEquals(3, info.band)
        assertEquals(1350, info.earfcn)
        assertEquals(12345678L, info.cellId)
        assertEquals(12.0, info.sinr!!, 0.0)
        assertEquals(2, info.signalLevel)
        assertEquals(RadioAssembler.MAX_NEIGHBOURS, info.neighbours!!.size)
        assertEquals(1, info.neighbours!!.first().pci) // strongest first
        assertEquals(NetType.LTE, info.neighbours!!.first().type)
        assertNull(info.nr)
        assertNull(info.score)

        val empty = RadioAssembler.assemble(RadioInputs(cells = emptyList(), airplane = true))
        assertNull(empty.neighbours)
        assertNull(empty.pci)
        assertEquals(true, empty.airplane)
    }

    @Test
    fun cellIdentityComparison() {
        val a = CellRef(pci = 101, cellId = 5, band = 3, earfcn = 1350)
        assertTrue(a.sameCellAs(a.copy(band = null)))
        assertFalse(a.sameCellAs(a.copy(cellId = 6)))
        // Missing cell id: PCI + channel decide.
        assertTrue(a.sameCellAs(a.copy(cellId = null)))
        assertFalse(a.sameCellAs(a.copy(cellId = null, earfcn = 3600)))
        assertFalse(a.copy(cellId = null).sameCellAs(a.copy(cellId = null, pci = 102)))
        assertNull(CellRef.of(RadioInfo(netType = NetType.LTE)))
        assertEquals(CellRef(pci = 1, cellId = null, band = 3, earfcn = 1350, netType = NetType.LTE), CellRef.of(RadioInfo(netType = NetType.LTE, pci = 1, band = 3, earfcn = 1350)))
    }

    @Test
    fun cellChangeNeedsTwoReadings() {
        val d = ChangeDebouncer<CellRef>(2) { x, y -> x.sameCellAs(y) }
        val a = CellRef(pci = 1, cellId = 10)
        val b = CellRef(pci = 2, cellId = 20)
        assertNull(d.offer(a)) // first reading only sets the state
        assertNull(d.offer(b)) // one reading of B: not yet
        assertNull(d.offer(a)) // flapped back: pending B discarded
        assertNull(d.offer(b))
        assertNull(d.offer(null)) // no service: ignored
        val change = d.offer(b)!!
        assertEquals(a, change.from)
        assertEquals(b, change.to)
        assertEquals(b, d.confirmed)
        assertNull(d.offer(b))
    }

    @Test
    fun debouncerSeedReportsChangeAcrossRestart() {
        val d = ChangeDebouncer<NetType>(2)
        d.seed(NetType.LTE)
        assertNull(d.offer(NetType.NR_NSA))
        assertEquals(Change(NetType.LTE, NetType.NR_NSA), d.offer(NetType.NR_NSA))
        val single = ChangeDebouncer<NetType>(1)
        assertNull(single.offer(NetType.LTE))
        assertEquals(Change(NetType.LTE, NetType.WCDMA), single.offer(NetType.WCDMA))
    }
}
