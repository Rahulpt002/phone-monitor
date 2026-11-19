package com.hotspotmonitor.agent.telemetry

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoTdscdma
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import com.hotspotmonitor.agent.protocol.RadioInfo
import com.hotspotmonitor.agent.protocol.SimState
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Reads the serving cell, NR leg, neighbours and SIM / data state (plan 6.2 "Radio") from
 * TelephonyManager. Every value the modem does not report is null. Display info (NR NSA icon
 * state) comes from TelephonyCallback on API 31+, PhoneStateListener on API 30, and ServiceState /
 * SignalStrength hints on API 29.
 */
internal class RadioCollector(private val app: Context, private val failures: FailureLog) {
    private val baseTm: TelephonyManager? = app.getSystemService(TelephonyManager::class.java)

    @Volatile
    private var displayOverride: Int? = null

    @Volatile
    private var displayNetworkType: Int? = null

    private var listenerSubId: Int? = null
    private var listener: Any? = null

    /** TelephonyManager of the default data SIM (dual-SIM phones), else the default one. */
    private fun dataTm(): Pair<TelephonyManager, Int?>? {
        val base = baseTm ?: return null
        val sub = try {
            SubscriptionManager.getDefaultDataSubscriptionId()
        } catch (e: Exception) {
            SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
        if (!SubscriptionManager.isValidSubscriptionId(sub)) return base to null
        return try {
            base.createForSubscriptionId(sub) to sub
        } catch (e: Exception) {
            base to null
        }
    }

    /**
     * Registers for display-info changes (API 30+). Safe to call on every reading: it only acts
     * when not registered yet (permissions may be granted after start) or the data SIM changed.
     */
    @Synchronized
    fun ensureDisplayListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val (tm, sub) = dataTm() ?: return
        if (listener != null && listenerSubId == sub) return
        stopListening()
        val registered = failures.soft("display_info_listener") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = DisplayInfoCallback { onDisplayInfo(it) }
                tm.registerTelephonyCallback(DirectExecutor, cb)
                listener = cb
            } else {
                if (!app.hasPermission(Manifest.permission.READ_PHONE_STATE)) {
                    throw SecurityException("READ_PHONE_STATE not granted yet")
                }
                val l = DisplayInfoListenerR { onDisplayInfo(it) }
                @Suppress("DEPRECATION")
                tm.listen(l, PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED)
                listener = l
            }
            listenerSubId = sub
            true
        }
        if (registered != true) listener = null
    }

    @Synchronized
    @Suppress("DEPRECATION")
    fun stopListening() {
        val l = listener ?: return
        listener = null
        val tm = baseTm ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && l is TelephonyCallback) {
                tm.unregisterTelephonyCallback(l)
            } else if (l is PhoneStateListener) {
                @Suppress("DEPRECATION")
                tm.listen(l, PhoneStateListener.LISTEN_NONE)
            }
        } catch (e: Exception) {
            // The phone process forgets listeners of a dead registration anyway.
        }
        displayOverride = null
        displayNetworkType = null
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun onDisplayInfo(info: TelephonyDisplayInfo) {
        displayOverride = info.overrideNetworkType
        displayNetworkType = info.networkType
    }

    /**
     * One reading. [fresh] asks the modem for new cell info (requestCellInfoUpdate) instead of the
     * cached list. Null only when the phone has no telephony service at all.
     */
    // Permissions are checked with hasPermission() and SecurityException is caught by failures.soft.
    @SuppressLint("MissingPermission")
    suspend fun read(fresh: Boolean): RadioInfo? {
        val (tm, _) = dataTm() ?: run {
            failures.fail("telephony", "No TelephonyManager on this device")
            return null
        }
        ensureDisplayListener()
        val cells = readCells(tm, fresh).mapNotNull { failures.soft("cell_parse") { snapshot(it) } }
        val signal: SignalStrength? = failures.soft("signal_strength") { tm.signalStrength }
        val hasPhoneState = app.hasPermission(Manifest.permission.READ_PHONE_STATE)
        val dataType = if (hasPhoneState) failures.soft("data_network_type") { tm.dataNetworkType } else null
        val override = displayOverride
        val displayOverrideNr = override?.let { isNrOverride(it) }
        val nrHint = if (displayOverrideNr == null && hasPhoneState) {
            failures.soft("service_state") { tm.serviceState?.toString()?.contains("nrState=CONNECTED") } ?: false
        } else {
            false
        }
        val inputs = RadioInputs(
            cells = cells,
            nrSignal = signal?.let { nrSignalOf(it) },
            dataNetworkType = dataType,
            displayOverrideNr = displayOverrideNr,
            displayNetworkType = displayNetworkType?.takeIf { it != 0 },
            nrConnectedHint = nrHint,
            operatorName = failures.soft("operator") { tm.networkOperatorName?.ifBlank { null } ?: tm.simOperatorName },
            networkOperator = failures.soft("network_operator") { tm.networkOperator },
            simState = failures.soft("sim_state") { simStateOf(tm.simState) },
            roaming = failures.soft("roaming") { tm.isNetworkRoaming },
            dataEnabled = if (hasPhoneState) failures.soft("data_enabled") { tm.isDataEnabled } else null,
            airplane = failures.soft("airplane") {
                Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
            },
            signalLevel = signal?.let { s -> failures.soft("signal_level") { s.level } }?.let { RadioMath.valid(it, 0, 4) },
        )
        return RadioAssembler.assemble(inputs)
    }

    @SuppressLint("MissingPermission") // ACCESS_FINE_LOCATION is checked first; soft() catches SecurityException.
    private suspend fun readCells(tm: TelephonyManager, fresh: Boolean): List<CellInfo> {
        if (!app.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            failures.fail("cell_info_permission", "Location permission missing: no cell info until it is granted")
            return emptyList()
        }
        failures.ok("cell_info_permission")
        if (fresh) {
            val updated = failures.soft("cell_info_update") { requestUpdate(tm) }
            if (updated != null) return updated
        }
        val cached = failures.soft("cell_info") { tm.allCellInfo }.orEmpty()
        if (cached.isEmpty()) failures.fail("cell_info_empty", "No cell info (location off, airplane mode or no SIM)")
        else failures.ok("cell_info_empty")
        return cached
    }

    @SuppressLint("MissingPermission") // Only called from readCells, after the location permission check.
    private suspend fun requestUpdate(tm: TelephonyManager): List<CellInfo>? = withTimeoutOrNull(CELL_UPDATE_TIMEOUT_MS) {
        suspendCancellableCoroutine<List<CellInfo>?> { cont ->
            tm.requestCellInfoUpdate(DirectExecutor, object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                    if (cont.isActive) cont.resume(cellInfo.toList())
                }

                override fun onError(errorCode: Int, detail: Throwable?) {
                    if (cont.isActive) cont.resume(null)
                }
            })
        }
    }

    private fun nrSignalOf(signal: SignalStrength): NrSignal? {
        val nr = failures.soft("nr_signal") {
            signal.getCellSignalStrengths(CellSignalStrengthNr::class.java)
                .firstOrNull { RadioMath.valid(it.ssRsrp, -156, -31) != null }
        } ?: return null
        return NrSignal(
            ssRsrp = RadioMath.valid(nr.ssRsrp, -156, -31),
            ssRsrq = RadioMath.valid(nr.ssRsrq, -43, 20),
            ssSinr = RadioMath.valid(nr.ssSinr, -23, 40)?.toDouble(),
        )
    }

    private fun snapshot(info: CellInfo): CellSnapshot? = when (info) {
        is CellInfoLte -> lte(info)
        is CellInfoNr -> nr(info)
        is CellInfoWcdma -> wcdma(info)
        is CellInfoGsm -> gsm(info)
        is CellInfoTdscdma -> tdscdma(info)
        is CellInfoCdma -> cdma(info)
        else -> null
    }

    private fun lte(info: CellInfoLte): CellSnapshot {
        val id = info.cellIdentity
        val ss = info.cellSignalStrength
        val earfcn = RadioMath.valid(id.earfcn, 0, 262_143)
        val band = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) bandsOf { id.bands } else null)
            ?: earfcn?.let { RadioMath.lteBandForEarfcn(it) }
        return CellSnapshot(
            rat = CellRat.LTE,
            registered = info.isRegistered,
            mccMnc = RadioMath.mccMnc(id.mccString, id.mncString),
            band = band,
            arfcn = earfcn,
            pci = RadioMath.valid(id.pci, 0, 503),
            cellId = RadioMath.valid(id.ci, 0, 268_435_455)?.toLong(),
            tac = RadioMath.valid(id.tac, 0, 65_535),
            rsrp = RadioMath.valid(ss.rsrp, -140, -43),
            rsrq = RadioMath.valid(ss.rsrq, -34, 3),
            sinr = RadioMath.rssnrDb(ss.rssnr),
            rssi = RadioMath.valid(ss.rssi, -113, -51),
            cqi = RadioMath.valid(ss.cqi, 0, 15),
            ta = RadioMath.valid(ss.timingAdvance, 0, 1282),
            level = RadioMath.valid(ss.level, 0, 4),
        )
    }

    private fun nr(info: CellInfoNr): CellSnapshot {
        val id = info.cellIdentity as? CellIdentityNr
        val ss = info.cellSignalStrength as? CellSignalStrengthNr
        return CellSnapshot(
            rat = CellRat.NR,
            registered = info.isRegistered,
            mccMnc = id?.let { RadioMath.mccMnc(it.mccString, it.mncString) },
            band = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) id?.let { i -> bandsOf { i.bands } } else null,
            arfcn = id?.let { RadioMath.valid(it.nrarfcn, 0, 3_279_165) },
            pci = id?.let { RadioMath.valid(it.pci, 0, 1007) },
            cellId = id?.let { RadioMath.validLong(it.nci, 0, 68_719_476_735L) },
            tac = id?.let { RadioMath.valid(it.tac, 0, 16_777_215) },
            rsrp = ss?.let { RadioMath.valid(it.ssRsrp, -156, -31) },
            rsrq = ss?.let { RadioMath.valid(it.ssRsrq, -43, 20) },
            sinr = ss?.let { RadioMath.valid(it.ssSinr, -23, 40)?.toDouble() },
            level = ss?.let { RadioMath.valid(it.level, 0, 4) },
        )
    }

    private fun wcdma(info: CellInfoWcdma): CellSnapshot {
        val id = info.cellIdentity
        val ss = info.cellSignalStrength
        return CellSnapshot(
            rat = CellRat.WCDMA,
            registered = info.isRegistered,
            mccMnc = RadioMath.mccMnc(id.mccString, id.mncString),
            arfcn = RadioMath.valid(id.uarfcn, 0, 16_383),
            pci = RadioMath.valid(id.psc, 0, 511),
            cellId = RadioMath.valid(id.cid, 0, 268_435_455)?.toLong(),
            tac = RadioMath.valid(id.lac, 0, 65_535),
            rssi = RadioMath.valid(ss.dbm, -120, -24),
            level = RadioMath.valid(ss.level, 0, 4),
        )
    }

    private fun gsm(info: CellInfoGsm): CellSnapshot {
        val id = info.cellIdentity
        val ss = info.cellSignalStrength
        return CellSnapshot(
            rat = CellRat.GSM,
            registered = info.isRegistered,
            mccMnc = RadioMath.mccMnc(id.mccString, id.mncString),
            arfcn = RadioMath.valid(id.arfcn, 0, 1023),
            pci = RadioMath.valid(id.bsic, 0, 63),
            cellId = RadioMath.valid(id.cid, 0, 65_535)?.toLong(),
            tac = RadioMath.valid(id.lac, 0, 65_535),
            rssi = RadioMath.valid(ss.dbm, -113, -51),
            ta = RadioMath.valid(ss.timingAdvance, 0, 219),
            level = RadioMath.valid(ss.level, 0, 4),
        )
    }

    private fun tdscdma(info: CellInfoTdscdma): CellSnapshot {
        val id = info.cellIdentity
        val ss = info.cellSignalStrength
        return CellSnapshot(
            rat = CellRat.TDSCDMA,
            registered = info.isRegistered,
            mccMnc = RadioMath.mccMnc(id.mccString, id.mncString),
            arfcn = RadioMath.valid(id.uarfcn, 0, 65_535),
            pci = RadioMath.valid(id.cpid, 0, 127),
            cellId = RadioMath.valid(id.cid, 0, 268_435_455)?.toLong(),
            tac = RadioMath.valid(id.lac, 0, 65_535),
            rssi = RadioMath.valid(ss.dbm, -120, -24),
            level = RadioMath.valid(ss.level, 0, 4),
        )
    }

    private fun cdma(info: CellInfoCdma): CellSnapshot {
        val id = info.cellIdentity
        val ss = info.cellSignalStrength
        return CellSnapshot(
            rat = CellRat.CDMA,
            registered = info.isRegistered,
            cellId = RadioMath.valid(id.basestationId, 0, 65_535)?.toLong(),
            tac = RadioMath.valid(id.networkId, 0, 65_535),
            rssi = RadioMath.valid(ss.dbm, -120, -24),
            level = RadioMath.valid(ss.level, 0, 4),
        )
    }

    /** First band from CellIdentity.getBands() (API 30+), null before API 30 or when empty. */
    private inline fun bandsOf(read: () -> IntArray?): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return failures.soft("cell_bands") { read()?.firstOrNull { it > 0 } }
    }

    private fun isNrOverride(override: Int): Boolean = override == OVERRIDE_NR_NSA ||
        override == OVERRIDE_NR_NSA_MMWAVE || override == OVERRIDE_NR_ADVANCED

    private fun simStateOf(state: Int): SimState = when (state) {
        TelephonyManager.SIM_STATE_READY, SIM_STATE_LOADED -> SimState.READY
        TelephonyManager.SIM_STATE_ABSENT -> SimState.ABSENT
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> SimState.PIN_REQUIRED
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> SimState.PUK_REQUIRED
        TelephonyManager.SIM_STATE_NETWORK_LOCKED, TelephonyManager.SIM_STATE_PERM_DISABLED,
        TelephonyManager.SIM_STATE_CARD_RESTRICTED -> SimState.LOCKED
        TelephonyManager.SIM_STATE_NOT_READY, SIM_STATE_PRESENT -> SimState.NOT_READY
        else -> SimState.UNKNOWN
    }

    private companion object {
        const val CELL_UPDATE_TIMEOUT_MS = 3_000L

        // TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_* (API 30/31).
        const val OVERRIDE_NR_NSA = 3
        const val OVERRIDE_NR_NSA_MMWAVE = 4
        const val OVERRIDE_NR_ADVANCED = 5

        // TelephonyManager.SIM_STATE_LOADED / SIM_STATE_PRESENT are @SystemApi.
        const val SIM_STATE_LOADED = 10
        const val SIM_STATE_PRESENT = 11
    }
}

/** API 31+: display info through TelephonyCallback. Only loaded on API 31+. */
@RequiresApi(Build.VERSION_CODES.S)
private class DisplayInfoCallback(
    private val onInfo: (TelephonyDisplayInfo) -> Unit,
) : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
    override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) = onInfo(telephonyDisplayInfo)
}

/** API 30: display info through PhoneStateListener (the Executor constructor needs no Looper). */
@Suppress("DEPRECATION")
@RequiresApi(Build.VERSION_CODES.R)
private class DisplayInfoListenerR(
    private val onInfo: (TelephonyDisplayInfo) -> Unit,
) : PhoneStateListener(DirectExecutor) {
    @Deprecated("Deprecated in Java")
    override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) = onInfo(telephonyDisplayInfo)
}
