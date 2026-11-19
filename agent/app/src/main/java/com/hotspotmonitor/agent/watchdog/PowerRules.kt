package com.hotspotmonitor.agent.watchdog

import com.hotspotmonitor.agent.protocol.WeeklyReboot
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.roundToInt

/** A weak-power episode started: plugged in, charging allowed, battery falling for a full window. */
internal data class WeakPowerAlert(val fromPct: Int, val toPct: Int, val minutes: Long, val avgCurrentMa: Int?)

/**
 * "Plugged in but battery falling for weak_power_min" (plan 6.5, 8.5) without I/O.
 *
 * Only samples taken while a supply is plugged in and the Agent allows charging count: when the
 * charge limit or heat rule stops charging, a falling battery is expected. The percent must drop
 * across a full window without rising above its start; when the net current is known, at least
 * half the samples must also show the battery draining (second signal). One alert per episode;
 * the episode ends when the percent rises again, the supply is unplugged or charging is stopped.
 */
internal class WeakPowerDetector {
    private data class Sample(val atMs: Long, val pct: Int, val currentMa: Int?)

    private val samples = ArrayDeque<Sample>()
    private var episodeMinPct: Int? = null

    val inEpisode: Boolean get() = episodeMinPct != null

    fun observe(
        nowMs: Long,
        pluggedIn: Boolean,
        chargingAllowed: Boolean,
        pct: Int?,
        currentMa: Int?,
        windowMin: Int,
    ): WeakPowerAlert? {
        if (!pluggedIn || !chargingAllowed || pct == null) {
            reset()
            return null
        }
        val episodeMin = episodeMinPct
        if (episodeMin != null) {
            if (pct <= episodeMin) {
                episodeMinPct = pct
                return null
            }
            reset() // recovering: start watching afresh
        }
        val windowMs = windowMin.coerceAtLeast(1) * 60_000L
        samples.addLast(Sample(nowMs, pct, currentMa))
        while (samples.size > 1 && samples[1].atMs <= nowMs - windowMs) samples.removeFirst()
        val first = samples.first()
        if (nowMs - first.atMs < windowMs) return null
        val falling = pct < first.pct && samples.all { it.pct <= first.pct }
        if (!falling) return null
        val currents = samples.mapNotNull { it.currentMa }
        val draining = currents.isEmpty() || currents.count { it < 0 } * 2 >= currents.size
        if (!draining) return null
        val alert = WeakPowerAlert(
            fromPct = first.pct,
            toPct = pct,
            minutes = (nowMs - first.atMs) / 60_000L,
            avgCurrentMa = if (currents.isEmpty()) null else currents.average().roundToInt(),
        )
        samples.clear()
        episodeMinPct = pct
        return alert
    }

    fun reset() {
        samples.clear()
        episodeMinPct = null
    }
}

/**
 * "USB connected but tethering is off -> turn it back on" (plan 6.5) without I/O: due once tethering
 * has been off for tether_fix_after_s while USB is connected and nothing suppresses the rule. Failed
 * attempts back off (x2 each time, up to x32) so a host that does not want tethering is not hammered.
 */
internal class TetherFixRule {
    private var offSinceMs: Long? = null
    private var nextAllowedMs = 0L

    var attempts: Int = 0
        private set

    fun observe(nowMs: Long, usbConnected: Boolean, tetherOn: Boolean, suppressed: Boolean, fixAfterS: Int): Boolean {
        if (!usbConnected || tetherOn) {
            reset()
            return false
        }
        if (suppressed) {
            offSinceMs = null
            return false
        }
        val since = offSinceMs ?: nowMs.also { offSinceMs = it }
        if (nowMs - since < fixAfterS.coerceAtLeast(0) * 1000L) return false
        return nowMs >= nextAllowedMs
    }

    fun onAttempt(nowMs: Long, fixAfterS: Int) {
        attempts++
        val base = fixAfterS.coerceAtLeast(1) * 1000L
        nextAllowedMs = nowMs + base * (1L shl (attempts - 1).coerceAtMost(MAX_BACKOFF_SHIFT))
    }

    fun reset() {
        offSinceMs = null
        nextAllowedMs = 0L
        attempts = 0
    }

    companion object {
        private const val MAX_BACKOFF_SHIFT = 5
    }
}

/** settings.watchdog.weekly_reboot in phone local time. */
internal object WeeklySchedule {
    /** The reboot may start up to this long after the configured time (the Agent may be busy or starting). */
    const val WINDOW_MIN = 15L
    private val TIME = Regex("^(\\d{1,2}):(\\d{2})$")

    fun parseTime(text: String): LocalTime? {
        val m = TIME.matchEntire(text.trim()) ?: return null
        val hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].toInt()
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }

    /**
     * The occurrence key (the local date) when the reboot is due at [now], or null. [lastRunKey] is
     * the key of the last occurrence acted on, so each week runs at most once.
     */
    fun dueOccurrence(now: LocalDateTime, cfg: WeeklyReboot, lastRunKey: String?): String? {
        if (!cfg.enabled) return null
        val time = parseTime(cfg.time) ?: return null
        // java.time: Monday = 1 .. Sunday = 7; settings: Sunday = 0 .. Saturday = 6.
        if (now.dayOfWeek.value % 7 != cfg.day) return null
        val start = now.toLocalDate().atTime(time)
        if (now.isBefore(start) || !now.isBefore(start.plusMinutes(WINDOW_MIN))) return null
        val key = now.toLocalDate().toString()
        return if (key == lastRunKey) null else key
    }
}
