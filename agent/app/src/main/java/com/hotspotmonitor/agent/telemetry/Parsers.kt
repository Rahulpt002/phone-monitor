package com.hotspotmonitor.agent.telemetry

import com.hotspotmonitor.agent.protocol.IfCounters
import com.hotspotmonitor.agent.protocol.ProcessInfo
import com.hotspotmonitor.agent.protocol.round1

/*
 * Parsers for command and file output (plan 6.2 sources). Pure Kotlin, no Android calls, so they
 * are unit-tested on the JVM (ParsersTest). Every parser tolerates junk lines and returns what it
 * could read instead of throwing.
 */

// ---------------------------------------------------------------------------------------------
// ping
// ---------------------------------------------------------------------------------------------

/** Result of one `ping -c N` run. */
internal data class PingStats(
    val transmitted: Int?,
    val received: Int?,
    val lossPct: Double?,
    val avgMs: Double?,
    /** iputils `mdev` (toybox does not print it). */
    val mdevMs: Double?,
    /** Individual round-trip times, in order. */
    val timesMs: List<Double>,
) {
    /** mdev when printed, else the mean difference between consecutive replies (RFC 3550 style). */
    val jitterMs: Double?
        get() {
            mdevMs?.let { return round1(it) }
            if (timesMs.size < 2) return null
            val diffs = timesMs.zipWithNext { a, b -> kotlin.math.abs(b - a) }
            return round1(diffs.average())
        }

    /** True when the statistics block was printed, i.e. ping itself ran. */
    val ran: Boolean get() = transmitted != null
}

private val PING_SUMMARY = Regex("""(\d+)\s+packets transmitted,\s+(\d+)\s+(?:packets\s+)?received""")
private val PING_LOSS = Regex("""([\d.]+)%\s+packet loss""")
private val PING_RTT = Regex("""(?:rtt|round-trip)\s+min/avg/max(?:/(?:mdev|stddev))?\s*=\s*([\d.]+)/([\d.]+)/([\d.]+)(?:/([\d.]+))?\s*ms""")
private val PING_TIME = Regex("""time[=<]\s*([\d.]+)\s*ms""")

/** Parses iputils or toybox ping output. Null when there is nothing ping-like in [output]. */
internal fun parsePing(output: String?): PingStats? {
    if (output.isNullOrBlank()) return null
    val summary = PING_SUMMARY.find(output)
    val times = PING_TIME.findAll(output).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
    if (summary == null && times.isEmpty()) return null
    val transmitted = summary?.groupValues?.get(1)?.toIntOrNull()
    val received = summary?.groupValues?.get(2)?.toIntOrNull()
    val loss = PING_LOSS.find(output)?.groupValues?.get(1)?.toDoubleOrNull()
        ?: if (transmitted != null && received != null && transmitted > 0) {
            (transmitted - received) * 100.0 / transmitted
        } else {
            null
        }
    val rtt = PING_RTT.find(output)
    val avg = rtt?.groupValues?.get(2)?.toDoubleOrNull() ?: times.takeIf { it.isNotEmpty() }?.average()
    val mdev = rtt?.groupValues?.get(4)?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    return PingStats(
        transmitted = transmitted,
        received = received,
        lossPct = loss?.let(::round1),
        avgMs = avg?.let(::round1),
        mdevMs = mdev,
        timesMs = times,
    )
}

/** Hosts we pass to ping / DNS: no options, no shell metacharacters. */
internal fun isSafeHost(host: String): Boolean =
    host.isNotEmpty() && host.length <= 253 && !host.startsWith("-") && host.all { it.isLetterOrDigit() || it in ".-:_" }

// ---------------------------------------------------------------------------------------------
// top
// ---------------------------------------------------------------------------------------------

private val WHITESPACE = Regex("\\s+")
private val COMMAND_COLUMNS = listOf("ARGS", "CMD", "COMMAND", "NAME", "CMDLINE")

/**
 * Parses toybox `top -b -n 1` output (header row with PID, %CPU, RES and ARGS/CMD/NAME). Returns at
 * most [max] processes in the order printed (top sorts by CPU), skipping `top` itself.
 */
