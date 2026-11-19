package com.hotspotmonitor.agent.telemetry

import com.hotspotmonitor.agent.protocol.NeighbourCell
import com.hotspotmonitor.agent.protocol.NetType
import com.hotspotmonitor.agent.protocol.NrLeg
import com.hotspotmonitor.agent.protocol.RadioInfo
import com.hotspotmonitor.agent.protocol.SimState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Radio model independent of android.telephony, so the selection and network-type rules are
 * unit-tested on the JVM (RadioModelTest). RadioCollector converts CellInfo objects into
 * [CellSnapshot]s; [RadioAssembler] turns them into the protocol RadioInfo (without the score).
 */

/** Radio access technology of one CellInfo. */
internal enum class CellRat { LTE, NR, WCDMA, GSM, CDMA, TDSCDMA }

/** One CellInfo with every unavailable value already turned into null. */
internal data class CellSnapshot(
    val rat: CellRat,
    val registered: Boolean,
    val mccMnc: String? = null,
    val band: Int? = null,
    /** EARFCN / NR-ARFCN / UARFCN / ARFCN */
    val arfcn: Int? = null,
    /** PCI / PSC / BSIC */
    val pci: Int? = null,
    /** ECI / NCI / CID / base station id */
    val cellId: Long? = null,
    /** TAC / LAC / CDMA network id */
    val tac: Int? = null,
    /** RSRP or SS-RSRP */
    val rsrp: Int? = null,
    /** RSRQ or SS-RSRQ */
    val rsrq: Int? = null,
    /** RSSNR or SS-SINR, dB */
    val sinr: Double? = null,
    /** RSSI / dBm for 2G/3G */
    val rssi: Int? = null,
    val cqi: Int? = null,
    val ta: Int? = null,
    val level: Int? = null,
) {
    val hasIdentity: Boolean get() = pci != null || cellId != null
}

/** SS-* values of the NR leg as SignalStrength reports them (NSA has no NR CellInfo on many phones). */
internal data class NrSignal(val ssRsrp: Int?, val ssRsrq: Int?, val ssSinr: Double?)

/** Everything read from the phone for one radio reading. */
internal data class RadioInputs(
    val cells: List<CellSnapshot>,
    val nrSignal: NrSignal? = null,
    /** TelephonyManager.getDataNetworkType(), null when unreadable. */
    val dataNetworkType: Int? = null,
    /** TelephonyDisplayInfo says NR NSA / NR advanced; null when no display info is available. */
    val displayOverrideNr: Boolean? = null,
    /** TelephonyDisplayInfo.getNetworkType() (API 30+), null when unknown. */
    val displayNetworkType: Int? = null,
    /** ServiceState says "nrState=CONNECTED" (API 29 fallback). */
    val nrConnectedHint: Boolean = false,
    val operatorName: String? = null,
    /** TelephonyManager.getNetworkOperator(): MCC+MNC of the registered network. */
    val networkOperator: String? = null,
    val simState: SimState? = null,
    val roaming: Boolean? = null,
    val dataEnabled: Boolean? = null,
    val airplane: Boolean? = null,
    val signalLevel: Int? = null,
)

internal object RadioMath {
    // TelephonyManager.NETWORK_TYPE_* values (stable since API 1..29).
    const val NT_GPRS = 1
    const val NT_EDGE = 2
    const val NT_UMTS = 3
    const val NT_CDMA = 4
    const val NT_EVDO_0 = 5
    const val NT_EVDO_A = 6
    const val NT_1XRTT = 7
    const val NT_HSDPA = 8
    const val NT_HSUPA = 9
    const val NT_HSPA = 10
    const val NT_IDEN = 11
    const val NT_EVDO_B = 12
    const val NT_LTE = 13
    const val NT_EHRPD = 14
    const val NT_HSPAP = 15
    const val NT_GSM = 16
    const val NT_TD_SCDMA = 17
    const val NT_IWLAN = 18
    const val NT_LTE_CA = 19
    const val NT_NR = 20

    /** CellInfo.UNAVAILABLE (Integer.MAX_VALUE) and out-of-range values become null. */
    fun valid(v: Int, min: Int, max: Int): Int? =
        if (v == Int.MAX_VALUE || v == Int.MIN_VALUE || v < min || v > max) null else v

    /** CellInfo.UNAVAILABLE_LONG (Long.MAX_VALUE) and out-of-range values become null. */
    fun validLong(v: Long, min: Long, max: Long): Long? =
        if (v == Long.MAX_VALUE || v == Long.MIN_VALUE || v < min || v > max) null else v

