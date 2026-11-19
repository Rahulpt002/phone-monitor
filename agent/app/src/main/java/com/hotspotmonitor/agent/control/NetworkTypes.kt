package com.hotspotmonitor.agent.control

import com.hotspotmonitor.agent.protocol.NetworkMode

/**
 * Allowed-network-types bitmasks as used by `cmd phone set-allowed-network-types-for-users`
 * (TelephonyManager.NETWORK_TYPE_BITMASK_*: bit (type - 1) for each TelephonyManager.NETWORK_TYPE_*).
 * The constants are spelled out because NETWORK_TYPE_BITMASK_* only exist from API 30 and the
 * values are fixed by the platform.
 */
internal object NetworkTypes {
    const val GPRS = 1
    const val EDGE = 2
    const val UMTS = 3
    const val CDMA = 4
    const val EVDO_0 = 5
    const val EVDO_A = 6
    const val RTT_1X = 7
    const val HSDPA = 8
    const val HSUPA = 9
    const val HSPA = 10
    const val IDEN = 11
    const val EVDO_B = 12
    const val LTE = 13
    const val EHRPD = 14
    const val HSPAP = 15
    const val GSM = 16
    const val TD_SCDMA = 17
    const val IWLAN = 18
    const val LTE_CA = 19
    const val NR = 20

    fun bit(networkType: Int): Long = 1L shl (networkType - 1)

    val GSM_FAMILY: Long = bit(GPRS) or bit(EDGE) or bit(GSM)
    val WCDMA_FAMILY: Long = bit(UMTS) or bit(HSDPA) or bit(HSUPA) or bit(HSPA) or bit(HSPAP) or bit(TD_SCDMA)
    val CDMA_FAMILY: Long = bit(CDMA) or bit(EVDO_0) or bit(EVDO_A) or bit(EVDO_B) or bit(RTT_1X) or bit(EHRPD)
    val LTE_FAMILY: Long = bit(LTE) or bit(LTE_CA)
    val NR_FAMILY: Long = bit(NR)
    val ALL: Long = GSM_FAMILY or WCDMA_FAMILY or CDMA_FAMILY or LTE_FAMILY or NR_FAMILY or bit(IWLAN)

    /** Names printed by TelephonyManager.convertNetworkTypeBitmaskToString (getNetworkTypeName), upper-cased. */
    private val NAMES: Map<String, Int> = mapOf(
        "GPRS" to GPRS, "EDGE" to EDGE, "UMTS" to UMTS, "CDMA" to CDMA,
        "CDMA - EVDO REV. 0" to EVDO_0, "CDMA - EVDO REV. A" to EVDO_A, "CDMA - 1XRTT" to RTT_1X,
        "HSDPA" to HSDPA, "HSUPA" to HSUPA, "HSPA" to HSPA, "IDEN" to IDEN, "CDMA - EVDO REV. B" to EVDO_B,
        "LTE" to LTE, "CDMA - EHRPD" to EHRPD, "HSPA+" to HSPAP, "GSM" to GSM, "TD_SCDMA" to TD_SCDMA,
        "IWLAN" to IWLAN, "LTE_CA" to LTE_CA, "NR" to NR,
    )

    private val BINARY = Regex("^[01]{8,64}$")
    private val DECIMAL = Regex("^[0-9]{1,19}$")

    fun bitmaskFor(mode: NetworkMode): Long = when (mode) {
        NetworkMode.AUTO -> ALL
        NetworkMode.NR_LTE -> NR_FAMILY or LTE_FAMILY
        NetworkMode.LTE_ONLY -> LTE_FAMILY
        NetworkMode.LTE_WCDMA -> LTE_FAMILY or WCDMA_FAMILY
        NetworkMode.WCDMA_ONLY -> WCDMA_FAMILY
    }

    /** The shell command takes the bitmask as a binary string (parsed with radix 2). */
    fun toBinary(mask: Long): String = java.lang.Long.toBinaryString(mask)

    /**
     * Parses the output of `cmd phone get-allowed-network-types-for-users`: a "|"-separated list of
     * network type names (current AOSP), a binary string, or a decimal number. Null when unreadable.
     */
    fun parseAllowed(output: String): Long? {
        val text = output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        if (BINARY.matches(text)) return text.toLongOrNull(2)
        if (DECIMAL.matches(text)) return text.toLongOrNull()
        if (text.equals("UNKNOWN", ignoreCase = true)) return null
        var mask = 0L
        for (part in text.split('|')) {
            val type = NAMES[part.trim().uppercase()] ?: return null
            mask = mask or bit(type)
        }
        return if (mask == 0L) null else mask
    }

    /** The [NetworkMode] that best describes [mask], or null when it matches none of them. */
    fun modeOf(mask: Long): NetworkMode? {
        val nr = (mask and NR_FAMILY) != 0L
        val lte = (mask and LTE_FAMILY) != 0L
        val wcdma = (mask and WCDMA_FAMILY) != 0L
        val gsm = (mask and GSM_FAMILY) != 0L
        return when {
            nr && lte && (wcdma || gsm) -> NetworkMode.AUTO
            nr && lte -> NetworkMode.NR_LTE
            !nr && lte && wcdma && gsm -> NetworkMode.AUTO
            !nr && lte && wcdma -> NetworkMode.LTE_WCDMA
            !nr && lte && !gsm -> NetworkMode.LTE_ONLY
            !nr && !lte && wcdma && !gsm -> NetworkMode.WCDMA_ONLY
            else -> null
        }
    }
}