internal fun parseTop(output: String?, max: Int = 10): List<ProcessInfo> {
    if (output.isNullOrBlank()) return emptyList()
    val lines = output.lines()
    val headerIndex = lines.indexOfFirst { line ->
        val cols = headerColumns(line)
        "PID" in cols && cols.any { it in COMMAND_COLUMNS }
    }
    if (headerIndex < 0) return emptyList()
    val columns = headerColumns(lines[headerIndex])
    val pidCol = columns.indexOf("PID")
    val cpuCol = columns.indexOfFirst { it == "%CPU" || it == "CPU%" }
    val resCol = columns.indexOfFirst { it == "RES" || it == "RSS" }
    val cmdCol = columns.indexOfFirst { it in COMMAND_COLUMNS }
    val out = ArrayList<ProcessInfo>()
    for (line in lines.drop(headerIndex + 1)) {
        if (out.size >= max) break
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        val parts = trimmed.split(WHITESPACE, limit = columns.size)
        if (parts.size < columns.size || cmdCol >= parts.size) continue
        val pid = parts[pidCol].toIntOrNull() ?: continue
        val command = parts[cmdCol].trim()
        val name = command.substringBefore(' ').substringAfterLast('/')
        if (name.isEmpty() || name == "top") continue
        out += ProcessInfo(
            pid = pid,
            name = name.take(64),
            cpuPct = if (cpuCol >= 0) parts[cpuCol].trimEnd('%').toDoubleOrNull()?.let(::round1) else null,
            memMb = if (resCol >= 0) parseSizeMb(parts[resCol]) else null,
        )
    }
    return out
}

private fun headerColumns(line: String): List<String> =
    line.replace('[', ' ').replace(']', ' ').trim().split(WHITESPACE).filter { it.isNotEmpty() }

/** "4.1M" -> 4.1, "1.5G" -> 1536.0, "4096K" -> 4.0, plain numbers are KiB (procps convention). */
internal fun parseSizeMb(text: String): Double? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val unit = t.last().uppercaseChar()
    val number = if (unit.isLetter()) t.dropLast(1) else t
    val value = number.toDoubleOrNull() ?: return null
    val mb = when (unit) {
        'K' -> value / 1024
        'M' -> value
        'G' -> value * 1024
        'T' -> value * 1024 * 1024
        'B' -> value / (1024 * 1024)
        else -> if (unit.isDigit()) value / 1024 else return null
    }
    return round1(mb)
}

// ---------------------------------------------------------------------------------------------
// ip addr / ip neigh
// ---------------------------------------------------------------------------------------------

/** One address from `ip -o addr show`. */
internal data class IfAddr(
    val iface: String,
    /** 4 or 6 */
    val family: Int,
    val address: String,
    val prefix: Int?,
    val scope: String?,
)

private val IP_ADDR_LINE = Regex("""^\s*\d+:\s+([^\s@:]+)(?:@\S+)?:?\s+(inet6?)\s+([0-9A-Fa-f.:]+)(?:/(\d+))?(.*)$""")
private val SCOPE = Regex("""\bscope\s+(\S+)""")

/** Parses `ip -o addr show` (one address per line). */
internal fun parseIpAddr(output: String?): List<IfAddr> {
    if (output.isNullOrBlank()) return emptyList()
    return output.lines().mapNotNull { line ->
        val m = IP_ADDR_LINE.find(line) ?: return@mapNotNull null
        IfAddr(
            iface = m.groupValues[1],
            family = if (m.groupValues[2] == "inet6") 6 else 4,
            address = m.groupValues[3],
            prefix = m.groupValues[4].toIntOrNull(),
            scope = SCOPE.find(m.groupValues[5])?.groupValues?.get(1),
        )
    }
}

/** One entry from `ip neigh show`. */
internal data class Neighbour(
    val ip: String,
    val iface: String?,
    val mac: String?,
    /** REACHABLE, STALE, DELAY, PROBE, FAILED, INCOMPLETE, PERMANENT, NOARP, ... */
    val state: String?,
)

private val NEIGH_STATES = setOf("REACHABLE", "STALE", "DELAY", "PROBE", "FAILED", "INCOMPLETE", "PERMANENT", "NOARP", "NONE")
private val MAC = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")

/**
 * Parses `ip neigh show [dev IF]`. Lines look like
 * `192.168.42.10 dev rndis0 lladdr aa:bb:cc:dd:ee:ff REACHABLE` (the `dev` part is absent when the
 * command was run with `dev IF`; [defaultIface] fills it in then).
 */
internal fun parseIpNeigh(output: String?, defaultIface: String? = null): List<Neighbour> {
    if (output.isNullOrBlank()) return emptyList()
    return output.lines().mapNotNull { line ->
        val tokens = line.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return@mapNotNull null
        val ip = tokens[0]
        if (!(ip.contains('.') || ip.contains(':'))) return@mapNotNull null
        val dev = tokens.indexOf("dev").takeIf { it >= 0 && it + 1 < tokens.size }?.let { tokens[it + 1] } ?: defaultIface
        val mac = tokens.indexOf("lladdr").takeIf { it >= 0 && it + 1 < tokens.size }?.let { tokens[it + 1] }
            ?.takeIf { MAC.matches(it) }?.lowercase()
        val state = tokens.lastOrNull { it in NEIGH_STATES }
        Neighbour(ip = ip, iface = dev, mac = mac, state = state)
    }
}