    /**
     * LTE RSSNR in dB. The documented range is -20..30 dB, but some RILs still report tenths of a
     * dB (e.g. 135 for 13.5 dB); values that only fit as tenths are scaled.
     */
    fun rssnrDb(raw: Int): Double? = when {
        raw == Int.MAX_VALUE || raw == Int.MIN_VALUE -> null
        raw in -20..30 -> raw.toDouble()
        raw in -200..300 -> raw / 10.0
        else -> null
    }

    /** "405" + "857" -> "405857"; null when either part is missing. */
    fun mccMnc(mcc: String?, mnc: String?): String? =
        if (mcc.isNullOrBlank() || mnc.isNullOrBlank()) null else mcc + mnc

    /** 3GPP TS 36.101 table 5.7.3-1: downlink EARFCN ranges per E-UTRA band. */
    private val LTE_BANDS: List<Triple<Int, Int, Int>> = listOf(
        Triple(1, 0, 599), Triple(2, 600, 1199), Triple(3, 1200, 1949), Triple(4, 1950, 2399),
        Triple(5, 2400, 2649), Triple(6, 2650, 2749), Triple(7, 2750, 3449), Triple(8, 3450, 3799),
        Triple(9, 3800, 4149), Triple(10, 4150, 4749), Triple(11, 4750, 4949), Triple(12, 5010, 5179),
        Triple(13, 5180, 5279), Triple(14, 5280, 5379), Triple(17, 5730, 5849), Triple(18, 5850, 5999),
        Triple(19, 6000, 6149), Triple(20, 6150, 6449), Triple(21, 6450, 6599), Triple(22, 6600, 7399),
        Triple(23, 7500, 7699), Triple(24, 7700, 8039), Triple(25, 8040, 8689), Triple(26, 8690, 9039),
        Triple(27, 9040, 9209), Triple(28, 9210, 9659), Triple(29, 9660, 9769), Triple(30, 9770, 9869),
        Triple(31, 9870, 9919), Triple(32, 9920, 10359), Triple(33, 36000, 36199), Triple(34, 36200, 36349),
        Triple(35, 36350, 36949), Triple(36, 36950, 37549), Triple(37, 37550, 37749), Triple(38, 37750, 38249),
        Triple(39, 38250, 38649), Triple(40, 38650, 39649), Triple(41, 39650, 41589), Triple(42, 41590, 43589),
        Triple(43, 43590, 45589), Triple(44, 45590, 46589), Triple(45, 46590, 46789), Triple(46, 46790, 54539),
        Triple(47, 54540, 55239), Triple(48, 55240, 56739), Triple(49, 56740, 58239), Triple(50, 58240, 59089),
        Triple(51, 59090, 59139), Triple(52, 59140, 60139), Triple(53, 60140, 60254), Triple(65, 65536, 66435),
        Triple(66, 66436, 67335), Triple(67, 67336, 67535), Triple(68, 67536, 67835), Triple(69, 67836, 68335),
        Triple(70, 68336, 68585), Triple(71, 68586, 68935), Triple(72, 68936, 68985), Triple(73, 68986, 69035),
        Triple(74, 69036, 69465), Triple(75, 69466, 70315), Triple(76, 70316, 70365), Triple(85, 70366, 70545),
        Triple(87, 70546, 70595), Triple(88, 70596, 70645),
    )

    /** LTE band for a downlink EARFCN (API 29 has no CellIdentityLte.getBands()). */
    fun lteBandForEarfcn(earfcn: Int): Int? = LTE_BANDS.firstOrNull { earfcn in it.second..it.third }?.first

    /** Coarse network type from TelephonyManager.getDataNetworkType(). */
    fun netTypeOf(dataNetworkType: Int?): NetType = when (dataNetworkType) {
        NT_LTE, NT_LTE_CA -> NetType.LTE
        NT_NR -> NetType.NR_SA
        NT_UMTS, NT_HSDPA, NT_HSUPA, NT_HSPA, NT_HSPAP -> NetType.WCDMA
        NT_GSM, NT_GPRS, NT_EDGE, NT_IDEN -> NetType.GSM
        NT_CDMA, NT_EVDO_0, NT_EVDO_A, NT_EVDO_B, NT_1XRTT, NT_EHRPD -> NetType.CDMA
        NT_TD_SCDMA -> NetType.TDSCDMA
        else -> NetType.UNKNOWN
    }
}

