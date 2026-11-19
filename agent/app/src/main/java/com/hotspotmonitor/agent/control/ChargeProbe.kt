package com.hotspotmonitor.agent.control

import com.hotspotmonitor.agent.root.ChargeControlPath
import com.hotspotmonitor.agent.root.RootShell
import com.hotspotmonitor.agent.root.shellQuote

/** One battery reading for the charge-switch effect check. Null fields are unknown. */
internal data class BatterySample(
    /** Any charger or USB power connected (BatteryManager.EXTRA_PLUGGED != 0). */
    val plugged: Boolean?,
    /** BatteryManager.BATTERY_STATUS_* value. */
    val status: Int?,
    /** Profile-corrected current, positive = charging. */
    val currentMa: Int?,
)

/** Outcome of watching the battery after the "off" value was written to a charge switch. */
internal enum class ChargeEffect {
    /** No sample shows charging: the switch works. */
    STOPPED,

    /** Every sample still shows charging with a clearly positive current: the switch does nothing. */
    NO_EFFECT,

    /** Mixed or unreadable samples: decide nothing. */
    UNKNOWN,
}

/** The chosen charge-control path, or null with the reason. */
internal data class ChargePathChoice(val path: ChargeControlPath?, val note: String?)

/**
 * Charge-control path selection and effect check without Android dependencies.
 *
 * `[ -w ]` / File.canWrite are useless here: root bypasses file permissions, so they report a
 * read-only sysfs attribute (mode 0444, no store method) as writable. A path qualifies when it
 * exists, its owner write bit is set (`stat -c %a`, else `ls -l`, as tools/phase0 does) and,
 * when it currently holds its `on` or `off` value, writing that same value back exits 0. Whether
 * the switch actually stops charging is checked later by watching the battery after the first
 * real stop while plugged in ([judgeEffect]), like `phase0.sh charge-discover --test`.
 */
internal object ChargeProbe {
    /** BatteryManager.BATTERY_STATUS_CHARGING. */
    const val STATUS_CHARGING = 2

    /** A sample counts as "still charging" only above this current (mA, positive = charging). */
    const val MIN_CHARGING_MA = 50

    /** Owner write bit (octal 0200). */
    private const val OWNER_WRITE = 0x80

    private val OCTAL_MODE = Regex("^[0-7]{3,4}$")
    private val LS_MODE = Regex("^[-dlcbps]([-r][-w][-xsS][-r][-w][-xsS][-r][-w][-xtT])")

    /** Prints the octal mode of [path] (toybox stat), else its `ls -l` line; fails when it is missing. */
    fun modeCommand(path: String): String {
        val q = shellQuote(path)
        return "stat -L -c %a $q 2>/dev/null || ls -lLd $q"
    }

    /** Permission bits from `stat -c %a` ("644") or an `ls -l` line ("-rw-r--r-- 1 root ..."). */
    fun parseMode(output: String): Int? {
        val line = output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        if (OCTAL_MODE.matches(line)) return line.toInt(8) and 0x1FF
        val perms = LS_MODE.find(line)?.groupValues?.get(1) ?: return null
        var mode = 0
        for ((i, ch) in perms.withIndex()) {
            val set = when (i % 3) {
                0 -> ch == 'r'
                1 -> ch == 'w'
                else -> ch == 'x' || ch == 's' || ch == 't'
            }
            if (set) mode = mode or (1 shl (8 - i))
        }
        return mode
    }

    fun ownerWritable(mode: Int): Boolean = (mode and OWNER_WRITE) != 0

    /** Octal text for logs ("644"). */
    fun modeText(mode: Int): String = Integer.toOctalString(mode)

    /**
     * The value to write back as a harmless write test: the current value when it is exactly this
     * switch's `on` or `off` value. Other values (a level file at 80, a formatted proc file) are not
     * written back, because the kernel might not accept its own read format.
     */
    fun writeBackValue(current: String?, path: ChargeControlPath): String? {
        val value = current?.trim() ?: return null
        return value.takeIf { it == path.on.trim() || it == path.off.trim() }
    }

    /** The first usable path of [candidates], skipping [skip] (paths that did not stop charging). */
    suspend fun choosePath(
        candidates: List<ChargeControlPath>,
        shell: RootShell,
        skip: Set<String> = emptySet(),
        timeoutMs: Long = 5_000,
    ): ChargePathChoice {
        if (candidates.isEmpty()) return ChargePathChoice(null, "the device profile lists no charge_control path")
        val rejected = ArrayList<String>()
        for (c in candidates) {
            if (c.path in skip) {
                rejected += "${c.path} did not stop charging"
                continue
            }
            val stat = shell.run(modeCommand(c.path), timeoutMs)
            if (!stat.ok) continue // missing
            val mode = parseMode(stat.out)
            if (mode != null && !ownerWritable(mode)) {
                rejected += "${c.path} is read-only (mode ${modeText(mode)})"
                continue
            }
            val writeBack = writeBackValue(shell.readFile(c.path, timeoutMs), c)
            if (writeBack != null) {
                val w = shell.writeFile(c.path, writeBack, timeoutMs)
                if (!w.ok) {
                    rejected += "${c.path} rejects writes (exit ${w.code})"
                    continue
                }
            }
            return ChargePathChoice(c, c.note)
        }
        val note = if (rejected.isEmpty()) {
            "none of the ${candidates.size} charge_control paths exists"
        } else {
            "no usable charge_control path: " + rejected.joinToString("; ")
        }
        return ChargePathChoice(null, note)
    }

    /**
     * Verdict from battery samples taken after charging was switched off (the first seconds, while
     * the charger reacts, should not be included). Mirrors phase0.sh: stopped when no sample shows
     * charging; a sample with the plug gone counts as stopped (switches that cut USB input).
     * [ChargeEffect.NO_EFFECT] needs every sample plugged, charging and above [minChargingMa], so a
     * wrong current sign or unknown current never condemns a working switch.
     */
    fun judgeEffect(samples: List<BatterySample>, minChargingMa: Int = MIN_CHARGING_MA): ChargeEffect {
        if (samples.isEmpty()) return ChargeEffect.UNKNOWN
        val noEffect = samples.all { s ->
            s.plugged == true && s.status == STATUS_CHARGING && s.currentMa != null && s.currentMa >= minChargingMa
        }
        if (noEffect) return ChargeEffect.NO_EFFECT
        val stopped = samples.all { s -> s.plugged == false || (s.status != null && s.status != STATUS_CHARGING) }
        return if (stopped) ChargeEffect.STOPPED else ChargeEffect.UNKNOWN
    }

    /** Whether a stop is worth checking: plugged in and charging right before the switch. */
    fun worthChecking(before: BatterySample): Boolean = before.plugged == true && before.status == STATUS_CHARGING
}