/** Neighbour states that mean "a device talked to us recently". */
internal val LIVE_NEIGH_STATES = setOf("REACHABLE", "STALE", "DELAY", "PROBE")

// ---------------------------------------------------------------------------------------------
// /proc, /sys
// ---------------------------------------------------------------------------------------------

/** `/proc/loadavg`: "0.52 0.58 0.59 1/1234 5678" -> [0.52, 0.58, 0.59]. */
internal fun parseLoadAvg(text: String?): List<Double>? {
    val parts = text?.trim()?.split(WHITESPACE) ?: return null
    if (parts.size < 3) return null
    val values = parts.take(3).map { it.toDoubleOrNull() ?: return null }
    return values
}

/** Lines "name rx_bytes tx_bytes" (from the counters shell loop) -> per interface counters. */
internal fun parseIfCounters(output: String?): Map<String, IfCounters> {
    if (output.isNullOrBlank()) return emptyMap()
    val out = LinkedHashMap<String, IfCounters>()
    for (line in output.lines()) {
        val parts = line.trim().split(WHITESPACE)
        if (parts.size < 3) continue
        val rx = parts[1].toLongOrNull() ?: continue
        val tx = parts[2].toLongOrNull() ?: continue
        if (rx < 0 || tx < 0) continue
        out[parts[0]] = IfCounters(rx = rx, tx = tx)
    }
    return out
}

/** Interfaces worth reporting in traffic.per_if (loopback and dummies are noise). */
internal fun isReportableInterface(name: String): Boolean =
    name != "lo" && !name.startsWith("dummy") && !name.startsWith("sit") && !name.startsWith("ip6tnl") &&
        !name.startsWith("ip_vti") && !name.startsWith("ip6_vti") && !name.startsWith("tunl") && !name.startsWith("gre") &&
        !name.startsWith("erspan") && !name.startsWith("ifb") && !name.startsWith("bond")

// ---------------------------------------------------------------------------------------------
// getprop, SoftAp config, IMEI, public IP
// ---------------------------------------------------------------------------------------------

/** First non-blank line of a getprop result, or null. */
internal fun parseGetprop(output: String?): String? =
    output?.lines()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }?.take(120)

private val SSID_LINE = Regex("""<string\s+name="(?:WifiSsid|SSID)">(.*?)</string>""")

/**
 * Hotspot name from a WifiConfigStoreSoftAp.xml line (`<string name="WifiSsid">&quot;MyAP&quot;</string>`).
 * Quoted values are UTF-8 text; unquoted values are hex-encoded bytes (Android 13+ for non-UTF-8 names).
 */
internal fun parseSoftApSsid(output: String?): String? {
    if (output.isNullOrBlank()) return null
    val raw = SSID_LINE.find(output)?.groupValues?.get(1) ?: return null
    val text = unescapeXml(raw).trim()
    if (text.length >= 2 && text.startsWith('"') && text.endsWith('"')) return text.substring(1, text.length - 1).ifEmpty { null }
    if (text.isNotEmpty() && text.length % 2 == 0 && text.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
        val bytes = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return String(bytes, Charsets.UTF_8).ifEmpty { null }
    }
    return text.ifEmpty { null }
}

private fun unescapeXml(s: String): String {
    var out = s.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
    out = Regex("""&#(x?[0-9A-Fa-f]+);""").replace(out) { m ->
        val v = m.groupValues[1]
        val code = if (v.startsWith("x")) v.drop(1).toIntOrNull(16) else v.toIntOrNull()
        code?.let { String(Character.toChars(it)) } ?: m.value
    }
    return out.replace("&amp;", "&")
}

/** Luhn checksum (IMEI validation). */
internal fun luhnValid(number: String): Boolean {
    if (number.isEmpty() || !number.all { it.isDigit() }) return false
    var sum = 0
    for ((i, c) in number.reversed().withIndex()) {
        var d = c - '0'
        if (i % 2 == 1) {
            d *= 2
            if (d > 9) d -= 9
        }
        sum += d
    }
    return sum % 10 == 0
}

/** Public IP as returned by a plain-text "what is my IP" service; null when not an address. */
internal fun parsePublicIp(body: String?): String? {
    val text = body?.trim()?.take(64) ?: return null
    val v4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""").matchEntire(text)
    if (v4 != null) return text.takeIf { v4.groupValues.drop(1).all { (it.toIntOrNull() ?: 999) <= 255 } }
    if (text.contains(':') && text.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }) return text.lowercase()
    return null
}