/** Turns [RadioInputs] into the protocol [RadioInfo] (score is added by the hub). */
internal object RadioAssembler {
    const val MAX_NEIGHBOURS = 12

    fun assemble(input: RadioInputs): RadioInfo {
        val serving = chooseServing(input.cells, input.dataNetworkType, input.networkOperator)
        val netType = netType(serving, input)
        val nrCells = input.cells.filter { it.rat == CellRat.NR }
        val nr = when (netType) {
            NetType.NR_SA -> serving?.takeIf { it.rat == CellRat.NR }?.let(::legOf) ?: buildNrLeg(nrCells, input.nrSignal)
            NetType.NR_NSA -> buildNrLeg(nrCells, input.nrSignal)
            else -> null
        }
        val neighbours = input.cells
            .filter { !it.registered && (it.pci != null || it.arfcn != null) }
            .sortedWith(compareByDescending<CellSnapshot> { it.rsrp ?: it.rssi ?: Int.MIN_VALUE })
            .take(MAX_NEIGHBOURS)
            .map { NeighbourCell(type = neighbourType(it.rat), pci = it.pci, earfcn = it.arfcn, rsrp = it.rsrp, rsrq = it.rsrq) }
        return RadioInfo(
            operator = input.operatorName?.trim()?.ifEmpty { null },
            mccMnc = input.networkOperator?.trim()?.ifEmpty { null } ?: serving?.mccMnc,
            netType = netType,
            band = serving?.band,
            earfcn = serving?.arfcn,
            pci = serving?.pci,
            cellId = serving?.cellId,
            tac = serving?.tac,
            rsrp = serving?.rsrp,
            rsrq = serving?.rsrq,
            sinr = serving?.sinr,
            rssi = serving?.rssi,
            cqi = serving?.cqi,
            ta = serving?.ta,
            signalLevel = input.signalLevel ?: serving?.level,
            nr = nr,
            neighbours = neighbours.ifEmpty { null },
            simState = input.simState,
            roaming = input.roaming,
            dataEnabled = input.dataEnabled,
            airplane = input.airplane,
        )
    }

    /**
     * The serving cell: a registered cell, preferring the operator of the data SIM (dual SIM), then
     * NR when the data network is NR (SA) or LTE otherwise, then 3G/2G, then cells with an identity.
     */
    fun chooseServing(cells: List<CellSnapshot>, dataNetworkType: Int?, networkOperator: String?): CellSnapshot? {
        var registered = cells.filter { it.registered }
        if (registered.isEmpty()) return null
        if (!networkOperator.isNullOrBlank()) {
            val sameOperator = registered.filter { it.mccMnc == networkOperator }
            if (sameOperator.isNotEmpty()) registered = sameOperator
        }
        val order = if (dataNetworkType == RadioMath.NT_NR) {
            listOf(CellRat.NR, CellRat.LTE, CellRat.WCDMA, CellRat.TDSCDMA, CellRat.GSM, CellRat.CDMA)
        } else {
            listOf(CellRat.LTE, CellRat.NR, CellRat.WCDMA, CellRat.TDSCDMA, CellRat.GSM, CellRat.CDMA)
        }
        return registered.sortedWith(
            compareBy<CellSnapshot> { order.indexOf(it.rat) }
                .thenBy { if (it.hasIdentity) 0 else 1 }
                .thenBy { if (it.rsrp != null || it.rssi != null) 0 else 1 },
        ).first()
    }

    fun netType(serving: CellSnapshot?, input: RadioInputs): NetType {
        val dataType = input.displayNetworkType ?: input.dataNetworkType
        if (serving == null) {
            val coarse = RadioMath.netTypeOf(dataType)
            return if (coarse == NetType.LTE && (input.displayOverrideNr == true || input.nrConnectedHint)) NetType.NR_NSA else coarse
        }
        return when (serving.rat) {
            CellRat.NR -> NetType.NR_SA
            CellRat.LTE -> when {
                input.displayOverrideNr == true -> NetType.NR_NSA
                input.displayOverrideNr == false -> NetType.LTE
                // No display info (API 29, or not registered yet): fall back to other NSA hints.
                input.nrConnectedHint -> NetType.NR_NSA
                dataType == RadioMath.NT_NR -> NetType.NR_NSA
                input.nrSignal?.ssRsrp != null -> NetType.NR_NSA
                else -> NetType.LTE
            }
            CellRat.WCDMA -> NetType.WCDMA
            CellRat.GSM -> NetType.GSM
            CellRat.CDMA -> NetType.CDMA
            CellRat.TDSCDMA -> NetType.TDSCDMA
        }
    }

