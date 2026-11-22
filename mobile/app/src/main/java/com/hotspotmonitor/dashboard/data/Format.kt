package com.hotspotmonitor.dashboard.data

import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

// Display formatting (port of web/src/lib/format.ts). Every helper returns DASH for a missing
// value instead of inventing one.

const val DASH = "—"

/** Milliseconds of an ISO timestamp, or of a date-only "2026-11-01" at local midnight. */
fun isoMs(t: String?): Long? {
    if (t.isNullOrBlank()) return null
    if (t.length == 10 && t[4] == '-' && t[7] == '-') {
        return runCatching { LocalDate.parse(t).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
    }
    return runCatching { Instant.parse(t).toEpochMilli() }.getOrNull()
}

fun isoNow(): String = Instant.now().toString()
fun isoOf(ms: Long): String = Instant.ofEpochMilli(ms).toString()

/** Trim to [digits] decimals and drop trailing zeros: 2.10 -> "2.1", 41.0 -> "41". */
fun trimNumber(v: Double, digits: Int): String {
    val s = String.format(Locale.US, "%.${digits}f", v)
    return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
}

private fun smart(v: Double): String {
    val a = abs(v)
    return trimNumber(v, if (a < 10) 2 else if (a < 100) 1 else 0)
}

private fun scaled(value: Double, units: List<String>, step: Double): String {
    var v = maxOf(0.0, value)
    var i = 0
    while (v >= step && i < units.size - 1) {
        v /= step
        i++
    }
    return "${if (i == 0) v.roundToLong().toString() else smart(v)} ${units[i]}"
}

/** Bits per second, decimal units: 5400000 -> "5.4 Mbps". */
fun formatBps(bps: Number?): String = bps?.toDouble()?.takeIf { it.isFinite() }?.let { scaled(it, listOf("bps", "kbps", "Mbps", "Gbps"), 1000.0) } ?: DASH

fun formatMbps(mbps: Number?): String = mbps?.toDouble()?.takeIf { it.isFinite() }?.let { "${smart(it)} Mbps" } ?: DASH

/** Bytes, decimal units (carriers bill in 10^9): 2100000000 -> "2.1 GB". */
fun formatBytes(bytes: Number?): String = bytes?.toDouble()?.takeIf { it.isFinite() }?.let { scaled(it, listOf("B", "KB", "MB", "GB", "TB"), 1000.0) } ?: DASH

fun formatMb(mb: Number?): String {
    val v = mb?.toDouble()?.takeIf { it.isFinite() } ?: return DASH
    return if (v >= 1000) "${smart(v / 1000)} GB" else "${v.roundToLong()} MB"
}

/** Seconds -> "45 s", "5 min 12 s", "3 h 4 min", "2 d 3 h". */
fun formatDuration(seconds: Number?): String {
    var s = seconds?.toDouble()?.takeIf { it.isFinite() }?.roundToLong()?.coerceAtLeast(0) ?: return DASH
    if (s < 60) return "$s s"
    val d = s / 86400; s -= d * 86400
    val h = s / 3600; s -= h * 3600
    val m = s / 60; s -= m * 60
    return when {
        d > 0 -> if (h > 0) "$d d $h h" else "$d d"
        h > 0 -> if (m > 0) "$h h $m min" else "$h h"
        else -> if (s > 0) "$m min $s s" else "$m min"
    }
}

/** "just now", "12 s ago", "5 min ago", "3 h ago", "2 d ago", "in 30 s". */
fun formatRelative(iso: String?, nowMs: Long = System.currentTimeMillis()): String {
    val ms = isoMs(iso) ?: return "never"
    return formatRelativeMs(ms, nowMs)
}

fun formatRelativeMs(ms: Long, nowMs: Long = System.currentTimeMillis()): String {
    val diff = (nowMs - ms) / 1000
    val a = abs(diff)
    // Up to a minute "in the future" is clock drift between this phone and the hotspot phone.
    if (a < 5 || diff in -60..0) return "just now"
    val body = when {
        a < 60 -> "$a s"
        a < 3600 -> "${a / 60} min"
        a < 86400 -> "${a / 3600} h"
        a < 86400L * 60 -> "${a / 86400} d"
        else -> return formatDate(isoOf(ms))
    }
    return if (diff >= 0) "$body ago" else "in $body"
}

/** "m:ss" countdown; negative clamps to 0:00. */
fun formatCountdown(msLeft: Long): String {
    val s = maxOf(0L, (msLeft + 999) / 1000)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

fun formatDateTime(iso: String?): String = isoMs(iso)?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: DASH
fun formatDate(iso: String?): String = isoMs(iso)?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) } ?: DASH
fun formatTime(iso: String?): String = isoMs(iso)?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) } ?: DASH
fun formatTimeMs(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

fun fmtNum(v: Number?, unit: String = "", digits: Int = 1): String =
    v?.toDouble()?.takeIf { it.isFinite() }?.let { "${trimNumber(it, digits)}$unit" } ?: DASH

fun formatPercent(v: Number?, digits: Int = 0): String = v?.toDouble()?.takeIf { it.isFinite() }?.let { "${trimNumber(it, digits)}%" } ?: DASH
fun formatTemp(c: Number?): String = c?.toDouble()?.takeIf { it.isFinite() }?.let { "${trimNumber(it, 1)} °C" } ?: DASH

/** Signed battery current: +120 mA charging, -35 mA draining. */
fun formatCurrent(ma: Number?): String {
    val r = ma?.toDouble()?.takeIf { it.isFinite() }?.roundToLong() ?: return DASH
    return "${if (r > 0) "+" else ""}$r mA"
}

fun formatVoltage(mv: Number?): String = mv?.toDouble()?.takeIf { it.isFinite() }?.let { "${trimNumber(it / 1000, 2)} V" } ?: DASH

/** Timing advance -> rough distance to the tower (LTE: ~78 m per step). */
fun formatTaDistance(ta: Number?): String {
    val m = ta?.toDouble()?.takeIf { it.isFinite() }?.times(78) ?: return DASH
    return if (m >= 1000) "~${trimNumber(m / 1000, 1)} km" else "~${m.roundToLong()} m"
}

fun titleCase(s: String): String = s.replace('_', ' ').replaceFirstChar { it.uppercase() }

private val NET_TYPE_LABEL = mapOf(
    "LTE" to "4G LTE", "NR_NSA" to "5G NSA", "NR_SA" to "5G SA", "WCDMA" to "3G WCDMA",
    "GSM" to "2G GSM", "CDMA" to "CDMA", "TDSCDMA" to "3G TD-SCDMA", "UNKNOWN" to "Unknown",
)

fun formatNetType(t: String?): String = if (t.isNullOrBlank()) DASH else NET_TYPE_LABEL[t] ?: t

val THERMAL_STATUS = listOf("None", "Light", "Moderate", "Severe", "Critical", "Emergency", "Shutdown")
fun formatThermalStatus(v: Int?): String = v?.let { THERMAL_STATUS.getOrNull(it) ?: it.toString() } ?: DASH

/** Enum wire name of a kotlinx-serialized enum (its @SerialName), e.g. NetType.NR_NSA -> "NR_NSA". */
fun Enum<*>.wire(): String = name

fun roundTo(v: Double, step: Double): Double = floor(v / step + 0.5) * step
