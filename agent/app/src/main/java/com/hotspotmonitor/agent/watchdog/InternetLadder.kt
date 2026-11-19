package com.hotspotmonitor.agent.watchdog

import com.hotspotmonitor.agent.protocol.CommandNames
import com.hotspotmonitor.agent.protocol.WatchdogSettings

/** The internet recovery ladder of plan 6.5, in order. */
internal enum class LadderStep(val action: String, val step: Int) {
    DATA_CYCLE(CommandNames.DATA_CYCLE, 1),
    AIRPLANE_CYCLE(CommandNames.AIRPLANE_CYCLE, 2),
    REBOOT(CommandNames.REBOOT, 3),
}

/** The internet came back after at least one ladder step. */
internal data class Recovery(val lastStep: LadderStep, val downForMs: Long)

/**
 * Internet recovery ladder without I/O. Times are monotonic milliseconds; thresholds come from the
 * [WatchdogSettings] passed to [due] on every call, so Dashboard edits apply at once.
 *
 * - Down for internet_fail_s -> data cycle; still down at airplane_after_s -> airplane cycle;
 *   still down at reboot_after_s -> reboot. All measured from the start of the outage.
 * - Each step runs once per outage, in order (one per call, even if later thresholds already passed).
 * - A reboot that did not happen (limit reached or failed) is retried every reboot_retry_s.
 * - Back online resets everything; [observe] then reports the last step taken.
 */
internal class InternetLadder {
    var downSinceMs: Long? = null
        private set

    /** Highest step taken in this outage: 0 none, 1 data cycled, 2 airplane cycled, 3 reboot requested. */
    var step: Int = 0
        private set

    var lastStep: LadderStep? = null
        private set

    private var lastRebootAttemptMs: Long? = null
    private var rebootPending = false

    /** Feeds one internet check result. Returns a [Recovery] when the internet came back after a step. */
    fun observe(nowMs: Long, online: Boolean): Recovery? {
        if (online) {
            val since = downSinceMs ?: return null
            val last = lastStep
            reset()
            return if (last != null) Recovery(last, nowMs - since) else null
        }
        if (downSinceMs == null) downSinceMs = nowMs
        return null
    }

    /** The step to run now, or null. */
    fun due(nowMs: Long, s: WatchdogSettings): LadderStep? {
        val since = downSinceMs ?: return null
        val downMs = nowMs - since
        return when {
            step < 1 && downMs >= s.internetFailS * 1000L -> LadderStep.DATA_CYCLE
            step < 2 && downMs >= s.airplaneAfterS * 1000L -> LadderStep.AIRPLANE_CYCLE
            step < 3 && downMs >= s.rebootAfterS * 1000L -> LadderStep.REBOOT
            step >= 3 && rebootPending && nowMs - (lastRebootAttemptMs ?: nowMs) >= s.rebootRetryS * 1000L -> LadderStep.REBOOT
            else -> null
        }
    }

    /**
     * Records that [done] ran at [nowMs]. For the reboot step, [rebootStarted] = false means the
     * phone did not reboot (limit reached or command failed), so it is retried later.
     */
    fun onStepDone(done: LadderStep, nowMs: Long, rebootStarted: Boolean = true) {
        step = maxOf(step, done.step)
        lastStep = done
        if (done == LadderStep.REBOOT) {
            lastRebootAttemptMs = nowMs
            rebootPending = !rebootStarted
        }
    }

    fun downForMs(nowMs: Long): Long? = downSinceMs?.let { nowMs - it }

    fun reset() {
        downSinceMs = null
        step = 0
        lastStep = null
        lastRebootAttemptMs = null
        rebootPending = false
    }
}