    /** The NR leg: identity from the best NR CellInfo, SS-* values from SignalStrength when present. */
    fun buildNrLeg(nrCells: List<CellSnapshot>, signal: NrSignal?): NrLeg? {
        val cell = nrCells.sortedWith(
            compareBy<CellSnapshot> { if (it.registered) 0 else 1 }.thenByDescending { it.rsrp ?: Int.MIN_VALUE },
        ).firstOrNull()
        val useSignal = signal?.ssRsrp != null
        val leg = NrLeg(
            ssRsrp = if (useSignal) signal?.ssRsrp else cell?.rsrp,
            ssRsrq = if (useSignal) signal?.ssRsrq else cell?.rsrq,
            ssSinr = if (useSignal) signal?.ssSinr else cell?.sinr,
            band = cell?.band,
            arfcn = cell?.arfcn,
            pci = cell?.pci,
            nci = cell?.cellId,
        )
        return leg.takeUnless { it == NrLeg() }
    }

    private fun legOf(cell: CellSnapshot) = NrLeg(
        ssRsrp = cell.rsrp, ssRsrq = cell.rsrq, ssSinr = cell.sinr,
        band = cell.band, arfcn = cell.arfcn, pci = cell.pci, nci = cell.cellId,
    )

    private fun neighbourType(rat: CellRat): NetType = when (rat) {
        CellRat.LTE -> NetType.LTE
        CellRat.NR -> NetType.NR_SA
        CellRat.WCDMA -> NetType.WCDMA
        CellRat.GSM -> NetType.GSM
        CellRat.CDMA -> NetType.CDMA
        CellRat.TDSCDMA -> NetType.TDSCDMA
    }
}

/** The identity of a serving cell, as used by cell_change events (`{pci, cell_id, band}`). */
@Serializable
internal data class CellRef(
    val pci: Int? = null,
    @SerialName("cell_id") val cellId: Long? = null,
    val band: Int? = null,
    val earfcn: Int? = null,
    @SerialName("net_type") val netType: NetType? = null,
) {
    /**
     * Same physical cell. Cell ids decide when both readings have one (PCIs repeat across a city);
     * otherwise PCI plus channel, because some modems drop the cell id while the screen is off.
     */
    fun sameCellAs(other: CellRef): Boolean {
        if (cellId != null && other.cellId != null) {
            return cellId == other.cellId && (pci == null || other.pci == null || pci == other.pci)
        }
        if (pci == null || other.pci == null) return false
        return pci == other.pci && (earfcn == null || other.earfcn == null || earfcn == other.earfcn)
    }

    companion object {
        /** Null when the reading has no serving cell identity (no service, airplane mode). */
        fun of(radio: RadioInfo): CellRef? {
            if (radio.pci == null && radio.cellId == null) return null
            return CellRef(pci = radio.pci, cellId = radio.cellId, band = radio.band, earfcn = radio.earfcn, netType = radio.netType)
        }
    }
}

/** A confirmed change from [from] to [to]. */
internal data class Change<T>(val from: T, val to: T)

/**
 * Debounces a value that flips between readings (serving cell, network type): a new value must be
 * seen in [required] consecutive readings before it replaces the confirmed one. Null readings (no
 * data) are ignored. Not thread-safe; the hub calls it under its radio lock.
 */
internal class ChangeDebouncer<T : Any>(
    private val required: Int = 2,
    private val same: (T, T) -> Boolean = { a, b -> a == b },
) {
    init {
        require(required >= 1) { "required must be >= 1" }
    }

    var confirmed: T? = null
        private set

    private var pending: T? = null
    private var pendingCount = 0

    /** Starts from a value remembered from an earlier run, without reporting it. */
    fun seed(value: T?) {
        confirmed = value
        pending = null
        pendingCount = 0
    }

    /** Feeds one reading; returns the change when one is confirmed. */
    fun offer(value: T?): Change<T>? {
        if (value == null) return null
        val current = confirmed
        if (current == null) {
            confirmed = value
            return null
        }
        if (same(current, value)) {
            // Keep the freshest details (e.g. a cell id that was missing before).
            confirmed = value
            pending = null
            pendingCount = 0
            return null
        }
        val p = pending
        if (p != null && same(p, value)) {
            pendingCount++
            pending = value
        } else {
            pending = value
            pendingCount = 1
        }
        if (pendingCount >= required) {
            confirmed = value
            pending = null
            pendingCount = 0
            return Change(current, value)
        }
        return null
    }
}
